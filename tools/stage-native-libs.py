#!/usr/bin/env python3
"""Stage the ARM64 ROS closure and record hashes; generated output stays ignored."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[1] / 'native-deps'
destination = root / 'apk-libs' / 'arm64-v8a'
destination.mkdir(parents=True, exist_ok=True)
hashes = {}
for source in sorted((root / 'install').rglob('*.so')):
    header = subprocess.check_output(['readelf', '-h', str(source)], text=True)
    if 'AArch64' not in header:
        raise SystemExit(f'Refusing to package a non-ARM64 library: {source.name}')
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    if source.name in hashes and hashes[source.name] != digest:
        raise SystemExit(f'Conflicting libraries named {source.name}')
    hashes[source.name] = digest
    shutil.copy2(source, destination / source.name)
# Remove only stale generated libraries from this project's staging directory.
for old in destination.glob('*.so'):
    if old.name not in hashes:
        old.unlink()
(root / 'native-library-checksums.json').write_text(json.dumps(hashes, indent=2) + '\n')
print(f'{len(hashes)} verified ARM64 ROS libraries staged')
