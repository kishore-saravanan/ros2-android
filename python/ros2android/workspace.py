"""Build/deploy ordinary colcon C++ workspaces as Android process-runner APKs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from .project_manifest import load_manifest
from .workspace_inspection import EXCLUDED, inventory, inspect_dependencies, default_host_prefixes


def digest_tree(path):
    digest = hashlib.sha256()
    for item in sorted(p for p in path.rglob('*') if p.is_file() and not EXCLUDED.intersection(p.relative_to(path).parts)):
        digest.update(item.relative_to(path).as_posix().encode()); digest.update(b'\0')
        digest.update(item.read_bytes()); digest.update(b'\0')
    return digest.hexdigest()


def elf_check(path):
    header = subprocess.check_output(['readelf', '-h', str(path)], text=True)
    if 'AArch64' not in header:
        raise ValueError('Refusing non-Android ARM64 artifact: ' + str(path))
    dynamic = subprocess.check_output(['readelf', '-d', str(path)], text=True)
    needed = set(re.findall(r'Shared library: \[([^\]]+)\]', dynamic))
    if any('/' in name for name in needed):
        raise ValueError('Non-portable shared-library dependency: ' + str(path))
    return needed


def stage(workspace, cache, project_id, launch, launch_arguments, with_shell=False, *, tooling_root):
    root = Path(tooling_root)
    prefix, sdk = cache / ('android-shell' if with_shell else 'android') / 'install', (root / 'native-deps/install').resolve()
    bundle, project = cache / 'bundle', cache / 'runner-project'
    if bundle.exists():
        shutil.rmtree(bundle)
    native = bundle / 'jniLibs/arm64-v8a'; native.mkdir(parents=True)
    share = bundle / 'assets/ros2-workspace/share'; share.mkdir(parents=True)
    for source in (sdk / 'share', prefix / 'share'):
        shutil.copytree(source, share, dirs_exist_ok=True)
    hashes, needed = {}, {}
    for source in [*sdk.rglob('*.so'), *prefix.rglob('*.so')]:
        if 'site-packages' in source.parts:
            continue
        dependencies = elf_check(source)
        sha = hashlib.sha256(source.read_bytes()).hexdigest()
        if source.name in hashes and hashes[source.name] != sha:
            raise ValueError('Shared-library collision: ' + source.name)
        hashes[source.name] = sha; needed[source.name] = dependencies
        shutil.copy2(source, native / source.name)
    sdk_home = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or str(Path.home() / 'Android/Sdk'))
    cxx = sdk_home / 'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so'
    needed[cxx.name] = elf_check(cxx)
    shutil.copy2(cxx, native / cxx.name)
    components = {}
    used = set()
    for path in sorted((prefix / 'lib').glob('*/*')):
        if not path.is_file() or path.name.endswith('.so') or not os.access(path, os.X_OK):
            continue
        if path.read_bytes()[:4] != b'\x7fELF':
            raise ValueError('Non-native installed executable is unsupported: ' + str(path))
        dependencies = elf_check(path)
        headers = subprocess.check_output(['readelf', '-l', str(path)], text=True)
        if '/system/bin/linker64' not in headers:
            raise ValueError('Expected Android PIE executable: ' + str(path))
        key = path.parent.name + '/' + path.name
        component = re.sub(r'[^A-Za-z0-9_]', '_', path.parent.name + '__' + path.name)
        if component in used:
            raise ValueError('Executable component name collision: ' + component)
        used.add(component); components[key] = component
        name = 'libworkspace_exec_' + component + '.so'
        needed[name] = dependencies
        shutil.copy2(path, native / name)
    if not components:
        raise ValueError('Workspace installed no native executables under lib/<package>')
    (bundle / 'assets/ros2-workspace/executables.json').write_text(json.dumps(components, indent=2) + '\n')
    if with_shell:
        subprocess.run([sys.executable, str(root / 'setup/prepare-ros-shell.py'), str(bundle)], check=True)
        # Include this workspace's target-generated Python message bindings too.
        for site in (prefix / 'lib').glob('python*/site-packages'):
            for package in site.iterdir():
                if package.is_dir() and not package.name.endswith(('.egg-info', '.dist-info', '__pycache__')):
                    destination = bundle / 'assets/ros2-shell/site-packages' / package.name
                    shutil.copytree(package, destination, dirs_exist_ok=True, ignore=shutil.ignore_patterns('__pycache__'))
                    for extension in destination.rglob('*.so'):
                        dependencies = elf_check(extension)
                        name = re.sub(r'\.cpython-[^.]+', '', extension.name)
                        if name != extension.name: extension.rename(extension.with_name(name))
                        if not dependencies <= (set(p.name for p in native.glob('*.so')) | {'libc.so','libm.so','libdl.so','liblog.so','libz.so','libandroid.so'}):
                            raise ValueError('Missing target Python extension dependency: ' + str(extension))
        for library in native.glob('*.so'):
            needed[library.name] = elf_check(library)
    provided = set(needed) | {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libz.so', 'libc++_shared.so'}
    for name, dependencies in needed.items():
        if dependencies - provided:
            raise ValueError('Missing Android dependencies for ' + name + ': ' + str(sorted(dependencies - provided)))
    project.mkdir(parents=True, exist_ok=True)
    manifest = {'schemaVersion': 1, 'id': project_id, 'name': workspace.name,
                'applicationId': 'dev.ros2android.android.runner.ws_' + project_id,
                'runtime': 'workspace', 'runtimeAbi': 2, 'defaultLaunch': 'launch.json',
                'launchComponents': components, 'bundleRoot': str(bundle),
                'workspace': str(workspace), 'workspaceSourceHash': digest_tree(workspace / 'src'),
                'workspaceLaunch': str(launch), 'withShell': with_shell, 'bundleHash': digest_tree(bundle)}
    (project / 'robot-project.json').write_text(json.dumps(manifest, indent=2) + '\n')
    arguments = {}
    for value in launch_arguments:
        key, separator, text = value.partition(':=')
        if not separator or key in arguments:
            raise ValueError('Use unique launch arguments NAME:=VALUE')
        arguments[key] = text
    subprocess.run([str(root / 'tools/compile-ros-launch.sh'), '--project', str(project),
                    '--launch', str(launch), '--arguments-json', json.dumps(arguments),
                    '--output', str(project / 'launch.json')], check=True, timeout=30)
    return project


JOURNEY = """typical journey:
  inspect      check packages and dependency providers (no build, no phone)
  build        cross-build the runner APK (--without-shell for C++ only)
  deploy       install the runner on the USB-connected phone
  start        start its nodes (blank addresses: phone-local/automatic discovery)
  status/logs  check state, nodes, background pauses and runner output
  ros -- ...   observe from this PC, e.g. ros -- topic echo /android_test/result
  stop         cooperative stop of the supervised nodes

