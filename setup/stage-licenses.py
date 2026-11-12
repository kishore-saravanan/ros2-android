#!/usr/bin/env python3
"""Stage upstream license/notice texts for native libraries packaged into APKs.

Output: native-deps/ros-assets/licenses/<component>/..., which the app and all runner
APKs include as assets/licenses/<component>/. The micro-ROS Agent stack is staged
separately under agent-assets by stage-micro-ros-agent.py. Fails when a locked source
has no license file, so a new dependency cannot ship without its terms.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
PATTERNS = ('LICENSE*', 'LICENCE*', 'COPYING*', 'NOTICE*', 'License*')
LLVM_HEADER = 'The LLVM Project is under the Apache License v2.0 with LLVM Exceptions:'


def license_files(source):
    """Top-level license files, else per-package files one level down (e.g. common_interfaces)."""
    found = sorted({path for pattern in PATTERNS for path in source.glob(pattern) if path.is_file()})
    if not found:
        found = sorted({path for pattern in PATTERNS for path in source.glob('*/' + pattern) if path.is_file()})
    return found


def llvm_license(notice_toolchain):
    """Extract the LLVM LICENSE.TXT block (Apache-2.0 WITH LLVM-exception, third-party and legacy terms)."""
    text = notice_toolchain.read_text(encoding='utf-8', errors='replace')
    start = text.find(LLVM_HEADER)
    if start < 0:
        raise RuntimeError(f'No LLVM license block in {notice_toolchain}')
    start = text.rfind('\n=', 0, start) + 1
    end = text.find(LLVM_HEADER, start + len('=' * 10) + len(LLVM_HEADER))
    block = text[start:end if end > 0 else len(text)]
    block = block[:block.rstrip().rfind('\n=')] if end > 0 else block  # drop the next block's separator
    if 'LLVM Exceptions to the Apache 2.0 License' not in block:
        raise RuntimeError('LLVM license block is incomplete')
    return block.rstrip() + '\n'


def stage(native, ndk, target):
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    index = []

    def copy(label, files, origin, revision=None):
        directory = target / label
        directory.mkdir()
        names = []
        seen = set()
        for path in files:
            data = path.read_bytes()
            digest = hashlib.sha256(data).hexdigest()
            if digest in seen:
                continue  # identical per-package copies
            seen.add(digest)
            name = path.name if path.name not in names else f'{path.parent.name}-{path.name}'
            (directory / name).write_bytes(data)
            names.append(name)
        index.append({'component': label, 'origin': origin, 'revision': revision, 'files': names})

    locked = json.loads((ROOT / 'native/ros-sources.lock.json').read_text())
    missing = []
    for repository in locked:
        source = native / 'src' / repository['directory']
        files = license_files(source)
        if not files:
            missing.append(repository['directory'])
            continue
        copy(repository['directory'], files, repository['url'], repository['revision'])
    if missing:
        raise RuntimeError('No license file found for locked sources: ' + ', '.join(missing))
    libyaml = native / 'build/libyaml_vendor/libyaml_vendor-prefix/src/libyaml_vendor/License'
    if not libyaml.is_file():
        raise RuntimeError(f'libyaml license missing at {libyaml}; build the native SDK first')
    copy('libyaml', [libyaml], 'https://github.com/yaml/libyaml (fetched by libyaml_vendor)')
    llvm = target / 'llvm-libcxx'
    llvm.mkdir()
    (llvm / 'LICENSE.TXT').write_text(llvm_license(ndk / 'NOTICE.toolchain'))
    index.append({'component': 'llvm-libcxx', 'origin': f'Android NDK {ndk.name} libc++_shared.so',
                  'revision': None, 'files': ['LICENSE.TXT']})
    (target / 'index.json').write_text(json.dumps(index, indent=2) + '\n')
    return index


def main(arguments=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--ndk', type=Path, help='Android NDK directory (default: ANDROID_HOME/ndk/27.2.12479018)')
    args = parser.parse_args(arguments)
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'Android/Sdk')
    ndk = (args.ndk or sdk / 'ndk/27.2.12479018').expanduser()
    native = (ROOT / 'native-deps').resolve()
    try:
        index = stage(native, ndk, native / 'ros-assets/licenses')
    except (OSError, RuntimeError) as error:
        print('stage licenses: ' + str(error), file=sys.stderr)
        return 1
    print(f'Staged license texts for {len(index)} components in {native / "ros-assets/licenses"}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
