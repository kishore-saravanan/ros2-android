#!/usr/bin/env python3
"""Extract checksum-pinned ROS Cyclone libraries into a user cache; never installs system packages."""
import hashlib
import json
from pathlib import Path
import platform
import subprocess

root = Path(__file__).resolve().parents[1]
cache = Path.home() / '.cache/ros2-android-host-runtime'
lock = json.loads((root / 'native/pc-cyclone-packages.lock.json').read_text())
if platform.machine() != 'x86_64' or 'VERSION_ID="24.04"' not in Path('/etc/os-release').read_text():
    raise SystemExit('Pinned host packages target Ubuntu 24.04 amd64. Use an installed matching Cyclone ROS runtime on other hosts.')
downloads = cache / 'downloads'
downloads.mkdir(parents=True, exist_ok=True)
missing = []
verified = []
for item in lock:
    matches = [p for p in downloads.glob('*.deb') if hashlib.sha256(p.read_bytes()).hexdigest() == item['sha256']]
    if matches:
        verified.append(matches[0])
    else:
        missing.append(item['package'] + '=' + item['version'])
if missing:
    subprocess.run(['apt-get', 'download', *missing], cwd=downloads, check=True)
    verified = []
    for item in lock:
        matches = [p for p in downloads.glob('*.deb') if hashlib.sha256(p.read_bytes()).hexdigest() == item['sha256']]
        if len(matches) != 1:
            raise SystemExit('Missing/checksum-mismatched package: ' + item['package'])
        verified.append(matches[0])
for package in verified:
    subprocess.run(['dpkg-deb', '--extract', str(package), str(cache / 'root')], check=True)
(cache / 'packages.json').write_text(json.dumps(lock, indent=2) + '\n')
print('Project-local Cyclone runtime ready:', cache / 'root/opt/ros/jazzy')
