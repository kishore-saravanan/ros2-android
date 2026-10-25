# ROS2 Android development knowledge base

Last updated: 2026-10-25. Read this before resuming development, then read
[AGENTS.md](../AGENTS.md) and check the actual Git/device state. This file is the
resume index; detailed architecture, usage and measurements remain in linked
files. Update it whenever a milestone, finding or verification boundary changes.

## Product and decisions

Replace a Raspberry Pi-class robot computer with a smartphone's processor and
sensors. Keep native Android; no Ubuntu installation, root or required Termux.
Edit/build/deploy from the PC over USB debugging or Wi-Fi, then execute ROS nodes
on the phone. A development PC must eventually be removable from the robot.

- App name: ROS2 Android. Credit: Built by Kishore Saravanan. About links to
  https://github.com/sponsors/kishore-saravanan with "Support development".
- Kotlin provides the Android UI, permissions and foreground-service lifecycle.
- Android ARM64 native ROS 2 Jazzy uses Cyclone DDS. Native rclcpp and embedded
  CPython/rclpy are real phone runtimes, not a PC ROS bridge.
- v0.1 means PC ↔ phone; v0.2 means phone ↔ MCU. Embedded micro-ROS Agent is
  still pending. Do not label v0.2 complete from phone/PC tests.
- Deploy project APKs before attempting dynamic binary loading. Project runners
  are separate app processes from the main hardware/sensor app.
- General Raspberry Pi binaries, arbitrary Linux packages/drivers and arbitrary
  pip native extensions do not automatically run on Android.
- Work on `develop`. Git identity is configured; preserve the existing identity.
  Do not push unless requested. Verify status before committing unrelated work.
- Never inspect personal phone apps/data, shared storage, notifications, photos,
  contacts or accounts. Only the main app, generated runners, own artifacts,
  hardware diagnostics and required development settings are authorized.
- Before UI input/capture, verify an allowed project app is foreground and the
  phone is unlocked. Use Activity FLAG_KEEP_SCREEN_ON; do not bypass Realme's
  restriction on the global stay-awake setting or change security settings.

## Implemented and physically verified

| Area | Evidence and scope |
| --- | --- |
| Phone/PC ROS topics | Native Jazzy/Cyclone DDS, real IMU and PC-to-phone text over Wi-Fi and USB tethering IP; [v0.1 guide](V0.1.md) |
| Sensor UI | Capability detection; front/back camera, motion and other available sensors; editable topics/rates, publisher selection, debug-text subscriber and type-matched discovery |
| Camera pipeline | Continuous Camera2 YUV capture, bounded JPEG publishing, timestamps/metrics; 29.677 received FPS in a 10-minute USB background run and 29.733 FPS in a 30-second Wi-Fi run; [evidence](verification/2026-10-25-camera-service.md) |
| Runtime lifecycle | Main app service owns hardware/runtime across Activity navigation; notification Stop and recreation verified; 1-hour/second-device qualification pending |
| About | Author credit and GitHub Sponsors button installed and visually checked |
| C++ ABI 1 | One PC-built rclcpp node executes on Android; status, exact command reply, IMU computation, custom messages, parameters and remaps; [guide](CPP-PROJECTS.md), [evidence](verification/2026-10-25-cpp-runner.md) |
| C++ ABI 2 | 1–16 compiled components share an executor/process; per-node names/namespaces/scalar params/remaps, partial-start rollback, group failure and explicit restart; [guide](WORKSPACE-LAUNCH.md), [evidence](verification/2026-10-25-workspace-launch.md) |
| Caught C++ exceptions | Failure monitoring, teardown, foreground-service removal, explicit recovery; [evidence](verification/2026-10-25-runner-supervision.md) |
| Python feasibility | Embedded CPython 3.12.15, rclpy 7.1.12; typed topics, real IMU norm calculation, custom messages, parameter changes, Python→phone C++→Python round trip, caught exception cleanup/restart; [guide](PYTHON-PROJECTS.md), [evidence](verification/2026-10-25-python-runner.md) |
| Ordinary colcon C++ workspace | Three standard packages and two standalone main() executables built on Linux/Android; custom-message/computation/parameter parity and child-exit recovery passed; [guide](COLCON-WORKSPACES.md), [evidence](verification/2026-10-25-colcon-workspace.md) |

## Completed milestone: ROS launch-file adapter

The initial adapter passed 19 tests and physical two-node/conditional-start
checks on the phone. Standard supported `.launch.py` descriptions
are evaluated on the PC and compiled into the existing Android component plan.
The resolved default is packaged in the APK; the phone does not need the PC to
re-evaluate it when Start is tapped. This is a build-time adapter, not a complete
on-phone ROS LaunchService. Registered C++ components are the first target.
Supported: registered C++ Node actions, launch arguments, basic substitutions,
conditions, Python includes and scoped namespaces. Build-time argument defaults
were verified from the packaged APK; a runtime condition launched only the
producer. Unsupported process actions, timers, parameter files and unregistered
nodes fail explicitly. See [ROS launch guide](ROS-LAUNCH.md) and
[physical evidence](verification/2026-10-25-ros-launch.md).

