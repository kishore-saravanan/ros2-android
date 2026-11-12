import contextlib
import io
import json
import os
from pathlib import Path
import shlex
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'python'))
from ros2android import shell


class ShellCommandTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.workspace = self.home / 'external_ws'
        self.cache = self.home / '.cache/ros2-android-workspaces/external_ws'
        self.env = patch.dict(os.environ, HOME=str(self.home), ANDROID_HOME=str(self.home / 'sdk'))
        self.env.start(); self.addCleanup(self.env.stop)
        self.package = 'dev.ros2android.android.runner.shell_fixture'

    def stage(self, **overrides):
        (self.cache / 'runner-project').mkdir(parents=True)
        (self.cache / 'workspace-root.txt').write_text(str(self.workspace))
        manifest = dict(applicationId=self.package, withShell=True)
        manifest.update(overrides)
        (self.cache / 'runner-project/robot-project.json').write_text(json.dumps(manifest))

    def invoke(self, *arguments):
        return shell.main(['--workspace', str(self.workspace), *arguments])

    def test_unbuilt_workspace_and_invalid_id_do_not_touch_device_or_create_cache(self):
        for arguments in ([], ['--id', '../../outside']):
            with self.subTest(arguments=arguments), patch.object(shell.subprocess, 'check_output') as adb, \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                self.invoke(*arguments)
            self.assertEqual(2, error.exception.code)
            adb.assert_not_called()
            self.assertFalse(self.cache.exists())

    def test_disabled_shell_and_non_runner_package_are_rejected_before_adb(self):
        self.stage(withShell=False)
        for manifest in [dict(applicationId=self.package, withShell=False),
                         dict(applicationId='unrelated.app', withShell=True)]:
            with self.subTest(manifest=manifest), patch.object(shell.subprocess, 'check_output') as adb, \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                (self.cache / 'runner-project/robot-project.json').write_text(json.dumps(manifest))
                self.invoke()
            adb.assert_not_called()

    def test_selected_device_and_quoted_command_enter_only_expected_runner(self):
        self.stage()
        expected = f'/data/user/0/{self.package}/files/ros2-shell/environment.sh'
        with patch.object(shell.subprocess, 'check_output', side_effect=[
            'showing=false', 'Starting', f'Broadcast completed: result=0, data="{expected}"'
        ]) as adb, patch.object(shell.os, 'execv') as execute:
            payload = 'hello;$(touch unexpected)'
            self.invoke('--serial', 'selected-fixture', '--', 'printf', '%s', payload)
        command = execute.call_args.args[1]
        self.assertEqual(['-s', 'selected-fixture'], command[1:3])
        target = shlex.split(command[-1])
        self.assertEqual(['run-as', self.package, '/system/bin/sh', '-c'], target[:4])
        self.assertIn(shlex.quote(payload), target[4])
        self.assertTrue(all(call.args[0][1:3] == ['-s', 'selected-fixture'] for call in adb.call_args_list))

    def test_unexpected_environment_cannot_enter_another_uid(self):
        self.stage()
        with patch.object(shell.subprocess, 'check_output', side_effect=[
            'showing=false', 'Starting', 'Broadcast completed: result=0, data="/unexpected/environment.sh"'
        ]), patch.object(shell.os, 'execv') as execute:
            with self.assertRaisesRegex(RuntimeError, 'Unexpected shell environment'):
                self.invoke()
        execute.assert_not_called()
