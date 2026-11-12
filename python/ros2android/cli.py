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
from .diagnostics import PROFILES, compatibility, diagnose


class MissingSdk(RuntimeError):
    pass


def sdk_root():
    archive = files('ros2android').joinpath('sdk.zip')
    if not archive.is_file():
        # Source checkouts run the backends in place, like the tools/ compatibility wrappers.
        source = Path(__file__).resolve().parents[2]
        if (source / 'native/ros-sources.lock.json').is_file():
            return source
        raise MissingSdk('This ros2-android installation has no bundled SDK snapshot. Install a wheel built with '
                         'setup/build-python-package.py; a plain pip build of the source omits it.')
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


def existing_tooling_root():
    """Locate prepared inputs without extracting the SDK or changing caches."""
    source = Path(__file__).resolve().parents[2]
    if (source / 'native/ros-sources.lock.json').is_file():
        return source
    archive = files('ros2android').joinpath('sdk.zip')
    if archive.is_file():
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()[:16]
        cache = Path(os.environ.get('XDG_CACHE_HOME', Path.home() / '.cache')) / 'ros2-android/tooling' / digest
        if (cache / '.complete').is_file():
            return cache
    return None


def doctor(arguments):
    parser = argparse.ArgumentParser(prog='ros2-android doctor', description='Check prerequisites without installation or phone access.')
    parser.add_argument('--profile', choices=PROFILES, default='android', help='host Linux build; android C++ workspace; shell workspace with ROS CLI; app main APK')
    parser.add_argument('--tooling-root', type=Path, help='Matching source checkout or extracted SDK (otherwise auto-detect existing inputs)')
    args = parser.parse_args(arguments)
    root = args.tooling_root.expanduser().resolve() if args.tooling_root else existing_tooling_root()
    report = diagnose(args.profile, root)
    print(json.dumps(report, indent=2))
    return 0 if report['prerequisitesPresent'] else 1


def main():
    parser = argparse.ArgumentParser(description='Build, deploy and operate native ROS 2 workspaces on Android.')
    parser.add_argument('--version', action='version', version=__version__)
    parser.add_argument('command', choices=['apk', 'doctor', 'compatibility', 'workspace', 'project', 'esp32', 'shell', 'setup', 'device'])
    # Preserve backend flags and ROS arguments verbatim; --help belongs to the selected command.
    if len(sys.argv) <= 1 or sys.argv[1] in ('-h', '--help', '--version'):
        parser.parse_args()
        return 0
    args = parser.parse_args(sys.argv[1:2])
    remaining = sys.argv[2:]
    if args.command == 'apk':
        from .apk import main as apk_main
        return apk_main(remaining)
    if args.command == 'doctor':
        return doctor(remaining)
    if args.command == 'compatibility':
        argparse.ArgumentParser(prog='ros2-android compatibility', description='Print the bundled preview compatibility matrix.').parse_args(remaining)
        print(json.dumps(compatibility(), indent=2))
        return 0
    if args.command == 'setup' and remaining[:1] == ['build-tools']:
        from .build_tools import run as build_tools
        return build_tools(remaining[1:])  # Host tool environment; needs no bundled SDK.
    try:
        root = sdk_root()
    except MissingSdk as error:
        print('ros2-android: ' + str(error), file=sys.stderr)
        return 1
    if args.command == 'project':
        from .project import run
        return run(remaining, tooling_root=root, prog='ros2-android project')
    if args.command == 'workspace':
        from .workspace import run
        return run(remaining, tooling_root=root, prog='ros2-android workspace')
    if args.command == 'device':
        device_parser = argparse.ArgumentParser(prog='ros2-android device')
        device_parser.add_argument('action', choices=['setup'])
        device_parser.add_argument('--serial', help='Authorized ADB target; otherwise select the single USB phone')
        selected = device_parser.parse_args(remaining)
        artifacts = files('ros2android').joinpath('artifacts')
        apk = Path(str(artifacts.joinpath('main.apk')))
        manifest = Path(str(artifacts.joinpath('main.json')))
        if not apk.is_file() or not manifest.is_file():
            device_parser.error('No bundled APK in this distribution. Install the ROS2 Android Debian package with its APK.')
        command = [str(root / 'setup/run-device.sh'), '--apk', str(apk),
                   '--bundle-manifest', str(manifest), '--install-only']
        if selected.serial:
            command += ['--serial', selected.serial]
        return subprocess.call(command)
    if args.command == 'setup':
        setup_parser = argparse.ArgumentParser(prog='ros2-android setup')
        setup_parser.add_argument('action', choices=['build-tools', 'sources', 'app-build', 'app-install', 'native', 'python', 'agent'])
        selected, extra = setup_parser.parse_known_args(remaining)
        if selected.action == 'sources':
            from .native_sources import run
            return run(extra, tooling_root=root)
        script = {'sources': 'fetch-native-ros.py', 'app-build': 'build.sh',
                  'app-install': 'run-device.sh', 'native': 'build-native-ros.sh',
                  'python': 'build-python-ros.sh', 'agent': 'build-micro-ros-agent.sh'}[selected.action]
        path = root / 'setup' / script
        command = [sys.executable, str(path)] if script.endswith('.py') else [str(path)]
        return subprocess.call([*command, *extra], cwd=root)
    if args.command == 'esp32':
        from .esp32 import run
        return run(remaining)
    if args.command == 'shell':
        from .shell import run
        return run(remaining)
    raise AssertionError('Unrouted command: ' + args.command)


if __name__ == '__main__':
    raise SystemExit(main())
