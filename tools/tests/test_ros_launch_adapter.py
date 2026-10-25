"""Run with the Jazzy Python environment, e.g. tools/pc-ros.sh -- /usr/bin/python3 -m unittest discover -s tools/tests -v."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
try:
    import launch_ros.actions
    from compile_ros_launch import compile_launch
except ImportError:
    compile_launch = None


@unittest.skipIf(compile_launch is None, 'Requires sourced ROS 2 Jazzy Python environment')
class RosLaunchAdapterTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / 'test.launch.py'
        self.registry = {'test_pkg/producer': 'producer', 'test_pkg/processor': 'processor'}

    def compile(self, body, arguments=None):
        self.path.write_text('from launch import LaunchDescription\n'
                             'from launch_ros.actions import Node\n' + body)
        return compile_launch(self.path, self.registry, arguments)

    def test_registered_nodes_without_pc_executable_lookup(self):
        result = self.compile("def generate_launch_description():\n return LaunchDescription([Node(package='test_pkg', executable='producer', name='p')])\n")
        self.assertEqual(result['nodes'][0]['component'], 'producer')
        self.assertEqual(result['nodes'][0]['namespace'], '/demo')

    def test_real_example_matches_original_json_pipeline(self):
        project = Path(__file__).resolve().parents[2] / 'examples/cpp_workspace'
        manifest = json.loads((project / 'robot-project.json').read_text())
        result = compile_launch(project / manifest['defaultLaunch'], manifest['launchComponents'])
        expected = json.loads((project / 'launch/robot.launch.json').read_text())
        self.assertEqual(result, expected)

    def test_arguments_condition_include_and_namespace(self):
        project = Path(__file__).resolve().parents[2] / 'examples/cpp_workspace'
        manifest = json.loads((project / 'robot-project.json').read_text())
        result = compile_launch(project / manifest['defaultLaunch'], manifest['launchComponents'],
                                {'robot_namespace': 'testbot', 'message': 'hello: "robot" ☕', 'enable_processor': 'false'})
        self.assertEqual(len(result['nodes']), 1)
        self.assertEqual(result['nodes'][0]['namespace'], '/testbot')
        self.assertEqual(result['nodes'][0]['parameters']['message'], 'hello: "robot" ☕')

    def test_group_namespace_does_not_leak(self):
        result = self.compile("""from launch.actions import GroupAction
from launch_ros.actions import PushROSNamespace
def generate_launch_description():
 return LaunchDescription([
  GroupAction([PushROSNamespace('inside'), Node(package='test_pkg', executable='producer', name='a')]),
  Node(package='test_pkg', executable='processor', name='b')])
""")
        self.assertEqual([n['namespace'] for n in result['nodes']], ['/inside', '/demo'])

    def test_unregistered_node_and_unsupported_fields_are_errors(self):
        for statement, message in [
            ("Node(package='unknown', executable='producer', name='p')", 'not registered'),
            ("Node(package='test_pkg', executable='producer', name='p', respawn=True)", 'Unsupported Node fields'),
        ]:
            with self.assertRaisesRegex(ValueError, message):
                self.compile('def generate_launch_description():\n return LaunchDescription([' + statement + '])\n')

    def test_execute_process_is_rejected_and_never_executed(self):
        marker = Path(self.temporary.name) / 'must-not-exist'
        with self.assertRaisesRegex(ValueError, 'Unsupported launch action: ExecuteProcess'):
            self.compile("from launch.actions import ExecuteProcess\ndef generate_launch_description():\n return LaunchDescription([ExecuteProcess(cmd=['touch', " + repr(str(marker)) + "])])\n")
        self.assertFalse(marker.exists())

    def test_timers_and_shell_substitutions_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unsupported launch action: TimerAction'):
            self.compile("from launch.actions import TimerAction\ndef generate_launch_description():\n return LaunchDescription([TimerAction(period=1.0, actions=[])])\n")
        with self.assertRaisesRegex(ValueError, 'Unsupported launch substitution: Command'):
            self.compile("from launch.substitutions import Command\ndef generate_launch_description():\n return LaunchDescription([Node(package='test_pkg', executable='producer', name=Command(['echo p']))])\n")

    def test_parameter_files_and_arrays_are_rejected(self):
        parameter_file = Path(self.temporary.name) / 'params.yaml'
        parameter_file.write_text('/**:\n  ros__parameters:\n    message: test\n')
        with self.assertRaisesRegex(ValueError, 'Parameter files'):
            self.compile('def generate_launch_description():\n return LaunchDescription([Node(package="test_pkg", executable="producer", name="p", parameters=[' + repr(str(parameter_file)) + '])])\n')
        with self.assertRaisesRegex(ValueError, 'Only finite scalar'):
            self.compile('def generate_launch_description():\n return LaunchDescription([Node(package="test_pkg", executable="producer", name="p", parameters=[{"array": [1,2]}])])\n')

    def test_unknown_argument_missing_argument_and_choice(self):
        body = """from launch.actions import DeclareLaunchArgument
def generate_launch_description():
 return LaunchDescription([DeclareLaunchArgument('mode', choices=['yes','no']),
  Node(package='test_pkg', executable='producer', name='p')])
"""
        for arguments in ({}, {'mode': 'maybe'}, {'mode': 'yes', 'typo': 'x'}):
            with self.assertRaises((ValueError, RuntimeError)):
                self.compile(body, arguments)

    def test_node_import_restored_after_exception(self):
        original = launch_ros.actions.Node
        with self.assertRaises(ValueError):
            self.compile('def generate_launch_description():\n return LaunchDescription([Node(package="missing", executable="x", name="n")])\n')
        self.assertIs(launch_ros.actions.Node, original)

    def test_recursive_include_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Recursive/deep launch include'):
            self.compile("""from launch.actions import IncludeLaunchDescription
from launch.launch_description_sources import PythonLaunchDescriptionSource
def generate_launch_description():
 return LaunchDescription([IncludeLaunchDescription(PythonLaunchDescriptionSource(__file__))])
""")
