import contextlib
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    'endurance_monitor', ROOT / 'tests/integration/endurance_monitor.py'
)
monitor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(monitor)

SAMPLE = """U|1000.00
P|dev.ros2android.android|42|204800|1000 500
P|dev.ros2android.android.runner.fixture_cpp|77|102400|300 100
B|level: 98
B|temperature: 331
B|USB powered: true
T|Thermal Status: 1
T|Temperature{mValue=41.5, mType=0, mName=cpu0, mStatus=0}
T|Temperature{mValue=36.0, mType=3, mName=skin, mStatus=0}
T|Temperature{mValue=99.0, mType=9, mName=other, mStatus=0}
"""


class EnduranceMonitorTest(unittest.TestCase):
    def test_parses_own_processes_battery_and_thermal_readings(self):
        sample = monitor.parse(SAMPLE)
        self.assertEqual(1_000_000, sample['boot_ms'])
        self.assertEqual(
            {'rssKb': 204800, 'cpuSeconds': 15.0},
            sample['processes']['dev.ros2android.android#42'],
        )
        self.assertEqual('98', sample['battery']['level'])
        self.assertEqual(1, sample['thermalStatus'])
        self.assertEqual(
            {'cpu': 41.5, 'skin': 36.0}, sample['temperatures']
        )  # unknown types ignored

    def test_summary_reports_growth_cpu_battery_and_peaks(self):
        later = (
            SAMPLE.replace('U|1000.00', 'U|1100.00')
            .replace('|204800|1000 500', '|215040|2000 1500')
            .replace('level: 98', 'level: 96')
            .replace('mValue=41.5', 'mValue=44.0')
        )
        summary = monitor.summarize([monitor.parse(SAMPLE), monitor.parse(later)])
        main = summary['processes']['dev.ros2android.android#42']
        self.assertEqual(210.0, main['peakRssMb'])
        self.assertEqual(10.0, main['rssGrowthMb'])
        self.assertEqual(20.0, main['meanCpuPercent'])  # 20 CPU-seconds over 100 s
        self.assertEqual(
            {'startLevel': 98, 'endLevel': 96, 'peakTemperatureC': 33.1}, summary['battery']
        )
        self.assertEqual(44.0, summary['thermal']['peakTemperaturesC']['cpu'])

    def test_rejects_other_packages_and_bad_intervals_before_device_access(self):
        for extra in (
            ['--package', 'com.example.app'],
            ['--package', 'dev.ros2android.android', '--interval', '0.5'],
        ):
            with (
                self.subTest(extra=extra),
                patch.object(monitor.subprocess, 'run') as run,
                contextlib.redirect_stderr(io.StringIO()),
                self.assertRaises(SystemExit),
            ):
                monitor.main([*extra, '--output', '/nonexistent/x.jsonl', '--', 'true'])

            run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
