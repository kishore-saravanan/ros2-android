# ROS2 Android

An Android app that uses a smartphone's computing power, sensors, display, and
audio as the brain and sensory device of a robot.

Status: v0.1 development. Native ROS 2 Jazzy is integrated on Android ARM64.
Real phone IMU data and PC-to-phone text have been verified over Wi-Fi and USB
networking. The app has configurable publishers, a display subscriber, editable
topics, matching-type topic discovery, and capability detection. The 0.1.1
development build adds continuous camera capture (15 fps default, up to 30),
stream metrics and foreground-service ownership of robot sessions. A separate
developer runner now hosts native C++ ROS nodes, including custom messages and on-phone IMU processing. Group launch plans are resolved on the PC. Standard workspace APKs now include
core ROS CLI commands and native LaunchService, verified with the example and
Python/XML/YAML launch tests. Phone-hosted Wi-Fi XRCE-DDS Agent communication
with the reference ESP32 is verified. Broad Linux compatibility, broader MCU USB drivers and
the micro-ROS graph-manager wrapper remain pending. ABI-2 projects can launch several compiled C++ nodes
with per-node parameters and remaps using a bounded JSON configuration. A separate
Python feasibility runner embeds CPython and native rclpy, verified with custom
messages, parameters, IMU computation and phone-hosted C++ interoperability.

- **v0.1:** PC ↔ phone over Wi-Fi and USB networking.
- **v0.2:** microcontroller ↔ phone; Wi-Fi round trip with a phone-hosted Agent
  verified on one ESP32. Reference CP2102 USB serial traffic and software Agent restart also pass; broader USB qualification remains pending.

The 0.1.1-dev4 UI uses Home/Sensors/Dashboard/Projects/Settings navigation, compact sensor
lists and dedicated controls, collapsed advanced information, and ROS-inspired
light/dark palettes that follow the device. About is at the bottom of Settings.
Projects now provides live workspace state, Start/Stop, connection settings and
collapsed output logs in the main app. Workspace runners have no app-drawer
entry; open them through Projects. A custom dot/robot logo appears on Home
and installed app icons. See [project controls](docs/PROJECTS.md) and [UI design](docs/UI-UX.md).

Installable host tooling now has a **local Debian alpha with a bundled APK**;
see [Debian installation](docs/DEBIAN-PACKAGE.md). No public APT repository exists yet.
The reusable wheel is also available; see
[host package](docs/HOST-PACKAGE.md) for installation, commands and qualification limits.
The [product requirements](docs/PRODUCT-REQUIREMENTS.md) prioritize independent
robot operation, recovery and ordinary workspaces. No public package is published.

Start here: [first battery-message walkthrough](docs/GETTING-STARTED.md).
More sensors and transport details: [v0.1 guide](docs/V0.1.md).
For custom nodes: [C++ project build and deployment guide](docs/CPP-PROJECTS.md).
For multiple nodes: [workspace launch guide](docs/WORKSPACE-LAUNCH.md).
For ROS descriptions: [supported launch-file adapter](docs/ROS-LAUNCH.md).
For ordinary packages: [colcon workspace build/deploy guide](docs/COLCON-WORKSPACES.md).
For Python: [Python project guide](docs/PYTHON-PROJECTS.md).
For ESP32: [Wi-Fi setup](docs/ESP32-WIFI.md), [direct phone USB setup and LED button](docs/ESP32-USB.md),
or [upload firmware through the phone](docs/ESP32-PROGRAMMING.md).
For native ROS commands and launch files on the phone: [Android development shell](docs/ANDROID-SHELL.md).

Resuming development? Read the [project knowledge base](docs/PROJECT-KNOWLEDGE.md)
and the [community release handoff](docs/HANDOFF.md).

