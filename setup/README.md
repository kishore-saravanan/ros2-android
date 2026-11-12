# Setup, build and installation

Run these commands from the repository root:

```bash
./setup/build.sh
./setup/run-device.sh
```

This folder contains dependency fetching, native/Python SDK preparation,
workspace/interface builds, library staging, Android builds and device
installation. `setup-adb-udev.sh` is the optional Linux USB permissions helper.
See [development prerequisites](../docs/DEVELOPMENT.md) before the first build;
`prepare-build-tools.py` prepares the managed colcon/vcstool venv those builds use.

The project commands in `tools/ros2android` and `tools/ros2android-workspace`
delegate build/install work here. Camera viewers and diagnostics remain in
[tools](../tools/README.md).

`prepare-ros-shell.py` stages the optional workspace shell's standalone Android
Python interpreter and upstream ROS CLI; the workspace builder calls it for
standard workspace builds (or explicit `build --with-shell`). See [Android shell](../docs/ANDROID-SHELL.md).

For ESP32 Wi-Fi setup, use [ESP32-WIFI.md](../docs/ESP32-WIFI.md):
configure-esp32-wifi.py prepares private credentials, esp32-wifi.sh builds/flashes
the reference board, and build-micro-ros-agent.sh stages the optional phone Agent.

For direct phone-to-ESP32 USB, see [ESP32-USB.md](../docs/ESP32-USB.md);
`./setup/esp32-usb.sh build|upload` builds the separate serial firmware profile.
