"""Local-world TurtleBot demo; no Fuel downloads or path-as-world-name topics."""
from pathlib import Path
from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import DeclareLaunchArgument, IncludeLaunchDescription, SetEnvironmentVariable
from launch.launch_description_sources import PythonLaunchDescriptionSource
from launch_ros.actions import Node


def generate_launch_description():
    share = lambda package: Path(get_package_share_directory(package))
    world_file = Path.home() / '.cache/ros2-android-demo/dashboard-world.sdf'
    gazebo = share('ros_gz_sim') / 'launch/gz_sim.launch.py'
    spawn = share('turtlebot4_gz_bringup') / 'launch/turtlebot4_spawn.launch.py'
    return LaunchDescription([
        DeclareLaunchArgument('world', default_value='depot'),
        SetEnvironmentVariable('GZ_SIM_RESOURCE_PATH', str(share('turtlebot4_description').parent)),
        SetEnvironmentVariable('GZ_GUI_PLUGIN_PATH', ':'.join(str(share(p) / 'lib') for p in ['turtlebot4_gz_gui_plugins', 'irobot_create_gz_plugins'])),
        IncludeLaunchDescription(PythonLaunchDescriptionSource(str(gazebo)),
            launch_arguments={'gz_args': f'-r -v 3 {world_file} --render-engine ogre'}.items()),
        Node(package='ros_gz_bridge', executable='parameter_bridge', name='clock_bridge',
             arguments=['/clock@rosgraph_msgs/msg/Clock[gz.msgs.Clock']),
        IncludeLaunchDescription(PythonLaunchDescriptionSource(str(spawn)),
            launch_arguments={'namespace': 'tb4_demo', 'world': 'depot', 'model': 'standard', 'rviz': 'false'}.items()),
    ])
