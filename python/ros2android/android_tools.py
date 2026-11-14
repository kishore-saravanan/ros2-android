"""Locate Android tools the same way everywhere."""
import os
from pathlib import Path
import shutil


def sdk_root():
    return Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'Android/Sdk').expanduser()


def adb_path():
    """The Android SDK's adb when installed, else the adb on PATH (the Debian package depends on Ubuntu's adb)."""
    candidate = sdk_root() / 'platform-tools' / 'adb'
    if candidate.is_file() and os.access(candidate, os.X_OK):
        return str(candidate)
    return shutil.which('adb') or str(candidate)