## Current milestone: ordinary colcon workspaces

The user clarified the intended workflow: take a normal Linux workspace, build
all its compatible packages for Android, deploy it and preserve its ROS behavior.
Earlier component factories/Python entry-module proofs did not establish this.

The first end-to-end example now passes on Linux and Android:
`examples/android_test_ws` contains ws_interfaces, ws_nodes and ws_bringup. All
three ament_cmake packages cross-build with colcon. The same C++ main() functions
run on the phone as separate native processes. The same probe verified custom
messages, numerical results, graph names and parameter services on both targets.
Child abort detection, controller survival, group cleanup and restart passed.
See [workspace guide](COLCON-WORKSPACES.md) and
[evidence](verification/2026-10-25-colcon-workspace.md).

This first builder supports native ament_cmake workspaces, not arbitrary Linux
or mixed Python workspaces. It automatically discovers installed executables and
generates the Android registry; user packages need no Android factory/manifest.
The new workspace supervisor heartbeat is elapsed supervisor time, not a user
callback progress measure. Its child stdout/stderr is pumped into runner logs.

Current next action: add ament_python workspace entry points/mixed launch and
broader dependency/resource compatibility; then full launch runtime semantics,
independent restart policies and physical PC removal. General Linux equivalence
remains a package/API qualification goal, not an implemented blanket guarantee.
ROS launch/knowledge-base checkpoint is bf4bc86. The ordinary-workspace milestone
and community handoff are checkpointed in the following commit; use `git log`
and `git status` to identify the current revision. See [handoff](HANDOFF.md).

Community direction: make the project approachable for robotics contributors,
with guided setup, a clean operator UI, reproducible examples and an explicit
compatibility matrix. Prioritize a documented community alpha; assess a separate
Play-distributed sensor/operator app without promising store approval for arbitrary
code deployment. License selection and public repository/release publication
remain owner decisions; neither has been performed by this checkpoint.

## Roadmap and remaining gates

[PLATFORM-PLAN.md](PLATFORM-PLAN.md) remains the product roadmap. Completion of a
feasibility spike does not complete every acceptance criterion in that milestone.

1. **Launch:** basic C++ ROS description adapter is verified; add worker
   mapping for Python/mixed projects, parameter files, timers/events and other
   launch features only with explicit semantics/tests.
2. **Workers/supervision:** independent node processes, observable exits and
   bounded restart policy. Current composed C++ nodes share one thread/process;
   one blocking callback stalls that group. Fatal native crashes are not covered
   by caught-exception tests. Shell `kill` force-stops only the selected runner.
3. **Autonomy qualification:** physically remove the PC link, then reconnect and
   inspect actual continued computation. A heartbeat while a PC is connected is
   not this test. Also qualify one-hour background operation and another phone.
4. **MCU v0.2:** phone-hosted micro-ROS Agent, UDP then USB/OTG, one reference MCU,
   watchdog/command expiry and unplug/reconnect tests without a PC Agent.
5. **Workspaces:** ordinary C++ colcon example is verified. Add Python/mixed
   packages, dependency ports, resource/ABI qualification and general workspace
   compatibility. Component factories remain an alternative, not a requirement
   for the new standalone C++ executable backend.
6. **Product UI:** Compose operator screens, camera preview/configuration,
   projects/launch selection, parameters/logs/recovery, calibration, accessibility
   and a second-device support matrix.

## Architecture and source map

| Path | Role |
| --- | --- |
| `android/app` | Main Kotlin sensor/operator app, package dev.robotbrain.android |
| `android/runner/src/main` | Shared runner Activity, foreground service, config/launch schema |
| `android/runner/src/cpp` | C++ runner backend, JNI API wrapper |
| `native/runner` | rclcpp host, ABI-1 factory / ABI-2 component registry contract |
| `android/workspaceRunner` | Foreground controller for ordinary packaged native executables; process exit/stop supervision |
| `tools/ros2android-workspace` | Workspace inspect, host/cross-colcon build, staging and delegated deployment/control |
| `examples/android_test_ws` | Standard three-package Linux/Android compatibility target |
| `android/pythonRunner` | Embedded CPython backend using shared runner UI/service |
| `android/pythonRunner/src/main/python/phone_runtime.py` | rclpy context/executor lifecycle and exception diagnostics |
| `native/python` | JNI environment setup for the Python ROS process |
| `examples/cpp_talker` | Single-node demo, custom ROSIDL message, optional callback fault |
| `examples/cpp_workspace` | Two-node producer/processor pipeline and launch examples |
| `examples/python_talker` | Editable phone_node.py, custom message and optional C++ bridge |
| `tools/ros2android` | PC CLI: SDK, build, deploy, launch-plan, start/status/stop/kill/logs/ROS tools |
| `tools/compile_ros_launch.py` | Supported ROS launch-description → Android plan adapter |
| `tools/robot_launch.py` | Strict bounded JSON launch schema validator |
| `native/*lock.json`, `native/patches` | Pinned sources/runtime artifacts and reviewed Android patches |
| `docs/verification` | Evidence, measured results and explicit untested boundaries |

