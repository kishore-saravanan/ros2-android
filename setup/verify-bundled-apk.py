#!/usr/bin/env python3
"""Validate the packaged main-app artifact before any device operation."""
import hashlib
import json
from pathlib import Path
import sys


def verify(apk, manifest):
    metadata = json.loads(Path(manifest).read_text())
    if metadata.get('schemaVersion') != 1 or metadata.get('applicationId') != 'dev.ros2android.android':
        raise ValueError('Refusing bundle outside the main ROS2 Android application')
    if metadata.get('sha256') != hashlib.sha256(Path(apk).read_bytes()).hexdigest():
        raise ValueError('Bundled APK checksum mismatch; reinstall the PC package')
    if metadata.get('abis') != ['arm64-v8a'] or metadata.get('minSdk') != 26:
        raise ValueError('Unsupported bundle target')
    return metadata


if __name__ == '__main__':
    try:
        verify(*sys.argv[1:])
    except (ValueError, OSError, TypeError) as error:
        raise SystemExit(str(error))
