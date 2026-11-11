#!/usr/bin/env python3
"""Maintainer: build a wheel with a tracked-source SDK snapshot; never ship local state."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SDK_DIRS = {'android', 'native', 'setup', 'tools', 'examples', 'gradle'}
SDK_FILES = {'LICENSE', 'NOTICE', 'CITATION.cff', 'gradlew', 'gradlew.bat', 'gradle.properties', 'build.gradle.kts', 'settings.gradle.kts'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=ROOT / 'dist')
    args = parser.parse_args()
    output = args.out.resolve(); output.mkdir(parents=True, exist_ok=True)
    tracked = subprocess.check_output(['git', '-C', str(ROOT), 'ls-files', '-z']).decode().split('\0')
    with tempfile.TemporaryDirectory(prefix='ros2android-package-') as temp:
        stage = Path(temp)
        for name in ('pyproject.toml', 'setup.py', 'README.md', 'LICENSE', 'NOTICE', 'CITATION.cff'):
            shutil.copy2(ROOT / name, stage / name)
        shutil.copytree(ROOT / 'python', stage / 'python', ignore=shutil.ignore_patterns('__pycache__', '*.egg-info', 'sdk.zip'))
        with zipfile.ZipFile(stage / 'python/ros2android/sdk.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
            for name in sorted(filter(None, tracked)):
                path = Path(name)
                if path.parts[0] in SDK_DIRS or name in SDK_FILES:
                    source = ROOT / path
                    if source.is_symlink():
                        raise ValueError('Refusing SDK symlink: ' + name)
                    archive.write(source, name)
        matrix_path = stage / 'python/ros2android/compatibility.json'
        matrix = json.loads(matrix_path.read_text())
        matrix['artifact'] = {'sdkSnapshotSha256': hashlib.sha256((stage / 'python/ros2android/sdk.zip').read_bytes()).hexdigest()}
        matrix_path.write_text(json.dumps(matrix, indent=2) + '\n')
        subprocess.run([sys.executable, '-m', 'pip', 'wheel', '--no-deps', '--no-build-isolation',
                        '--wheel-dir', str(output), str(stage)], check=True)
    print('Built local alpha wheel; public publication and licensing review remain pending.')


if __name__ == '__main__':
    main()
