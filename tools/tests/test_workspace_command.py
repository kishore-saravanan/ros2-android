"""Packaged workspace orchestration preserves paths, errors and backend failures."""
import contextlib
import io
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'python'))
from ros2android.workspace import run


class WorkspaceCommandTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.home = self.root / 'home'
        self.home.mkdir()
        self.workspace = self.root / 'my_ws'
        package = self.workspace / 'src/demo'
        package.mkdir(parents=True)
        (package / 'package.xml').write_text('<package><name>demo</name></package>')
        self.arguments = ['--workspace', str(self.workspace)]
        self.addCleanup(patch.stopall)
        patch('ros2android.workspace.Path.home', return_value=self.home).start()
        self.backend = patch('ros2android.workspace.subprocess.run').start()

    def invoke(self, *arguments):
        with contextlib.redirect_stderr(io.StringIO()) as error:
            try:
                code = run([*self.arguments, *arguments], tooling_root=self.root / 'SDK with spaces')
            except SystemExit as failure:
                code = failure.code
        return code, error.getvalue()

    def test_unknown_build_arguments_do_not_create_cache_or_run_backend(self):
        for command in ('host-build', 'build'):
            code, error = self.invoke(command, '--typo')
            self.assertEqual(2, code)
            self.assertIn('Unknown arguments', error)
            self.assertEqual([], list(self.home.iterdir()))
        self.backend.assert_not_called()

    def test_unbuilt_operations_offer_build_guidance_without_writes(self):
        for command in ('deploy', 'start', 'status', 'stop', 'kill', 'logs', 'launch-plan'):
            code, error = self.invoke(command)
            self.assertEqual(2, code)
            self.assertIn('build first', error)
            self.assertEqual([], list(self.home.iterdir()))
        self.backend.assert_not_called()

    def test_shell_prerequisite_failure_has_installed_command_guidance(self):
        code, error = self.invoke('build')
        self.assertEqual(2, code)
        self.assertIn('ros2-android setup python', error)
        self.assertEqual([], list(self.home.iterdir()))
        self.backend.assert_not_called()

    def test_host_backend_uses_explicit_tooling_root_and_preserves_failure(self):
        self.backend.side_effect = subprocess.CalledProcessError(9, 'colcon')
        code, error = self.invoke('host-build')
        self.assertEqual(1, code)
        self.assertIn('exit status 9', error)
        self.assertNotIn('Traceback', error)
        command = self.backend.call_args.args[0]
        self.assertEqual(str(self.root / 'SDK with spaces/setup/build-colcon-workspace.sh'), command[0])
        self.assertEqual(str(self.workspace), command[1])
        self.assertEqual('host', command[-1])

    def test_cache_ownership_rejects_another_workspace_without_overwrite(self):
        cache = self.home / '.cache/ros2-android-workspaces/my_ws'
        cache.mkdir(parents=True)
        owner = cache / 'workspace-root.txt'
        owner.write_text('/another/workspace')
        code, error = self.invoke('host-build')
        self.assertEqual(2, code)
        self.assertIn('another workspace', error)
        self.assertEqual('/another/workspace', owner.read_text())
        self.backend.assert_not_called()


class WorkspaceJourneyTest(unittest.TestCase):
    """Help explains each step; ros observes from the PC with the workspace's own message overlay."""

    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.home = self.root / 'home'; self.home.mkdir()
        self.workspace = self.root / 'demo_ws'; (self.workspace / 'src').mkdir(parents=True)
        self.addCleanup(patch.stopall)
        patch('ros2android.workspace.Path.home', return_value=self.home).start()
        self.backend = patch('ros2android.workspace.subprocess.run').start()

    def invoke(self, *arguments):
        with contextlib.redirect_stdout(io.StringIO()) as output, contextlib.redirect_stderr(io.StringIO()) as error:
            try:
                code = run(['--workspace', str(self.workspace), *arguments], tooling_root=self.root / 'sdk',
                           prog='ros2-android workspace')
            except SystemExit as failure:
                code = failure.code
        return code, output.getvalue(), error.getvalue()

    def test_help_names_every_step_and_the_journey(self):
        code, output, _ = self.invoke('--help')
        self.assertEqual(0, code)
        self.assertIn('usage: ros2-android workspace', output)
        for step in ('inspect', 'host-build', 'build', 'ros', 'deploy', 'start', 'status', 'stop', 'kill', 'logs', 'launch-plan'):
            self.assertRegex(output, rf'\n    {re.escape(step)}\s+[A-Z]')
        self.assertIn('typical journey:', output)

    def test_ros_uses_the_workspace_overlay_and_pc_middleware_wrapper(self):
        overlay = self.home / '.cache/ros2-android-workspaces/demo_ws/host/install/local_setup.bash'
        overlay.parent.mkdir(parents=True); overlay.write_text('')
        code, _, error = self.invoke('ros', '--interface', '192.0.2.10', '--', 'topic', 'echo', '/result')
        self.assertEqual(0, code); self.assertEqual('', error)
        command = self.backend.call_args.args[0]
        self.assertEqual([str(self.root / 'sdk/tools/pc-ros.sh'), '--interface', '192.0.2.10', '--', 'ros2', 'topic', 'echo', '/result'], command)
        self.assertEqual(str(overlay), self.backend.call_args.kwargs['env']['ROS2_ANDROID_PROJECT_OVERLAY'])

    def test_ros_without_host_build_still_runs_but_explains_custom_types(self):
        code, _, error = self.invoke('ros', '--', 'topic', 'list')
        self.assertEqual(0, code)
        self.assertIn('custom message types need host-build first', error)
        self.assertFalse((self.home / '.cache').exists())

    def test_ros_requires_a_command(self):
        code, _, error = self.invoke('ros')
        self.assertEqual(2, code)
        self.assertIn('Provide ros2 arguments after --', error)
        self.backend.assert_not_called()
