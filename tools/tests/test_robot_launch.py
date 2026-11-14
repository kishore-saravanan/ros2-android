import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from robot_launch import validate_launch


class LaunchValidationTest(unittest.TestCase):
    def setUp(self):
        self.launch = {
            'schemaVersion': 1,
            'nodes': [
                {
                    'component': 'producer',
                    'name': 'source',
                    'namespace': '/robot',
                    'parameters': {'message': 'hello: "robot" ☕', 'rate': 5, 'enabled': True},
                    'remappings': {'output': '/robot/pipeline/input'},
                }
            ],
        }

    def reject(self, value):
        with self.assertRaises(ValueError):
            validate_launch(value)

    def test_scalar_parameters_and_explicit_remaps(self):
        self.assertEqual(validate_launch(self.launch), self.launch)

    def test_reject_unsupported_launch_actions_instead_of_ignoring(self):
        for field in ('include', 'condition', 'timer', 'respawn', 'executable'):
            value = copy.deepcopy(self.launch)
            value['nodes'][0][field] = True
            self.reject(value)

    def test_duplicate_names_resolve_root_namespace(self):
        self.launch['nodes'][0]['namespace'] = '/'
        self.launch['nodes'].append({'component': 'processor', 'name': 'source'})

        with self.assertRaises(ValueError):
            validate_launch(self.launch, namespace='/')

    def test_same_name_in_different_namespaces_is_valid(self):
        self.launch['nodes'].append(
            {'component': 'processor', 'name': 'source', 'namespace': '/other'}
        )
        validate_launch(self.launch)

    def test_reject_non_scalar_and_nonfinite_parameters(self):
        for value in (None, {}, [1], float('nan'), float('inf'), 2**63):
            launch = copy.deepcopy(self.launch)
            launch['nodes'][0]['parameters']['bad'] = value
            self.reject(launch)

    def test_bound_node_count_and_payloads(self):
        for nodes in ([], self.launch['nodes'] * 17):
            self.reject({'schemaVersion': 1, 'nodes': nodes})

        self.launch['nodes'][0]['parameters']['message'] = 'a' * 4096
        self.reject(self.launch)

    def test_reject_reserved_name_remapping_and_wildcards(self):
        for remap in ({'__node': 'other'}, {'input': '**'}, {'input': 4}):
            value = copy.deepcopy(self.launch)
            value['nodes'][0]['remappings'] = remap
            # Reserved ROS names must use the explicit name/namespace fields.
            self.reject(value)

    def test_reject_wrong_schema_and_missing_component(self):
        self.reject({'schemaVersion': True, 'nodes': self.launch['nodes']})
        del self.launch['nodes'][0]['component']
        self.reject(self.launch)


if __name__ == '__main__':
    unittest.main()
