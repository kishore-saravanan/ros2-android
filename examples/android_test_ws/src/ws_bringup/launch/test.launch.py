from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument
from launch.substitutions import LaunchConfiguration
from launch_ros.actions import Node


def generate_launch_description():
    namespace = LaunchConfiguration('namespace')
    return LaunchDescription([
        DeclareLaunchArgument('namespace', default_value='android_test'),
        Node(package='ws_nodes', executable='talker', name='talker', namespace=namespace,
             parameters=[{'period_ms': 200}]),
        Node(package='ws_nodes', executable='processor', name='processor', namespace=namespace),
    ])
