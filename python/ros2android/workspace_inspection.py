"""Read-only ROS manifest/dependency diagnostics; never execute workspace code."""
import os
import operator
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from .launch_inspection import inspect_launches

EXCLUDED = {'.git', 'build', 'install', 'log', '__pycache__', '.cxx', '.gradle'}
DEPENDENCY_TAGS = {'depend', 'build_depend', 'build_export_depend', 'exec_depend',
                   'buildtool_depend', 'buildtool_export_depend', 'test_depend', 'doc_depend', 'group_depend'}


def read_package(path):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as error:
        raise ValueError(f'{path}: invalid package XML: {error}') from error
    name = root.findtext('name', '').strip()
    if root.tag != 'package' or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_-]*', name):
        raise ValueError(f'{path}: expected a package with a valid name')
    return root


def inventory(workspace, allow_unsupported=False):
    packages = []
    for path in sorted((workspace / 'src').rglob('package.xml')):
        if EXCLUDED.intersection(path.relative_to(workspace / 'src').parts):
            continue
        parents = [path.parent, *path.parent.parents]
        if any((parent / 'COLCON_IGNORE').exists() for parent in parents
               if parent == workspace or workspace in parent.parents):
            continue
        manifest = read_package(path)
        packages.append({'name': manifest.findtext('name').strip(), 'path': str(path.parent),
                         'buildType': manifest.findtext('export/build_type', 'ament_cmake').strip()})
    if not packages:
        raise ValueError('No ROS packages found under workspace/src')
    if len({p['name'] for p in packages}) != len(packages):
        raise ValueError('Duplicate package names')
    unsupported = [p['name'] + ': ' + p['buildType'] for p in packages if p['buildType'] != 'ament_cmake']
    if unsupported and not allow_unsupported:
        raise ValueError('Workspace process runner currently supports ament_cmake packages; unsupported: ' + ', '.join(unsupported))
    return packages


def indexed_packages(prefixes):
    """Installed ament-index markers are evidence of presence, not binary compatibility."""
    found = {}
    for prefix in prefixes:
        index = prefix / 'share/ament_index/resource_index/packages'
        if index.is_dir():
            for marker in sorted(index.iterdir()):
                if marker.is_file():
                    found.setdefault(marker.name, []).append(str(prefix))
    return found


def default_host_prefixes():
    # The build script sources this setup, retaining inherited overlays.
    setup = Path(os.environ.get('ROBOT_ROS_SETUP', '/opt/ros/jazzy/setup.bash')).expanduser()
    return list(dict.fromkeys([setup.parent, *[Path(p).expanduser() for p in
        os.environ.get('AMENT_PREFIX_PATH', '').split(os.pathsep) if p]]))


def evaluate_target_condition(expression):
    """Use the upstream ROS manifest evaluator when available, never Python eval."""
    context = {'ROS_VERSION': '2', 'ROS_DISTRO': 'jazzy', 'ROS_PYTHON_VERSION': '3'}
    missing = sorted(set(re.findall(r'\$([A-Za-z_][A-Za-z0-9_]*)', expression)) - context.keys())
    if missing:
        return {'state': 'unresolved', 'reason': 'Target values are unknown for: ' + ', '.join(missing)}
    try:
        from catkin_pkg.condition import evaluate_condition
    except ImportError:
        return {'state': 'unresolved', 'reason': 'Install the client inspection extra (catkin-pkg) to evaluate ROS manifest conditions.'}
    try:
        active = evaluate_condition(expression, context)
    except ValueError as error:
        return {'state': 'unresolved', 'reason': str(error)}
    return {'state': 'active' if active else 'inactive'}


def check_versions(row, constraints, manifests):
    """Check declared three-part ROS versions in provider precedence order."""
    operators = {'version_lt': operator.lt, 'version_lte': operator.le,
                 'version_eq': operator.eq, 'version_gte': operator.ge, 'version_gt': operator.gt}
    if row.get('conditionEvaluation', {}).get('state') == 'unresolved':
        return {'state': 'unresolved', 'reason': 'Dependency condition is unresolved.'}
    if row['provider'] not in ('workspace', 'android-sdk', 'host-only'):
        return {'state': 'not-used' if row['provider'] == 'not-used' else 'unresolved',
                'reason': 'No selected package provider to check.'}
    if any(key not in operators or not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', value)
           for key, value in constraints.items()):
        return {'state': 'unresolved', 'reason': 'Only standard ROS version constraints with three numeric components are supported.'}
    path = manifests[0]
    try:
        manifest = read_package(path)
        if manifest.findtext('name', '').strip() != row['dependency']:
            return {'state': 'unresolved', 'reason': 'Provider manifest name differs from the dependency.'}
        version = manifest.findtext('version', '').strip()
    except (OSError, ValueError) as error:
        return {'state': 'unresolved', 'reason': str(error)}
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
        return {'state': 'unresolved', 'reason': 'Provider manifest has no standard three-part version.'}
    actual = tuple(map(int, version.split('.')))
    satisfied = all(operators[key](actual, tuple(map(int, wanted.split('.'))))
                    for key, wanted in constraints.items())
    return {'state': 'satisfied' if satisfied else 'unsatisfied', 'providerVersion': version,
            'manifest': str(path), 'scope': 'declared provider version; not Android binary compatibility'}


