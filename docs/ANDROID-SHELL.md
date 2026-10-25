# Native ROS commands in an Android development shell

Workspace APKs now include a standard ROS 2 Jazzy command bundle by default.
Commands execute on Android ARM64 using standalone CPython 3.12.15 and target
ROS libraries, without Ubuntu, Termux, root, a container or a PC ROS proxy.

## Build and connect

Complete DEVELOPMENT.md and COLCON-WORKSPACES.md first. Update the Python SDK
once to include the standard CLI's interface dependencies:

```bash
./setup/build-python-ros.sh
./tools/ros2android-workspace --workspace examples/android_test_ws build
./tools/ros2android-workspace --workspace examples/android_test_ws deploy
./tools/connect-shell --workspace examples/android_test_ws
```

Build includes the CLI automatically. `build --with-shell` is accepted as an
explicit equivalent. Use `build --without-shell` for a smaller C++-only APK that
has group controls but no command shell. Separate Android build caches prevent
Python-generated bindings leaking into the minimal build.

The connector opens Android's system shell under only the deployed debug runner's
UID. Executables stay in the APK's read-only native library directory; private
prefix links make upstream ament/ros2run discovery work. Exit an old shell and
reconnect after replacing its APK.

## Commands inside the phone shell

Start a deployed project group from the phone or PC before inspecting its graph:

```sh
ros2 node list
ros2 topic list -t
ros2 topic echo /android_test/input --once
ros2 param get /android_test/talker period_ms
ros2 service list
ros2 service call /android_test/talker/get_parameters rcl_interfaces/srv/GetParameters '{names: [period_ms]}'
ros2 interface show ws_interfaces/msg/Sample
ros2 pkg executables ws_nodes
```

Custom workspace message bindings are generated for Android and included, so
`topic echo` can import their types. Interface packages still need supported
build-time dependencies; arbitrary Linux/Python workspace parity is not implied.

To start one node independently:

```sh
ros2 run ws_nodes talker --ros-args -r __ns:=/shell_test
```

Ctrl+C stops that foreground node and returns to the phone shell. Open another
PC terminal, connect again, and run processor independently:

```sh
ros2 run ws_nodes processor --ros-args -r __ns:=/shell_test
```

Avoid starting duplicate node names through group controls and terminals.
`exit` leaves the phone shell. Shell nodes are separate from app group status
and Start/Stop project controls; stop each foreground command in its terminal.

## Launch files on Android

Upstream LaunchService, launch_ros and Python/XML/YAML frontend dependencies are
bundled. Before launching the example using its usual namespace, stop any copy
already started through group controls. From a PC terminal:

```bash
./tools/ros2android-workspace --workspace examples/android_test_ws stop
./tools/connect-shell
```

Then inside the phone shell:

```sh
ros2 launch ws_bringup test.launch.py
```

An explicit namespace prevents conflicts with another example instance:

```sh
ros2 launch ws_bringup test.launch.py namespace:=shell_launch
```

Ctrl+C shuts down the launched processes and returns to the shell. The example
was functionally verified this way against the PC. This runs launch on Android;
the PC-side static launch adapter is still used to generate the separate app
group plan during packaging. Unsupported adapter actions can therefore still
require builder work. Not every Linux-specific launch action/package is portable.

## Complete bundled core command set

`action`, `component`, `interface`, `launch`, `lifecycle`, `multicast`, `node`,
`param`, `pkg`, `plugin`, `run`, `service`, `topic`. Use `ros2 COMMAND --help`.
The lock in native/ros-shell-packages.lock.json defines the complete set and
versions together with pure-Python dependencies. Android NumPy/BLAS comes from
the Python runner's pinned wheels; no host ELF extensions are copied. CLI and
launch package resources and this workspace's target Python message bindings
are included. Assembly checks guard required package assets.

To verify every bundled command/subcommand on the phone, from the PC run:

```bash
./tools/check-shell-cli --workspace examples/android_test_ws
```

This checks CLI help imports and exit codes. Live functional tests are separate.
Action/lifecycle operations need an appropriate server; component operations
need deployed compatible components/container executables. Listing an empty
registry does not establish those runtimes are installed.

**Additional backends still pending:** rosbag2 recording/playback (`bag`), Linux
network/distribution diagnostics (`doctor`), and DDS security tooling. Those are
not added as broken plugins: Android native dependencies/builds are required.
Daemon discovery is intentionally omitted; ROS2CLI_NO_DAEMON=1 selects direct
native discovery, so ordinary graph commands work without daemon setup. This is
a core CLI bundle, not every ROS desktop package or GUI application.

## Domain and network

The shell uses the runner's saved domain and peer/local IPv4 addresses. Inspect
or override the domain inside the phone shell:

```sh
echo "$ROS_DOMAIN_ID"
export ROS_DOMAIN_ID=0
```

A shell-local override affects future commands in that shell; it does not change
app settings or already running nodes. PC monitoring terminals must use the same
domain and source the host custom-message overlay:

```bash
source /opt/ros/jazzy/setup.bash
source "$HOME/.cache/ros2-android-workspaces/android_test_ws/host/install/local_setup.bash"
export ROS_DOMAIN_ID=0
ros2 topic echo /shell_test/result
```

USB debugging carries shell control. ROS messages use reachable Wi-Fi or USB
networking. An already authorized wireless ADB device can be selected using
`--serial HOST:PORT`; sharing Wi-Fi alone does not enable a remote shell. No
SSH server, public command listener or global security/stay-awake changes are
installed. Wireless ADB, disconnect/background recovery and unattended execution
are not qualified; keep connected development sessions for current trials.

## APK and identity

Each extra package changes APK size. The initial run/pkg-only shell was 21.65 MiB;
node inspection with NumPy was 32.23 MiB. The current full core CLI with launch and its resources measures **34.09 MiB**.
See [full-CLI verification](verification/2026-10-25-core-cli-bundle.md).
These are debug ARM64 APK file sizes; installed storage is larger. Release size
has not been measured. The main sensor app remains a separate small APK.

App ID: dev.ros2android.android. Runner IDs:
dev.ros2android.android.runner.<project>, including
`dev.ros2android.android.runner.ws_android_test_ws`. Other legacy project APKs
need rebuilding/deployment to adopt these identities.
