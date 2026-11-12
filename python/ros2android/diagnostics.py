"""Task-scoped, read-only prerequisite checks; no downloads or device connection."""
import hashlib
from importlib.resources import files
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys

PROFILES = ('host', 'android', 'shell', 'app')


def compatibility():
    return json.loads(files('ros2android').joinpath('compatibility.json').read_text())


def probe(command):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=5, check=False)
        return (result.stdout + result.stderr) if result.returncode == 0 else ''
    except (OSError, subprocess.TimeoutExpired):
        return ''


def diagnose(profile='android', tooling_root=None):
    if profile not in PROFILES:
        raise ValueError('Unknown diagnostic profile: ' + profile)
    matrix = compatibility()
    android = matrix['android']
    home = Path.home()
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or str(home / 'Android/Sdk')).expanduser()
    checks = {}

    def add(name, ok, found, action):
        checks[name] = {'status': 'ok' if ok else 'missing-or-incompatible',
                        'found': found, 'action': None if ok else action}

    def executable(path):
        return path.is_file() and os.access(path, os.X_OK)

    def tool(name, action):
        found = shutil.which(name)
        add(name, found is not None, found, action)

    add('host', platform.system() == matrix['host']['system'] and platform.machine() == matrix['host']['architecture'],
        platform.system() + '/' + platform.machine(), 'Use the Linux x86_64 reference build environment (Ubuntu 24.04).')
    add('client_python', sys.version_info[:2] >= tuple(matrix['host']['clientPythonMinimum']),
        platform.python_version(), 'Install Python 3.10+ for the host client.')
    ros_python = probe(['/usr/bin/python3', '--version'])
    version = re.search(r'Python (\d+)\.(\d+)', ros_python)
    add('ros_build_python', bool(version and [int(version[1]), int(version[2])] == matrix['host']['rosPython']),
        version[0] if version else None, 'The reference Jazzy build uses /usr/bin/python3 3.12 with ROS build dependencies.')
    setup = Path((os.environ.get('ROBOT_ROS_SETUP') or '/opt/ros/jazzy/setup.bash') if profile in ('host', 'android') else '/opt/ros/jazzy/setup.bash').expanduser()
    add('ros_setup', setup.is_file(), str(setup), 'Install ROS 2 Jazzy and its build dependencies; configure ROBOT_ROS_SETUP for an alternative workspace setup script.')
    requested = os.environ.get('COLCON_BIN') or str(home / '.local/share/ros2-android-tools/build-venv/bin/colcon')
    colcon = Path(shutil.which(requested) or requested if '/' not in requested else requested).expanduser()
    add('colcon', executable(colcon), str(colcon), 'Run ros2-android setup build-tools (source checkout: python3 setup/prepare-build-tools.py), or set COLCON_BIN to another colcon executable. A different colcon on PATH is not used by these backends.')
    for name in ('cmake', 'git', 'c++'):
        tool(name, f'Install the Linux {name} build prerequisite; see the development guide.')
    if profile != 'host':
        tool('ninja', 'Install Ninja for Android cross-builds.')
        java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
        output = probe([java, '-version']) if java else ''
        version = re.search(r'(?:openjdk|java)(?: version)?\s+"?(\d+)(?:\.(\d+))?', output)
        major = (int(version[2]) if version[1] == '1' and version[2] else int(version[1])) if version else None
        add('java', major == matrix['host']['javaMajor'], {'path': java, 'major': major}, 'Set JAVA_HOME to JDK 21, which runs this Gradle toolchain.')
        for name, path in {
            'sdk_manager': sdk / 'cmdline-tools/latest/bin/sdkmanager',
            'adb': sdk / 'platform-tools/adb',
            'aapt': sdk / f"build-tools/{android['buildTools']}/aapt",
            'apksigner': sdk / f"build-tools/{android['buildTools']}/apksigner",
        }.items():
            add(name, executable(path), str(path), 'Install official Android command-line/platform/build tools in ANDROID_HOME; tools do not need to be on PATH.')
        jar = sdk / f"platforms/android-{android['compileApi']}/android.jar"
        add('android_platform', jar.is_file(), str(jar), f"Install Android SDK platform {android['compileApi']}.")
        ndk = sdk / 'ndk' / android['ndk']
        properties = ndk / 'source.properties'
        revision = re.search(r'^Pkg.Revision\s*=\s*(\S+)', properties.read_text(), re.M) if properties.is_file() else None
        add('android_ndk', bool(revision and revision[1] == android['ndk'] and (ndk / 'build/cmake/android.toolchain.cmake').is_file() and executable(ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/clang')),
            {'path': str(ndk), 'revision': revision[1] if revision else None}, f"Install NDK {android['ndk']}; other NDK folders do not satisfy the pin.")
        cmake = sdk / f"cmake/{android['cmake']}/bin/cmake"
        output = probe([str(cmake), '--version']) if executable(cmake) else ''
        version = re.search(r'cmake version (\d+\.\d+\.\d+)', output)
        add('android_cmake', bool(version and version[1] == android['cmake']),
            {'path': str(cmake), 'version': version[1] if version else None}, f"Install Android SDK CMake {android['cmake']}.")
        root = Path(tooling_root) if tooling_root else None
        mismatched = []
        for relative, expected in matrix['sourceLocks'].items():
            lock = root / relative if root else None
            actual = hashlib.sha256(lock.read_bytes()).hexdigest() if lock and lock.is_file() else None
            if actual != expected:
                mismatched.append(relative)
        add('sdk_source_contract', not mismatched, {'mismatchedLocks': mismatched},
            'Run ros2-android setup sources for this client snapshot, or pass --tooling-root for a matching source checkout.')
        artifacts = {'native_ros': 'native-deps/install/lib/librcl.so',
                     'native_cpp': 'native-deps/install/lib/librclcpp.so'}
        if profile == 'app':
            artifacts.pop('native_cpp')
            artifacts.update(staged_ros='native-deps/apk-libs/arm64-v8a/librcl.so',
                             micro_ros_agent='native-deps/agent-apk-libs/arm64-v8a/libmicro_ros_agent_exec.so')
        if profile == 'shell':
            artifacts.update(python_ros='native-deps/python-packages/rclpy/_rclpy_pybind11.so',
                             python_runtime='native-deps/python-target/jniLibs/arm64-v8a/libpython3.12.so',
                             shell_sources='native-deps/python-packages/composition_interfaces/__init__.py')
        for name, relative in artifacts.items():
            path = root / relative if root else None
            add(name, bool(path and path.is_file()), str(path) if path else None,
                'Prepare the native SDK with setup native --with-cpp; shell also needs setup python, app needs setup agent. See clean-machine setup order.')
    failures = [name for name, row in checks.items() if row['status'] != 'ok']
    return {'schemaVersion': 1, 'version': matrix['hostClientVersion'], 'profile': profile,
            'toolingRoot': str(tooling_root) if tooling_root else None, 'checks': checks,
            'missingOrIncompatible': failures, 'prerequisitesPresent': not failures,
            'note': 'Read-only diagnostic, not build/ABI/runtime qualification. Artifact presence does not verify provenance or dependency closure; ROS setup is not sourced, SDK licenses and Python modules are not validated, and no phone is queried.'}
