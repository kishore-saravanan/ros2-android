from launch import LaunchDescription
from launch_ros.actions import Node


def generate_launch_description():
    return LaunchDescription([
        Node(package='ball_follower', executable='vision', namespace='ball', output='screen'),
        Node(package='ball_follower', executable='follower', namespace='ball', output='screen'),
    ])
