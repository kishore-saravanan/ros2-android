# Run a C++ ROS project on Android

This development milestone runs native C++ `rclcpp` nodes on Android ARM64,
using ROS 2 Jazzy and Cyclone DDS. The PC builds an APK; computation runs on
its phone processor. The sensor app and project runner are separate applications.

## Prepare and build

From this repository, with the Android SDK/NDK and ROS build prerequisites in
[DEVELOPMENT.md](DEVELOPMENT.md):

```bash
./setup/build-native-ros.sh --with-cpp
./tools/ros2android host-sdk
./tools/ros2android host-interfaces
./tools/ros2android build
./tools/ros2android deploy
```

`host-sdk` extracts checksum-pinned Ubuntu 24.04 amd64 Cyclone DDS packages into
this user's cache. It requires no sudo and does not change the system middleware.
`host-interfaces` builds the example's custom messages for PC tools.

Unlock the phone before starting. Find the current PC/phone IPv4 addresses on
Wi-Fi or USB tethering; substitute them below. USB tethering is an IP link, while
ADB handles APK deployment and project control.

```bash
./tools/ros2android start --peer PC_IP --local PHONE_IP
./tools/ros2android status
```

The runner reports its installed source fingerprint, node name, heartbeat,
state and native errors. It keeps the screen awake while its activity is visible
and uses a foreground service while running. Background execution is subject to
Android/device restrictions; there is no automatic reboot restart.

## Inspect and control

Use the project-local Cyclone wrapper for reliable parameter services:

```bash
./tools/ros2android ros --interface PC_IP -- topic list -t
./tools/ros2android ros --interface PC_IP -- topic echo /demo/status
./tools/ros2android ros --interface PC_IP -- topic echo /demo/runtime_status
./tools/ros2android ros --interface PC_IP -- topic pub --once /demo/command std_msgs/msg/String "{data: 'hello'}"
./tools/ros2android ros --interface PC_IP -- param set /demo/cpp_demo message 'Updated from PC'
./tools/ros2android logs
./tools/ros2android stop
```

Stop topic/log viewing with Ctrl+C. This does not stop the phone node.
The example publishes status and custom `demo_interfaces/msg/RuntimeStatus`,
replies on `/demo/echo`, and processes `/phone/imu` into
`/demo/acceleration_magnitude`. Enable IMU publishing in the sensor app to use
that computation. Topic traffic also passed against PC Fast DDS; parameter
service calls required matching Cyclone DDS in the tested setup.

Names, namespaces, remaps and initial parameters are supported:

```bash
./tools/ros2android start --peer PC_IP --local PHONE_IP --namespace /robot -- -r __node:=worker -r status:=worker_status -p 'message:=Hello robot'
```

This creates `/robot/worker` and `/robot/worker_status`. Stop before changing
startup arguments. A stop timeout or cleanup failure returns a nonzero CLI exit
code; inspect status/logs before deciding how to recover. `kill` force-stops only
the selected project runner if a user
callback prevents cooperative shutdown; restart explicitly with `start`.

## Edit your project

The default project is `examples/cpp_talker`:

- `robot-project.json`: project ID, runner application ID and ABI version.
- `src/talker.cpp`: standard ROS C++ publishers, subscriptions and parameters.
- `interfaces/demo_interfaces`: a normal ROSIDL message package.
- `CMakeLists.txt`: builds the `robot_project` static target and links typesupport.

Edit the source, then run `build`, `deploy`, and `start` again. Deployment and
startup reject source fingerprints that differ from the built/installed project.
For another project, copy the example outside it, assign unique IDs, and pass
`--project /absolute/project/path` before each CLI command. Generated application
IDs must start with `dev.ros2android.android.runner.`.

The ABI-1 native factory returns one node to the host executor.
For several compiled nodes, see [workspace launch](WORKSPACE-LAUNCH.md). The SDK includes
pinned ROS libraries and shared libc++; user projects must match that ABI.
Custom interface subdirectories are generated for both Android and the PC;
this is not yet a general cross-colcon workspace builder.

## Current boundaries

This is a debug developer runner. Its debug control receiver requires Android's
privileged DUMP permission and is absent from release builds. APK deployment uses
ADB; paired production control is not implemented. No personal phone storage is
required. Python nodes, arbitrary ROS launch files, independent node worker supervision,
dynamic plugins, embedded micro-ROS Agent and broad package compatibility remain
pending. A force-stop recovery test is not a deliberate native crash test.

## Callback failure and recovery

The runner monitors its executor. If a C++ callback throws an exception caught
by the host, it reports `Failed`, shuts down the ROS context, removes its
foreground service, and releases its wake/multicast locks. Failure diagnostics
remain available in the current application process. Restart is explicit, with
no automatic retry loop. A fatal native signal or process kill still requires
reopening the app; a callback that never returns can require `kill`.

The demo provides an opt-in `fail_after_ticks` parameter (zero disables it).
After building and deploying the current demo, run this physical regression:

```bash
python3 tools/test-runner-recovery.py --peer PC_IP --local PHONE_IP
```

It triggers a callback exception after three ticks, requires failed diagnostics,
a stopped native node and a removed project service, then restarts normally and
checks that the executor heartbeat advances. It leaves the demo running.
