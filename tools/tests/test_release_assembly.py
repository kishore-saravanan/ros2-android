import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


release = load('assemble_release', 'setup/assemble-release.py')
verifier = load('verify_release', 'setup/verify-release.py')

APKSIGNER_OUTPUT = '''Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Number of signers: 1
Signer #1 certificate DN: CN=Test
Signer #1 certificate SHA-256 digest: {digest}
Signer #1 certificate SHA-1 digest: 0000000000000000000000000000000000000000
'''


def deb_with_apk(path, apk_bytes):
    """Minimal ar archive: debian-binary, control.tar.gz, data.tar.gz with the bundled APK."""
    def member(name, body):
        header = f'{name + "/":<16}{0:<12}{0:<6}{0:<6}{"100644":<8}{len(body):<10}`\n'.encode()
        return header + body + (b'\n' if len(body) % 2 else b'')
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode='w:gz') as tar:
        info = tarfile.TarInfo('./usr/lib/python3/dist-packages/ros2android/artifacts/main.apk'); info.size = len(apk_bytes)
        tar.addfile(info, io.BytesIO(apk_bytes))
    path.write_bytes(b'!<arch>\n' + member('debian-binary', b'2.0\n') + member('control.tar.gz', b'x') +
                     member('data.tar.gz', data.getvalue()))


class ReleaseAssemblyTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)

    def test_signer_parses_one_certificate_and_verified_schemes(self):
        digest = 'ab' * 32
        with patch.object(release.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, APKSIGNER_OUTPUT.format(digest=digest), '')):
            self.assertEqual({'certificateSha256': digest, 'schemes': ['v2', 'v3']}, release.signer(self.root / 'a.apk'))
        with patch.object(release.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', 'DOES NOT VERIFY')), \
                self.assertRaisesRegex(RuntimeError, 'DOES NOT VERIFY'):
            release.signer(self.root / 'a.apk')
        two = APKSIGNER_OUTPUT.format(digest=digest) + 'Signer #2 certificate SHA-256 digest: ' + 'cd' * 32 + '\n'
        with patch.object(release.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, two, '')), \
                self.assertRaisesRegex(RuntimeError, 'exactly one'):
            release.signer(self.root / 'a.apk')

    def test_debian_cross_check_reads_the_bundled_apk(self):
        deb = self.root / 'package.deb'; deb_with_apk(deb, b'apk-bytes')
        self.assertEqual(hashlib.sha256(b'apk-bytes').hexdigest(), release.deb_apk_sha256(deb))
        with patch.object(release.shutil, 'which', return_value=None):  # pure-Python fallback without dpkg
            self.assertEqual(hashlib.sha256(b'apk-bytes').hexdigest(), release.deb_apk_sha256(deb))
            zstd = self.root / 'zstd.deb'
            zstd.write_bytes(deb.read_bytes().replace(b'data.tar.gz/   ', b'data.tar.zst/  '))
            with self.assertRaisesRegex(RuntimeError, 'zstd'):
                release.deb_apk_sha256(zstd)
        (self.root / 'bad.deb').write_bytes(b'not an archive')
        with self.assertRaisesRegex(RuntimeError, 'not a Debian archive'):
            release.deb_apk_sha256(self.root / 'bad.deb')

    def folder(self, signature):
        folder = self.root / 'release'; folder.mkdir()
        (folder / 'app.apk').write_bytes(b'apk'); (folder / 'NOTICE').write_text('notice')
        identity = {'package': release.PACKAGE, 'versionCode': 8, 'versionName': '0.1.1-dev6', 'debuggable': False, 'abis': ['arm64-v8a']}
        manifest = {'artifacts': {'apk': {'file': 'app.apk', **identity, 'signature': signature}}, 'versions': {'app': {}}}
        (folder / release.MANIFEST).write_text(json.dumps(manifest))
        release.write_checksums(folder)
        return folder, identity

    def verify(self, folder, identity, actual_signer=None, **options):
        module = verifier.release  # the verifier loads its own copy of the assembler helpers
        with patch.object(module, 'badging', return_value=identity), patch.object(module, 'require_contents'), \
                patch.object(module, 'signer', return_value=actual_signer):
            return verifier.verify(folder, **options)

    def test_verify_accepts_matching_signed_folder_and_expected_certificate(self):
        signature = {'certificateSha256': 'ab' * 32, 'schemes': ['v2', 'v3']}
        folder, identity = self.folder(signature)
        report = self.verify(folder, identity, signature, expect_certificate='AB' * 32)
        self.assertTrue(report['verified'], report['problems'])
        report = self.verify(folder, identity, signature, expect_certificate='cd' * 32)
        self.assertIn('Signer certificate is not the expected maintainer certificate', report['problems'])

    def test_verify_reports_tampering_identity_drift_and_unsigned_apks(self):
        folder, identity = self.folder(None)
        (folder / 'NOTICE').write_text('changed'); (folder / 'extra.bin').write_bytes(b'x')
        report = self.verify(folder, dict(identity, versionCode=9, debuggable=True))
        self.assertFalse(report['verified'])
        for problem in ('Checksum mismatch: NOTICE', 'Unlisted file: extra.bin', 'APK versionCode 9 differs from manifest 8',
                        'APK is debuggable', 'APK is unsigned (dry run); not a release'):
            self.assertIn(problem, report['problems'])

    def test_signed_release_refuses_dirty_trees_keys_inside_the_repository_and_missing_alias(self):
        for arguments, message in (
                (['--keystore', str(self.root / 'k.jks'), '--key-alias', 'a', '--allow-dirty'], 'clean tracked tree'),
                (['--keystore', str(ROOT / 'release.jks'), '--key-alias', 'a'], 'outside the repository'),
                (['--keystore', str(self.root / 'k.jks')], 'requires --key-alias')):
            with self.subTest(arguments=arguments), contextlib.redirect_stderr(io.StringIO()) as error, \
                    self.assertRaises(SystemExit):
                release.main(['--apk', 'x.apk', '--out', str(self.root / 'out'), *arguments])
            self.assertIn(message, error.getvalue())
        with patch.object(release.subprocess, 'run', side_effect=[subprocess.CompletedProcess([], 0, 'abc\n', ''),
                                                                   subprocess.CompletedProcess([], 0, ' M file\n', '')]):
            with self.assertRaisesRegex(RuntimeError, 'commit first'):
                release.source_state(allow_dirty=False)


if __name__ == '__main__':
    unittest.main()
