import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('own_process_observer', ROOT / 'tests/integration/own_process_observer.py')
observer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(observer)


class OwnProcessObserverTest(unittest.TestCase):
    def test_rejects_other_packages_before_any_device_call(self):
        with tempfile.TemporaryDirectory() as temp:
            for name in ('com.example.app', 'dev.ros2android.android.runner.x;reboot',
                         'dev.ros2android.androidx', 'dev.ros2android.android.runner.'):
                with self.subTest(name=name), patch.object(observer.subprocess, 'Popen') as popen, \
                        patch.object(observer.subprocess, 'call') as call, \
                        contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                    observer.main(['--package', name, '--output', str(Path(temp) / 'o.jsonl'), '--', 'true'])
                popen.assert_not_called(); call.assert_not_called()

    def test_requires_bounded_interval_and_command(self):
        for extra in (['--interval', '0.01', '--', 'true'], ['--interval', '9', '--', 'true'], []):
            with self.subTest(extra=extra), patch.object(observer.subprocess, 'Popen') as popen, \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                observer.main(['--package', 'dev.ros2android.android', '--output', '/nonexistent/o.jsonl', *extra])
            popen.assert_not_called()

    def test_device_loop_names_only_validated_packages(self):
        loop = observer.device_loop(['dev.ros2android.android', 'dev.ros2android.android.runner.fixture_cpp'], 0.2)
        self.assertIn('for name in dev.ros2android.android dev.ros2android.android.runner.fixture_cpp;', loop)
        self.assertIn('sleep 0.200', loop)

    def test_parses_samples_and_summarizes_frozen_intervals(self):
        lines = ['S|100.00|dev.ros2android.android.runner.fixture_cpp|7|S|/|/foreground',
                 'S|100.20|dev.ros2android.android.runner.fixture_cpp|7|D|/frozen|/background',
                 'S|100.40|dev.ros2android.android.runner.fixture_cpp|7|D|/frozen|/background',
                 'S|100.60|dev.ros2android.android.runner.fixture_cpp|7|S|/|/foreground',
                 'S|100.80|dev.ros2android.android.runner.fixture_cpp|7|D|/frozen|/background',
                 'noise from shell', 'S|bad|x|1|S||']
        samples = [sample for sample in map(observer.parse, lines) if sample]
        self.assertEqual(5, len(samples))
        self.assertEqual({'boot_ms': 100200, 'package': 'dev.ros2android.android.runner.fixture_cpp', 'pid': 7,
                          'state': 'D', 'freezer': '/frozen', 'cpuset': '/background'}, samples[1])
        row = observer.summarize(samples)['dev.ros2android.android.runner.fixture_cpp#7']
        self.assertEqual(5, row['samples']); self.assertEqual(3, row['frozenSamples'])
        self.assertEqual([{'from_ms': 100200, 'to_ms': 100400}, {'from_ms': 100800, 'to_ms': 100800}],
                         row['frozenIntervals'])
        self.assertEqual({'S': 2, 'D': 3}, row['states'])

    def test_cgroup_v2_freezer_and_unavailable_state_are_distinct(self):
        frozen = observer.parse('S|101.0|dev.ros2android.android.runner.fixture_cpp|7|S|||1|do_freezer_trap|12 3')
        unknown = observer.parse('S|102.0|dev.ros2android.android.runner.fixture_cpp|7|S||||ep_poll|12 3')
        self.assertTrue(frozen['cgroupFrozen'])
        self.assertIsNone(unknown['cgroupFrozen'])
        row = observer.summarize([frozen, unknown])['dev.ros2android.android.runner.fixture_cpp#7']
        self.assertEqual(1, row['frozenSamples'])
        self.assertEqual(1, row['freezerReadableSamples'])
        self.assertIn('*/pid_"$pid"', observer.device_loop(['dev.ros2android.android'], 0.2))


if __name__ == '__main__':
    unittest.main()
