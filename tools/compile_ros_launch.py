#!/usr/bin/env python3
"""Resolve a supported Jazzy .launch.py into an Android component plan on the PC.

Python launch descriptions are executable developer code, just as with ros2 launch.
This adapter never executes Node/ExecuteProcess actions on the PC. It is not a sandbox.
"""

import argparse
from contextlib import contextmanager
import json
from pathlib import Path
import sys

from launch import Action, LaunchContext
from launch.actions import (
    DeclareLaunchArgument,
    GroupAction,
    IncludeLaunchDescription,
    SetLaunchConfiguration,
    PushLaunchConfigurations,
    PopLaunchConfigurations,
    PushEnvironment,
    PopEnvironment,
)
from launch.conditions import IfCondition, UnlessCondition
from launch.launch_description_sources import PythonLaunchDescriptionSource
from launch.substitutions import (
    TextSubstitution,
    LaunchConfiguration,
    PathJoinSubstitution,
    ThisLaunchFileDir,
)
from launch.utilities import normalize_to_list_of_substitutions, perform_substitutions
import launch_ros.actions
import launch_ros.actions.node
from launch_ros.actions import PushROSNamespace
from launch_ros.utilities import (
    normalize_parameters,
    evaluate_parameters,
    make_namespace_absolute,
    prefix_namespace,
)
from robot_launch import validate_launch, IDENTIFIER


class AndroidNode(Action):
    """Build-time implementation of the supported Node constructor fields."""

    def __init__(
        self,
        *,
        package,
        executable,
        name,
        namespace=None,
        parameters=None,
        remappings=None,
        condition=None,
        output=None,
        **unsupported,
    ):
        if unsupported:
            raise ValueError('Unsupported Node fields: ' + ', '.join(sorted(unsupported)))

        if output not in (None, 'screen'):
            raise ValueError('Only output="screen" (aggregated Android logs) is supported')

        super().__init__(condition=condition)
        self.package = package
        self.executable = executable
        self.name = name
        self.namespace = namespace
        self.parameters = parameters or []
        self.remappings = remappings or []


class AndroidContext(LaunchContext):
    def perform_substitution(self, substitution):
        if type(substitution) not in (
            TextSubstitution,
            LaunchConfiguration,
            PathJoinSubstitution,
            ThisLaunchFileDir,
        ):
            raise ValueError('Unsupported launch substitution: ' + type(substitution).__name__)

        return super().perform_substitution(substitution)


@contextmanager
def android_node_adapter():
    # Launch files import the ordinary upstream module paths. Intercept only Node
    # construction, then restore imports even after validation/import failures.
    package_node, module_node = launch_ros.actions.Node, launch_ros.actions.node.Node
    launch_ros.actions.Node = launch_ros.actions.node.Node = AndroidNode
    try:
        yield
    finally:
        launch_ros.actions.Node, launch_ros.actions.node.Node = package_node, module_node


