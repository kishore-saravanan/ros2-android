"""Connect to a deployed workspace's native Android ROS shell over authorized ADB."""

import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
from .android_tools import adb_path


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--workspace', type=Path, default=Path('examples/android_test_ws'))
    parser.add_argument('--id', help='Cache ID used when building the workspace')
    parser.add_argument(
        '--serial',
        help='Authorized ADB device (including an already connected wireless ADB device)',
    )
    parser.add_argument(
        'command',
        nargs=argparse.REMAINDER,
        help='Optional command after --; otherwise open an interactive phone shell',
    )
    args = parser.parse_args(arguments)
    workspace = args.workspace.expanduser().resolve()
    project_id = args.id or workspace.name
    if not re.fullmatch(r'[a-z][a-z0-9_]{0,63}', project_id):
        parser.error('Use --id with lowercase letters/digits/underscores, matching the build')

    cache = Path.home() / '.cache/ros2-android-workspaces' / project_id
    if (
        not (cache / 'workspace-root.txt').is_file()
        or not (cache / 'runner-project/robot-project.json').is_file()
    ):
        parser.error('No built workspace for this ID; build with shell support and deploy first')

    if (cache / 'workspace-root.txt').read_text() != str(workspace):
        parser.error('Workspace/cache mismatch; use the same --workspace and --id as build')

    manifest = json.loads((cache / 'runner-project/robot-project.json').read_text())
    if not manifest.get('withShell'):
        parser.error('Build with --with-shell, then deploy before connecting')

    package = manifest['applicationId']
    if not re.fullmatch(r'dev\.ros2android\.android\.runner\.[a-z][a-z0-9_]*', package):
        parser.error('Refusing application outside this project runner namespace')

    adb = [adb_path(), *(['-s', args.serial] if args.serial else ['-d'])]

    def remote(*command):
        return subprocess.check_output([*adb, 'shell', shlex.join(command)], text=True)

    if 'showing=true' in remote('dumpsys', 'window', 'policy'):
        parser.error('Unlock the phone first; no security settings are changed')

    remote('am', 'start', '-W', '-n', package + '/dev.ros2android.runner.MainActivity')
    response = remote(
        'am',
        'broadcast',
        '--include-stopped-packages',
        '-n',
        package + '/dev.ros2android.runner.DebugControlReceiver',
        '-a',
        'dev.ros2android.runner.PREPARE_SHELL',
    )
    match = re.search(r'Broadcast completed: result=(-?\d+)(?:, data="(.*)")?', response)
    if not match or match[1] != '0':
        raise RuntimeError('Shell preparation failed: ' + response.strip())

    env = match[2]
    expected = f'/data/user/0/{package}/files/ros2-shell/environment.sh'
    if env != expected:
        raise RuntimeError('Unexpected shell environment path; use the primary Android user')

    command = args.command
    if command and command[0] == '--':
        command = command[1:]

    script = '. ' + shlex.quote(env) + '; '
    script += 'exec ' + (shlex.join(command) if command else '/system/bin/sh -i')
    # run-as enters only this debug runner UID; executables remain in APK read-only libs.
    target = shlex.join(['run-as', package, '/system/bin/sh', '-c', script])
    os.execv(adb[0], [*adb, 'shell', *([] if command else ['-t']), target])


def run(arguments=None):
    try:
        main(arguments)
        return 0
    except KeyboardInterrupt:
        return 130
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        print('connect-shell: ' + str(error), file=sys.stderr)
        return 1
