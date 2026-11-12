"""Packaged workspace orchestration preserves paths, errors and backend failures."""
import contextlib
import io
import os
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
