import sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from controller_identity import controller_certificate


class ControllerIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / 'controller.apk'
        self.apk.write_bytes(b'fixture')

    def test_only_verified_main_apk_single_signer_is_accepted(self):
        digest = 'AB' * 32
        with patch('ros2android.controller_identity.subprocess.check_output', side_effect=[
            "package: name='dev.ros2android.android' versionCode='8'\n",
            f'Signer #1 certificate SHA-256 digest: {digest}\n']) as run:
            self.assertEqual(digest.lower(), controller_certificate(self.apk))
            self.assertEqual('verify', run.call_args_list[1].args[0][1])

    def test_wrong_package_is_rejected_before_signature_tool(self):
        with patch('ros2android.controller_identity.subprocess.check_output', return_value="package: name='other.app' versionCode='1'\n") as run:
            with self.assertRaises(ValueError): controller_certificate(self.apk)
            self.assertEqual(1, run.call_count)

    def test_missing_or_multiple_signers_fail_closed(self):
        for signing in ('', '\n'.join(f'Signer #{n} certificate SHA-256 digest: ' + 'ab'*32 for n in (1, 2))):
            with patch('ros2android.controller_identity.subprocess.check_output', side_effect=[
                "package: name='dev.ros2android.android' versionCode='8'\n", signing]):
                with self.assertRaises(ValueError): controller_certificate(self.apk)
