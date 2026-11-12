import contextlib
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))
from ros2android import project


class ProjectStartTest(unittest.TestCase):
    def test_invalid_profiles_fail_before_any_subprocess(self):
        cases = [
            (["--domain", "102"], "domain"),
            (["--domain", "-1"], "domain"),
            (["--namespace", "relative"], "namespace"),
            (["--namespace", "/robot//camera"], "namespace"),
            (["--peer", "PC_IP"], "Peer address"),
            (["--local", "256.1.2.3"], "Phone address"),
            (["--local", "::1"], "Phone address"),
            (["--", "x" * 4097], "ROS arguments"),
            (["--", "x\0y"], "ROS arguments"),
            (["--", *(["x"] * 129)], "ROS arguments"),
        ]
        for arguments, reason in cases:
            with self.subTest(arguments=reason), patch.object(project.subprocess, "check_output") as read, \
                    patch.object(project.subprocess, "run") as run, \
                    contextlib.redirect_stderr(io.StringIO()) as errors:
                result = project.run(["--project", str(ROOT / "examples/cpp_talker"),
                                      "start", *arguments], tooling_root=ROOT)
                self.assertEqual(1, result)
                self.assertIn(reason, errors.getvalue())
                read.assert_not_called()
                run.assert_not_called()

    def test_offline_and_explicit_profiles_keep_ros_arguments(self):
        for domain, namespace, peer, local in [
            (0, "/", "", ""), (101, "/robot/camera", "192.168.1.2", "192.168.1.3")
        ]:
            with self.subTest(domain=domain):
                self.assertEqual(["-p", "message:=hello"], project.validate_start(
                    domain, namespace, peer, local, ["--", "--ros-args", "-p", "message:=hello"]))
        self.assertEqual(["x" * 4096] * 128, project.validate_start(
            0, "/robot", "", "", ["x" * 4096] * 128))

    def test_argument_limits_match_android_utf16_length(self):
        self.assertEqual(["😀" * 2048], project.validate_start(0, "/", "", "", ["😀" * 2048]))
        with self.assertRaises(ValueError):
            project.validate_start(0, "/", "", "", ["😀" * 2049])