`native-deps` is an ignored symlink to the native SDK cache outside the repository
(default ~/.cache/ros2-android-native). Upstream generators need a path without
spaces, even though the project folder has spaces. C++ outputs use install/ and
apk-libs/; Python uses python-install/, python-packages/, python-apk-libs/ and
python-assets/. Do not replace the C++ SDK with the Python SDK.

The pinned PC Cyclone middleware lives in ~/.cache/ros2-android-host-runtime.
Generated PC message overlays are in ~/.cache/ros2-android-projects/<id>/host.
Device identifiers/logs/screenshots and generated outputs belong in ignored
`device-logs/` or build/cache directories, never in Git.

## Quick resume and verification

From the repository root:

```bash
git status --short
git branch --show-current
./tools/build.sh
python3 -m unittest discover -s tools/tests -v
```

Use tools/build.sh for Gradle assembly/unit/lint. Install with tools/run-device.sh
or the CLI deploy command which calls it. Instrumented main-app tests use
`:app:connectedDebugAndroidTest`; separate ROS probes verify actual traffic.

Build prerequisites: Ubuntu 24.04 PC with Jazzy, Android SDK 35, NDK
27.2.12479018, CMake 3.22.1, JDK 17, Python 3.12; Python SDK builds also need
NumPy and pybind11 headers. See [DEVELOPMENT.md](DEVELOPMENT.md).

```bash
python3 tools/fetch-native-ros.py
./tools/ros2android --project examples/cpp_workspace build
./tools/ros2android --project examples/cpp_workspace deploy
./tools/ros2android --project examples/cpp_workspace start --peer PC_IP --local PHONE_IP
./tools/ros2android --project examples/cpp_workspace status
./tools/pc-ros.sh --interface PC_IP -- python3 tools/pc-workspace-probe.py
```

Read current USB/Wi-Fi addresses from the network interface diagnostics; DHCP
addresses and interface names change after reconnect. Never reuse an old address
from a transcript as authoritative. ADB availability, lock state, installed
builds and running services must be checked afresh.

Python setup, bridge and six-criterion probe commands are in
[PYTHON-PROJECTS.md](PYTHON-PROJECTS.md). Enable `/phone/imu` in the main app for
IMU-dependent probes. A missing IMU publisher is not a Python calculation failure.
No new runtime-wide soak or Wi-Fi Python result is implied by USB checks.

## Findings that save debugging time

- USB tethering is an IP network; ADB deploy/control is a separate channel. Raw
  MCU USB serial needs an Agent/protocol and is not ordinary DDS over a cable.
- PC Fast DDS topics interoperated with phone Cyclone. Parameter calls across
  that tested combination stalled; project-local PC Cyclone resolved them.
  Use the scoped tools/pc-ros.sh wrapper instead of changing system defaults.
- rcl uses the first matching remap rule. Per-node launch identity/remaps must
  precede generic CLI namespace rules. Keep this regression covered.
- Realme can discard the first Activity launch just after APK installation.
  The CLI waits for its runner to resume and retries that own Activity once.
- Stopped apps require explicit include-stopped-packages for debug broadcasts.
  The receiver requires privileged DUMP permission and is absent from release.
- Retained previous errors can appear during Starting. Use lifecycle failure
  states for startup errors, not any nonempty old error field.
- Android Python extensions must link explicitly to libpython. The CPython target
  library has no SONAME; use portable link names, not absolute PC library paths.
- Include native vendor libraries outside SDK lib/, notably libyaml. Staging
  checks ARM64, portable DT_NEEDED names, collisions and the packaged closure.
- NumPy/pybind11 build headers must match the supported Python/Android ABI; do
  not add the entire host /usr/include directory to Android compilation.
- Workspace native executables run from the APK native-library directory, not
  writable app data. Package the NDK shared libc++ explicitly when the APK has
  no externalNativeBuild task. Java owns only its launched Process objects and
  reports child exits; source fingerprints guard deploy/start after workspace edits.
- Project source fingerprints exclude build/.git/.cxx/.gradle/__pycache__. They
  identify project inputs, not the entire SDK; APK SHA-256 records the full build.
- Do not claim ROS interoperability from JNI/sensor diagnostics, or fatal crash
  handling from a caught exception/force-stop test. Keep pending items explicit.

## Recorded checkpoints

- 1d80a96: continuous cameras and foreground session ownership.
- 71b67d1: isolated C++ project runner, custom messages and PC tooling.
- e093edc: C++ callback failure monitoring and recovery.
- 76e72ab: About/author/Sponsors UI.
- d972184: composed C++ launch groups.
- 7e46364: embedded rclpy, Python/C++ interoperability and recovery.
- bf4bc86: supported ROS launch adapter and this development knowledge base.

Before ending a milestone, update this file and its evidence links with what
changed, checks actually run, limitations, current next action and commit state.
Do not store transient phone identifiers or mark untested roadmap items complete.