def compile_launch(path, components, arguments=None, namespace='/demo'):
    if not isinstance(components, dict) or not components:
        raise ValueError(
            'ROS launch requires a non-empty launchComponents registry in robot-project.json'
        )

    if any(
        not isinstance(k, str)
        or k.count('/') != 1
        or not isinstance(v, str)
        or not IDENTIFIER.fullmatch(v)
        for k, v in components.items()
    ):
        raise ValueError(
            'launchComponents must map package/executable to compiled component names'
        )

    context = AndroidContext()
    context.launch_configurations.update(arguments or {})
    nodes, declared, stack = [], set(), []
    action_count = 0

    def text(value):
        return perform_substitutions(context, normalize_to_list_of_substitutions(value))

    def load(source):
        if type(source) is not PythonLaunchDescriptionSource:
            raise ValueError('Only PythonLaunchDescriptionSource includes are supported')

        description = source.get_launch_description(context)
        filename = Path(source.location).resolve()
        if filename in stack or len(stack) >= 16:
            raise ValueError('Recursive/deep launch include: ' + str(filename))

        old_locals = dict(context.get_locals_as_dict())
        context.extend_locals(
            {
                'current_launch_file_path': str(filename),
                'current_launch_file_directory': str(filename.parent),
            }
        )
        stack.append(filename)

        try:
            walk(description.entities)
        finally:
            stack.pop()
            context.extend_locals(old_locals)

    def walk(entities):
        nonlocal action_count
        allowed = (
            AndroidNode,
            DeclareLaunchArgument,
            GroupAction,
            IncludeLaunchDescription,
            SetLaunchConfiguration,
            PushROSNamespace,
            PushLaunchConfigurations,
            PopLaunchConfigurations,
            PushEnvironment,
            PopEnvironment,
        )

        for action in entities:
            action_count += 1
            if action_count > 512:
                raise ValueError('Launch exceeds 512 actions')

            if type(action) not in allowed:
                raise ValueError('Unsupported launch action: ' + type(action).__name__)

            condition = action.condition
            if condition is not None:
                if type(condition) not in (IfCondition, UnlessCondition):
                    raise ValueError('Unsupported launch condition: ' + type(condition).__name__)

                if not condition.evaluate(context):
                    continue

            if type(action) is AndroidNode:
                key = text(action.package) + '/' + text(action.executable)
                if key not in components:
                    raise ValueError('Node is not registered for Android: ' + key)

                ns = (
                    make_namespace_absolute(
                        prefix_namespace(
                            context.launch_configurations.get('ros_namespace'),
                            text(action.namespace) if action.namespace is not None else None,
                        )
                    )
                    or namespace
                )

                if not isinstance(action.parameters, (list, tuple)) or any(
                    not isinstance(value, dict) for value in action.parameters
                ):
                    raise ValueError(
                        'Parameter files and Parameter objects are not supported; use '
                        'inline scalar dictionaries'
                    )

                parameters = {}

                for value in evaluate_parameters(context, normalize_parameters(action.parameters)):
                    if not isinstance(value, dict):
                        raise ValueError(
                            'Parameter files and Parameter objects are not supported; use '
                            'inline scalar dictionaries'
                        )

                    parameters.update(value)

                node = {
                    'component': components[key],
                    'name': text(action.name),
                    'namespace': ns,
                    'parameters': parameters,
                    'remappings': {},
                }

                for source, destination in action.remappings:
                    source, destination = text(source), text(destination)
                    if source in node['remappings']:
                        raise ValueError('Duplicate remapping source: ' + source)

                    node['remappings'][source] = destination

                nodes.append(node)

                if len(nodes) > 16:
                    raise ValueError('Launch exceeds 16 nodes')
            elif type(action) is IncludeLaunchDescription:
                values = {text(k): text(v) for k, v in action.launch_arguments}
                context.launch_configurations.update(values)
                load(action.launch_description_source)
            else:
                if type(action) is DeclareLaunchArgument:
                    declared.add(action.name)

                returned = action.execute(context)
                if returned:
                    walk(returned)

    with android_node_adapter():
        load(PythonLaunchDescriptionSource(str(Path(path).resolve())))

    unknown = set(arguments or {}) - declared
    if unknown:
        raise ValueError('Undeclared launch arguments: ' + ', '.join(sorted(unknown)))

    return validate_launch({'schemaVersion': 1, 'nodes': nodes}, namespace)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--launch', type=Path, required=True)
    parser.add_argument('--namespace', default='/demo')
    parser.add_argument('--arguments-json', default='{}')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads((args.project / 'robot-project.json').read_text())
    result = compile_launch(
        args.launch,
        manifest.get('launchComponents'),
        json.loads(args.arguments_json),
        args.namespace,
    )
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('ROS launch adapter: ' + str(error), file=sys.stderr)
        raise SystemExit(1)
