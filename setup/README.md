# Setup, build and installation

Run these commands from the repository root:

```bash
./setup/build.sh
./setup/run-device.sh
```

This folder contains dependency fetching, native/Python SDK preparation,
workspace/interface builds, library staging, Android builds and device
installation. `setup-adb-udev.sh` is the optional Linux USB permissions helper.
See [development prerequisites](../docs/DEVELOPMENT.md) before the first build.

The project commands in `tools/ros2android` and `tools/ros2android-workspace`
delegate build/install work here. Camera viewers and diagnostics remain in
[tools](../tools/README.md).

`prepare-ros-shell.py` stages the optional workspace shell's standalone Android
Python interpreter and upstream ROS CLI; the workspace builder calls it for
standard workspace builds (or explicit `build --with-shell`). See [Android shell](../docs/ANDROID-SHELL.md).