host-build builds the same workspace for this PC; ros then decodes its custom messages."""


def bundled_controller():
    """The release app shipped inside the Debian package, if this installation has one."""
    from importlib.resources import files
    apk = Path(str(files('ros2android').joinpath('artifacts/main.apk')))
    return apk if apk.is_file() else None


def main(arguments=None, *, tooling_root, prog=None):
    root = Path(tooling_root)
    parser = argparse.ArgumentParser(prog=prog, description=__doc__, epilog=JOURNEY,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--workspace', type=Path, required=True)
    parser.add_argument('--id', help='Unique cache/project ID (default: workspace directory name)')
    parser.add_argument('--serial', help='ADB serial when several devices are connected')
    commands = parser.add_subparsers(dest='command', required=True, metavar='command')
    inspect = commands.add_parser('inspect', help='Inspect packages and declared dependency providers without building')
    commands.add_parser('host-build', help='Build the workspace for this PC (parity checks; message types for ros)')
    build = commands.add_parser('build', help='Cross-build the workspace into an Android runner APK')
    for options in (inspect, build):
        shell = options.add_mutually_exclusive_group()
        shell.add_argument('--with-shell', dest='with_shell', action='store_true', default=True,
                           help='Include the Python/shell SDK in the selected build profile (default)')
        shell.add_argument('--without-shell', dest='with_shell', action='store_false',
                           help='Select the C++-only profile without the ROS CLI')
    build.add_argument('--launch', type=Path)
    controller = build.add_mutually_exclusive_group()
    controller.add_argument('--controller-apk', type=Path,
                            help='Main app APK allowed to manage this runner (default: the app bundled with this ros2-android, if any)')
    controller.add_argument('--same-signature', action='store_true',
                            help='Build for a main app you built and signed yourself (no certificate pinning)')
    build.add_argument('--launch-arg', action='append', default=[])
    observe = commands.add_parser('ros', help='Run ROS 2 CLI on this PC with matching middleware and message overlay')
    observe.add_argument('--interface', default='', help='PC IPv4 address to use for discovery (default: automatic)')
    observe.add_argument('ros_command', nargs=argparse.REMAINDER, help='ros2 arguments after --')
    forwarded_help = {'deploy': 'Install the built runner APK on the phone (the sensor app is untouched)',
                      'start': 'Start the deployed workspace; options are forwarded to the project start command',
                      'status': 'Show runner state, nodes and health', 'stop': 'Request a cooperative stop',
                      'kill': 'Force-stop only this runner (recovery tests)',
                      'logs': "Stream this runner's logs; Ctrl+C stops viewing",
                      'launch-plan': 'Print the resolved launch plan without starting nodes'}
    for command, description in forwarded_help.items():
        commands.add_parser(command, add_help=False, help=description)
    args, forward = parser.parse_known_args(arguments)
    if args.command in ('inspect', 'host-build', 'build', 'ros') and forward:
        parser.error('Unknown arguments: ' + str(forward))
    if args.command == 'build' and args.with_shell and not (root / 'native-deps/python-packages/composition_interfaces').is_dir():
        parser.error('The standard CLI needs the Python SDK: run ros2-android setup python, or use build --without-shell for C++ only')
    workspace = args.workspace.expanduser().resolve()
    project_id = args.id or workspace.name
    if args.command != 'inspect' and not re.fullmatch(r'[a-z][a-z0-9_]{0,63}', project_id):
        parser.error('Use --id with lowercase letters/digits/underscores')
    packages = inventory(workspace, allow_unsupported=args.command == 'inspect') if args.command in ('inspect', 'host-build', 'build') else []
    if args.command == 'inspect':
        prefixes = ([root / 'native-deps/python-install'] if args.with_shell else []) + [root / 'native-deps/install']
        report = inspect_dependencies(packages, prefixes, default_host_prefixes())
        print(json.dumps({'workspace': str(workspace), 'packages': packages,
                          'sourceHash': digest_tree(workspace / 'src'),
                          'inspection': report}, indent=2))
        return
    cache = Path.home() / '.cache/ros2-android-workspaces' / project_id
    owner = cache / 'workspace-root.txt'
    if owner.exists() and owner.read_text() != str(workspace):
        parser.error('Cache ID belongs to another workspace; choose --id')
    project = cache / 'runner-project'
    if args.command == 'ros':
        command = args.ros_command[1:] if args.ros_command[:1] == ['--'] else args.ros_command
        if not command:
            parser.error('Provide ros2 arguments after --, e.g. ros -- topic list')
        overlay = cache / 'host/install/local_setup.bash'
        if not overlay.is_file():
            print('Note: no PC build of this workspace; custom message types need host-build first.', file=sys.stderr)
        wrapper = [str(root / 'tools/pc-ros.sh'), *(['--interface', args.interface] if args.interface else [])]
        subprocess.run([*wrapper, '--', 'ros2', *command], check=True,
                       env=dict(os.environ, ROS2_ANDROID_PROJECT_OVERLAY=str(overlay)))
        return
    if args.command in ('host-build', 'build'):
        cache.mkdir(parents=True, exist_ok=True)
        owner.write_text(str(workspace))
    elif not (project / 'robot-project.json').is_file():
        parser.error('No built workspace for this ID; run workspace --workspace ' + str(workspace) + ' build first')
    if args.command == 'host-build':
        subprocess.run([str(root / 'setup/build-colcon-workspace.sh'), str(workspace), str(cache / 'host'), 'host'], check=True); return
    if args.command == 'build':
        subprocess.run([str(root / 'setup/build-colcon-workspace.sh'), str(workspace), str(cache / ('android-shell' if args.with_shell else 'android')), 'android', str(args.with_shell).lower()], check=True)
        files = sorted((cache / ('android-shell' if args.with_shell else 'android') / 'install/share').rglob('*.launch.py'))
        launch = args.launch.expanduser().resolve() if args.launch else (files[0] if len(files) == 1 else None)
        if launch is None: parser.error('Select --launch when the workspace has zero or several launch files')
        project = stage(workspace, cache, project_id, launch, args.launch_arg, args.with_shell, tooling_root=root)
        forwarded = 'build'
        controller = args.controller_apk or (None if args.same_signature else bundled_controller())
        if controller:
            if not args.controller_apk:
                print(f'Using the bundled ROS2 Android app as the controller: {controller}', flush=True)
            forward += ['--controller-apk', str(Path(controller).expanduser().resolve())]
    else:
        manifest = load_manifest(project)
        if args.command in ('deploy', 'start', 'launch-plan') and manifest['workspaceSourceHash'] != digest_tree(workspace / 'src'):
            parser.error('Workspace source changed; rebuild before deployment/start')
        if args.command in ('start', 'launch-plan') and '--launch-arg' in forward and '--launch' not in forward:
            forward += ['--launch', manifest['workspaceLaunch']]
        forwarded = args.command
    command = [str(root / 'tools/ros2android'), '--project', str(project)]
    if args.serial: command += ['--serial', args.serial]
    subprocess.run([*command, forwarded, *forward], check=True)


def run(arguments=None, *, tooling_root, prog=None):
    """Return a stable CLI failure code without exposing an implementation traceback."""
    try:
        main(arguments, tooling_root=tooling_root, prog=prog)
        return 0
    except KeyboardInterrupt:
        return 130  # Ctrl+C ends streams such as `ros -- topic echo` and `logs`.
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError, ET.ParseError) as error:
        print('ros2android-workspace: ' + str(error), file=sys.stderr)
        return 1
