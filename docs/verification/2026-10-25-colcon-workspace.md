# Ordinary colcon workspace verification — 2026-10-25

Ubuntu/Jazzy PC and physical Android 12 ARM64 phone, USB IP link, ROS domain 42.
Three packages: ws_interfaces, ws_nodes, ws_bringup. Two ordinary standalone
rclcpp executables: talker and processor, each with a standard main().

## Passed

- Normal host colcon build completed all three packages. Upstream ros2 launch
  ran the installed launch file and native Linux executables.
- Android NDK cross-colcon build completed all three packages from the same
  workspace source tree, using the target ROS SDK underlay.
- Android bundle checked ARM64 executables, Android linker64 interpreter,
  portable library dependency names/collisions and the packaged closure,
  including vendor YAML and shared libc++.
- Workspace runner APK assembly and lint passed; deployment used the project's
  guarded install helper and a generated runner application ID.
- Native executable main() functions ran on the phone as separate Java-owned
  child processes from the APK's native-library directory.
- The identical PC probe passed on Linux and Android: both named nodes present,
  custom Sample inputs, at least three matching squared-value outputs and a
  successful period_ms parameter-service read.
- Linux and phone processes used /android_test/input and /android_test/result
  with the same namespace, types and computation contract. Initial sequence
  numbers differed due to discovery timing; numerical correctness was verified
  for each matched pair, not by assuming identical timing.
- Stop removed the group's node list. A deliberate uncaught processor exception
  exited that child with code 134; the controller survived, detected the exit,
  terminated the remaining group and explicitly restarted both executables.
- Workspace inventory/fingerprint tests and existing launch tests passed together:
  24 tests, in the sourced Jazzy Python environment.

Generated project fingerprint at initial device verification: 2fcb02bfd3f4.
Build/device logs, addresses, identifiers and APKs remain ignored by Git.

## Limits

This verifies the example C++ workspace, not all ROS/Linux workspaces. The new
workspace builder currently accepts ament_cmake/native installed executables.
Separate Python runner feasibility does not yet establish ament_python workspace
support. Launch uses the existing supported static adapter; full LaunchService,
parameter YAML files, mixed workers and arbitrary ROS dependencies remain
pending. Physical PC disconnection, workspace Wi-Fi qualification, long runs,
other phones, grandchild supervision and automatic respawn were not tested here.
