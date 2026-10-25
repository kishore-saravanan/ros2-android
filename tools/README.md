# Viewing, diagnostics and project commands

Run from the repository root:

```bash
./tools/view-camera
```

This opens the phone's back-camera stream in a configured RViz window. Publish
the camera in the app first. Use `--help` for topic and ROS domain options.
`view-camera.sh` opens the separate OpenCV viewer with received-FPS information.

Other tools include camera benchmarks/probes, ROS middleware helpers, project
and workspace commands, launch configuration adapters, recovery checks and
their tests. `ros2android` and `ros2android-workspace` remain the developer
entry points; their build/install commands call scripts in `setup/`.

Dependency setup, Android builds and installation live in
[setup](../setup/README.md). The previous `tools/build.sh` and
`tools/run-device.sh` paths have moved there. Historical verification records
retain the paths used at the time; use the current guides for new runs.

`./tools/connect-shell --workspace examples/android_test_ws` opens a native ROS
development shell for a standard workspace runner (CLI included by default). Once connected, type
`ros2 run`, `ros2 topic`, `ros2 param` and `ros2 launch` commands normally. See [Android shell](../docs/ANDROID-SHELL.md).

`./tools/check-shell-cli` validates imports/help for all bundled commands and
subcommands on the connected phone.
