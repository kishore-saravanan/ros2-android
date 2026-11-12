#!/usr/bin/env python3
"""Package only the Android Agent ELF closure with APK-compatible library names."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

build, install = map(Path, sys.argv[1:3])
root = Path(__file__).resolve().parents[1]
destination = root / 'native-deps/agent-apk-libs/arm64-v8a'
destination.mkdir(parents=True, exist_ok=True)
patchelf = shutil.which('patchelf') or str(Path.home() / '.cache/ros2-android-esp32/toolchain-venv/bin/patchelf')
executable = install / 'bin/MicroXRCEAgent'
if not executable.exists():
    executable = build / 'MicroXRCEAgent'
assert executable.is_file(), 'Android Agent executable not found'
sources = [executable]
for prefix in [install, build / 'temp_install']:
    sources.extend(path for path in prefix.rglob('*.so*') if path.is_file())
by_soname = {}
for path in sources[1:]:
    data = subprocess.check_output(['readelf', '-d', str(path)], text=True)
    found = re.search(r'\(SONAME\).*\[(.*?)\]', data)
    if found: by_soname[found.group(1)] = path
queue = [executable]
closure = {}
while queue:
    path = queue.pop()
    name = 'libmicro_ros_agent_exec.so' if path == executable else path.name.split('.so')[0] + '.so'
    if name in closure: continue
    header = subprocess.check_output(['readelf', '-h', str(path)], text=True)
    assert 'AArch64' in header, f'Non-Android library rejected: {path.name}'
    closure[name] = path
    data = subprocess.check_output(['readelf', '-d', str(path)], text=True)
    for needed in re.findall(r'\(NEEDED\).*\[(.*?)\]', data):
        if needed in by_soname: queue.append(by_soname[needed])
        elif needed not in {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libc++_shared.so'}:
            raise RuntimeError(f'Unresolved Agent dependency: {needed}')
for name, source in closure.items():
    target = destination / name
    shutil.copy2(source, target)
    if source != executable: subprocess.run([patchelf, '--set-soname', name, str(target)], check=True)
    for soname in by_soname:
        plain = soname.split('.so')[0] + '.so'
        if plain in closure and soname != plain:
            subprocess.run([patchelf, '--replace-needed', soname, plain, str(target)], check=True)
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'Android/Sdk')
strip = sdk / 'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'
for name in closure:
    subprocess.run([str(strip), '--strip-unneeded', str(destination / name)], check=True)
for path in destination.glob('*.so'):
    if path.name not in closure: path.unlink()
manifest = {name: hashlib.sha256((destination / name).read_bytes()).hexdigest() for name in closure}
(root / 'native-deps/micro-ros-agent-checksums.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(f'{len(closure)} Android Agent ELF files staged; libc++ comes from the main app NDK build')

# Carry upstream notices with the binary closure, including compiled-in headers.
license_roots = {
    'micro-xrce-agent': build.parent / 'sources/Micro-XRCE-DDS-Agent',
    'fastdds': build / 'fastdds/src/fastdds',
    'fastcdr': build / 'fastcdr/src/fastcdr',
    'foonathan-memory': build / 'foonathan_memory/src/foonathan_memory',
    'spdlog': build / 'spdlog/src/spdlog',
    'asio': build / 'fastdds/src/fastdds/thirdparty/asio/asio',
    'tinyxml2': build / 'fastdds/src/fastdds/thirdparty/tinyxml2',
    'android-ifaddrs': build / 'fastdds/src/fastdds/thirdparty/android-ifaddrs',
}
# Some vendored sources carry their terms only in a source-file header comment.
header_licenses = {'tinyxml2': 'tinyxml2.h', 'android-ifaddrs': 'ifaddrs.c'}
empty = []
for label, prefix in license_roots.items():
    target = root / 'native-deps/agent-assets/licenses' / label
    target.mkdir(parents=True, exist_ok=True)
    for pattern in ('LICENSE*', 'COPYING*', 'NOTICE*'):
        for path in prefix.glob(pattern):
            if path.is_file(): shutil.copy2(path, target / path.name)
    if not any(target.iterdir()) and label in header_licenses:
        header = re.match(r'\s*/\*(.*?)\*/', (prefix / header_licenses[label]).read_text(errors='replace'), re.S)
        if header:
            (target / 'LICENSE-from-source-header.txt').write_text(
                f'License terms from the header of {header_licenses[label]}:\n\n' + header.group(1).strip('\n') + '\n')
    if not any(target.iterdir()):
        empty.append(label)
assert not empty, 'No license text staged for: ' + ', '.join(empty)
