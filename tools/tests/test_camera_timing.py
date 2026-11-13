import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('camera_timing', Path(__file__).resolve().parents[1] / 'camera_timing.py')
clock = importlib.util.module_from_spec(spec)
spec.loader.exec_module(clock)


class CameraClockTest(unittest.TestCase):
    def test_shortest_round_trip_sets_offset_and_uncertainty(self):
        with patch.object(clock.time, 'time', side_effect=[100, 100.04, 200, 200.01]), \
             patch.object(clock.time, 'monotonic', side_effect=[1, 1.04, 2, 2.01]), \
             patch.object(clock.subprocess, 'run', side_effect=[
                 subprocess.CompletedProcess([], 0, '100.52\n'),
                 subprocess.CompletedProcess([], 0, '200.505\n')]) as run:
            offset, uncertainty = clock.estimate_adb_clock_offset('/sdk/adb', 'test-target', 2)
        self.assertAlmostEqual(500, offset)
        self.assertAlmostEqual(5, uncertainty)
        self.assertEqual(['/sdk/adb', '-s', 'test-target', 'shell', 'echo $EPOCHREALTIME'], run.call_args.args[0])

    def test_shell_error_and_nonfinite_values_are_not_measurements(self):
        for code, text in [(1, ''), (0, 'nan'), (0, '$EPOCHREALTIME'), (0, '-1')]:
            with self.subTest(text=text), patch.object(clock.time, 'time', side_effect=[100, 100.01]), \
                 patch.object(clock.time, 'monotonic', side_effect=[1, 1.01]), \
                 patch.object(clock.subprocess, 'run', return_value=subprocess.CompletedProcess([], code, text)):
                with self.assertRaises(ValueError):
                    clock.estimate_adb_clock_offset(samples=1)

    def test_local_clock_jump_is_rejected(self):
        with patch.object(clock.time, 'time', side_effect=[100, 110]), \
             patch.object(clock.time, 'monotonic', side_effect=[1, 1.01]), \
             patch.object(clock.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '100.1')):
            with self.assertRaisesRegex(ValueError, 'PC clock changed'):
                clock.estimate_adb_clock_offset(samples=1)

    def test_invalid_sample_count_never_contacts_adb(self):
        with patch.object(clock.subprocess, 'run') as run:
            with self.assertRaises(ValueError):
                clock.estimate_adb_clock_offset(samples=0)
            run.assert_not_called()
