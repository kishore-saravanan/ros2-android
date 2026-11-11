"""Artifact gates must reject invalid payloads before installation."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('package_verification', ROOT / 'setup/verify-python-package.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)


class PackageVerificationTest(unittest.TestCase):
    def wheel(self, root, sdk_extra=None, bad_hash=False, version='0.1.0a1', legal=True, wheel_extra=None):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as sdk:
            for name in ('gradlew', 'LICENSE', 'NOTICE', 'CITATION.cff'):
                sdk.writestr(name, 'fixture')
            for name in sdk_extra or []:
                sdk.writestr(name, 'not for distribution')
        matrix = {'hostClientVersion': version, 'artifact': {'sdkSnapshotSha256':
                  '0'*64 if bad_hash else hashlib.sha256(data.getvalue()).hexdigest()}}
        path = root / 'test.whl'
        with zipfile.ZipFile(path, 'w') as archive:
            for name in wheel_extra or []:
                archive.writestr(name, 'not for distribution')
            archive.writestr('ros2android/sdk.zip', data.getvalue())
            archive.writestr('ros2android/compatibility.json', json.dumps(matrix))
            archive.writestr('ros2_android-0.1.0a1.dist-info/METADATA', 'Metadata-Version: 2.1\nName: ros2-android\nVersion: 0.1.0a1\n')
            if legal:
                for name in ('LICENSE', 'NOTICE'):
                    archive.writestr('ros2_android-0.1.0a1.dist-info/licenses/' + name, 'fixture')
        return path

    def test_accepts_consistent_payload(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual('0.1.0a1', module.check_archive(self.wheel(Path(folder)))['hostClientVersion'])

    def test_unsafe_private_and_key_paths_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            for name in ('../escape', '/absolute', 'device-logs/private.png', 'android/build/output.apk',
                         'native-deps/install/lib.so', 'android/keys/signing.jks', 'android/private.key',
                         'examples/demo/install/setup.bash'):
                for field in ('sdk_extra', 'wheel_extra'):
                    with self.subTest(name=name, field=field), self.assertRaises(RuntimeError):
                        module.check_archive(self.wheel(Path(folder), **{field: [name]}))

    def test_hash_version_and_legal_mismatches_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            for options in ({'bad_hash': True}, {'version': '99.0'}, {'legal': False}):
                with self.subTest(options=options), self.assertRaises(RuntimeError):
                    module.check_archive(self.wheel(Path(folder), **options))
