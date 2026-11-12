"""Artifact gates must reject invalid payloads before installation."""
import hashlib
import importlib.util
import io
import json
import os
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
            for name in ('gradlew', 'LICENSE', 'NOTICE', 'CITATION.cff',
                         'python/ros2android/project.py', 'python/ros2android/robot_launch.py'):
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


class SnapshotReproducibilityTest(unittest.TestCase):
    def test_checkout_timestamps_do_not_change_sdk_identity_or_executable_modes(self):
        spec = importlib.util.spec_from_file_location('package_builder', ROOT / 'setup/build-python-package.py')
        builder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(builder)
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'build.sh'
            source.write_text('#!/bin/sh\nexit 0\n')
            source.chmod(0o755)
            outputs = []
            for timestamp in (1000000000, 1800000000):
                os.utime(source, (timestamp, timestamp))
                data = io.BytesIO()
                with zipfile.ZipFile(data, 'w') as archive:
                    builder.add_sdk_file(archive, source, 'setup/build.sh')
                outputs.append(data.getvalue())
            self.assertEqual(outputs[0], outputs[1])
            with zipfile.ZipFile(io.BytesIO(outputs[0])) as archive:
                self.assertEqual(0o100755, archive.getinfo('setup/build.sh').external_attr >> 16)
                self.assertEqual((1980, 1, 1, 0, 0, 0), archive.getinfo('setup/build.sh').date_time)
                self.assertEqual(source.read_bytes(), archive.read('setup/build.sh'))

    def test_untracked_python_files_are_excluded_and_tracked_symlinks_rejected(self):
        spec = importlib.util.spec_from_file_location('package_builder', ROOT / 'setup/build-python-package.py')
        builder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(builder)
        with tempfile.TemporaryDirectory() as folder:
            builder.ROOT = Path(folder) / 'source'
            package = builder.ROOT / 'python/ros2android'
            package.mkdir(parents=True)
            (package / 'cli.py').write_text('tracked = True')
            (package / 'private_notes.py').write_text('private fixture')
            stage = Path(folder) / 'stage'
            builder.copy_python_sources(stage, ['python/ros2android/cli.py'])
            self.assertTrue((stage / 'python/ros2android/cli.py').is_file())
            self.assertFalse((stage / 'python/ros2android/private_notes.py').exists())
            (package / 'link.py').symlink_to(package / 'private_notes.py')
            with self.assertRaisesRegex(ValueError, 'source symlink'):
                builder.copy_python_sources(stage, ['python/ros2android/link.py'])
