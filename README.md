<p align="center">
  <img src="docs/assets/ros2-android.svg" width="112" alt="ROS2 Android robot logo">
</p>

<h1 align="center">ROS2 Android</h1>

<p align="center"><b>Turn an Android phone into your robot's onboard ROS 2 computer.</b></p>

<p align="center">
  <a href="https://github.com/kishore-saravanan/ros2-android/releases/tag/v1.0.0"><img src="https://img.shields.io/badge/release-1.0.0-0b6e99" alt="Release 1.0.0"></a>
  <img src="https://img.shields.io/badge/ROS%202-Jazzy-22314e" alt="ROS 2 Jazzy">
  <img src="https://img.shields.io/badge/Android-8.0%2B%20ARM64-3ddc84?logo=android&logoColor=white" alt="Android 8.0+ ARM64">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="Apache-2.0"></a>
  <a href="https://github.com/sponsors/kishore-saravanan"><img src="https://img.shields.io/badge/Sponsor-%E2%9D%A4-db61a2?logo=githubsponsors&logoColor=white" alt="Sponsor on GitHub"></a>
</p>

<p align="center">
  <a href="#download"><b>Download</b></a> ·
  <a href="docs/STARTER-GUIDE.md"><b>Starter guide</b></a> ·
  <a href="#documentation">Documentation</a> ·
  <a href="#community">Community</a>
</p>

A phone already has a fast computer, cameras, motion sensors, Wi-Fi and a battery.
ROS2 Android runs **native ROS 2 Jazzy on the phone itself**, with no Ubuntu, root or
container, so the phone on your robot can sense, think and talk to its microcontroller
on its own.

https://github.com/user-attachments/assets/cf2f065b-a2af-451b-9734-392699fe368c

*The phone on the robot sees the red ball, runs the perception and control nodes and
drives the motors through an ESP32. A second phone starts the run and shows what the
robot sees. There is no PC in the loop.*

## Features

- **Phone sensors on ROS topics:** camera, IMU, magnetometer, barometer and battery.
- **Your ROS nodes on the phone:** build C++ and Python workspaces on your PC, deploy
  them, and start or stop them from the app.
- **Development on a mounted robot:** deploy over Wi-Fi while the phone stays on the robot;
  the nodes keep running when the PC leaves.
- **Microcontrollers:** a micro-ROS Agent for an ESP32 over USB or Wi-Fi, and firmware
  updates flashed through the phone.
- **Dashboard:** buttons, switches, sliders, a joystick, live values and camera images.

<table>
  <tr>
    <td align="center"><img src="docs/assets/screenshots/home.png" width="190" alt="Home: runtime state, connection and running projects"><br><b>Home</b></td>
    <td align="center"><img src="docs/assets/screenshots/sensors.png" width="190" alt="Sensors: phone hardware ready to publish"><br><b>Sensors</b></td>
    <td align="center"><img src="docs/assets/screenshots/dashboard.png" width="190" alt="Dashboard: controls and live values"><br><b>Dashboard</b></td>
    <td align="center"><img src="docs/assets/screenshots/projects.png" width="190" alt="Projects: deployed workspaces with Start and Stop"><br><b>Projects</b></td>
  </tr>
</table>

## Download

