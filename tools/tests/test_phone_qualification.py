import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('qualification', Path(__file__).resolve().parents[2] / 'tests/integration/phone_qualification.py')
qualification = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(qualification)


class PhoneQualificationTest(unittest.TestCase):
    def test_no_results_and_skipped_opt_in_are_not_qualification(self):
        for name, cases in [('suite', []), ('dashboard-peers', [{'status': 'skip'}]),
                            ('dashboard-peers', [{'status': 'pass'}])]:
            self.assertFalse(qualification.batch_result(name, 0, cases)['qualified'])

    def test_default_suite_skips_are_scoped_and_failures_cannot_pass(self):
        cases = [{'status': 'pass'}, {'status': 'skip'}]
        self.assertTrue(qualification.batch_result('suite', 0, cases)['qualified'])
        self.assertFalse(qualification.batch_result('suite', 1, cases)['qualified'])
        self.assertFalse(qualification.batch_result('suite', 0, cases + [{'status': 'fail'}])['qualified'])
        self.assertTrue(qualification.batch_result('dashboard-peers', 0, [{'status': 'pass'}] * 2)['qualified'])

    def test_failed_build_cannot_reuse_previous_xml(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); xml = root / 'gradle'; xml.mkdir()
            (xml / 'old.xml').write_text('<testsuite><testcase classname="old" name="old"/></testsuite>')
            with patch.object(qualification.subprocess, 'call', return_value=1):
                result = qualification.run_batch('suite', ['build'], xml, root, {})
            self.assertEqual([], result['cases'])
            self.assertFalse(result['qualified'])
            self.assertFalse(xml.exists())

    def test_each_batch_keeps_its_own_xml_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); xml = root / 'gradle'
            def write_results(*args, **kwargs):
                xml.mkdir()
                (xml / 'new.xml').write_text('<testsuite><testcase classname="fixture.Current" name="works" time="1"/></testsuite>')
                return 0
            with patch.object(qualification.subprocess, 'call', side_effect=write_results):
                result = qualification.run_batch('suite', ['build'], xml, root, {})
            self.assertTrue(result['qualified'])
            self.assertTrue((root / 'suite-results/new.xml').is_file())
            self.assertEqual('Current#works', result['cases'][0]['test'])
