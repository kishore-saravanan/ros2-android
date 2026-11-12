import contextlib
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))
from ros2android import project


class ProjectStopTest(unittest.TestCase):
    def invoke(self, snapshots, clock):
        output, errors = io.StringIO(), io.StringIO()
        replies = ['Broadcast completed: result=0, data="' + json.dumps(value) + '"'
                   for value in snapshots]
        with patch.object(project.subprocess, "check_output", side_effect=replies) as adb, \
                patch.object(project.time, "monotonic", side_effect=clock), \
                patch.object(project.time, "sleep"), \
                contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            result = project.run(["--project", str(ROOT / "examples/cpp_talker"), "stop"], tooling_root=ROOT)
        return result, output.getvalue(), errors.getvalue(), adb.call_args_list

    def test_backend_transition_at_deadline_is_failure_without_force_stop(self):
        result, output, errors, calls = self.invoke([
            {"state": "Stopping", "busy": False, "native": {"transition": True}}
        ], [0, 11])
        self.assertEqual(1, result)
        self.assertEqual("", output)
        self.assertIn("Cooperative stop did not finish", errors)
        self.assertEqual(1, len(calls))
        self.assertIn("dev.ros2android.runner.STOP", calls[0].args[0][-1])
        self.assertNotIn("force-stop", calls[0].args[0][-1])

    def test_cleanup_failure_is_not_reported_as_stopped(self):
        result, output, errors, _ = self.invoke([
            {"state": "Stop failed: process did not terminate", "busy": False,
             "native": {"running": False}}
        ], [0, 11])
        self.assertEqual(1, result)
        self.assertEqual("", output)
        self.assertIn("process did not terminate", errors)

    def test_transition_can_finish_before_deadline(self):
        stopped = {"state": "Stopped", "busy": False, "native": {"running": False}}
        result, output, errors, calls = self.invoke([
            {"state": "Stopping", "busy": True, "native": {"transition": True}}, stopped
        ], [0, 0, 1])
        self.assertEqual(0, result)
        self.assertEqual(stopped, json.loads(output))
        self.assertEqual("", errors)
        self.assertEqual(2, len(calls))
        self.assertIn("dev.ros2android.runner.STATUS", calls[1].args[0][-1])

    def test_already_stopped_is_idempotent(self):
        stopped = {"state": "Stopped", "busy": False, "native": {"running": False}}
        result, output, errors, calls = self.invoke([stopped], [0, 0])
        self.assertEqual(0, result)
        self.assertEqual(stopped, json.loads(output))
        self.assertEqual("", errors)
        self.assertEqual(1, len(calls))


class StatusHealthHintTest(unittest.TestCase):
    def test_hints_explain_pauses_and_battery_policy_and_ignore_old_runners(self):
        from ros2android.project import health_hints
        package = 'dev.ros2android.android.runner.demo'
        self.assertEqual([], health_hints({'state': 'Running'}, package))  # older runner: unknown, no claim
        self.assertEqual([], health_hints({'health': {'pauses': 0, 'batteryOptimized': False}}, package))
        hints = health_hints({'health': {'pauses': 2, 'longestPauseMs': 2200, 'batteryOptimized': True}}, package)
        self.assertEqual(2, len(hints))
        self.assertIn('paused dev.ros2android.android.runner.demo 2 time(s), longest 2.2 s', hints[0])
        self.assertIn('battery optimization applies', hints[1])
