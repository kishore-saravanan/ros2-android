import contextlib
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'python'))
from ros2android import apk, cli

BADGING = """package: name='dev.ros2android.android' versionCode='8' versionName='preview'
sdkVersion:'26'
targetSdkVersion:'35'
native-code: 'arm64-v8a'
application-debuggable
"""
TREE = '  E: manifest (line=1)\n    A: android:versionCode(0x0101021b)=(type 0x10)0x8\n    E: application (line=2)\n'
SIGNING = 'Signer #1 certificate SHA-256 digest: ' + 'AB' * 32 + '\n'


class ApkArtifactsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.file = self.root / 'path with spaces.apk'; self.file.write_bytes(b'fixture')

    def inspect(self, badging=BADGING, signing=SIGNING, tree=TREE):
        with patch('ros2android.apk.probe', side_effect=[badging, tree, signing]):
            return apk.inspect(self.file, self.root / 'SDK with spaces')

    def test_verified_identity_metadata_and_hash_without_device_calls(self):
        with patch('ros2android.apk.probe', side_effect=[BADGING, TREE, SIGNING]) as probe:
            report = apk.inspect(self.file, self.root / 'SDK with spaces')
        self.assertEqual('ab' * 32, report['signerSha256'])
        self.assertEqual(hashlib.sha256(b'fixture').hexdigest(), report['sha256'])
        self.assertEqual(8, report['longVersionCode'])
        self.assertEqual(['arm64-v8a'], report['abis'])
        self.assertEqual((26, 35, True), (report['minSdk'], report['targetSdk'], report['debuggable']))
        self.assertEqual(['aapt', 'aapt', 'apksigner'], [Path(c.args[0][0]).name for c in probe.call_args_list])
        self.assertEqual(['verify', '--print-certs', str(self.file)], probe.call_args.args[0][1:])

    def test_accepts_runner_and_combines_major_version(self):
        report = self.inspect(BADGING.replace("name='dev.ros2android.android'", "name='dev.ros2android.android.runner.ws_demo'")
                              .replace("versionCode='8'", "versionCode='8' versionCodeMajor='2'")
                              .replace('application-debuggable\n', ''),
                              tree=TREE.replace('    E: application', '    A: android:versionCodeMajor(0x01010576)=(type 0x10)0x2\n    E: application'))
        self.assertEqual((2 << 32) | 8, report['longVersionCode'])
        self.assertFalse(report['debuggable'])

    def test_rejects_other_apps_before_verifying_signature(self):
        for name in ['other.app', 'dev.ros2android.android.evil', 'dev.ros2android.android.runner.',
                     'dev.ros2android.android.runner.MixedCase']:
            with patch('ros2android.apk.probe', return_value=BADGING.replace('dev.ros2android.android', name)) as probe:
                with self.assertRaisesRegex(ValueError, 'namespace'):
                    apk.inspect(self.file)
                self.assertEqual(1, probe.call_count)

    def test_rejects_missing_or_malformed_package_versions_and_sdk(self):
        for badging in [BADGING + BADGING, '', BADGING.replace("versionCode='8'", "versionCode='8' split='config.arm64_v8a'"), BADGING.replace("versionCode='8'", "versionCode='-1'"),
                        BADGING.replace("versionCode='8'", "versionCode='4294967296'"),
                        BADGING.replace("versionCode='8'", "versionCodeMajor='2'"),
                        BADGING.replace("sdkVersion:'26'", "sdkVersion:'Preview'"),
                        BADGING.replace("targetSdkVersion:'35'", ''),
                        BADGING + "native-code: 'x86'\n"]:
            with self.subTest(badging=badging), self.assertRaises(ValueError): self.inspect(badging)

    def test_missing_or_multiple_signers_are_rejected(self):
        for signing in ['', SIGNING + SIGNING.replace('#1', '#2'), SIGNING.replace('AB' * 32, 'short')]:
            with self.subTest(signing=signing), self.assertRaisesRegex(ValueError, 'one current'):
                self.inspect(signing=signing)

    def test_changing_artifact_is_not_reported_as_verified(self):
        with patch('ros2android.apk.probe', side_effect=[BADGING, TREE, SIGNING]), \
             patch('ros2android.apk.digest', side_effect=['before', 'after']):
            with self.assertRaisesRegex(ValueError, 'changed while'): apk.inspect(self.file)

    def test_missing_file_and_failed_or_hanging_tools_do_not_pass(self):
        with self.assertRaisesRegex(ValueError, 'does not exist'): apk.inspect(self.root / 'missing.apk')
        for error in [FileNotFoundError(), subprocess.TimeoutExpired(['aapt'], 20),
                      subprocess.CalledProcessError(1, ['apksigner'])]:
            with patch('ros2android.apk.subprocess.run', side_effect=error) as run:
                with self.assertRaises(ValueError): apk.probe(['aapt', 'dump', 'badging', 'fixture.apk'])
                self.assertEqual(20, run.call_args.kwargs['timeout'])
                self.assertNotIn('shell', run.call_args.kwargs)

    def test_sdk_environment_fallback_and_streaming_digest(self):
        with patch.dict(os.environ, {'ANDROID_HOME': '', 'ANDROID_SDK_ROOT': str(self.root / 'fallback')}, clear=True), \
             patch('ros2android.apk.probe', side_effect=[BADGING, TREE, SIGNING]) as probe:
            apk.inspect(self.file)
        self.assertTrue(probe.call_args_list[0].args[0][0].startswith(str(self.root / 'fallback')))
        self.assertEqual(hashlib.sha256(b'fixture').hexdigest(), apk._digest_stream(io.BytesIO(b'fixture')))

    def test_replacement_checks_package_signer_and_numeric_version_independently(self):
        before = self.inspect()
        after = copy.deepcopy(before); after['sha256'] = 'changed'
        self.assertEqual('identical', apk.compare(before, before)['decision'])
        self.assertEqual('candidate', apk.compare(before, after)['decision'])
        for field, value in [('applicationId', 'dev.ros2android.android.runner.other'),
                             ('signerSha256', 'cd' * 32), ('longVersionCode', 7)]:
            altered = dict(after, **{field: value})
            with self.subTest(field=field):
                result = apk.compare(before, altered)
                self.assertEqual('blocked', result['decision']); self.assertEqual(1, len(result['reasons']))
        after['longVersionCode'] = 10; after['versionName'] = 'older-looking-name'
        self.assertEqual('candidate', apk.compare(before, after)['decision'])

    def test_cli_outputs_structured_errors_and_never_extracts_sdk(self):
        before = self.inspect(); after = dict(before, longVersionCode=7)
        for arguments, inspections, code in [
            (['inspect', str(self.file)], [before], 0),
            (['compare', str(self.file), str(self.file)], [before, after], 1),
        ]:
            with patch.object(sys, 'argv', ['ros2-android', 'apk', *arguments]), \
                 patch('ros2android.apk.inspect', side_effect=inspections), \
                 patch('ros2android.cli.sdk_root', side_effect=AssertionError('SDK extracted')), \
                 contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(code, cli.main())
                self.assertEqual(1, json.loads(output.getvalue())['schemaVersion'])
        with patch('ros2android.apk.inspect', side_effect=ValueError('bad signature')), \
             contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(2, apk.main(['inspect', str(self.file)]))
            self.assertEqual('bad signature', json.loads(output.getvalue())['error'])

    def test_manifest_major_is_read_even_when_badging_omits_it(self):
        tree = TREE.replace('    E: application', '    A: android:versionCodeMajor(0x01010576)=(type 0x10)0x2\n    E: application')
        self.assertEqual((2 << 32) | 8, self.inspect(tree=tree)['longVersionCode'])
        for invalid in ['', tree + TREE, tree.replace('(type 0x10)0x2', '"2"'),
                        tree.replace('(type 0x10)0x2', '(type 0x10)0xffffffff'),
                        TREE.replace('versionCode(', 'somethingElse(')]:
            with self.subTest(tree=invalid), self.assertRaises(ValueError): self.inspect(tree=invalid)
