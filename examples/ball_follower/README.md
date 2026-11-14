# Python ball follower

Two standard rclpy nodes in the `ball_follower` ament_python package:

- `ball_vision`: JPEG camera input → colour mask/circle fit → target + images.
- `ball_follower`: fresh targets + Track ball toggle → Twist commands.

The Android runner embeds both nodes in one Python process. requirements.txt
adds a pinned Android Pillow wheel; NumPy and native rclpy come from the Python
SDK. This example uses the project builder, not the C++-only workspace builder.

Read [the complete guide](../../docs/BALL-FOLLOWER.md) for building, deploying,
Dashboard image/switch setup, tests, parameters and compatibility limits.

Tracking starts OFF. Once switched on, commands go to `/cmd_vel` and drive the
robot; while off, the follower stays quiet. For a dry run, start it with
`-p command_topic:=/ball_demo/cmd_vel`.

## Linux package

With ROS 2 Jazzy, colcon, NumPy and Pillow installed:

```bash
cd examples/ball_follower
colcon build --base-paths src --symlink-install
source install/setup.bash
ros2 launch ball_follower ball.launch.py
```

Both nodes use namespace `/ball`. This receives the phone's camera topic; it does
not open a PC camera. Linux runs the same package for comparison. On Android,
build and start from the repository root using the guide's project commands.

Target JSON includes image-space measurements. Optional monocular range requires
measured ball size and camera calibration and is not used by the controller.
No collision avoidance, calibrated physical speed or hard real-time guarantee
is provided by this demonstration.
