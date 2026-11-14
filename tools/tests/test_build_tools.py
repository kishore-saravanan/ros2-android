import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'python'))
from ros2android import build_tools, cli


class FakeHost:
    """Simulates venv creation and pip results without network access."""

    def __init__(self, venv, host_provides_tools):
        self.venv, self.host_provides_tools, self.calls = venv, host_provides_tools, []

    def script(self, name):
        path = self.venv / 'bin' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('#!/bin/sh\n')
        path.chmod(0o755)

    def run(self, command, check=False, capture_output=False, text=False):
        command = [str(part) for part in command]
        self.calls.append(command)

        if command[1:4] == ['-m', 'venv', '--system-site-packages']:
            self.script('python')
        elif command[1:4] == ['-m', 'pip', 'install'] and '--no-deps' in command:
            self.script('colcon' if command[-1].startswith('colcon-core==') else 'vcs')
        elif command[1:4] == ['-m', 'pip', 'install'] and not self.host_provides_tools:
            self.script('colcon')
            self.script('vcs')
        elif command[1:4] == ['-m', 'pip', '--version']:
            return subprocess.CompletedProcess(
                command, 1 if getattr(self, 'no_pip', False) else 0, '', ''
            )
        elif command[1] == '-c':  # importlib.metadata.version
            return subprocess.CompletedProcess(
                command, 0, {'colcon-core': '0.19.0\n', 'vcstool': '0.3.0\n'}[command[-1]], ''
            )

        return subprocess.CompletedProcess(command, 0, '', '')


class BuildToolsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.venv = Path(temp.name) / 'tools venv'

    def prepare(self, host_provides_tools):
        host = FakeHost(self.venv, host_provides_tools)
        with patch.object(build_tools.subprocess, 'run', side_effect=host.run):
            return build_tools.prepare(self.venv), host.calls

    def test_fresh_host_installs_pins_into_new_system_site_venv(self):
        report, calls = self.prepare(host_provides_tools=False)
        self.assertEqual(
            ['/usr/bin/python3', '-m', 'venv', '--system-site-packages', str(self.venv)], calls[0]
        )
        self.assertEqual([str(self.venv / 'bin/python'), '-m', 'pip', '--version'], calls[1])
        self.assertEqual(
            [str(self.venv / 'bin/python'), '-m', 'pip', 'install', *build_tools.PINNED], calls[2]
        )
        self.assertFalse(any('--no-deps' in call for call in calls))
        self.assertEqual([str(self.venv / 'bin/colcon'), '--help'], calls[-1])
        self.assertTrue(report['created'])
        self.assertEqual({}, report['scriptsReinstalledFromHostVersions'])

    def test_host_apt_colcon_still_gets_venv_scripts_without_replacing_dependencies(self):
        report, calls = self.prepare(host_provides_tools=True)
        forced = [call for call in calls if '--no-deps' in call]
        self.assertEqual(
            [
                [
                    str(self.venv / 'bin/python'),
                    '-m',
                    'pip',
                    'install',
                    '--no-deps',
                    '--ignore-installed',
                    'colcon-core==0.19.0',
                ],
                [
                    str(self.venv / 'bin/python'),
                    '-m',
                    'pip',
                    'install',
                    '--no-deps',
                    '--ignore-installed',
                    'vcstool==0.3.0',
                ],
            ],
            forced,
        )
        self.assertEqual(
            {'colcon-core': '0.19.0', 'vcstool': '0.3.0'},
            report['scriptsReinstalledFromHostVersions'],
        )
        self.assertTrue(os.access(self.venv / 'bin/colcon', os.X_OK))

    def test_existing_venv_is_reused_and_missing_scripts_fail_clearly(self):
        host = FakeHost(self.venv, host_provides_tools=False)
        host.script('python')

        with patch.object(build_tools.subprocess, 'run', side_effect=host.run):
            report = build_tools.prepare(self.venv)

        self.assertFalse(report['created'])
        self.assertFalse(any(call[1:3] == ['-m', 'venv'] for call in host.calls))

        broken = FakeHost(self.venv / '..' / 'broken', host_provides_tools=True)
        broken.run = (
            lambda original: (
                lambda command, **kw: (
                    subprocess.CompletedProcess(command, 0, '', '')
                    if '--no-deps' in [str(c) for c in command]
                    else original(command, **kw)
                )
            )
        )(broken.run)
        with (
            patch.object(build_tools.subprocess, 'run', side_effect=broken.run),
            self.assertRaisesRegex(RuntimeError, 'colcon, vcs'),
        ):
            build_tools.prepare(self.venv / '..' / 'broken')

    def test_cli_routes_without_extracting_the_sdk(self):
        with (
            patch.object(
                sys, 'argv', ['ros2-android', 'setup', 'build-tools', '--venv', str(self.venv)]
            ),
            patch.object(cli, 'sdk_root') as sdk,
            patch.object(build_tools, 'prepare', return_value={'ok': True}) as prepare,
            contextlib.redirect_stdout(io.StringIO()) as output,
        ):
            self.assertEqual(0, cli.main())

        sdk.assert_not_called()
        prepare.assert_called_once_with(self.venv, '/usr/bin/python3')
        self.assertEqual({'ok': True}, json.loads(output.getvalue()))

    def test_existing_venv_without_pip_gives_an_actionable_error(self):
        host = FakeHost(self.venv, host_provides_tools=False)
        host.script('python')
        host.no_pip = True
        with (
            patch.object(build_tools.subprocess, 'run', side_effect=host.run),
            self.assertRaisesRegex(RuntimeError, 'has no pip'),
        ):
            build_tools.prepare(self.venv)

        self.assertFalse(any(call[1:4] == ['-m', 'pip', 'install'] for call in host.calls))

    def test_missing_os_venv_support_gives_an_actionable_error(self):
        def fail(command, **kwargs):
            raise subprocess.CalledProcessError(1, command)

        with (
            patch.object(build_tools.subprocess, 'run', side_effect=fail),
            self.assertRaisesRegex(RuntimeError, 'python3.12-venv'),
        ):
            build_tools.prepare(self.venv)

    def test_failure_is_reported_without_traceback(self):
        with (
            patch.object(build_tools, 'prepare', side_effect=RuntimeError('pip unavailable')),
            contextlib.redirect_stderr(io.StringIO()) as error,
        ):
            self.assertEqual(1, build_tools.run(['--venv', str(self.venv)]))

        self.assertIn('build tools: pip unavailable', error.getvalue())


if __name__ == '__main__':
    unittest.main()
