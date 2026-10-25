from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.conditions import IfCondition
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node
from launch_ros.parameter_descriptions import ParameterValue


def generate_launch_description():
    return LaunchDescription([
        DeclareLaunchArgument('message'),
        DeclareLaunchArgument('enable_processor', default_value='true', choices=['true', 'false']),
        Node(package='cpp_workspace', executable='producer', name='producer', output='screen',
             parameters=[{'message': ParameterValue(LaunchConfiguration('message'), value_type=str), 'period_ms': 200}],
             remappings=[('output', 'pipeline/input')]),
        Node(package='cpp_workspace', executable='processor', name='processor', output='screen',
             condition=IfCondition(LaunchConfiguration('enable_processor')),
             parameters=[{'prefix': ParameterValue('Phone processed: ', value_type=str)}],
             remappings=[('input', 'pipeline/input'), ('output', 'pipeline/result')]),
    ])
