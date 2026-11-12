"""Conservative launch diagnostics: parse Python syntax without importing user code."""
import ast
from pathlib import Path

EXCLUDED = {'.git', 'build', 'install', 'log', '__pycache__', '.cxx', '.gradle'}
UNSUPPORTED = {
    'launch.actions.ExecuteProcess', 'launch.actions.TimerAction',
    'launch.actions.OpaqueFunction', 'launch.actions.RegisterEventHandler',
    'launch_ros.actions.ComposableNodeContainer', 'launch_ros.actions.LoadComposableNodes',
    'launch_ros.actions.LifecycleNode', 'launch_ros.actions.SetParameter',
    'launch_ros.actions.SetParametersFromFile',
    'launch.launch_description_sources.XMLLaunchDescriptionSource',
    'launch.launch_description_sources.AnyLaunchDescriptionSource',
}
NODE_FIELDS = {'package', 'executable', 'name', 'namespace', 'parameters', 'remappings', 'condition', 'output'}


def inspect_launches(packages):
    paths = sorted({path for package in packages for path in Path(package['path']).rglob('*.launch.py')
                    if not EXCLUDED.intersection(path.relative_to(package['path']).parts)})
    reports = []
    for path in paths[:128]:
        row = {'path': str(path), 'state': 'requires-build-time-resolution', 'issues': []}
        reports.append(row)
        issues = row['issues']
        try:
            if path.stat().st_size > 1024 * 1024:
                issues.append({'line': 1, 'kind': 'unresolved', 'message': 'Launch file exceeds the 1 MiB static inspection limit.'})
                continue
            tree = ast.parse(path.read_text(), filename=str(path))
        except (OSError, UnicodeError, SyntaxError, RecursionError) as error:
            issues.append({'line': getattr(error, 'lineno', None) or 1, 'kind': 'unresolved', 'message': str(error)})
            continue
        aliases = {}
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.module:
                for imported in node.names:
                    aliases[imported.asname or imported.name] = node.module + '.' + imported.name
            elif isinstance(node, ast.Import):
                for imported in node.names:
                    aliases[imported.asname or imported.name.split('.')[0]] = imported.name if imported.asname else imported.name.split('.')[0]

        def qualified(node):
            if isinstance(node, ast.Name):
                return aliases.get(node.id, '')
            if isinstance(node, ast.Attribute):
                parent = qualified(node.value)
                return parent + '.' + node.attr if parent else ''
            return ''

        for node in ast.walk(tree):
            if not isinstance(node, ast.Call):
                continue
            name = qualified(node.func)
            if name in UNSUPPORTED:
                issues.append({'line': node.lineno, 'kind': 'unsupported',
                               'message': name + ' is unsupported by the Android launch adapter if executed.'})
            if name in ('launch_ros.actions.Node', 'launch_ros.actions.node.Node'):
                for keyword in node.keywords:
                    if keyword.arg is None:
                        issues.append({'line': node.lineno, 'kind': 'unresolved', 'message': 'Dynamic Node keyword arguments require build-time resolution.'})
                    elif keyword.arg not in NODE_FIELDS:
                        issues.append({'line': node.lineno, 'kind': 'unsupported', 'message': 'Unsupported Android Node field: ' + keyword.arg})
        issues.sort(key=lambda issue: (issue['line'], issue['message']))
    return {'files': reports, 'truncated': len(paths) > 128,
            'limits': 'Syntax only; imported helpers, aliases reassigned at runtime, includes, conditions and generated actions require build-time resolution. No workspace code is executed.'}
