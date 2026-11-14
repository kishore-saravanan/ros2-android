"""Public project contracts reject incompatible inputs before device/build access."""

import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from project_manifest import load_manifest, validate_manifest

ROOT = Path(__file__).resolve().parents[2]


class ProjectManifestTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {
            'schemaVersion': 1,
            'runtimeAbi': 1,
            'id': 'test_project',
            'applicationId': 'dev.ros2android.android.runner.test_project',
        }

    def test_existing_examples_remain_compatible(self):
        for path in (ROOT / 'examples').glob('*/robot-project.json'):
            with self.subTest(project=path.parent.name):
                load_manifest(path.parent)

    def test_runtime_contract_and_legacy_cpp_default(self):
        self.assertEqual(validate_manifest(self.manifest), self.manifest)

        for runtime, supported in [('cpp', (1, 2)), ('python', (1,)), ('workspace', (2,))]:
            for abi in (1, 2, 3, True, '1', None):
                with self.subTest(runtime=runtime, abi=abi):
                    value = dict(self.manifest, runtime=runtime, runtimeAbi=abi)

                    if type(abi) is int and abi in supported:
                        validate_manifest(value)
                    else:
                        with self.assertRaisesRegex(ValueError, 'runtimeAbi'):
                            validate_manifest(value)

    def test_malformed_versions_ids_and_field_types_fail_with_context(self):
        for field, values in {
            'schemaVersion': [True, '1', 2, None],
            'id': ['../other', 'a' * 65, [], None],
            'applicationId': ['personal.app', None, {}],
            'runtime': ['unknown', [], None],
            'name': ['', [], None],
            'launchComponents': [[], {'pkg/exe': '../bad'}, {'bad': 'ok'}],
        }.items():
            for bad in values:
                with self.subTest(field=field, value=bad):
                    with self.assertRaisesRegex(ValueError, field):
                        validate_manifest(dict(self.manifest, **{field: bad}))

        for bad in ([], None, 'text'):
            with self.assertRaisesRegex(ValueError, 'JSON object'):
                validate_manifest(bad)

    def test_relative_paths_and_launch_abi(self):
        for field in ('defaultLaunch', 'pythonSourceDir'):
            for bad in ('/tmp/source', '../source', 'launch/../../source', '', [], None, 'a\x00b'):
                with self.subTest(field=field, value=bad):
                    with self.assertRaisesRegex(ValueError, field):
                        validate_manifest(dict(self.manifest, runtimeAbi=2, **{field: bad}))

        with self.assertRaisesRegex(ValueError, 'runtimeAbi 2'):
            validate_manifest(dict(self.manifest, defaultLaunch='launch/main.launch.py'))

        validate_manifest(dict(self.manifest, runtimeAbi=2, defaultLaunch='launch/main.launch.py'))

    def test_additive_metadata_is_preserved(self):
        value = dict(self.manifest, futureMetadata={'key': 'value'})
        self.assertEqual(validate_manifest(copy.deepcopy(value)), value)

    def test_cli_inspect_and_failures_do_not_require_sdk_or_phone(self):
        with tempfile.TemporaryDirectory() as folder:
            project = Path(folder)
            path = project / 'robot-project.json'

            def run(command='inspect'):
                return subprocess.run(
                    [
                        sys.executable,
                        str(ROOT / 'tools/ros2android'),
                        '--project',
                        folder,
                        command,
                    ],
                    capture_output=True,
                    text=True,
                )

            result = run()
            self.assertNotEqual(0, result.returncode)
            self.assertIn('workspace command', result.stderr)
            self.assertNotIn('Traceback', result.stderr)
            path.write_text(json.dumps(self.manifest))
            result = run()
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual('cpp', json.loads(result.stdout)['compatibility']['runtime'])
            path.write_text(json.dumps(dict(self.manifest, schemaVersion=99)))

            for command in ('build', 'deploy', 'start', 'status'):
                result = run(command)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('schemaVersion 99', result.stderr)
                self.assertNotIn('Traceback', result.stderr)

            path.write_text('{broken')
            self.assertIn('Invalid JSON', run().stderr)
