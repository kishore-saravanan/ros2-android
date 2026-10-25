# Run Python ROS nodes on Android

The Python feasibility runner embeds CPython 3.12.15 and a cross-built Jazzy
`rclpy` extension inside an Android APK. Your node executes on the phone using
Cyclone DDS, without Ubuntu, Termux or a ROS bridge on the PC. The first physical
verification used Android 12 ARM64 over USB tethering IP.

## Build and run

Use the Android/ROS development setup in [DEVELOPMENT.md](DEVELOPMENT.md).
Additional PC build requirements are Python 3.12, NumPy headers (tested with
Ubuntu NumPy 1.26.4), and pybind11 headers (tested with 2.11.1). The phone runtime
uses the pinned Android NumPy 1.26.2 and PyYAML 6.0.3 wheels.

From the repository root:

```bash
python3 tools/fetch-native-ros.py
./tools/ros2android --project examples/python_talker sdk
./tools/ros2android host-sdk
./tools/ros2android --project examples/python_talker host-interfaces
./tools/ros2android --project examples/python_talker build
./tools/ros2android --project examples/python_talker deploy
./tools/ros2android --project examples/python_talker start --peer PC_IP --local PHONE_IP
```

Replace the addresses with the current USB-tethering or Wi-Fi IPv4 addresses.
The phone must be unlocked for startup. The selected runner alone is installed;
its application ID is restricted to this project's generated runner namespace.

The Python SDK lives under `native-deps/python-*` in the ignored external cache.
It does not overwrite the C++ SDK's `install` or `apk-libs`. The first build needs
network access for the pinned source revisions, CPython target, Gradle plugin and
Android wheels. It builds the example's custom interfaces with Python support.

## Inspect and edit

Edit `examples/python_talker/phone_node.py`. It is a standard `rclpy.node.Node`
with publishers, subscriptions, a timer and parameters. Rebuild, deploy and start
after editing. The CLI checks source fingerprints against built/installed code.
The default Python namespace is `/python`, distinct from the C++ demo's `/demo`.

```bash
./tools/ros2android --project examples/python_talker status
./tools/ros2android --project examples/python_talker ros --interface PC_IP -- topic echo /python/status
./tools/ros2android --project examples/python_talker ros --interface PC_IP -- topic echo /python/runtime_status
./tools/ros2android --project examples/python_talker ros --interface PC_IP -- topic pub --once /python/command std_msgs/msg/String "{data: 'hello Python'}"
./tools/ros2android --project examples/python_talker ros --interface PC_IP -- param set /python/python_demo message 'Updated from the PC'
./tools/ros2android --project examples/python_talker logs
./tools/ros2android --project examples/python_talker stop
```

Command replies appear on `/python/echo`. Enable the main app's IMU publisher at
`/phone/imu` to get Python-computed acceleration magnitude on
`/python/acceleration_magnitude`. The custom `demo_interfaces/msg/RuntimeStatus`
on `/python/runtime_status` reports message text, tick count and IMU sample count.
The scoped PC Cyclone wrapper supports the verified parameter services without
changing the PC's system middleware.

The phone's Python runner console can start/stop the packaged node. Closing its
activity does not stop an active foreground service. Status retains the backend
`native` key for CLI compatibility and identifies `runtime: python` and the
actual Python version. Caught callback exceptions show a traceback, trigger
cleanup and require an explicit restart.

## Interoperate with the C++ phone node

Build/deploy the C++ demo using [CPP-PROJECTS.md](CPP-PROJECTS.md). Then:

```bash
./tools/ros2android start --peer PC_IP --local PHONE_IP
./tools/ros2android --project examples/python_talker stop
./tools/ros2android --project examples/python_talker start --peer PC_IP --local PHONE_IP -- -p cpp_bridge_enabled:=true
```

For this optional test, Python forwards each `/python/command` to the C++ demo's
`/demo/command`, receives `/demo/echo`, and publishes the reply on
`/python/cpp_reply`. Both computations run on the phone in separate app processes.
The C++ bridge namespace must differ from the Python node's namespace.

## Reproduce verification

After enabling IMU and starting both demos with the bridge:

```bash
ROS2_ANDROID_PROJECT_OVERLAY="$HOME/.cache/ros2-android-projects/python_talker/host/install/local_setup.bash" \
  ./tools/pc-ros.sh --interface PC_IP -- python3 tools/pc-project-probe.py \
  --namespace /python --expected-text 'Hello from Android Python' \
  --echo-prefix 'phone Python processed: ' --require-imu --custom-message --require-cpp

python3 tools/test-runner-recovery.py --project examples/python_talker --peer PC_IP --local PHONE_IP
```

The recovery test intentionally throws after three ticks, checks failed status,
removed service and stopped node, then restarts with normal defaults. It leaves
the bridge disabled; stop/start with `cpp_bridge_enabled:=true` to test it again.

## Supported scope and remaining work

This proves native `rclpy`, typed topics, parameters, custom messages, Python IMU
computation and Python/C++ interoperability on one phone. It does not promise
arbitrary pip packages or Raspberry Pi/Linux binaries. Native Python extensions
need compatible Android wheels or a cross-build. Package new dependencies in the
APK; no runtime pip installation flow is provided.

The example entry module is `phone_node`, exporting `PhoneNode`. The current SDK
build script includes this example's interface package; a general Python package
workspace builder is future work. Python multi-node launch, ROS Python launch
files, independent worker supervision, physical PC disconnection, Wi-Fi Python
qualification, long-duration testing and the embedded micro-ROS Agent remain
pending. Fatal native errors or callbacks that block Python/native execution can
require `kill`; caught Python exceptions do not establish fatal-crash recovery.

## Implementation references

- [Chaquopy Android Gradle integration](https://chaquo.com/chaquopy/doc/current/android.html)
- [Jazzy rclpy source](https://github.com/ros2/rclpy/tree/4e607c09d624debc39a4ae5405680c06d87fac35)
- [Jazzy ROSIDL Python generator source](https://github.com/ros2/rosidl_python/tree/fe4e01f2007d3451bf73c0cb334b96a7de759ef2)

The patches explicitly link Android extension modules to libpython, use portable
DT_NEEDED library names, and opt into Python interface generation only for the
Python SDK. Staging rejects non-ARM64 binaries, absolute dependency paths and
missing packaged dependencies. CPython target download is checksum verified.
