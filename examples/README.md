# Examples

These are platform fixtures and optional applications, not the ROS2 Android
product itself. Keep platform changes independent of robot-specific tuning.

| Example | Purpose |
| --- | --- |
| android_test_ws | Ordinary colcon workspace build/deploy/run compatibility |
| cpp_talker / cpp_workspace | Native nodes and composed/launch paths |
| python_talker | Embedded Python runtime demonstration |
| runner_fixture_cpp / runner_fixture_python | Harmless cross-process Dashboard and offline runtime qualification |
| ball_follower | Optional onboard-vision/control proof of concept |

See the [workspace guide](../docs/COLCON-WORKSPACES.md),
[Python guide](../docs/PYTHON-PROJECTS.md) and
[ball example guide](../docs/BALL-FOLLOWER.md) for the supported paths.
Folder names and dependencies are described by each example's manifest/source;
these examples do not establish arbitrary Linux workspace compatibility.

The ball follower and robot-specific firmware are planned for the companion
`ros2-android-examples` repository, consuming versioned platform artifacts.
Until its independent deployment walkthrough is qualified, these application
copies remain available here. Keep small compatibility fixtures in the platform.
See [ADR 0001](../docs/decisions/0001-platform-and-examples.md).
