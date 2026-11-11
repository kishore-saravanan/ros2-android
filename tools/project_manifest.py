"""Versioned source-manifest contract shared by host project/workspace commands."""
import json
from pathlib import Path
import re

SCHEMA_VERSION = 1
RUNTIME_ABIS = {'cpp': (1, 2), 'python': (1,), 'workspace': (2,)}
PACKAGE_PATTERN = r'dev\.ros2android\.android\.runner\.[a-z][a-z0-9_]*'


def validate_manifest(manifest):
    """Validate known fields; allow additive metadata for forward compatibility."""
    if not isinstance(manifest, dict):
        raise ValueError('Project manifest must be a JSON object')
    schema = manifest.get('schemaVersion')
    if type(schema) is not int or schema != SCHEMA_VERSION:
        raise ValueError(f'Unsupported schemaVersion {schema!r}; this client supports 1. Use a matching SDK/client.')
    for field, pattern in (('id', r'[a-z][a-z0-9_]{0,63}'), ('applicationId', PACKAGE_PATTERN)):
        value = manifest.get(field)
        if not isinstance(value, str) or not re.fullmatch(pattern, value):
            raise ValueError(f'Invalid {field}: {value!r}. Expected ' +
                             ('a lowercase project ID (up to 64 characters).' if field == 'id' else
                              'dev.ros2android.android.runner.<lowercase_name>; refusing to control another app.'))
    runtime = manifest.get('runtime', 'cpp')
    if not isinstance(runtime, str) or runtime not in RUNTIME_ABIS:
        raise ValueError(f'Unsupported runtime {runtime!r}; supported: cpp, python, workspace')
    abi = manifest.get('runtimeAbi')
    if type(abi) is not int or abi not in RUNTIME_ABIS[runtime]:
        raise ValueError(f'Unsupported runtimeAbi {abi!r} for {runtime}; supported: {RUNTIME_ABIS[runtime]}. Use a matching SDK/client.')
    if 'name' in manifest and (not isinstance(manifest['name'], str) or not manifest['name'].strip()):
        raise ValueError('Project name must be a nonempty string')
    for field in ('defaultLaunch', 'pythonSourceDir'):
        if field not in manifest:
            continue
        value = manifest[field]
        if (not isinstance(value, str) or not value or '\x00' in value or
                Path(value).is_absolute() or '..' in Path(value).parts):
            raise ValueError(f'{field} must be a nonempty project-relative path without parent traversal')
    if 'defaultLaunch' in manifest and abi != 2:
        raise ValueError('defaultLaunch requires runtimeAbi 2')
    components = manifest.get('launchComponents', {})
    if not isinstance(components, dict) or any(
            not isinstance(key, str) or not re.fullmatch(r'[A-Za-z0-9_]+/[A-Za-z0-9_.-]+', key) or
            not isinstance(value, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', value)
            for key, value in components.items()):
        raise ValueError('launchComponents must map package/executable names to component IDs')
    return manifest


def load_manifest(project):
    path = Path(project) / 'robot-project.json'
    try:
        manifest = json.loads(path.read_text())
    except FileNotFoundError as error:
        raise ValueError(f'No project manifest at {path}. For an ordinary colcon workspace, use the workspace command and build it first.') from error
    except (json.JSONDecodeError, UnicodeError) as error:
        raise ValueError(f'Invalid JSON in {path}: {error}') from error
    try:
        return validate_manifest(manifest)
    except ValueError as error:
        raise ValueError(f'{path}: {error}') from error
