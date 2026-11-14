#!/usr/bin/env python3
"""Stage a standalone Android CPython and the actual upstream ROS CLI in a workspace bundle."""

import argparse
import configparser
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
STDLIB_SHA = '3d2c518265718355b4ddbb7bb7a0fced519699c3184501185d2ed3150c3e9b06'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    args = parser.parse_args()
    deps = (ROOT / 'native-deps').resolve()
    target = deps / 'python-target'

    if not (deps / 'python-packages/composition_interfaces').is_dir():
        parser.error('Update the Python SDK first: ./setup/build-python-ros.sh')

    if not (deps / 'python-packages/rclpy').is_dir():
        parser.error('Build the Python SDK first: ./setup/build-python-ros.sh')

    subprocess.run(['python3', str(ROOT / 'setup/prepare-python-target.py')], check=True)
    stdlib = target / 'stdlib-pyc.zip'
    if not stdlib.exists():
        cached = list(
            (
                Path.home()
                / '.gradle/caches/modules-2/files-2.1/com.chaquo.python/target/3.12.15-0'
            ).glob('*/target-3.12.15-0-stdlib-pyc.zip')
        )
        for candidate in cached:
            if hashlib.sha256(candidate.read_bytes()).hexdigest() == STDLIB_SHA:
                shutil.copy2(candidate, stdlib)
                break

    if not stdlib.exists():
        urllib.request.urlretrieve(
            'https://repo.maven.apache.org/maven2/com/chaquo/python/target/3.12.15-0/'
            'target-3.12.15-0-stdlib-pyc.zip',
            stdlib,
        )

    if hashlib.sha256(stdlib.read_bytes()).hexdigest() != STDLIB_SHA:
        raise ValueError('Android stdlib checksum mismatch')

    assets = args.bundle / 'assets/ros2-shell'
    assets.mkdir(parents=True, exist_ok=True)
    packages = assets / 'site-packages'
    shutil.copytree(
        deps / 'python-packages',
        packages,
        dirs_exist_ok=True,
        ignore=shutil.ignore_patterns('__pycache__'),
    )
    shutil.copy2(stdlib, assets / 'stdlib.zip')
    shutil.copytree(target / 'lib-dynload/arm64-v8a', assets / 'lib-dynload', dirs_exist_ok=True)
    libs = args.bundle / 'jniLibs/arm64-v8a'

    # Use the target Python SDK's compatible ROS underlay; workspace executables remain unchanged.
    for source in [
        *(deps / 'python-apk-libs/arm64-v8a').glob('*.so'),
        *(target / 'jniLibs/arm64-v8a').glob('*.so'),
    ]:
        shutil.copy2(source, libs / source.name)

    host = Path('/opt/ros/jazzy/lib/python3.12/site-packages')
    lock = json.loads((ROOT / 'native/ros-shell-packages.lock.json').read_text())
    provenance = {}

    for module, version in lock['rosPackages'].items():
        shutil.copytree(
            host / module,
            packages / module,
            dirs_exist_ok=True,
            ignore=shutil.ignore_patterns('__pycache__'),
        )
        metadata = list(host.glob(module + '-*.egg-info'))
        if len(metadata) != 1:
            raise ValueError('Expected one installed Jazzy distribution for ' + module)

        shutil.copytree(metadata[0], packages / metadata[0].name, dirs_exist_ok=True)
        provenance[module] = re.search(
            r'^Version: (.+)$', (metadata[0] / 'PKG-INFO').read_text(), re.MULTILINE
        )[1]
        if provenance[module] != version:
            raise ValueError(
                'Expected Jazzy ' + module + ' ' + version + '; review/update the shell lock'
            )

    # Register the core command bundle from the lock. Daemon discovery remains
    # disabled on Android; command implementations stay upstream.
    for metadata in [
        packages / p.name for p in host.glob('ros2*-*.egg-info') if (packages / p.name).exists()
    ]:
        entries = metadata / 'entry_points.txt'
        config = configparser.ConfigParser()
        config.optionxform = str
        config.read(entries)

        if config.has_option('ros2cli.command', 'daemon'):
            config.remove_option('ros2cli.command', 'daemon')

        with entries.open('w') as output:
            config.write(output)

    # Direct discovery avoids the daemon's unbundled psutil dependency on Android.
    daemon = packages / 'ros2cli/daemon/__init__.py'
    text = daemon.read_text()
    original = 'from ros2cli.node.network_aware import NetworkAwareNode'
    if text.count(original) != 1 or '    ros_domain_id = get_ros_domain_id()' not in text:
        raise ValueError('Review Android daemon import patch for this ros2cli source')

    daemon.write_text(
        text.replace(original, '').replace(
            '    ros_domain_id = get_ros_domain_id()',
            '    from ros2cli.node.network_aware import NetworkAwareNode\n    '
            'ros_domain_id = get_ros_domain_id()',
            1,
        )
    )
    server = packages / 'ros2cli/xmlrpc/local_server.py'
    text = server.read_text()
    if text.count('import psutil') != 1 or text.count('def get_local_ipaddrs():') != 1:
        raise ValueError('Review Android lazy XMLRPC dependency patch for this ros2cli source')

    server.write_text(
        text.replace('import psutil', '').replace(
            'def get_local_ipaddrs():', 'def get_local_ipaddrs():\n    import psutil'
        )
    )
    strategy = packages / 'ros2cli/node/strategy.py'
    text = strategy.read_text()
    original = "use_daemon = not getattr(args, 'no_daemon', False)"
    if text.count(original) != 1:
        raise ValueError('Review Android direct-discovery patch for this ros2cli source')

    strategy.write_text(
        'import os\n'
        + text.replace(original, original + " and os.environ.get('ROS2CLI_NO_DAEMON') != '1'")
    )
    # ros2node uses rosidl_runtime_py, which imports NumPy. Reuse the pinned
    # Android wheel resolution from the Python runner, never host NumPy ELF.
    wheels = ROOT / 'android/pythonRunner/build/python/pip/debug/common'
    if not (wheels / 'numpy').is_dir():
        subprocess.run(
            [str(ROOT / 'setup/build.sh'), ':pythonRunner:generateDebugPythonRequirements'],
            check=True,
        )

    metadata = wheels / 'numpy-1.26.2.dist-info/METADATA'
    if not metadata.is_file():
        raise ValueError('Expected pinned Android NumPy 1.26.2 wheel')

    shutil.copytree(
        wheels / 'numpy',
        packages / 'numpy',
        dirs_exist_ok=True,
        ignore=shutil.ignore_patterns('__pycache__', 'tests'),
    )
    vendor = packages / 'chaquopy/lib'
    vendor.mkdir(parents=True, exist_ok=True)

    for name in ['libopenblas.so', 'libgfortran.so.3']:
        shutil.copy2(wheels / 'chaquopy/lib' / name, vendor / name)

    provenance['numpy'] = '1.26.2'

    # Pure Python dependencies. No host ELF extensions are copied.
    for module in lock['purePythonDependencies']:
        source = Path('/usr/lib/python3/dist-packages') / module
        if source.is_dir():
            shutil.copytree(
                source,
                packages / module,
                dirs_exist_ok=True,
                ignore=shutil.ignore_patterns('*.so', '__pycache__'),
            )
        elif source.with_suffix('.py').is_file():
            shutil.copy2(source.with_suffix('.py'), packages / (module + '.py'))
        else:
            raise ValueError('Missing pure-Python shell dependency: ' + module)

    # Full interface resources and Python helpers from the target SDK.
    shutil.copytree(
        deps / 'python-assets/ros2-python-sdk/share',
        args.bundle / 'assets/ros2-workspace/share',
        dirs_exist_ok=True,
    )

    for module in lock['rosPackages']:
        resources = Path('/opt/ros/jazzy/share') / module
        if resources.is_dir():
            shutil.copytree(
                resources, args.bundle / 'assets/ros2-workspace/share' / module, dirs_exist_ok=True
            )
            marker = (
                args.bundle
                / 'assets/ros2-workspace/share/ament_index/resource_index/packages'
                / module
            )
            marker.parent.mkdir(parents=True, exist_ok=True)
            marker.touch()

    (assets / 'capabilities.json').write_text(
        json.dumps(
            {
                'commands': sorted(
                    m[4:] for m in lock['rosPackages'] if m.startswith('ros2') and m != 'ros2cli'
                ),
                'unsupported': lock['unsupported'],
            },
            indent=2,
        )
        + '\n'
    )
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
    compiler = sdk / (
        'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang'
    )
    subprocess.run(
        [
            str(compiler),
            '-O2',
            '-fPIE',
            '-pie',
            '-I' + str(target / 'include/python3.12'),
            str(ROOT / 'native/shell/python_main.c'),
            '-L' + str(libs),
            '-lpython3.12',
            '-Wl,-rpath,$ORIGIN',
            '-Wl,-z,max-page-size=16384,-z,common-page-size=16384',
            '-o',
            str(libs / 'libros2_python_exec.so'),
        ],
        check=True,
    )
    (assets / 'provenance.json').write_text(
        json.dumps(
            {
                'python': '3.12.15',
                'stdlibSha256': STDLIB_SHA,
                'hostRosCli': provenance,
                'androidPatches': ['direct-discovery', 'lazy-daemon-imports'],
            },
            indent=2,
        )
        + '\n'
    )
    print('Staged native Android ROS CLI:', json.dumps(provenance))


if __name__ == '__main__':
    main()
