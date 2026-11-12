#!/usr/bin/env python3
"""Offline-install a built wheel and exercise it without checkout imports or SDK/device access."""
import argparse
from email.parser import Parser
import hashlib
import io
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile
import venv
import zipfile


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def require_safe_paths(names):
    forbidden = {'device-logs', '.git', 'local.properties', 'native-deps', '.gradle', '.cxx',
                 '.pio', '__pycache__', 'build', 'install', 'log', 'dist'}
    for name in names:
        relative = Path(name)
        require(not relative.is_absolute() and '..' not in relative.parts, 'Unsafe artifact path: ' + name)
        require(not forbidden.intersection(relative.parts), 'Private/generated artifact path: ' + name)
        require(relative.suffix not in {'.jks', '.keystore', '.pem', '.key'}, 'Signing material in artifact: ' + name)


def check_archive(wheel):
    with zipfile.ZipFile(wheel) as archive:
        names = archive.namelist()
        require_safe_paths(names)
        for legal in ('LICENSE', 'NOTICE'):
            require(any('.dist-info/' in name and name.endswith('/' + legal) for name in names), 'Missing ' + legal)
        metadata = [name for name in names if name.endswith('.dist-info/METADATA')]
        require(len(metadata) == 1, 'Expected one wheel metadata entry')
        version = Parser().parsestr(archive.read(metadata[0]).decode())['Version']
        sdk_bytes = archive.read('ros2android/sdk.zip')
        matrix = json.loads(archive.read('ros2android/compatibility.json'))
        require(matrix['hostClientVersion'] == version, 'Wheel metadata and compatibility version differ')
        require(matrix['artifact']['sdkSnapshotSha256'] == hashlib.sha256(sdk_bytes).hexdigest(), 'Bundled SDK snapshot hash mismatch')
        with zipfile.ZipFile(io.BytesIO(sdk_bytes)) as sdk:
            require_safe_paths(sdk.namelist())
            require({'gradlew', 'LICENSE', 'NOTICE', 'CITATION.cff'}.issubset(sdk.namelist()), 'Incomplete SDK snapshot')
        return matrix


def fingerprint(root):
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() if p.is_file() else None
            for p in root.rglob('*')}


