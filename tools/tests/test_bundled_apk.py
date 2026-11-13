"""Bundled APK installation must validate before ADB and preserve app data."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class BundledApkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / 'main app.apk'; self.apk.write_bytes(b'fixture apk')
        self.manifest = self.root / 'main.json'
        self.manifest.write_text(json.dumps({'schemaVersion': 1, 'applicationId': 'dev.ros2android.android',
            'minSdk': 26, 'abis': ['arm64-v8a'], 'sha256': hashlib.sha256(self.apk.read_bytes()).hexdigest()}))
        self.adb = self.root / 'platform-tools/adb'; self.adb.parent.mkdir()
        self.log = self.root / 'adb.jsonl'
        self.adb.write_text('''#!/usr/bin/python3
import json, os, sys
with open(os.environ['ADB_TEST_LOG'], 'a') as stream: stream.write(json.dumps(sys.argv[1:]) + '\\n')
command = sys.argv[3:]
if command == ['get-state']: print('device')
elif command == ['shell', 'getprop', 'ro.build.version.sdk']: print('31')
elif command == ['shell', 'getprop', 'ro.product.cpu.abilist']: print(os.environ.get('ADB_TEST_ABI', 'arm64-v8a,armeabi-v7a'))
elif command[:4] == ['install', '--user', 'current', '-r']: print('Success')
else: raise SystemExit(2)
'''); self.adb.chmod(0o755)
        self.env = dict(os.environ, ANDROID_HOME=str(self.root), ADB_TEST_LOG=str(self.log))

    def install(self):
        return subprocess.run([str(ROOT / 'setup/run-device.sh'), '--apk', str(self.apk),
            '--bundle-manifest', str(self.manifest), '--serial', 'test-phone', '--install-only'],
            env=self.env, capture_output=True, text=True)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_install_updates_only_main_app_without_launch_or_clear(self):
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls()[-1], ['-s', 'test-phone', 'install', '--user', 'current', '-r', str(self.apk)])
        self.assertEqual(len(self.calls()), 4)

    def test_tampered_apk_rejected_before_adb(self):
        self.apk.write_bytes(b'tampered')
        self.assertNotEqual(self.install().returncode, 0)
        self.assertEqual(self.calls(), [])

    def test_other_package_rejected_before_adb(self):
        metadata = json.loads(self.manifest.read_text()); metadata['applicationId'] = 'other.app'
        self.manifest.write_text(json.dumps(metadata))
        self.assertNotEqual(self.install().returncode, 0)
        self.assertEqual(self.calls(), [])

    def test_incompatible_phone_rejected_before_install(self):
        self.env['ADB_TEST_ABI'] = 'x86_64'
        self.assertNotEqual(self.install().returncode, 0)
        self.assertFalse(any('install' in call for call in self.calls()))
