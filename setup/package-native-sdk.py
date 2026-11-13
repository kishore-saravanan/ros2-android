#!/usr/bin/env python3
"""Package the built native SDK as a relocatable, checksummed archive for release.

Run after `setup native --with-cpp`, `setup python` and `setup agent`, from a clean checkout. The
archive holds only built outputs, their license texts and the project's notices; sources, build
trees and logs stay behind. Users install it with `ros2-android setup sdk --archive FILE`.
Build official archives on a neutral machine such as CI: binaries can keep the build path in
debug strings, which installation does not rewrite.
"""
import argparse
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'python'))
from ros2android.native_sdk import ABI, CONTENT, FORMAT, MANIFEST, NOTICES, REQUIRED, inputs_digest, sha256_file  # noqa: E402


def git(*arguments):
    return subprocess.run(['git', '-C', str(ROOT), *arguments], check=True, capture_output=True, text=True).stdout.strip()


def files_under(native):
    """Every SDK path (directories, files and links) in a stable order."""
    paths = []
    for entry in CONTENT:
        top = native / entry
        paths.append(top)
        if top.is_dir() and not top.is_symlink():
            for directory, dirs, names in os.walk(top):
                dirs.sort()
                base = Path(directory)
                paths += [base / name for name in sorted(dirs + names)]
    return paths


def normalized(epoch):
    def apply(info):
        info.uid = info.gid = 0
        info.uname = info.gname = ''
        info.mtime = epoch
        if info.isdir() or info.mode & 0o111:
            info.mode = 0o755
        elif info.isfile():
            info.mode = 0o644
        return info
    return apply


def package(native, out, allow_dirty=False):
    native = Path(native).resolve()
    missing = [path for path in REQUIRED if not (native / path).exists()]
    absent = [entry for entry in CONTENT if not (native / entry).exists()]
    if missing or absent:
        raise RuntimeError('Build the SDK first (setup native --with-cpp, setup python, setup agent). Missing: '
                           + ', '.join(missing + absent))
    revision = git('rev-parse', 'HEAD')
    dirty = bool(git('status', '--porcelain', '--untracked-files=no'))
    if dirty and not allow_dirty:
        raise RuntimeError('Tracked files differ from HEAD; commit first or pass --allow-dirty for a trial')
    epoch = int(os.environ.get('SOURCE_DATE_EPOCH') or git('log', '-1', '--format=%ct'))
    matrix = json.loads((ROOT / 'python/ros2android/compatibility.json').read_text())
    prefix = str(native).encode()
    paths = files_under(native)
    text, binary = [], 0
    for path in paths:
        if path.is_file() and not path.is_symlink():
            data = path.read_bytes()
            if prefix in data:
                if b'\0' in data[:8192]:
                    binary += 1
                else:
                    text.append(path.relative_to(native).as_posix())
    version = matrix['hostClientVersion']
    manifest = {
        'format': FORMAT, 'abi': ABI, 'version': version, 'ros': matrix['ros']['distribution'],
        'android': {key: matrix['android'][key] for key in ('minApi', 'ndk')},
        'source': {'revision': revision, 'clean': not dirty},
        'inputsDigest': inputs_digest(ROOT), 'prefix': str(native),
        'textFilesWithPrefix': text, 'binaryFilesWithPrefix': binary,
        'contents': list(CONTENT),
    }
    out.mkdir(parents=True, exist_ok=True)
    archive = out / f'ros2-android-native-sdk-{version}-{ABI}.tar.xz'
    partial = archive.with_name(archive.name + '.partial')
    apply = normalized(epoch)
    with tarfile.open(partial, 'w:xz', format=tarfile.PAX_FORMAT, preset=6) as tar:
        data = (json.dumps(manifest, indent=2) + '\n').encode()
        info = apply(tarfile.TarInfo(MANIFEST))
        info.size = len(data)
        tar.addfile(info, io.BytesIO(data))
        for name in NOTICES:
            tar.add(ROOT / name, arcname=name, recursive=False, filter=apply)
        for path in paths:
            tar.add(path, arcname=path.relative_to(native).as_posix(), recursive=False, filter=apply)
    os.replace(partial, archive)
    digest = sha256_file(archive)
    archive.with_name(archive.name + '.sha256').write_text(f'{digest}  {archive.name}\n')
    return archive, digest, manifest


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--native-root', type=Path, default=ROOT / 'native-deps', help='Built SDK workspace (default: native-deps)')
    parser.add_argument('--out', type=Path, default=ROOT / 'dist', help='Output folder (default: dist)')
    parser.add_argument('--allow-dirty', action='store_true', help='Allow uncommitted tracked changes (trials only)')
    args = parser.parse_args(arguments)
    try:
        archive, digest, manifest = package(args.native_root, args.out, args.allow_dirty)
    except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print('package-native-sdk: ' + str(error), file=sys.stderr)
        return 1
    print(json.dumps({'archive': str(archive), 'sha256': digest, 'bytes': archive.stat().st_size,
                      'textFilesWithPrefix': len(manifest['textFilesWithPrefix']),
                      'binaryFilesWithPrefix': manifest['binaryFilesWithPrefix']}, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
