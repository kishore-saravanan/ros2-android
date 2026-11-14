"""setup workspace-tools: the one command that prepares workspace builds after the .deb."""

import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'python'))
from ros2android import cli, native_sdk, workspace_tools as tools


class WorkspaceToolsTest(unittest.TestCase):
    def setUp(self):
        self.temp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.temp)
        self.matrix = json.loads((ROOT / 'python/ros2android/compatibility.json').read_text())

    def test_missing_ubuntu_packages_are_listed_as_one_apt_command(self):
        def dpkg(command, **kwargs):
            installed = command[-1] not in ('cmake', 'openjdk-21-jdk-headless')
            return subprocess.CompletedProcess(
                command, 0 if installed else 1, 'install ok installed' if installed else '', ''
            )

        with (
            mock.patch.object(tools.subprocess, 'run', side_effect=dpkg),
            contextlib.redirect_stdout(io.StringIO()) as output,
        ):
            self.assertEqual(1, tools.run([], tooling_root=self.temp))

        self.assertIn('sudo apt install cmake openjdk-21-jdk-headless', output.getvalue())

    def test_download_verifies_checksum_and_leaves_nothing_on_mismatch(self):
        payload = b'android tools'
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.headers = {'Content-Length': str(len(payload))}
        response.read.side_effect = [payload, b'']
        target = self.temp / 'cache' / 'tools.zip'

        with (
            mock.patch.object(tools.urllib.request, 'urlopen', return_value=response),
            contextlib.redirect_stdout(io.StringIO()),
            self.assertRaisesRegex(ValueError, 'checksum mismatch'),
        ):
            tools.download('https://example.invalid/tools.zip', target, '0' * 64, 'Tools')

        self.assertEqual([], list(target.parent.iterdir()))

    def test_command_line_tools_unpack_with_executable_bits_and_reject_traversal(self):
        archive = self.temp / 'tools.zip'
        with zipfile.ZipFile(archive, 'w') as bundle:
            info = zipfile.ZipInfo('cmdline-tools/bin/sdkmanager')
            info.external_attr = (stat.S_IFREG | 0o755) << 16
            bundle.writestr(info, '#!/bin/sh\n')

        sdkmanager = tools.extract_cmdline_tools(archive, self.temp / 'sdk')
        self.assertEqual(self.temp / 'sdk/cmdline-tools/latest/bin/sdkmanager', sdkmanager)
        self.assertTrue(os.access(sdkmanager, os.X_OK))
        evil = self.temp / 'evil.zip'
        with zipfile.ZipFile(evil, 'w') as bundle:
            bundle.writestr('../outside', 'x')

        with self.assertRaisesRegex(ValueError, 'Unexpected entry'):
            tools.extract_cmdline_tools(evil, self.temp / 'sdk2')

        self.assertFalse((self.temp / 'outside').exists())

    def test_installed_android_packages_skip_downloads_and_licences(self):
        root = self.temp / 'sdk'
        sdkmanager = root / 'cmdline-tools/latest/bin/sdkmanager'
        sdkmanager.parent.mkdir(parents=True)
        sdkmanager.write_text('#!/bin/sh\n')

        for folder in tools.android_packages(self.matrix).values():
            (root / folder).mkdir(parents=True)
            (root / folder / 'source.properties').write_text('Pkg.Revision=1\n')

        with (
            mock.patch.dict(os.environ, {'ANDROID_HOME': str(root)}),
            mock.patch.object(tools.subprocess, 'run') as run,
            mock.patch.object(tools, 'download') as download,
            contextlib.redirect_stdout(io.StringIO()),
        ):
            tools.ensure_android(self.matrix)

        run.assert_not_called()
        download.assert_not_called()

    def test_packages_without_sdkmanager_get_the_command_line_tools(self):
        root = self.temp / 'sdk'

        for folder in tools.android_packages(self.matrix).values():
            (root / folder).mkdir(parents=True)
            (root / folder / 'source.properties').write_text('Pkg.Revision=1\n')

        with (
            mock.patch.dict(os.environ, {'ANDROID_HOME': str(root)}),
            mock.patch.object(tools, 'download', return_value=self.temp / 'tools.zip') as download,
            mock.patch.object(tools, 'extract_cmdline_tools') as extract,
            mock.patch.object(tools.subprocess, 'run') as run,
            contextlib.redirect_stdout(io.StringIO()),
        ):
            tools.ensure_android(self.matrix)

        download.assert_called_once()
        extract.assert_called_once()
        run.assert_not_called()  # Nothing else to install, so no licence prompt.

    def test_missing_android_packages_need_an_interactive_licence_review(self):
        root = self.temp / 'sdk'
        sdkmanager = root / 'cmdline-tools/latest/bin/sdkmanager'
        sdkmanager.parent.mkdir(parents=True)
        sdkmanager.write_text('#!/bin/sh\n')

        with (
            mock.patch.dict(os.environ, {'ANDROID_HOME': str(root)}),
            mock.patch.object(tools.sys.stdin, 'isatty', return_value=False),
            mock.patch.object(tools.subprocess, 'run') as run,
            contextlib.redirect_stdout(io.StringIO()),
            self.assertRaisesRegex(ValueError, 'interactive terminal'),
        ):
            tools.ensure_android(self.matrix)

        run.assert_not_called()

    def test_an_installed_matching_native_sdk_is_kept(self):
        tooling = self.temp / 'tooling'
        target = self.temp / 'native'
        tooling.mkdir()
        (tooling / 'native-deps').symlink_to(target, target_is_directory=True)

        for path in native_sdk.REQUIRED:
            (target / path).parent.mkdir(parents=True, exist_ok=True)
            (target / path).write_text('x')

        with (
            mock.patch.object(tools.native_sdk, 'inputs_digest', return_value='abc'),
            mock.patch.object(tools.native_sdk, 'install') as install,
            contextlib.redirect_stdout(io.StringIO()),
        ):
            (target / '.sdk-install.json').write_text(json.dumps({'inputsDigest': 'abc'}))
            tools.ensure_native_sdk(tooling, self.matrix)
            install.assert_not_called()
            (target / '.sdk-install.json').write_text(json.dumps({'inputsDigest': 'old'}))

            with mock.patch.object(tools, 'download', return_value=self.temp / 'sdk.tar.xz'):
                tools.ensure_native_sdk(tooling, self.matrix)

            install.assert_called_once()
            self.assertTrue(install.call_args.kwargs['replace'])

    def test_kept_sdk_gets_the_native_deps_link_after_a_client_update(self):
        tooling = self.temp / 'new-tooling'
        tooling.mkdir()
        target = self.temp / 'home' / '.cache' / 'ros2-android-native'

        for path in native_sdk.REQUIRED:
            (target / path).parent.mkdir(parents=True, exist_ok=True)
            (target / path).write_text('x')

        (target / '.sdk-install.json').write_text(json.dumps({'inputsDigest': 'abc'}))

        with (
            mock.patch.object(tools.Path, 'home', return_value=self.temp / 'home'),
            mock.patch.object(tools.native_sdk, 'inputs_digest', return_value='abc'),
            mock.patch.object(tools.native_sdk, 'install') as install,
            contextlib.redirect_stdout(io.StringIO()),
        ):
            tools.ensure_native_sdk(tooling, self.matrix)

        install.assert_not_called()
        self.assertEqual(target.resolve(), (tooling / 'native-deps').resolve())

    def test_partial_source_build_is_replaced_keeping_sources(self):
        tooling = self.temp / 'tooling'
        tooling.mkdir()
        target = self.temp / 'native'
        (tooling / 'native-deps').symlink_to(target, target_is_directory=True)
        (target / 'install/lib').mkdir(parents=True)
        (target / 'install/lib/librcl.so').write_text('x')  # C++ only: no Python or Agent parts

        with (
            mock.patch.object(tools.native_sdk, 'inputs_digest', return_value='abc'),
            mock.patch.object(tools, 'download', return_value=self.temp / 'sdk.tar.xz'),
            mock.patch.object(tools.native_sdk, 'install') as install,
            contextlib.redirect_stdout(io.StringIO()),
        ):
            tools.ensure_native_sdk(tooling, self.matrix)

        self.assertTrue(install.call_args.kwargs['replace'])
        self.assertEqual(target.resolve(), install.call_args.kwargs['target'])

    def test_release_metadata_names_the_published_sdk_for_this_version(self):
        info = tools.release_info()['nativeSdk']
        self.assertEqual(self.matrix['hostClientVersion'], info['version'])
        self.assertRegex(info['sha256'], '^[0-9a-f]{64}$')

    def test_sdk_build_inputs_match_the_published_sdk(self):
        # The published SDK only installs with tooling built from the same inputs. Changing any
        # native_sdk.INPUTS file, even its formatting, needs a new SDK build and release.json pin.
        pinned = tools.release_info()['nativeSdk']['inputsDigest']
        self.assertEqual(pinned, native_sdk.inputs_digest(ROOT))

    def test_bundled_release_app_is_the_default_controller(self):
        from ros2android import workspace

        package = self.temp / 'ros2android'
        (package / 'artifacts').mkdir(parents=True)
        (package / 'artifacts/main.apk').write_bytes(b'apk')

        with mock.patch('importlib.resources.files', return_value=package):
            self.assertEqual(package / 'artifacts/main.apk', workspace.bundled_controller())

        with mock.patch('importlib.resources.files', return_value=self.temp / 'wheel-without-app'):
            self.assertIsNone(workspace.bundled_controller())

    def test_cli_routes_setup_workspace_tools(self):
        with (
            mock.patch.object(sys, 'argv', ['ros2-android', 'setup', 'workspace-tools']),
            mock.patch.object(cli, 'sdk_root', return_value=self.temp),
            mock.patch.object(tools, 'run', return_value=0) as run,
        ):
            self.assertEqual(0, cli.main())

        run.assert_called_once_with([], tooling_root=self.temp)


if __name__ == '__main__':
    unittest.main()
