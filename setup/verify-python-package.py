#!/usr/bin/env python3
"""Install a built wheel into a disposable environment and exercise it outside the checkout."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import venv
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('wheel', type=Path)
    args = parser.parse_args()
    wheel = args.wheel.resolve()
    with zipfile.ZipFile(wheel) as archive:
        for legal in ('LICENSE', 'NOTICE'):
            assert any('.dist-info/' in name and name.endswith('/' + legal) for name in archive.namelist()), legal
        sdk_bytes = archive.read('ros2android/sdk.zip')
        matrix = json.loads(archive.read('ros2android/compatibility.json'))
        assert matrix['artifact']['sdkSnapshotSha256'] == hashlib.sha256(sdk_bytes).hexdigest()
        with zipfile.ZipFile(io.BytesIO(sdk_bytes)) as sdk:
            forbidden = {'device-logs', '.git', 'local.properties', 'native-deps', '.gradle', '.cxx', '.pio', '__pycache__', 'build'}
            assert not any(forbidden.intersection(Path(name).parts) for name in sdk.namelist())
            assert {'gradlew', 'LICENSE', 'NOTICE', 'CITATION.cff'}.issubset(sdk.namelist())
    with tempfile.TemporaryDirectory(prefix='ros2android-wheel-test-') as temp:
        root = Path(temp)
        venv.EnvBuilder(with_pip=True).create(root / 'venv')
        python = root / 'venv/bin/python'
        subprocess.run([str(python), '-m', 'pip', 'install', '--no-index', '--no-deps', str(wheel)], check=True, capture_output=True)
        cli = root / 'venv/bin/ros2-android'
        env = dict(os.environ, XDG_CACHE_HOME=str(root / 'cache'))
        env.pop('PYTHONPATH', None)
        def run(*arguments):
            return subprocess.check_output([str(cli), *arguments], cwd=root, env=env, text=True)
        assert run('--version').strip() == '0.1.0a1'
        diagnosis = subprocess.run([str(cli), 'doctor'], cwd=root, env=env, capture_output=True, text=True)
        assert diagnosis.returncode in (0, 1)
        assert 'checks' in json.loads(diagnosis.stdout)
        matrix = json.loads(run('compatibility'))
        assert matrix['hostClientVersion'] == run('--version').strip()
        assert not (root / 'cache').exists(), 'Doctor/compatibility must not extract the SDK'
        for command in ('workspace', 'project', 'shell', 'esp32', 'setup'):
            assert 'usage:' in run(command, '--help')
        package = root / 'example_ws/src/demo'
        package.mkdir(parents=True)
        (package / 'package.xml').write_text('<package><name>demo</name><export><build_type>ament_cmake</build_type></export></package>')
        # The existing backend records cache ownership under HOME; isolate that too.
        env['HOME'] = str(root / 'home')
        result = json.loads(run('workspace', '--workspace', str(package.parents[1]), 'inspect'))
        assert result['packages'][0]['name'] == 'demo'
        print('PASS: installed CLI, five backend help commands, external workspace inspection, and private-artifact exclusion')


if __name__ == '__main__':
    main()
