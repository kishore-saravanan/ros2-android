"""Read-only ROS manifest/dependency diagnostics; never execute workspace code."""
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET

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
            versions = {k: v for k, v in entry.attrib.items() if k.startswith('version_')}
            if versions:
                row['versionConstraints'] = versions
            if entry.tag == 'group_depend':
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
        'summary': {**counts, 'conditional': sum('condition' in r for r in dependencies),
                    'versionConstrained': sum('versionConstraints' in r for r in dependencies)},
        'unsupportedPackages': [{'name': p['name'], 'buildType': p['buildType']} for p in packages
                                if p['buildType'] != 'ament_cmake'],
        'limits': ['Only declared direct dependencies and installed ament-index markers are inspected.',
                   'Conditions, version constraints and dependency groups are not evaluated.',
                   'No rosdep resolution, transitive closure, CMake execution, ELF validation or launch analysis is performed.',
                   'Shell assets and supported host-side launch adaptation may satisfy dependencies without target ament markers.',
                   'An index marker does not prove an Android-compatible binary or that the workspace builds.'],
    }
