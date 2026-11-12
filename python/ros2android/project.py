"""PC-side development commands for packaged Android C++ and Python ROS projects."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import tempfile
from .robot_launch import NAMESPACE, validate_launch
from .controller_identity import controller_certificate
from .project_manifest import load_manifest


def source_id(project):
    digest = hashlib.sha256()
    excluded = {'build', '.git', '.cxx', '.gradle', '__pycache__'}
    paths = sorted(p for p in project.rglob('*') if p.is_file() and not excluded.intersection(p.relative_to(project).parts))
    for path in paths:
        digest.update(path.relative_to(project).as_posix().encode())
        digest.update(b'\0'); digest.update(path.read_bytes()); digest.update(b'\0')
    return digest.hexdigest()[:12]


def validate_start(domain, namespace, peer, local, arguments):
    """Check the runner's bounded start profile before any build or device call."""
    if not 0 <= domain <= 101:
        raise ValueError('ROS domain must be 0–101')
    if namespace != '/' and not NAMESPACE.fullmatch(namespace):
        raise ValueError('Use an absolute ROS namespace, for example /robot')
    for label, address in (('Peer', peer), ('Phone', local)):
        if address and (not re.fullmatch(r'[0-9]{1,3}(?:\.[0-9]{1,3}){3}', address)
                        or any(int(octet) > 255 for octet in address.split('.'))):
            raise ValueError(label + ' address must be IPv4, or blank for automatic discovery')
    result = list(arguments)
    if result and result[0] == '--':
        result = result[1:]
    if result and result[0] == '--ros-args':
        result = result[1:]
    if len(result) > 128 or any(len(value.encode('utf-16-le')) // 2 > 4096 or '\0' in value for value in result):
        raise ValueError('ROS arguments exceed runner limits (128 arguments, 4096 characters each, no NUL)')
    return result


def main(arguments=None, *, tooling_root):
    root = Path(tooling_root)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, default=root / 'examples/cpp_talker')
    parser.add_argument('--serial', help='Optional ADB serial; otherwise use the single physical device')
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('inspect', help='Validate project compatibility without building or connecting to a phone')
    commands.add_parser('sdk', help='Build pinned Android ROS dependencies for the selected runtime')
    commands.add_parser('host-sdk', help='Prepare checksum-pinned PC Cyclone libraries in the user cache')
    commands.add_parser('host-interfaces', help='Build this project custom messages for the PC')
    ros = commands.add_parser('ros', help='Run ROS CLI with matching middleware and this project interface overlay')
    ros.add_argument('--interface', default='')
    ros.add_argument('ros_command', nargs=argparse.REMAINDER)
    build = commands.add_parser('build', help='Cross-build project into a debug runner APK')
    build.add_argument('--launch-arg', action='append', default=[], metavar='NAME:=VALUE')
    build.add_argument('--controller-apk', type=Path, help='Pin the public certificate of this verified main APK')
    plan = commands.add_parser('launch-plan', help='Resolve and print a launch configuration without starting nodes')
    plan.add_argument('--launch', type=Path)
    plan.add_argument('--launch-arg', action='append', default=[], metavar='NAME:=VALUE')
    plan.add_argument('--namespace', default='/demo')
    commands.add_parser('deploy', help='Install the built project APK without touching the sensor app')
    start = commands.add_parser('start', help='Open the project app and start it through shell-only debug control')
    start.add_argument('--launch', type=Path, help='JSON or supported .launch.py override; otherwise use packaged plan')
    start.add_argument('--launch-arg', action='append', default=[], metavar='NAME:=VALUE')
    start.add_argument('--domain', type=int, default=0)
    start.add_argument('--namespace', help='Default: /demo for C++, /python for Python')
    start.add_argument('--peer', default='')
    start.add_argument('--local', default='')
    start.add_argument('ros_args', nargs=argparse.REMAINDER, help='ROS flags after --, e.g. -- -p message:=Hello')
    commands.add_parser('stop', help='Request cooperative project shutdown')
    commands.add_parser('status', help='Show installed project source ID and native heartbeat')
    commands.add_parser('kill', help='Force-stop only this generated runner for recovery/isolation tests')
    commands.add_parser('logs', help='Stream only this runner process logs; Ctrl+C stops viewing')
    args = parser.parse_args(arguments)
    project = args.project.expanduser().resolve()
    manifest = load_manifest(project)
    package = manifest['applicationId']
    if hasattr(args, 'namespace') and args.namespace is None:
        args.namespace = '/python' if manifest.get('runtime') == 'python' else '/demo'
    if args.command == 'inspect':
        print(json.dumps({'project': str(project), 'manifest': manifest,
                          'compatibility': {'schemaVersion': 1, 'runtime': manifest.get('runtime', 'cpp'),
                                            'runtimeAbi': manifest['runtimeAbi']},
                          'sourceId': source_id(project)}, indent=2))
        return
    if args.command == 'start':
        args.ros_args = validate_start(args.domain, args.namespace, args.peer, args.local, args.ros_args)
    launch = None
    launch_arguments = {}
    for argument in getattr(args, 'launch_arg', []):
        name, separator, value = argument.partition(':=')
        if not separator or not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', name) or name in launch_arguments:
            parser.error('Use unique launch arguments NAME:=VALUE')
        launch_arguments[name] = value
    # Start with no override uses the exact plan baked into the installed APK.
    resolve = args.command in ('build', 'launch-plan') or (args.command == 'start' and
                (args.launch is not None or bool(launch_arguments)))
    if resolve:
        launch_path = getattr(args, 'launch', None)
        if launch_path is None and manifest.get('defaultLaunch'):
            launch_path = project / manifest['defaultLaunch']
        if launch_path:
            if manifest['runtimeAbi'] != 2:
                parser.error('Launch components require runtime ABI 2')
            launch_path = launch_path.expanduser().resolve()
            if launch_path.name.endswith('.launch.py'):
                with tempfile.TemporaryDirectory(prefix='ros2-android-launch-') as folder:
                    output = Path(folder) / 'plan.json'
                    subprocess.run([str(root / 'tools/compile-ros-launch.sh'), '--project', str(project),
                                    '--launch', str(launch_path), '--namespace', getattr(args, 'namespace', '/demo'),
                                    '--arguments-json', json.dumps(launch_arguments), '--output', str(output)],
                                   check=True, timeout=30)
                    launch = validate_launch(json.loads(output.read_text()), getattr(args, 'namespace', '/demo'))
            elif launch_path.suffix == '.json':
                if launch_arguments:
                    parser.error('Launch arguments require a .launch.py file')
                launch = validate_launch(json.loads(launch_path.read_text()), getattr(args, 'namespace', '/demo'))
            else:
                parser.error('Use a .launch.py or .json launch file')
        elif manifest['runtimeAbi'] == 2 or launch_arguments or args.command == 'launch-plan':
            parser.error('Project has no launch file; set defaultLaunch or pass --launch')
    runtime = manifest.get('runtime', 'cpp')
    if runtime not in ('cpp', 'python', 'workspace'):
        parser.error('Unsupported project runtime')
    module = {'cpp': 'runner', 'python': 'pythonRunner', 'workspace': 'workspaceRunner'}[runtime]
    apk = root / f'android/{module}/build/outputs/apk/debug/{module}-debug.apk'
    build_info = root / f'android/{module}/build/outputs/robot-project.json'
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Android/Sdk'))))
    adb = sdk / 'platform-tools/adb'
    selection = ['-s', args.serial] if args.serial else ['-d']

    def device(*arguments, capture=True):
        command = [str(adb), *selection, *arguments]
        if capture:
            return subprocess.check_output(command, text=True)
        subprocess.run(command, check=True)

    def remote(*arguments):
        # ADB's remote shell joins arguments. Quote the entire remote command explicitly.
        return device('shell', shlex.join(str(v) for v in arguments))

    def control(action, extras=()):
        output = remote('am', 'broadcast', '--include-stopped-packages', '-n', package + '/dev.ros2android.runner.DebugControlReceiver',
                        '-a', 'dev.ros2android.runner.' + action, *extras)
        match = re.search(r'Broadcast completed: result=(-?\d+)(?:, data="(.*)")?', output)
        if not match or match[1] != '0':
            raise RuntimeError(output.strip())
        data = match[2]
        if not data:
            raise RuntimeError('Runner returned no status: ' + output)
        try:
            return json.loads(data)
        except json.JSONDecodeError:
            return json.loads(json.loads('"' + data + '"'))

    if args.command == 'sdk':
        subprocess.run([str(root / 'setup/build-python-ros.sh')] if runtime == 'python' else
                       [str(root / 'setup/build-native-ros.sh'), '--with-cpp'], cwd=root, check=True)
    elif args.command == 'host-sdk':
        subprocess.run([sys.executable, str(root / 'setup/setup-pc-cyclone.py')], check=True)
    elif args.command == 'host-interfaces':
        subprocess.run([str(root / 'setup/build-project-interfaces.sh'), str(project), manifest['id']], check=True)
    elif args.command == 'ros':
        command = args.ros_command
        if command and command[0] == '--':
            command = command[1:]
        if not command:
            parser.error('Provide ROS subcommands after --, e.g. ros -- param list')
        wrapper = [str(root / 'tools/pc-ros.sh')]
        if args.interface:
            wrapper += ['--interface', args.interface]
        overlay = Path.home() / '.cache/ros2-android-projects' / manifest['id'] / 'host/install/local_setup.bash'
        env = dict(os.environ, ROS2_ANDROID_PROJECT_OVERLAY=str(overlay))
        subprocess.run([*wrapper, '--', 'ros2', *command], env=env, check=True)
    elif args.command == 'launch-plan':
        print(json.dumps(launch, indent=2, ensure_ascii=False))
    elif args.command == 'build':
        build_options = []
        if launch is not None:
            plan_path = root / 'device-logs/launch-plans' / (manifest['id'] + '.json')
            plan_path.parent.mkdir(parents=True, exist_ok=True)
            plan_path.write_text(json.dumps(launch, ensure_ascii=False, indent=2) + '\n')
            build_options = ['-ProbotLaunchPlan=' + str(plan_path)]
        if args.controller_apk:
            build_options += ['-PcontrollerCertificateSha256=' + controller_certificate(args.controller_apk)]
        subprocess.run([str(root / 'setup/build.sh'), f':{module}:assembleDebug', f':{module}:lintDebug',
                        '-ProbotProjectDir=' + str(project), *build_options], cwd=root, check=True)
        print(json.dumps({'apk': str(apk), 'sourceId': source_id(project), 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest()}))
    elif args.command == 'deploy':
        if not build_info.is_file() or not apk.is_file():
            raise ValueError('No built runner APK/metadata for this runtime. Run build successfully before deploy.')
        info = json.loads(build_info.read_text())
        if info['applicationId'] != package or info['sourceId'] != source_id(project):
            raise RuntimeError('Built APK does not match this project/source. Run build first.')
        command = [str(root / 'setup/run-device.sh'), {'cpp': '--runner', 'python': '--python-runner', 'workspace': '--workspace-runner'}[runtime], package, '--install-only']
        if args.serial:
            command += ['--serial', args.serial]
        subprocess.run(command, cwd=root, check=True)
        print('Installed project ' + package)
    elif args.command == 'start':
        policy = remote('dumpsys', 'window', 'policy')
        if 'showing=true' in policy:
            raise RuntimeError('Unlock the phone before starting a project. Security settings are unchanged.')
        ros_args = args.ros_args
        print(remote('am', 'start', '-W', '-n', package + '/dev.ros2android.runner.MainActivity').strip())
        deadline = time.monotonic() + 10
        retry_at = time.monotonic() + 2
        while time.monotonic() < deadline:
            activity = remote('dumpsys', 'activity', 'activities')
            if any(package + '/dev.ros2android.runner.MainActivity' in line
                   for line in activity.splitlines() if 'mResumedActivity:' in line):
                break
            if time.monotonic() >= retry_at:
                if 'showing=true' in remote('dumpsys', 'window', 'policy'):
                    raise RuntimeError('Unlock the phone before starting a project.')
                # Some devices discard the first post-install activity launch.
                remote('am', 'start', '-W', '-n', package + '/dev.ros2android.runner.MainActivity')
                retry_at = float('inf')
            time.sleep(.2)
        else:
            raise RuntimeError('Runner did not become foreground. Unlock the phone and retry start.')
        installed = control('STATUS')
        if installed.get('sourceId') != source_id(project) or installed.get('project') != manifest['id']:
            raise RuntimeError('Installed source differs from the local project. Rebuild and deploy before starting.')
        if installed.get('native', {}).get('running') or installed.get('busy'):
            raise RuntimeError('Project is already running or changing state. Stop it before starting with new arguments.')
        extras = ['--ei', 'domain', str(args.domain), '--es', 'namespace', args.namespace,
                  '--es', 'peer', args.peer, '--es', 'local', args.local,
                  '--es', 'ros_args', json.dumps(ros_args, ensure_ascii=False)]
        if launch is not None:
            extras += ['--es', 'launch_json', json.dumps(launch, ensure_ascii=False)]
        info = control('START', extras)
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            info = control('STATUS')
            if info.get('state', '').startswith(('Failed', 'Failing')):
                raise RuntimeError(json.dumps(info, ensure_ascii=False))
            if info.get('state') == 'Running' and not info.get('busy') and info.get('native', {}).get('running'):
                break
            time.sleep(.2)
        else:
            raise RuntimeError('Project did not start: ' + json.dumps(info))
        if info.get('sourceId') != source_id(project):
            raise RuntimeError('Installed source differs from the local project. Rebuild/deploy or switch --project.')
        print(json.dumps(info, indent=2, ensure_ascii=False))
    elif args.command == 'status':
        print(json.dumps(control('STATUS'), indent=2, ensure_ascii=False))
    elif args.command == 'stop':
        info = control('STOP')
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline and (info.get('busy') or info.get('native', {}).get('running') or info.get('native', {}).get('transition')):
            time.sleep(.2); info = control('STATUS')
        if (info.get('native', {}).get('running') or info.get('native', {}).get('transition')
                or info.get('busy') or info.get('state', '').startswith('Stop failed')):
            raise RuntimeError('Cooperative stop did not finish; inspect status/logs before retrying. '
                               'Use kill only to force-stop this runner: ' + json.dumps(info))
        print(json.dumps(info, indent=2))
    elif args.command == 'kill':
        print(remote('am', 'force-stop', package).strip() or ('Force-stopped ' + package))
    elif args.command == 'logs':
        pid = remote('pidof', package).strip()
        if not pid.isdigit():
            raise RuntimeError('Runner process is not running')
        device('logcat', '--pid=' + pid, capture=False)


def run(arguments=None, *, tooling_root):
    try:
        main(arguments, tooling_root=tooling_root)
        return 0
    except KeyboardInterrupt:
        return 130
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print('ros2android: ' + str(error), file=sys.stderr)
        return 1