Development branch: `develop`. Repository: [ros2-android](https://github.com/kishore-saravanan/ros2-android).

Product direction: use the phone as the robot's main ROS 2 computer, with PC-side
editing, builds and deployment. See the [platform plan](docs/PLATFORM-PLAN.md) for
user nodes, launch files, camera performance, UI and reliability milestones.

Build, install, and test instructions: [Development guide](docs/DEVELOPMENT.md).

```bash
./setup/build.sh
./setup/run-device.sh
```

Scripts are separated by purpose: [setup/](setup/README.md) contains dependency
setup, builds and installation; [tools/](tools/README.md) contains viewing,
diagnostics and project commands. For the camera in RViz: `./tools/view-camera`.

See [Architecture and implementation strategy](ARCHITECTURE.md) for the current
design, standalone micro-ROS Agent requirement, compatibility policy, and delivery
gates. It supersedes the provisional implementation choices below.

## Phase 1 — ROS 2 infrastructure and connectivity

Build an app that can publish and subscribe to ROS 2 topics over a network and
support a wired USB connection appropriate to the attached hardware.

Initial scope:

- Start and stop the ROS runtime from the app.
- Configure the robot namespace, ROS domain ID, and connection settings.
- Publish a heartbeat and receive a test message from a Linux ROS 2 machine.
- Show connection state, received messages, message counts, and errors.
- Recover after disconnects without creating duplicate nodes or publishers.
- Start with topics; add services, actions, and parameters as later milestones.

Implementation: Kotlin Android app, JNI, and a C++ library using Jazzy's `rcl`
C client API with Cyclone DDS. Native source revisions are pinned in
`native/ros-sources.lock.json`; reviewed Android patches are in `native/patches`.
This runtime interoperates with the PC's existing Fast DDS ROS installation.

If a native build proves impractical, evaluate a bridge explicitly: it would
require a separate ROS 2 host and would change the standalone-phone design.

### Connection paths

| Connection | Proposed approach | Verification needed |
| --- | --- | --- |
| Phone ↔ Linux over Wi-Fi | ROS 2 over IP | Discovery, bidirectional topics, QoS compatibility, reconnect |
| Phone ↔ Linux over USB | Evaluate USB tethering as an IP link | Phone support, Linux interface, routing, middleware discovery or explicit peers |
| Phone ↔ microcontroller over USB (v0.2) | Android USB host/OTG plus micro-ROS Agent integration | USB roles, permission, power, device driver/protocol, framing and reconnect |

USB is a physical connection, not a ROS 2 transport by itself. A serial USB
device needs a defined protocol and an adapter to ROS topics. Do not assume
ordinary DDS messages can be sent directly to an arbitrary microcontroller.
Android accessory mode is another option when external hardware acts as host;
choose it only if the target hardware supports that protocol.

### Phase 1 acceptance criteria

1. Install and launch a reproducible app build on the chosen physical phone.
2. Publish `/phone/heartbeat` (`std_msgs/msg/String`) at 1 Hz; receive it on Linux.
3. Publish `/phone/test_input` (`std_msgs/msg/String`) from Linux; show it in the app.
4. Demonstrate both directions over Wi-Fi and the selected USB path.
5. Disconnect and reconnect each path; confirm recovery and one active runtime.
6. Record the tested phone, Android version, ROS distribution, middleware,
   build versions, configuration, and results.

For a Linux peer, use these commands once ROS integration is implemented:

```bash
ros2 topic echo /phone/heartbeat
ros2 topic pub --once /phone/test_input std_msgs/msg/String "{data: 'hello phone'}"
```

These commands now work while the corresponding app endpoints are active.

## Phase 2 — Phone hardware interfaces

Expose each capability independently with permissions, enable/disable controls,
rate settings, and a visible indication when a device lacks the capability.

| Capability | Initial ROS interface proposal | Notes |
| --- | --- | --- |
| IMU | `/phone/imu`, `sensor_msgs/msg/Imu` | Accelerometer and gyroscope; orientation only when available and valid |
| Camera | `/phone/camera/image_raw`, `sensor_msgs/msg/Image`; `/phone/camera/camera_info`, `sensor_msgs/msg/CameraInfo` | Start at modest resolution/rate; publish calibration status honestly |
| Display | `/phone/display/text`, `std_msgs/msg/String` subscriber | Show robot status, messages, or a face |
| Speaker | `/phone/speech/text`, `std_msgs/msg/String` subscriber | Start with text-to-speech; define audio playback separately |
| Microphone | Interface to be selected | Specify audio format, timestamps, chunk size, and bandwidth before streaming |
| Location | `/phone/fix`, `sensor_msgs/msg/NavSatFix` | Optional, permission-dependent; report fix quality |
| Other sensors | Select per capability | Magnetometer, light, proximity, pressure, and battery status where available |

Implement IMU first, then camera, display/speaker, microphone, and optional
sensors. Camera, audio, and IMU need timestamps mapped to the ROS clock;
multi-device use also needs an explicit clock synchronization strategy.

Phase 2 must verify units, sensor axis mapping, frame IDs, covariance/unknown
fields, and phone-to-robot mounting transforms. Do not treat Android sensor
coordinates or camera coordinates as interchangeable with robot coordinates.
Validate observed rates, latency, data loss, bandwidth, battery use, and thermal
behavior on the actual phone. Select compatible QoS per stream and bound queues.
Verify Android lifecycle behavior and applicable foreground-service requirements
before promising capture while the app is backgrounded or the screen is off.

## System boundary and future work

The phone is intended to run higher-level perception and robot decisions.
A separate motor controller should handle time-critical actuator control and
stop safely when commands expire or the phone disconnects. Motor control,
navigation, SLAM, and on-device AI are future scope after these two phases.

## Next milestone

Complete the remaining v0.1 hardware and usability checks on the Realme RMX2085
(Android 12, API 31, ARM64), then continue v0.2 MCU USB and graph-manager qualification
so the final robot can operate with a phone and MCU without requiring a PC.

## Primary references

- [ROS 2 Java and Android bindings](https://github.com/ros2-java/ros2_java)
- [ROS 2 Android bindings](https://github.com/ros2-java/ros2_android)
- [Android USB host and accessory overview](https://developer.android.com/develop/connectivity/usb)
- [Android sensor framework](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)

References reviewed on 2026-10-24. Architecture and interface names above are
project proposals, not claims of tested implementation.

Configurable controls and standard subscribed values: [Controls dashboard](docs/CONTROLS.md).

## License and citation

Original ROS2 Android project material is licensed under the
[Apache License 2.0](LICENSE). Copyright 2026 Kishore Saravanan. See [NOTICE](NOTICE).
Third-party components retain their own licenses and attribution requirements.

If you use ROS2 Android in your research or project, please cite the software
using [CITATION.cff](CITATION.cff). Citation is requested, not an additional
condition of the Apache license.
