#!/usr/bin/env python3
"""Extract a Debian alpha without root and test its real launcher with a fake phone."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('deb', type=Path)
    args = parser.parse_args()
    package = args.deb.resolve()

    with tempfile.TemporaryDirectory(prefix='ros2android-deb-test-') as temp:
        root = Path(temp)
        subprocess.run(['dpkg-deb', '-x', str(package), str(root / 'payload')], check=True)
        subprocess.run(['dpkg-deb', '-e', str(package), str(root / 'control')], check=True)
        assert set(p.name for p in (root / 'control').iterdir()) == {'control'}, (
            'Unexpected package maintainer scripts'
        )
        cli = root / 'payload/usr/bin/ros2-android'
        env = dict(os.environ, HOME=str(root / 'home'), XDG_CACHE_HOME=str(root / 'cache'))
        env.pop('PYTHONPATH', None)

        def run(*arguments):
            return subprocess.check_output([str(cli), *arguments], cwd=root, env=env, text=True)

        expected = json.loads(
            (
                Path(__file__).resolve().parents[1] / 'python/ros2android/compatibility.json'
            ).read_text()
        )['hostClientVersion']
        assert run('--version').strip() == expected, (
            'Packaged client version differs from the compatibility matrix'
        )
        assert 'usage:' in run('device', '--help')
        assert 'usage:' in run('workspace', '--help')
        sdk = root / 'sdk'
        adb = sdk / 'platform-tools/adb'
        adb.parent.mkdir(parents=True)
        log = root / 'adb.jsonl'
        adb.write_text("""#!/usr/bin/python3
import json, os, sys
with open(os.environ['ADB_TEST_LOG'], 'a') as output: output.write(json.dumps(sys.argv[1:]) + '\\n')
command = sys.argv[3:]
if command == ['get-state']: print('device')
elif command == ['shell', 'getprop', 'ro.build.version.sdk']: print('31')
elif command == ['shell', 'getprop', 'ro.product.cpu.abilist']: print('arm64-v8a')
elif command[:4] == ['install', '--user', 'current', '-r']: print('Success')
else: raise SystemExit(2)
""")
        adb.chmod(0o755)
        env.update(ANDROID_HOME=str(sdk), ADB_TEST_LOG=str(log))
        assert 'Success' in run('device', 'setup', '--serial', 'test-phone')
        calls = [json.loads(line) for line in log.read_text().splitlines()]
        assert len(calls) == 4
        assert calls[-1][:6] == ['-s', 'test-phone', 'install', '--user', 'current', '-r']
        apk = Path(calls[-1][6])
        assert apk.is_relative_to(root / 'payload')
        assert apk.stat().st_size > 0
        print(
            'PASS: Debian payload/control, installed launcher, backend help and explicit '
            'checksummed APK update via fake ADB; no root or real phone operations'
        )


if __name__ == '__main__':
    main()
