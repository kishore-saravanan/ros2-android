#!/usr/bin/env python3
"""Stage only verified ARM64 extensions and Python runtime resources into ignored output."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

root = Path(__file__).resolve().parents[1] / 'native-deps'
prefix = root / 'python-install'
packages = root / 'python-packages'
libraries = root / 'python-apk-libs/arm64-v8a'
assets = root / 'python-assets/ros2-python-sdk'
for destination in (packages, libraries, assets):
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
def verify(source):
    if 'AArch64' not in subprocess.check_output(['readelf', '-h', str(source)], text=True):
        raise SystemExit('Non-ARM64 library: ' + str(source))
    dynamic = subprocess.check_output(['readelf', '-d', str(source)], text=True)
    for needed in re.findall(r'Shared library: \[([^\]]+)\]', dynamic):
        if '/' in needed:
            raise SystemExit('Non-portable DT_NEEDED in ' + str(source) + ': ' + needed)

checksums = {}
for source in sorted(prefix.rglob('*.so')):
    if 'site-packages' in source.parts:
        continue
    verify(source)
    if source.name in checksums and checksums[source.name] != hashlib.sha256(source.read_bytes()).hexdigest():
        raise SystemExit('Conflicting library basename: ' + source.name)
    shutil.copy2(source, libraries / source.name)
    checksums[source.name] = hashlib.sha256(source.read_bytes()).hexdigest()
for site in (prefix / 'lib').glob('python*/site-packages'):
    for source in site.iterdir():
        if source.is_dir() and not source.name.endswith(('.egg-info', '.dist-info', '__pycache__')):
            shutil.copytree(source, packages / source.name, dirs_exist_ok=True,
                            ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
# Pure runtime utility package has no target binary. Avoid pulling its test-only
# interface packages into the production cross-build.
shutil.copytree(root / 'src/rosidl_runtime_py/rosidl_runtime_py', packages / 'rosidl_runtime_py', dirs_exist_ok=True,
                ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
for source in list(packages.rglob('*.so')):
    verify(source)
    name = re.sub(r'\.cpython-[^.]+', '', source.name)
    if name != source.name:
        source.rename(source.with_name(name))
# Check the packaged closure, including vendor libraries outside prefix/lib.
provided = {p.name for p in libraries.glob('*.so')}
provided.update(p.name for p in (root / 'python-target/jniLibs/arm64-v8a').glob('*.so'))
provided.update({'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libz.so', 'libc++_shared.so'})
for source in [*libraries.glob('*.so'), *packages.rglob('*.so')]:
    dynamic = subprocess.check_output(['readelf', '-d', str(source)], text=True)
    missing = set(re.findall(r'Shared library: \[([^\]]+)\]', dynamic)) - provided
    if missing:
        raise SystemExit(f'Missing packaged dependencies for {source.name}: {sorted(missing)}')
for source in (prefix / 'share').rglob('*'):
    if source.is_file() and ('resource_index' in source.parts or source.suffix in ('.msg', '.srv', '.action', '.idl', '.json') or source.name == 'package.xml'):
        destination = assets / source.relative_to(prefix)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
(root / 'python-library-checksums.json').write_text(json.dumps(checksums, indent=2) + '\n')
print(f'Staged {len(checksums)} ARM64 libraries and {len(list(packages.rglob("*.so")))} Python extensions')
