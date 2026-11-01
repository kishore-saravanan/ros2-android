"""Read the controller's public signing identity from a verified APK, never a key."""
import os
from pathlib import Path
import re
import subprocess


def controller_certificate(apk):
    apk = Path(apk).expanduser().resolve()
    if not apk.is_file():
        raise ValueError('Controller APK does not exist')
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Android/Sdk'))))
    tools = sdk / 'build-tools/35.0.0'
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    if not re.search(r"^package: name='dev\.ros2android\.android' ", badging, re.M):
        raise ValueError('Controller APK must be dev.ros2android.android')
    signing = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    certificates = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$', signing, re.M)
    if len(certificates) != 1:
        raise ValueError('Controller APK requires exactly one current signing certificate')
    return certificates[0].lower()
