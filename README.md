# ROS2 Android

Use an Android phone as a robot's onboard ROS 2 computer: run supported nodes
locally, publish phone sensors, connect microcontrollers and configure a control
Dashboard. Develop on Linux and deploy to the phone over USB or Wi-Fi.

The phone runs native ROS 2 without Ubuntu, root, a container or a mandatory PC
relay. This project is a reusable platform; ball following is an optional
proof-of-concept application, not the product's primary purpose.

**Development preview: 0.1.1-dev6 · ROS 2 Jazzy · Android ARM64 · Apache-2.0.**
There is no stable release or public APT repository yet. Android API 26 is the
build minimum; supported hardware and runtime evidence are described in
[compatibility and validation](docs/STATUS.md), not implied by that minimum.

## Start here

| Your goal | Guide |
| --- | --- |
| Install the preview and publish a first topic | [Getting started](docs/GETTING-STARTED.md) |
| Run your own ROS workspace on the phone | [Workspace build and deployment](docs/COLCON-WORKSPACES.md) |
| Configure buttons, joystick and subscribed values/images | [Dashboard](docs/CONTROLS.md) |
| Connect an MCU and operate without the PC | [Robot operation](docs/ROBOT-OPERATION.md) |
| Build or contribute to the platform | [Contributing](CONTRIBUTING.md) |

The host client is `ros2-android`. Local wheel and Debian alpha packaging exist;
see [host package](docs/HOST-PACKAGE.md) and [Debian installation](docs/DEBIAN-PACKAGE.md).
Phone installation/deployment is an explicit device operation, not a side effect
of installing the Linux package.

## What the platform provides

- **Phone sensors:** capability discovery, editable topics, sensor publishers and
  camera resolution/FPS/JPEG controls based on device support.
- **Dashboard:** buttons, switches, joystick, scalar subscriptions and JPEG/PNG
  `sensor_msgs/msg/CompressedImage` viewing.
- **Projects:** inspect, cross-build and deploy supported workspaces; start/stop
  them from the app. Each workspace currently uses a hidden runner APK.
- **Microcontrollers:** phone-hosted micro-ROS Agent using supported USB serial
  adapters or UDP. ESP32/CP2102 is the reference tested USB combination.
- **ROS networking:** direct DDS communication, LAN discovery where multicast
  works and explicit peers where needed. Peers need matching domains and QoS.

The Android app has Home, Sensors, Dashboard, Projects and Settings tabs and
follows the device's light/dark theme. Services own active resources independently
of screen navigation. Projects, sensor/Dashboard sessions and the Agent have
explicit, separate stop scopes.

## Architecture and limits

Kotlin owns Android UI, permissions and hardware. JNI connects to native Jazzy
`rcl` and Cyclone DDS. Workspace runners host native ROS processes or the supported
embedded Python runtime; the micro-ROS Agent connects MCU nodes to the ROS graph.

See [current architecture](ARCHITECTURE.md), [target high-level design](docs/design/HIGH-LEVEL-DESIGN.md),
[low-level design](docs/design/LOW-LEVEL-DESIGN.md) and [roadmap](docs/ROADMAP.md).
The direction is a modular application with isolated runners, not microservices.

This preview does not promise arbitrary Linux package compatibility, every phone,
boot-time autonomy or hard real-time deadlines. Mixed Python/C++ workspace
coverage, independently signed runner authorization, long-duration operation and
broader device qualification remain release gates. Dashboard raw `sensor_msgs/msg/Image`
viewing is pending. A local write or Running label is not remote acknowledgement.

## Examples and development

Minimal [example workspaces](examples/README.md) exercise platform compatibility.
The [ball follower](docs/BALL-FOLLOWER.md) demonstrates onboard Python vision and
ROS control; it is not a calibrated or generally supported robot controller.
Robot-specific examples will move to a companion repository after the external
build/deploy journey is proven. No migration has occurred yet.

Contributor entry points: [build and test](docs/DEVELOPMENT.md),
[CI and test scope](docs/TESTING.md), [architecture decisions](docs/decisions/0001-platform-and-examples.md),
[release checklist](docs/RELEASING.md), [changelog](CHANGELOG.md) and
[security reporting](SECURITY.md).

## License and citation

Original project material is [Apache-2.0](LICENSE), copyright 2026 Kishore
Saravanan. See [NOTICE](NOTICE); dependencies retain their own licenses.
Please cite [CITATION.cff](CITATION.cff) when using the project in research or
other work. Citation is requested, not an additional license condition.
