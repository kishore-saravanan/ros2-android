# Build an ordinary ROS workspace for Android

The first complete workspace workflow is now verified for the repository's
`examples/android_test_ws`. Its three normal ROS packages and two standard C++
executables build on Linux and cross-build for Android without changing their
sources or replacing their main() functions with project factories.

This is the beginning of workspace portability, not universal Linux ROS support.
The current workspace tool accepts ament_cmake packages with native executables.
Mixed ament_python workspaces, arbitrary dependencies and full ROS launch
semantics still require integration. Standalone Python rclpy feasibility is
already verified separately in [PYTHON-PROJECTS.md](PYTHON-PROJECTS.md).

## Example contents

```text
android_test_ws/
  src/
    ws_interfaces/    Sample.msg: sequence and value
    ws_nodes/         talker and processor, each with ordinary main()
    ws_bringup/       test.launch.py, used on both Linux and Android
```

The talker publishes value = sequence × 0.25. The processor publishes the square
and sequence as a JSON string. Both platforms expose:

- `/android_test/talker` and `/android_test/processor`.
- `/android_test/input` (`ws_interfaces/msg/Sample`).
- `/android_test/result` (`std_msgs/msg/String`).
- Standard node parameter services, including talker's period_ms=200.

## PC build and baseline

Use the development prerequisites and the existing Android C++ SDK described
in [DEVELOPMENT.md](DEVELOPMENT.md) and [CPP-PROJECTS.md](CPP-PROJECTS.md).

```bash
./tools/ros2android-workspace --workspace examples/android_test_ws inspect
./tools/ros2android-workspace --workspace examples/android_test_ws host-build
source "$HOME/.cache/ros2-android-workspaces/android_test_ws/host/install/setup.bash"
ros2 launch ws_bringup test.launch.py
```

Stop with Ctrl+C. This is a normal colcon build and upstream ROS launch on Linux.
The build output is in the user's cache to preserve the source tree. You may also
run ordinary `colcon build` from the example workspace with Jazzy sourced.

## Cross-build, package and deploy

```bash
./tools/ros2android-workspace --workspace examples/android_test_ws build
./tools/ros2android-workspace --workspace examples/android_test_ws deploy
./tools/ros2android-workspace --workspace examples/android_test_ws start \
  --domain 42 --peer PC_IP --local PHONE_IP
./tools/ros2android-workspace --workspace examples/android_test_ws status
```

Use current USB-tethering/Wi-Fi IPv4 addresses. Unlock the phone before startup.
The verified physical path is USB tethering; Wi-Fi qualification of this workspace
backend remains pending. Domain 42 keeps this example separate from domain-0 demos.
Use the same domain in the PC monitoring shell.

The build performs these steps:

1. Discover/validate workspace packages and invoke colcon for Android ARM64 using
   the NDK and target ROS SDK underlay.
2. Collect installed resources, shared libraries and ordinary package executables
   from the target install prefix. Check ARM64 binaries and dependency closure.
3. Automatically map installed package/executable names into the supported ROS
   launch adapter. No hand-written Android factory/registry is required.
4. Package the executable PIE binaries and libraries in the APK's read-only
   native-library area and package ament/package resources as assets.
5. Build the Android workspace runner through tools/build.sh, then install through
   tools/run-device.sh. Source fingerprints reject stale deployment/start inputs.

Android's foreground service starts each selected native executable with
ProcessBuilder. The child receives ROS arguments, domain, Cyclone configuration,
library paths and its private installed package resource prefix. Node main()
functions call normal rclcpp::init/spin/shutdown. Child output is streamed into
this runner's Android log with component labels. Code is executed from packaged
APK native libraries, rather than a writable data-directory binary.

```bash
./tools/ros2android-workspace --workspace examples/android_test_ws logs
./tools/ros2android-workspace --workspace examples/android_test_ws stop
```

Stop terminates this workspace's launched child processes. If a child exits, the
controller reports the component/exit code, stops the remaining group and permits
an explicit restart. No automatic respawn policy is enabled. The main sensor app
and other project runner apps are separate.

## Verify the same behavior

After a host build, source the custom-message overlay for PC probes:

```bash
ROS_DOMAIN_ID=42 \
ROS2_ANDROID_PROJECT_OVERLAY="$HOME/.cache/ros2-android-workspaces/android_test_ws/host/install/local_setup.bash" \
  ./tools/pc-ros.sh --interface PC_IP -- /usr/bin/python3 tools/pc-colcon-probe.py
```

The probe requires both nodes, three matching custom-message/processed-output
pairs and the parameter service. It was run against Linux, then against Android.
For direct topic monitoring:

```bash
export ROS_DOMAIN_ID=42
source "$HOME/.cache/ros2-android-workspaces/android_test_ws/host/install/local_setup.bash"
ros2 topic echo /android_test/input
ros2 topic echo /android_test/result
```

Use the project-local Cyclone wrapper for parameter-service interoperability as
explained in the earlier guides. Ctrl+C only stops topic viewing.

The physical child-process failure regression is:

```bash
python3 tools/test-workspace-processes.py --workspace examples/android_test_ws \
  --peer PC_IP --local PHONE_IP --domain 42
```

It deliberately causes the processor's uncaught C++ exception to abort that
child. The controller must remain alive, detect the nonzero exit, stop the group,
and restart both executables. It leaves the normal example running.

## Another workspace

Pass its absolute root as `--workspace`. No robot-project.json is needed inside
the user's workspace. The wrapper generates that metadata in its own cache.
Use `--id unique_name` when two workspaces have the same directory name.
Multiple installed launch files require an explicit `build --launch PATH`.
Build supports `--launch-arg NAME:=VALUE`; runtime start supports the same launch
overrides and connection controls as tools/ros2android.

Outputs live in ~/.cache/ros2-android-workspaces/<id>/{host,android,bundle,runner-project}.
Source edits require rebuild/deploy before another start. SDK/app-runtime changes
also require rebuilding the APK, even when workspace sources are unchanged.

## Compatibility boundaries

This first end-to-end example proves the source → colcon → APK → phone-executable
workflow and the specified message/computation/parameter behavior. It does not
prove identical scheduling, latency, every ROS API or every package.

Current limits include ament_cmake/native executables, supported static launch
features, the existing Android SDK's dependency closure, ARM64/API 26+ with only
one physical Android 12 device verified. Non-native installed scripts and
unsupported package types fail explicitly. rosdep/apt dependencies cannot simply
be installed into Android; they need Android builds or replacements. Parameter
files, dynamic launch events, arbitrary plugins, package-dependent data-path
assumptions and Linux-only drivers/services need package-specific qualification.

Python workspace entry points, mixed-language launch, full on-phone LaunchService,
additional ROS packages, grandchild-process supervision, automatic restart,
physical PC removal and long-duration/device qualification remain pending.
The workspace supervisor heartbeat is its own elapsed-time indicator, not proof
that user callbacks are making progress; use actual ROS traffic probes.

[Physical verification](verification/2026-10-25-colcon-workspace.md) ·
[Project knowledge base](PROJECT-KNOWLEDGE.md) ·
[Android executable-code restrictions](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)
