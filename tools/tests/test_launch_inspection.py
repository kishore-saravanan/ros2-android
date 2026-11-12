"""Inspect launch hazards without importing or running untrusted workspace code."""
import ast
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'python'))
from ros2android.launch_inspection import NODE_FIELDS, inspect_launches


class LaunchInspectionTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)

    def inspect(self, source):
        (self.root / 'demo.launch.py').write_text(source)
        return inspect_launches([{'path': str(self.root)}])['files'][0]

    def test_aliases_and_module_qualified_unsupported_actions_are_reported(self):
        row = self.inspect('from launch.actions import TimerAction as Later\n'
                           'import launch.actions as actions\n'
                           'Later(period=1); actions.ExecuteProcess(cmd=["false"])\n')
        self.assertEqual(2, len(row['issues']))
        self.assertTrue(all(issue['kind'] == 'unsupported' for issue in row['issues']))

    def test_node_unknown_and_dynamic_fields_are_distinguished(self):
        row = self.inspect('from launch_ros.actions import Node\n'
                           'Node(package="p", executable="e", name="n", prefix="gdb", **options)\n')
        self.assertEqual({'unsupported', 'unresolved'}, {i['kind'] for i in row['issues']})
        self.assertTrue(any('prefix' in i['message'] for i in row['issues']))

    def test_no_code_runs_and_supported_syntax_is_not_declared_runtime_compatible(self):
        flag = self.root / 'must-not-exist'
        row = self.inspect(f'from pathlib import Path\nPath({str(flag)!r}).touch()\n'
                           'from launch_ros.actions import Node\n'
                           'Node(package="p", executable="e", name="n")\n')
        self.assertFalse(flag.exists())
        self.assertEqual([], row['issues'])
        self.assertEqual('requires-build-time-resolution', row['state'])

    def test_syntax_error_and_size_limit_are_diagnostics(self):
        self.assertEqual('unresolved', self.inspect('def bad(')['issues'][0]['kind'])
        self.assertIn('1 MiB', self.inspect('#' * (1024 * 1024 + 1))['issues'][0]['message'])

    def test_generated_launch_files_are_excluded(self):
        path = self.root / 'build'; path.mkdir()
        (path / 'ignored.launch.py').write_text('invalid syntax')
        self.assertEqual([], inspect_launches([{'path': str(self.root)}])['files'])

    def test_node_fields_match_the_actual_build_adapter_constructor(self):
        source = Path(__file__).resolve().parents[1] / 'compile_ros_launch.py'
        tree = ast.parse(source.read_text())
        node = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == 'AndroidNode')
        constructor = next(n for n in node.body if isinstance(n, ast.FunctionDef) and n.name == '__init__')
        self.assertEqual(NODE_FIELDS, {a.arg for a in constructor.args.kwonlyargs})
