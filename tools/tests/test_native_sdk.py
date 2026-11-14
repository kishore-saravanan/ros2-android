import struct
"""Prebuilt native SDK archive: packaging, verified relocation and refusal of unsafe or mismatched archives."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'python'))
from ros2android import cli, native_sdk

spec = importlib.util.spec_from_file_location('package_native_sdk', ROOT / 'setup/package-native-sdk.py')
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)


class NativeSdkArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.temp)
        self.native = self.temp / 'build-machine' / 'ros2-android-native'
        prefix = str(self.native).encode()
        def write(relative, data):
            path = self.native / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        for relative in native_sdk.REQUIRED:
            if relative != 'ros-assets/licenses':
                elf = bytearray(120)
                elf[:6] = b'\x7fELF\x02\x01'
                struct.pack_into('<H', elf, 18, 183)
                struct.pack_into('<Q', elf, 32, 64)
                struct.pack_into('<HH', elf, 54, 56, 1)
                struct.pack_into('<IIQQQQQQ', elf, 64, 1, 5, 0, 0, 0, 120, 120, 16384)
                write(relative, bytes(elf) + prefix + b'\0debug')
        write('ros-assets/licenses/rcl/LICENSE', b'Apache License 2.0\n')
        write('install/share/rcl/local_setup.sh', b'export PREFIX="' + prefix + b'/install"\n')
        write('install/share/ament_index/resource_index/parent_prefix_path/rcl', prefix + b'/install')
        os.symlink('librcl.so', self.native / 'install/lib/librcl_alias.so')
        for entry in native_sdk.CONTENT:
            if entry.endswith('.json'):
                write(entry, b'{}\n')
            else:
                (self.native / entry).mkdir(parents=True, exist_ok=True)
        # Tooling with the same pinned inputs as this checkout.
        self.tooling = self.temp / 'tooling'
        for entry in native_sdk.INPUTS:
            source, target = ROOT / entry, self.tooling / entry
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, target, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
            else:
                shutil.copyfile(source, target)
        self.archive, self.digest, self.manifest = packager.package(self.native, self.temp / 'dist', allow_dirty=True)
        self.target = self.temp / 'user-home' / '.cache' / 'ros2-android-native'

    def test_packager_rejects_incompatible_native_binary_before_writing_archive(self):
        path = self.native / 'apk-libs/arm64-v8a/librcl.so'
        data = bytearray(path.read_bytes())
        struct.pack_into('<Q', data, 112, 4096)
        path.write_bytes(data)
        output = self.temp / 'rejected'
        with self.assertRaisesRegex(ValueError, '16 KB'):
            packager.package(self.native, output, allow_dirty=True)
        self.assertFalse(output.exists())

    def install(self, **options):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return native_sdk.install(self.archive, tooling_root=self.tooling, target=self.target, **options)

    def test_package_records_prefix_files_and_install_relocates_them(self):
        self.assertIn('install/share/rcl/local_setup.sh', self.manifest['textFilesWithPrefix'])
        self.assertIn('install/share/ament_index/resource_index/parent_prefix_path/rcl', self.manifest['textFilesWithPrefix'])
        self.assertEqual(len(native_sdk.REQUIRED) - 1, self.manifest['binaryFilesWithPrefix'])
        self.assertTrue(self.archive.with_name(self.archive.name + '.sha256').read_text().startswith(self.digest))
        self.install()
        script = (self.target / 'install/share/rcl/local_setup.sh').read_text()
        self.assertIn(str(self.target) + '/install', script)
        self.assertNotIn(str(self.native), script)
        self.assertIn(str(self.native).encode(), (self.target / 'install/lib/librcl.so').read_bytes())  # binaries untouched
        self.assertEqual('librcl.so', os.readlink(self.target / 'install/lib/librcl_alias.so'))
        self.assertEqual(self.target.resolve(), (self.tooling / 'native-deps').resolve())
        record = json.loads((self.target / '.sdk-install.json').read_text())
        self.assertEqual(self.digest, record['sha256'])
        self.assertTrue((self.target / 'SDK-THIRD-PARTY-NOTICES.md').is_file())
        self.assertEqual([], [path.name for path in self.target.parent.iterdir() if path.name.startswith('.ros2-android-native-')])

    def test_checksum_mismatch_installs_nothing(self):
        with self.assertRaisesRegex(ValueError, 'Checksum mismatch'):
            self.install(expected_sha256='0' * 64)
        self.assertFalse((self.target / 'install').exists())

    def test_archive_from_other_inputs_is_refused_and_cleaned_up(self):
        with open(self.tooling / 'native/patches/rcl.patch', 'a') as patch:
            patch.write('\n# changed\n')
        with self.assertRaisesRegex(ValueError, 'different pinned inputs'):
            self.install()
        self.assertFalse(self.target.parent.exists(), 'A mismatched archive must be refused before anything is written')

    def test_existing_sdk_needs_replace_and_keeps_sources(self):
        self.install()
        (self.target / 'src').mkdir()
        (self.target / 'install/stale.txt').write_text('old')
        with self.assertRaisesRegex(ValueError, 'already'):
            self.install()
        self.install(replace=True)
        self.assertTrue((self.target / 'src').is_dir())
        self.assertFalse((self.target / 'install/stale.txt').exists())

    def test_python_without_extraction_filters_is_refused(self):
        saved = getattr(native_sdk.tarfile, 'data_filter', None)
        try:
            del native_sdk.tarfile.data_filter
            with self.assertRaisesRegex(ValueError, 'cannot extract archives safely'):
                self.install()
        finally:
            if saved is not None:
                native_sdk.tarfile.data_filter = saved
        self.assertFalse((self.target / 'install').exists())

    def test_unsafe_archives_are_refused(self):
        for name, build in {
            'escape': lambda tar: tar.addfile(tarfile.TarInfo('../outside.txt'), io.BytesIO(b'')),
            'absolute-link': lambda tar: tar.addfile(self.link('install/evil', '/etc/passwd')),
            'escaping-link': lambda tar: tar.addfile(self.link('install/evil', '../../outside')),
            'unknown-top': lambda tar: tar.addfile(tarfile.TarInfo('payload.sh'), io.BytesIO(b'')),
        }.items():
            with self.subTest(name):
                archive = self.temp / f'{name}.tar.xz'
                with tarfile.open(archive, 'w:xz') as tar:
                    build(tar)
                with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(ValueError):
                    native_sdk.install(archive, tooling_root=self.tooling, target=self.target,
                                       expected_sha256=native_sdk.sha256_file(archive))
                self.assertFalse((self.temp / 'outside.txt').exists())
                self.assertFalse((self.target / 'install').exists())

    @staticmethod
    def link(name, target):
        info = tarfile.TarInfo(name)
        info.type, info.linkname = tarfile.SYMTYPE, target
        return info

    def test_setup_action_help_reaches_the_selected_action_without_installing(self):
        for action, expected in [('sdk', '--archive'), ('sources', 'Fetch pinned upstream sources')]:
            with self.subTest(action=action), patch.object(sys, 'argv', ['ros2-android', 'setup', action, '--help']), \
                    patch.object(cli, 'sdk_root', return_value=self.tooling), \
                    patch.object(native_sdk, 'install') as install, \
                    contextlib.redirect_stdout(io.StringIO()) as output, self.assertRaises(SystemExit) as exited:
                cli.main()
            self.assertEqual(0, exited.exception.code)
            self.assertIn(expected, output.getvalue())
            install.assert_not_called()

    def test_cli_routes_setup_sdk_to_the_installer(self):
        argv = ['ros2-android', 'setup', 'sdk', '--archive', str(self.archive)]
        with patch.object(sys, 'argv', argv), patch.object(cli, 'sdk_root', return_value=self.tooling), \
                patch.object(native_sdk, 'install', return_value={'version': 'test'}) as install, \
                contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, cli.main())
        install.assert_called_once_with(self.archive, tooling_root=self.tooling, expected_sha256=None, replace=False)
        self.assertIn('doctor --profile android', output.getvalue())


if __name__ == '__main__':
    unittest.main()