def verify(wheel):
    matrix = check_archive(wheel)
    with tempfile.TemporaryDirectory(prefix='ros2android-wheel-test-') as temp:
        root = Path(temp)
        # The invoking Python needs pip>=22.3. The tested venv deliberately has no pip/ensurepip.
        venv.EnvBuilder(with_pip=False, symlinks=True).create(root / 'venv')
        python = root / 'venv/bin/python'
        install = subprocess.run([sys.executable, '-m', 'pip', '--isolated', '--disable-pip-version-check',
            '--python', str(python), 'install', '--no-index', '--no-deps', '--no-cache-dir', str(wheel)],
            capture_output=True, text=True, timeout=120)
        require(install.returncode == 0, 'Offline wheel installation failed (invoke with pip>=22.3): ' + install.stderr)
        cli = root / 'venv/bin/ros2-android'
        env = {key: value for key, value in os.environ.items() if key not in {
            'PYTHONPATH', 'PYTHONHOME', 'VIRTUAL_ENV', 'AMENT_PREFIX_PATH', 'CMAKE_PREFIX_PATH',
            'COLCON_PREFIX_PATH', 'ROS_DISTRO', 'PYTHONOPTIMIZE'}}
        (root / 'home').mkdir()
        env.update(HOME=str(root / 'home'), XDG_CACHE_HOME=str(root / 'cache'), ANDROID_HOME=str(root / 'missing-sdk'),
            ANDROID_SDK_ROOT=str(root / 'missing-sdk'), COLCON_BIN=str(root / 'missing-colcon'),
            ROBOT_ROS_SETUP=str(root / 'missing-ros/setup.bash'), PYTHONNOUSERSITE='1')
        blocked = root / 'blocked-tools.log'
        guard = root / 'guard'; guard.mkdir()
        for name in ('adb', 'sdkmanager', 'ros2', 'colcon', 'vcs', 'git'):
            executable = guard / name
            executable.write_text('#!/bin/sh\nprintf "%s\\n" "' + name + '" >> "$ROS2_ANDROID_PACKAGE_PROBE_LOG"\nexit 99\n')
            executable.chmod(0o755)
        env['ROS2_ANDROID_PACKAGE_PROBE_LOG'] = str(blocked)
        env['PATH'] = str(guard) + os.pathsep + env.get('PATH', os.defpath)

        def run(*arguments, code=0):
            result = subprocess.run([str(cli), *arguments], cwd=root, env=env, capture_output=True, text=True, timeout=30)
            require(result.returncode == code, f'CLI {arguments} exited {result.returncode}: {result.stderr}')
            return result.stdout

        require(run('--version').strip() == matrix['hostClientVersion'], 'Installed CLI version mismatch')
        require(json.loads(run('compatibility')) == matrix, 'Installed compatibility payload mismatch')
        diagnosis = json.loads(run('doctor', code=1))
        require({'colcon', 'ros_setup', 'android_ndk', 'native_ros'}.issubset(diagnosis['missingOrIncompatible']),
                'Missing prerequisites were not diagnosed')
        require(not (root / 'cache').exists(), 'Doctor/compatibility unexpectedly extracted the SDK')
        require('usage:' in run('apk', '--help'), 'Missing APK command help')
        require('error' in json.loads(run('apk', 'inspect', str(root / 'missing.apk'), code=2)), 'Missing APK did not fail clearly')
        require(not (root / 'cache').exists(), 'APK checks unexpectedly extracted the SDK')
        for command in ('doctor', 'compatibility', 'workspace', 'project', 'shell', 'esp32', 'setup'):
            require('usage:' in run(command, '--help'), 'Missing help for ' + command)
        package = root / 'External workspace/src/demo'; package.mkdir(parents=True)
        (package / 'package.xml').write_text('<package><name>demo</name><depend>missing_driver</depend>'
            '<depend condition="$ROS_VERSION == 1">optional_driver</depend>'
            '<export><build_type>ament_cmake</build_type></export></package>')
        (package / 'CMakeLists.txt').write_text('message(FATAL_ERROR "Inspection must not execute build code")\n')
        workspace = package.parents[1]
        original = fingerprint(workspace)
        for _ in range(2):
            result = json.loads(run('workspace', '--workspace', str(workspace), 'inspect', '--without-shell'))
            require(result['packages'][0]['name'] == 'demo', 'External workspace missing')
            require(result['inspection']['summary']['not-found'] == 2, 'Expected missing dependency report')
            conditional = next(r for r in result['inspection']['dependencies'] if r['dependency'] == 'optional_driver')
            require(conditional['conditionEvaluation']['state'] == 'unresolved', 'Missing optional evaluator must not silently drop dependencies')
            require('launchAnalysis' in result['inspection'], 'Missing bounded syntax-only launch diagnostics')
            require(not any(p['indexPresent'] for p in result['inspection']['targetPrefixes']), 'Unexpected prepared SDK')
        for command in ('start', 'status', 'stop'):
            run('workspace', '--workspace', str(workspace), '--id', 'external_test', command, code=2)
        require(not (root / 'home/.cache/ros2-android-workspaces').exists(),
                'Unbuilt workspace operations created cache state')
        run('workspace', '--workspace', str(workspace), '--id', 'external_test', 'host-build', '--typo', code=2)
        require(not (root / 'home/.cache/ros2-android-workspaces').exists(),
                'Invalid build arguments created cache state')
        require(fingerprint(workspace) == original, 'Inspection modified the external workspace')
        require(not blocked.exists(), 'Unexpected build/device/network tool invocation')
        require(not (root / 'missing-sdk').exists(), 'Unexpected Android SDK writes')
        return {'schemaVersion': 1, 'scope': 'installed-host-client-no-ROS-or-device',
            'clientVersion': matrix['hostClientVersion'], 'pythonVersion': platform.python_version(),
            'sdkSnapshotSha256': matrix['artifact']['sdkSnapshotSha256'],
            'checks': ['archive/legal/private-input validation', 'offline installation in fresh venv',
                'version and compatibility', 'missing-prerequisite diagnostics without extraction',
                'eight command help screens and missing-APK rejection without extraction', 'repeatable read-only external-workspace inspection',
                'unbuilt operations and invalid build flags fail without cache writes',
                'no guarded tool invocation'],
            'limits': 'No native SDK build, ROS traffic, signing upgrade, phone deployment or full clean-machine qualification.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('wheel', type=Path)
    parser.add_argument('--report', type=Path, help='Write a shareable result without local paths or device data')
    args = parser.parse_args()
    report = verify(args.wheel.resolve())
    output = json.dumps(report, indent=2) + '\n'
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(output)
    print(output, end='')


if __name__ == '__main__':
    main()
