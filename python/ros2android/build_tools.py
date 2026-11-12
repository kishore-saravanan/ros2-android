"""Prepare the managed colcon/vcstool build environment used by the native build backends.

The backends default to ``~/.local/share/ros2-android-tools/build-venv/bin/colcon``.
The venv keeps ``--system-site-packages`` so host ROS build modules stay visible.
When the host already provides colcon/vcstool (for example ROS's ``ros-dev-tools``
APT packages), pip reports the pinned requirements as satisfied and installs no
console scripts in the venv. This helper then reinstalls only the script-providing
distributions at the already visible versions with ``--no-deps``, so dependency
versions such as setuptools still come from the host.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

PINNED = ('colcon-common-extensions==0.3.0', 'vcstool==0.3.0')
SCRIPTS = {'colcon': 'colcon-core', 'vcs': 'vcstool'}


def default_venv():
    return Path.home() / '.local/share/ros2-android-tools/build-venv'


def _run(command):
    subprocess.run([str(part) for part in command], check=True)


def _executable(path):
    return path.is_file() and os.access(path, os.X_OK)


def visible_version(python, distribution):
    """Version of a distribution as seen by the venv interpreter, including host packages."""
    script = 'import importlib.metadata as m, sys; print(m.version(sys.argv[1]))'
    return subprocess.run([str(python), '-c', script, distribution], check=True,
                          capture_output=True, text=True).stdout.strip()


def prepare(venv, base_python='/usr/bin/python3'):
    venv = Path(venv).expanduser().absolute()
    python = venv / 'bin/python'
    created = not python.is_file()
    if created:
        try:
            _run([base_python, '-m', 'venv', '--system-site-packages', venv])
        except subprocess.CalledProcessError as error:
            raise RuntimeError(f'Cannot create {venv} with {base_python}. Install the OS Python venv '
                               'package (Ubuntu 24.04: python3.12-venv) and retry.') from error
    if subprocess.run([str(python), '-m', 'pip', '--version'], capture_output=True).returncode:
        raise RuntimeError(f'{venv} has no pip. Bootstrap pip into that venv as the development guide '
                           'describes, or recreate it after installing the OS Python venv package.')
    _run([python, '-m', 'pip', 'install', *PINNED])
    reinstalled = {}
    for script, distribution in SCRIPTS.items():
        if _executable(venv / 'bin' / script):
            continue
        version = visible_version(python, distribution)
        if not version:
            raise RuntimeError(f'{distribution} is not visible to {python}')
        # Host packages satisfied the requirement without creating this venv's script.
        _run([python, '-m', 'pip', 'install', '--no-deps', '--ignore-installed', f'{distribution}=={version}'])
        reinstalled[distribution] = version
    missing = [script for script in SCRIPTS if not _executable(venv / 'bin' / script)]
    if missing:
        raise RuntimeError('Build tool scripts missing after installation: ' + ', '.join(missing))
    check = subprocess.run([str(venv / 'bin/colcon'), '--help'], capture_output=True, text=True)
    if check.returncode:
        raise RuntimeError('colcon does not start: ' + (check.stderr.strip() or check.stdout.strip())[-500:])
    return {'schemaVersion': 1, 'venv': str(venv), 'created': created,
            'colcon': str(venv / 'bin/colcon'), 'vcs': str(venv / 'bin/vcs'),
            'scriptsReinstalledFromHostVersions': reinstalled}


def main(arguments=None):
    parser = argparse.ArgumentParser(prog='ros2-android setup build-tools', description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--venv', type=Path, default=default_venv(),
                        help='Managed venv (default: %(default)s); set COLCON_BIN to use another colcon')
    parser.add_argument('--python', default='/usr/bin/python3',
                        help='Base interpreter used to create a new venv (default: the ROS build Python)')
    args = parser.parse_args(arguments)
    print(json.dumps(prepare(args.venv, args.python), indent=2))


def run(arguments=None):
    try:
        main(arguments)
        return 0
    except KeyboardInterrupt:
        return 130
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        print('build tools: ' + str(error), file=sys.stderr)
        return 1
