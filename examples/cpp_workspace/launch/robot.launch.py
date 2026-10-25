"""ROS launch description compiled into the Android runner's packaged plan."""
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument, GroupAction, IncludeLaunchDescription
from launch.launch_description_sources import PythonLaunchDescriptionSource
from launch.substitutions import LaunchConfiguration, PathJoinSubstitution, ThisLaunchFileDir
from launch_ros.actions import PushROSNamespace


def generate_launch_description():
    return LaunchDescription([
        DeclareLaunchArgument('robot_namespace', default_value='robot'),
        DeclareLaunchArgument('message', default_value='Computed on Android'),
        DeclareLaunchArgument('enable_processor', default_value='true', choices=['true', 'false']),
        GroupAction([
            PushROSNamespace(LaunchConfiguration('robot_namespace')),
            IncludeLaunchDescription(
                PythonLaunchDescriptionSource(PathJoinSubstitution([ThisLaunchFileDir(), 'nodes.launch.py'])),
                launch_arguments={'message': LaunchConfiguration('message'),
                                  'enable_processor': LaunchConfiguration('enable_processor')}.items()),
        ]),
    ])
