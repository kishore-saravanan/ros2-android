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

## Your own workspace and controller APK

The installed client accepts an absolute external workspace path; source does
not need to live in this repository. With the supported SDK prepared:

```bash
ros2-android workspace --workspace /absolute/path/to/my_ws inspect
ros2-android workspace --workspace /absolute/path/to/my_ws build \
  --controller-apk /absolute/path/to/ros2-android.apk
ros2-android workspace --workspace /absolute/path/to/my_ws deploy
```

The APK is the main app installed on the phone; only its public certificate is
used. In Projects → Details, authorize the runner before starting it. See
[runner authorization](RUNNER-AUTHORIZATION.md) for the trust boundary and
remaining preview gates. Omit the flag for the existing same-signature workflow.
The external smoke test used `build --without-shell`; it reuses prepared SDK
artifacts and is not fresh-machine qualification. Shell-enabled builds retain
their additional Python SDK prerequisites.

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

**Replace `PC_IP` and `PHONE_IP` before running the commands.** They are
placeholders, not literal arguments. PC_IP is the PC's IPv4 from
`ip -4 -brief address`; PHONE_IP is the phone IPv4 shown in ROS2 Android →
Settings → Network addresses. Choose both from the same Wi-Fi or USB-tethering
link. Unlock the phone first. Run each command separately and stop on errors.


```bash
./tools/ros2android-workspace --workspace examples/android_test_ws build
./tools/ros2android-workspace --workspace examples/android_test_ws deploy
./tools/ros2android-workspace --workspace examples/android_test_ws start \
  --domain 42 --peer PC_IP --local PHONE_IP
./tools/ros2android-workspace --workspace examples/android_test_ws status
```

Successful `start` prints `state: Running`, `native.running: true`, and the
talker/processor node names. `Stopped` with empty nodes only confirms that no
workspace processes are active; it does not prove start succeeded. Check the
preceding start command's output before proceeding to status or topic monitoring.
Use current USB-tethering/Wi-Fi IPv4 addresses. Unlock the phone before startup.
The initial verified physical path is USB tethering; a later functional Wi-Fi
probe is recorded below. Broader transport qualification remains pending. Domain 42 keeps this example separate from domain-0 demos.
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
5. Build the Android workspace runner through setup/build.sh, then install through
   setup/run-device.sh. Source fingerprints reject stale deployment/start inputs.

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

## Native ros2 run in a connected phone shell

Build (includes the core ROS CLI by default), deploy and connect using
`./tools/connect-shell`. Use `--without-shell` for a C++-only build.
Then use upstream `ros2 run` inside the phone shell to run one deployed executable
at a time. See [Android shell guide](ANDROID-SHELL.md) for the full workflow,
independent terminals, domains, current plugin/lifecycle limits and APK sizes.
The upstream on-phone LaunchService now runs the example; the builder still
uses its supported static adapter for the separate app group plan.

## On-phone project controls

Current builds advertise the project discovery interface. After deployment, open
ROS2 Android → Projects → Refresh, then Open project controls. Opening does not
start nodes. Use the runner's Start/Stop controls and its own connection settings;
UI defaults to domain 0, unlike the domain-42 CLI examples above. The main sensor
app's Stop all does not stop workspace nodes. See [project guide](PROJECTS.md).

A subsequent functional PC Wi-Fi-interface probe also passed from UI-started
nodes on domain 0; see [compatibility and validation](STATUS.md).
Long-duration workspace Wi-Fi/device qualification remains pending.

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

Outputs live in ~/.cache/ros2-android-workspaces/<id>/ under host/,
android-shell/ (standard), android/ (minimal), bundle/ and runner-project/.
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

Native LaunchService now runs in the standard shell bundle; Python workspace
entry points, arbitrary mixed-language launch packaging, additional ROS packages,
grandchild-process supervision, automatic restart,
physical PC removal and long-duration/device qualification remain pending.
The workspace supervisor heartbeat is its own elapsed-time indicator, not proof
that user callbacks are making progress; use actual ROS traffic probes.

[compatibility and validation](STATUS.md) ·
[Android executable-code restrictions](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission)

Source-manifest and runtime versions are described in the
[project compatibility contract](PROJECT-CONTRACT.md). Workspace inspection is
a package inventory, not proof that every dependency has an Android build.
