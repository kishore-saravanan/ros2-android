"""Stable installed entry point; existing build backends run from a private cache."""
import argparse
import hashlib
from importlib.resources import files
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

from . import __version__


def sdk_root():
    archive = files('ros2android').joinpath('sdk.zip')
    data = archive.read_bytes()
    digest = hashlib.sha256(data).hexdigest()[:16]
    cache = Path(os.environ.get('XDG_CACHE_HOME', Path.home() / '.cache')) / 'ros2-android' / 'tooling'
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / digest
    if (target / '.complete').is_file():
        return target
    stage = Path(tempfile.mkdtemp(prefix='.extract-', dir=cache))
    try:
        with archive.open('rb') as stream, zipfile.ZipFile(stream) as bundle:
            for item in bundle.infolist():
                relative = Path(item.filename)
                if relative.is_absolute() or '..' in relative.parts:
                    raise ValueError('Invalid bundled SDK path')
                path = stage / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(bundle.read(item))
                path.chmod(0o755 if (item.external_attr >> 16) & 0o111 else 0o644)
        (stage / '.complete').write_text(digest)
        try:
            stage.rename(target)
        except OSError:
            if not (target / '.complete').is_file():
                raise
    finally:
        if stage.exists():
            shutil.rmtree(stage)
    return target


def doctor():
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Android/Sdk'))))
    checks = {name: shutil.which(name) for name in ('java', 'cmake', 'ninja', 'colcon', 'ros2')}
    checks['adb'] = str(sdk / 'platform-tools/adb') if (sdk / 'platform-tools/adb').is_file() else shutil.which('adb')
    checks['android_platform_35'] = (sdk / 'platforms/android-35').is_dir()
    checks['android_ndk_27'] = (sdk / 'ndk/27.2.12479018').is_dir()
    print(json.dumps({'version': __version__, 'checks': checks,
                      'note': 'Presence checks only; source ROS and consult the setup guide for versions and runtime qualification.'}, indent=2))
    return 0 if all(checks.values()) else 1


def main():
    parser = argparse.ArgumentParser(description='Build, deploy and operate native ROS 2 workspaces on Android.')
    parser.add_argument('--version', action='version', version=__version__)
    parser.add_argument('command', choices=['doctor', 'workspace', 'project', 'esp32', 'shell', 'setup'])
    # Preserve backend flags and ROS arguments verbatim; --help belongs to the selected command.
    if len(sys.argv) <= 1 or sys.argv[1] in ('-h', '--help', '--version'):
        parser.parse_args()
        return 0
    args = parser.parse_args(sys.argv[1:2])
    remaining = sys.argv[2:]
    if args.command == 'doctor':
        if remaining:
            parser.error('doctor takes no arguments')
        return doctor()
    root = sdk_root()
    if args.command == 'setup':
        setup_parser = argparse.ArgumentParser(prog='ros2-android setup')
        setup_parser.add_argument('action', choices=['sources', 'app-build', 'app-install', 'native', 'python', 'agent'])
        selected, extra = setup_parser.parse_known_args(remaining)
        script = {'sources': 'fetch-native-ros.py', 'app-build': 'build.sh',
                  'app-install': 'run-device.sh', 'native': 'build-native-ros.sh',
                  'python': 'build-python-ros.sh', 'agent': 'build-micro-ros-agent.sh'}[selected.action]
        path = root / 'setup' / script
        command = [sys.executable, str(path)] if script.endswith('.py') else [str(path)]
        return subprocess.call([*command, *extra], cwd=root)
    backend = {'workspace': 'ros2android-workspace', 'project': 'ros2android',
               'esp32': 'esp32-via-phone', 'shell': 'connect-shell'}[args.command]
    path = root / 'tools' / backend
    command = [sys.executable, str(path)]
    return subprocess.call([*command, *remaining])


if __name__ == '__main__':
    raise SystemExit(main())
