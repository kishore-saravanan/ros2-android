import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest
import zipfile
import struct
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


release = load('assemble_release', 'setup/assemble-release.py')
verifier = load('verify_release', 'setup/verify-release.py')
native_sdk = release.native_sdk

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


def deb_with_apk(path, apk_bytes, main_json=None):
    """Minimal ar archive: debian-binary, control.tar.gz, data.tar.gz with the bundled APK (and main.json)."""
    def member(name, body):
        header = f'{name + "/":<16}{0:<12}{0:<6}{0:<6}{"100644":<8}{len(body):<10}`\n'.encode()
        return header + body + (b'\n' if len(body) % 2 else b'')
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode='w:gz') as tar:
        files = {'main.apk': apk_bytes}
        if main_json is not None:
            files['main.json'] = json.dumps(main_json).encode()
        for name, body in files.items():
            info = tarfile.TarInfo('./usr/lib/python3/dist-packages/ros2android/artifacts/' + name); info.size = len(body)
            tar.addfile(info, io.BytesIO(body))
    path.write_bytes(b'!<arch>\n' + member('debian-binary', b'2.0\n') + member('control.tar.gz', b'x') +
                     member('data.tar.gz', data.getvalue()))


class ReleaseAssemblyTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)

    def test_release_content_gate_rejects_four_kb_native_library(self):
        data = bytearray(120)
        data[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<H', data, 18, 183)
        struct.pack_into('<Q', data, 32, 64)
        struct.pack_into('<HH', data, 54, 56, 1)
        struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0, 0, 120, 120, 4096)
        path = self.root / 'bad.apk'
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('lib/arm64-v8a/libbad.so', data)
        with self.assertRaisesRegex(ValueError, '16 KB'):
            release.require_contents(path)

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

    def test_pre_signed_apk_needs_expected_certificate_and_unchanged_app_sources(self):
        with contextlib.redirect_stderr(io.StringIO()) as error, self.assertRaises(SystemExit):
            release.main(['--apk', 'x.apk', '--out', str(self.root / 'out'), '--already-signed'])
        self.assertIn('requires --expect-certificate and --apk-revision', error.getvalue())
        args = type('Args', (), {'already_signed': True, 'unsigned_dry_run': False, 'expect_certificate': 'AB' * 32,
                                 'apk_revision': 'HEAD', 'apk': self.root / 'a.apk', 'out': self.root / 'out',
                                 'allow_dirty': True, 'wheel': None, 'deb': None, 'sdk': None})()
        matrix = json.loads((ROOT / 'python/ros2android/compatibility.json').read_text())
        identity = {'package': release.PACKAGE, 'debuggable': False, **matrix['app']}
        with patch.object(release, 'badging', return_value=identity), patch.object(release, 'require_contents'), \
                patch.object(release, 'signer', return_value={'certificateSha256': 'cd' * 32, 'schemes': ['v2']}):
            with self.assertRaisesRegex(RuntimeError, 'unexpected certificate'):
                release.assemble(args)

    def sdk_archive(self, **overrides):
        """A minimal SDK archive whose manifest is the first member, as the packager writes it."""
        matrix = json.loads((ROOT / 'python/ros2android/compatibility.json').read_text())
        manifest = {'format': native_sdk.FORMAT, 'abi': native_sdk.ABI, 'version': matrix['hostClientVersion'],
                    'inputsDigest': native_sdk.inputs_digest(ROOT), 'source': {'revision': 'abc', 'clean': True}, 'prefix': '/build'}
        manifest.update(overrides)
        archive = self.root / 'ros2-android-native-sdk-test-arm64-v8a.tar.xz'
        with tarfile.open(archive, 'w:xz') as tar:
            data = json.dumps(manifest).encode()
            info = tarfile.TarInfo(native_sdk.MANIFEST); info.size = len(data)
            tar.addfile(info, io.BytesIO(data))
        return archive, manifest, matrix

    def test_sdk_archive_must_match_this_release(self):
        archive, manifest, matrix = self.sdk_archive()
        self.assertEqual(manifest, release.sdk_manifest(archive, matrix))
        for change, message in (({'inputsDigest': '0' * 64}, 'different pinned inputs'), ({'version': '9.9'}, 'client 9.9'),
                                ({'source': {'revision': 'abc', 'clean': False}}, 'dirty tree'), ({'abi': 'x86_64'}, 'Unsupported')):
            with self.subTest(change=change):
                archive, _, _ = self.sdk_archive(**change)
                with self.assertRaisesRegex(RuntimeError, message):
                    release.sdk_manifest(archive, matrix)

    def test_verify_cross_checks_the_sdk_archive(self):
        signature = {'certificateSha256': 'ab' * 32, 'schemes': ['v2', 'v3']}
        folder, identity = self.folder(signature)
        archive, manifest, matrix = self.sdk_archive()
        shutil.copyfile(archive, folder / archive.name)
        data = json.loads((folder / release.MANIFEST).read_text())
        data['versions']['hostClient'] = matrix['hostClientVersion']
        data['artifacts']['sdk'] = {'file': archive.name, **{key: manifest[key] for key in ('version', 'abi', 'inputsDigest', 'source')}}
        def save():
            (folder / release.MANIFEST).write_text(json.dumps(data)); release.write_checksums(folder)
        save()
        report = self.verify(folder, identity, signature)
        self.assertTrue(report['verified'], report['problems'])
        data['artifacts']['sdk']['inputsDigest'] = '0' * 64; save()
        self.assertIn('SDK archive inputsDigest differs from manifest', self.verify(folder, identity, signature)['problems'])

    def client_wheel(self, pinned_sha256, inputs_root=ROOT, version=None):
        """A wheel holding release.json and an sdk.zip snapshot of the SDK inputs."""
        matrix = json.loads((ROOT / 'python/ros2android/compatibility.json').read_text())
        snapshot = io.BytesIO()
        with zipfile.ZipFile(snapshot, 'w') as tooling:
            for entry in native_sdk.INPUTS:
                path = Path(inputs_root) / entry
                for item in ([path] if path.is_file() else sorted(p for p in path.rglob('*') if p.is_file() and '__pycache__' not in p.parts)):
                    tooling.write(item, item.relative_to(inputs_root).as_posix())
        wheel = self.root / 'client.whl'
        with zipfile.ZipFile(wheel, 'w') as bundle:
            pin = {'version': version or matrix['hostClientVersion'], 'sha256': pinned_sha256,
                   'inputsDigest': native_sdk.inputs_digest(inputs_root)}
            bundle.writestr('ros2android/release.json', json.dumps({'nativeSdk': pin}))
            bundle.writestr('ros2android/sdk.zip', snapshot.getvalue())
        return wheel

    def test_release_consistency_catches_mismatched_client_deb_and_sdk(self):
        archive, manifest, _ = self.sdk_archive()
        canonical = archive.with_name(f"ros2-android-native-sdk-{manifest['version']}-{native_sdk.ABI}.tar.xz")
        archive.rename(canonical)
        wheel = self.client_wheel(release.sha256(canonical))
        deb = self.root / 'client.deb'
        deb_with_apk(deb, b'apk', {'wheelSha256': release.sha256(wheel)})
        self.assertEqual([], release.release_consistency(wheel, deb, canonical, manifest))
        wrong_pin = self.client_wheel('0' * 64)
        self.assertIn('The client pins a different native SDK (release.json); setup workspace-tools would reject this one',
                      release.release_consistency(wrong_pin, None, canonical, manifest))
        self.assertIn('The Debian package was built from a different wheel',
                      release.release_consistency(wrong_pin, deb, None, None))
        changed = dict(manifest, inputsDigest='0' * 64)
        self.assertIn("The client's build inputs differ from the SDK's; setup sdk would reject it",
                      release.release_consistency(wheel, None, canonical, changed))


if __name__ == '__main__':
    unittest.main()