def inspect_dependencies(packages, target_prefixes, host_prefixes):
    target = indexed_packages(target_prefixes)
    host = indexed_packages(host_prefixes)
    sources = {p['name']: p['path'] for p in packages}
    dependencies = []
    for package in packages:
        root = read_package(Path(package['path']) / 'package.xml')
        for entry in root:
            if entry.tag not in DEPENDENCY_TAGS:
                continue
            name = (entry.text or '').strip()
            if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_-]*', name):
                raise ValueError(f"{package['path']}/package.xml: invalid {entry.tag} name {name!r}")
            row = {'package': package['name'], 'dependency': name, 'kind': entry.tag}
            if 'condition' in entry.attrib:
                row['condition'] = entry.attrib['condition']
                row['conditionEvaluation'] = evaluate_target_condition(entry.attrib['condition'])
            versions = {k: v for k, v in entry.attrib.items() if k.startswith('version_')}
            if versions:
                row['versionConstraints'] = versions
            if row.get('conditionEvaluation', {}).get('state') == 'inactive':
                row.update(provider='not-used', guidance='Dependency condition is false in the Jazzy ROS 2 target context.')
            elif entry.tag == 'group_depend':
                row.update(provider='group-unresolved', guidance='Group membership needs build-system resolution; no individual package match is inferred.')
            elif entry.tag in ('test_depend', 'doc_depend'):
                row.update(provider='not-used', guidance='This workflow disables tests and does not build documentation.')
            elif name in sources:
                row.update(provider='workspace', locations=[sources[name]])
            elif name in target:
                row.update(provider='android-sdk', locations=target[name])
            elif name in host:
                row.update(provider='host-only', locations=host[name], guidance=(
                    'Build tools run on Linux; verify this tool and its target-generation support.'
                    if entry.tag in ('buildtool_depend', 'buildtool_export_depend') else
                    'No selected Android ament marker. A host installation alone does not provide Android artifacts; check compatible sources, target SDK or separately bundled runtime assets.'))
            else:
                row.update(provider='not-found', guidance=(
                    'No ament package marker found. Check the build tool on Linux and target-generation support.'
                    if entry.tag in ('buildtool_depend', 'buildtool_export_depend') else
                    'No ament package marker found. Prepare the Android SDK, add compatible workspace sources, or investigate a non-ament/system dependency.'))
            if versions:
                if row['provider'] == 'workspace':
                    manifests = [Path(sources[name]) / 'package.xml']
                else:
                    manifests = [Path(location) / 'share' / name / 'package.xml' for location in row.get('locations', [])]
                row['versionCheck'] = check_versions(row, versions, manifests)
            dependencies.append(row)
    dependencies.sort(key=lambda r: (r['package'], r['dependency'], r['kind'], r.get('condition', '')))
    counts = {provider: sum(r['provider'] == provider for r in dependencies) for provider in
              ('workspace', 'android-sdk', 'host-only', 'not-found', 'not-used', 'group-unresolved')}
    return {
        'schemaVersion': 1,
        'target': {'platform': 'android', 'abi': 'arm64-v8a', 'minApi': 26, 'rosDistribution': 'jazzy'},
        'targetPrefixes': [{'path': str(p), 'indexPresent': (p / 'share/ament_index/resource_index/packages').is_dir()}
                           for p in target_prefixes],
        'hostPrefixes': [str(p) for p in host_prefixes],
        'dependencies': dependencies,
        'launchAnalysis': inspect_launches(packages),
        'summary': {**counts, 'conditional': sum('condition' in r for r in dependencies),
                    'versionConstrained': sum('versionConstraints' in r for r in dependencies),
                    'inactive': sum(r.get('conditionEvaluation', {}).get('state') == 'inactive' for r in dependencies),
                    'conditionUnresolved': sum(r.get('conditionEvaluation', {}).get('state') == 'unresolved' for r in dependencies),
                    'versionUnsatisfied': sum(r.get('versionCheck', {}).get('state') == 'unsatisfied' for r in dependencies)},
        'unsupportedPackages': [{'name': p['name'], 'buildType': p['buildType']} for p in packages
                                if p['buildType'] != 'ament_cmake'],
        'limits': ['Only declared direct dependencies and installed ament-index markers are inspected.',
                   'Conditions use the optional upstream catkin-pkg evaluator with ROS_VERSION=2, ROS_DISTRO=jazzy and ROS_PYTHON_VERSION=3; unknown context stays unresolved.',
                   'Version checks use declared three-part versions from the first provider, not binary or transitive compatibility. Dependency groups remain unresolved.',
                   'No rosdep resolution, transitive closure, CMake execution or ELF validation is performed; launch scanning is syntax-only.',
                   'Shell assets and supported host-side launch adaptation may satisfy dependencies without target ament markers.',
                   'An index marker does not prove an Android-compatible binary or that the workspace builds.'],
    }