| | |
| --- | --- |
| **Android phone** (ARM64, Android 8.0+) | [ros2-android-1.0.0.apk](https://github.com/kishore-saravanan/ros2-android/releases/download/v1.0.0/ros2-android-1.0.0.apk) |
| **Ubuntu 24.04 PC:** the `ros2-android` tool (includes the app) | [ros2-android_1.0.0-1_all.deb](https://github.com/kishore-saravanan/ros2-android/releases/download/v1.0.0/ros2-android_1.0.0-1_all.deb) |
| **Native SDK** to build and deploy your own workspaces with that tool | [ros2-android-native-sdk-1.0.0-arm64-v8a.tar.xz](https://github.com/kishore-saravanan/ros2-android/releases/download/v1.0.0/ros2-android-native-sdk-1.0.0-arm64-v8a.tar.xz) |

[Checksums](https://github.com/kishore-saravanan/ros2-android/releases/download/v1.0.0/SHA256SUMS) ·
[APK signing certificate](SECURITY.md#verify-a-downloaded-apk) · [All release files](https://github.com/kishore-saravanan/ros2-android/releases/tag/v1.0.0)

## Quickstart

1. **Install the app.** Open the APK on the phone. Or, with the phone connected by USB and
   USB debugging on, install the PC tool and let it install the app:

   ```bash
   sudo apt install ./ros2-android_1.0.0-1_all.deb
   ros2-android device setup
   ```

2. **Publish.** In the app tap **Sensors → Battery percentage → Publish**.
3. **Listen.** On a ROS 2 Jazzy PC on the same Wi-Fi:

   ```bash
   source /opt/ros/jazzy/setup.bash
   ros2 topic echo /phone/battery_percent std_msgs/msg/Float64 --qos-reliability best_effort
   ```

   The phone's battery level arrives once a second.

**Next:** the [Starter guide](docs/STARTER-GUIDE.md) takes you on to your own nodes on the phone,
wireless deployment to a mounted robot and an ESP32, step by step.

## Documentation

- [Starter guide](docs/STARTER-GUIDE.md): from install to your own nodes and an ESP32
- [Getting started](docs/GETTING-STARTED.md): installation details and troubleshooting
- [Workspaces](docs/COLCON-WORKSPACES.md): build and deploy your own ROS 2 workspaces
- [Dashboard](docs/CONTROLS.md): widgets, topics and camera images
- ESP32: [over USB](docs/ESP32-USB.md) · [over Wi-Fi](docs/ESP32-WIFI.md) · [flashing through the phone](docs/ESP32-PROGRAMMING.md)
- [Running without a PC](docs/STANDALONE-OPERATION.md): standalone startup and recovery
- [Compatibility](docs/COMPATIBILITY.md) and [status](docs/STATUS.md): tested phones, versions and limits
- [Architecture](ARCHITECTURE.md): how it works inside
- [Ball follower](docs/BALL-FOLLOWER.md): the advanced example from the video (source checkout)

**Tested on** a Realme phone (Android 12), a Samsung Galaxy S24+ (Android 17) and an
Android 15 emulator, with an ESP32 (CP2102) over USB and Wi-Fi. For long runs keep the app
open with the screen on, since phone power management can stop background work. Use
trusted networks: ROS traffic is not encrypted. The Dashboard is not an emergency stop.

## Community

- **Found a bug?** [Report it](https://github.com/kishore-saravanan/ros2-android/issues/new?template=bug_report.md).
  **Tried another phone?** [Send a phone report](https://github.com/kishore-saravanan/ros2-android/issues/new?template=phone_report.md).
  **Have an idea?** [Request a feature](https://github.com/kishore-saravanan/ros2-android/issues/new?template=feature_request.md).
  Report security issues privately through [SECURITY.md](SECURITY.md).
- **Want to fix something?** Pick a [good first issue](https://github.com/kishore-saravanan/ros2-android/issues?q=is%3Aopen+label%3A%22good+first+issue%22)
  and read [Contributing](CONTRIBUTING.md): branch from `develop`, add a test, one problem per pull request.
- **Like the project?** ROS2 Android is built by Kishore Saravanan. You can support it through
  [GitHub Sponsors](https://github.com/sponsors/kishore-saravanan).

## License

Apache-2.0, copyright 2026 Kishore Saravanan; see [LICENSE](LICENSE) and [NOTICE](NOTICE).
Dependencies keep their own licenses: see [third-party notices](THIRD-PARTY-NOTICES.md),
also shown in the app under Settings → About. To cite the project, use [CITATION.cff](CITATION.cff).
