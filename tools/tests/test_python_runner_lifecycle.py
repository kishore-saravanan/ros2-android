"""Exercise the actual embedded runner lifecycle with host rclpy and tiny user nodes."""

import importlib.util
import json
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch

try:
    from rclpy.node import Node
except ImportError:
    Node = None

ROOT = Path(__file__).resolve().parents[2]


@unittest.skipUnless(Node, 'Source ROS before testing the Python lifecycle')
class PythonRunnerLifecycleTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location(
            'embedded_runner_test', ROOT / 'android/pythonRunner/src/main/python/phone_runtime.py'
        )
        self.runtime = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.runtime)

    def tearDown(self):
        self.runtime.stop()

    def test_existing_single_node_entrypoint(self):
        module = types.ModuleType('phone_node')
        module.PhoneNode = lambda **kw: Node('legacy_python_node', **kw)
        with patch.dict(sys.modules, phone_node=module):
            self.runtime.start(91, '/runner_test', [])

        state = json.loads(self.runtime.status())
        self.assertTrue(state['running'])
        self.assertEqual(state['nodes'], ['/runner_test/legacy_python_node'])
        self.runtime.stop()
        self.assertEqual(json.loads(self.runtime.status())['nodes'], [])

    def test_multiple_nodes_stop_and_restart(self):
        module = types.ModuleType('phone_node')
        module.create_nodes = lambda **kw: [Node('vision', **kw), Node('follower', **kw)]
        with patch.dict(sys.modules, phone_node=module):
            for _ in range(2):
                self.runtime.start(91, '/runner_test', [])
                self.assertEqual(
                    json.loads(self.runtime.status())['nodes'],
                    ['/runner_test/vision', '/runner_test/follower'],
                )
                self.runtime.stop()
                self.assertFalse(json.loads(self.runtime.status())['running'])

    def test_empty_factory_fails_and_cleans_context(self):
        module = types.ModuleType('phone_node')
        module.create_nodes = lambda **kw: []
        with patch.dict(sys.modules, phone_node=module):
            with self.assertRaisesRegex(ValueError, '1..16 ROS nodes'):
                self.runtime.start(91, '/runner_test', [])

        self.assertIsNone(self.runtime._context)
        self.assertFalse(json.loads(self.runtime.status())['running'])

    def test_invalid_factory_preserves_validation_error_and_cleans_valid_nodes(self):
        module = types.ModuleType('phone_node')
        module.create_nodes = lambda **kw: [Node('valid_before_failure', **kw), object()]
        with patch.dict(sys.modules, phone_node=module):
            with self.assertRaisesRegex(ValueError, '1..16 ROS nodes'):
                self.runtime.start(91, '/runner_test', [])

        self.assertIsNone(self.runtime._context)
        self.assertEqual(json.loads(self.runtime.status())['nodes'], [])
