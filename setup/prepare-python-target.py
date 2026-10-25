#!/usr/bin/env python3
"""Prepare checksum-pinned CPython Android headers/libraries, outside the repository."""
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile

root = Path(__file__).resolve().parents[1]
lock = json.loads((root / 'native/python-runtime.lock.json').read_text())
target = root / 'native-deps/python-target'
target.mkdir(parents=True, exist_ok=True)
archive = target / 'target.zip'
if not archive.exists():
    urllib.request.urlretrieve(lock['targetUrl'], archive)
if hashlib.sha256(archive.read_bytes()).hexdigest() != lock['targetSha256']:
    raise SystemExit('Python target checksum mismatch; refusing to extract')
with zipfile.ZipFile(archive) as files:
    for item in files.infolist():
        destination = target / item.filename
        if item.is_dir():
            destination.mkdir(parents=True, exist_ok=True)
        else:
            content = files.read(item)
            destination.parent.mkdir(parents=True, exist_ok=True)
            if not destination.exists() or destination.read_bytes() != content:
                destination.write_bytes(content)
print('Verified Android CPython ' + lock['python'])
