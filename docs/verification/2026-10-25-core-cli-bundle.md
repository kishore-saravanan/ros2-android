# Standard core CLI bundle and native launch — 2026-10-25

Reference Android 12 ARM64 phone; prepared Jazzy host. No container, Ubuntu,
Termux, root or public command service. Commands below execute within the
renamed workspace runner via authorized USB ADB. ROS interoperability used Wi-Fi.

## Bundle and build changes

- Version/dependency manifest: native/ros-shell-packages.lock.json.
- Core commands: action, component, interface, launch, lifecycle, multicast,
  node, param, pkg, plugin, run, service, topic. Includes their registered verbs,
  launch/launch_ros/Python/XML/YAML frontends, CLI resources/templates and pure
  Python dependency closure. Android NumPy/BLAS comes from pinned target wheels.
- Native Python SDK now builds composition_interfaces. Standard workspace builds
  enable target Python interface generation; generated message modules and ARM64
  extensions are included, so custom topic echo/pub can import actual bindings.
- Core CLI is the default workspace build. --with-shell is an explicit alias;
  --without-shell produces a smaller C++-only APK. Separate android-shell and
  android build/install caches prevent mode contamination.
- sys.executable in the ros2 launcher identifies the actual python3 interpreter,
  so Python subprocesses requested by launch don't re-enter ros2 CLI parsing.
- Existing direct-discovery and AAPT underscore-directory compatibility fixes
  remain. Dependency closure rejects host/non-ARM64/native-library mismatches.

## Passing checks

- Standard workspace cross-build, assembly, APK asset assertions and lint.
- Separate minimal-mode workspace build/assembly/lint; no minimal APK installed.
- tools/check-shell-cli: **63 command/verb help checks, zero failures** on the
  final phone installation. It checks stderr as well as exit codes because
  upstream help can return 0 while omitting plugins with missing dependencies.
- 24 host tooling tests passed. Python compilation, shell syntax and diff checks
  passed. Main app wasn't rebuilt/reinstalled for this CLI-only milestone.
- Native topic list -t discovered typed workspace topics.
- Native topic echo /android_test/input --once returned the target-generated
  custom ws_interfaces/Sample sequence/value.
- Native topic pub --once sent a std_msgs/String that a PC subscriber received.
- Native param get returned 200; set to 400/get returned 400; restored to 200.
- Native service list returned interfaces; GetParameters service call returned
  the actual period_ms=200 from the C++ talker.
- Native interface show displayed the custom message definition.
- Action list, lifecycle nodes and component types returned successfully on an
  empty corresponding graph/registry. This does not qualify goals, transitions,
  component loading or missing container executables.
- Native ros2 launch ws_bringup test.launch.py namespace:=native_launch_probe
  started talker and processor on Android using upstream LaunchService.
  PC probe passed custom input, correct computation, both nodes and parameter
  service with three matching samples. Ctrl+C reported clean child exits and
  returned to the shell. Both test launch nodes were stopped afterward.
- Private temporary Python launch using ExecuteProcess([sys.executable, '-c',
  ...]) ran a Python child and returned successfully.
- XML and YAML LogInfo launch fixtures each ran successfully on the phone.
- Native pkg create generated a valid private CMake package using an explicit
  test maintainer fixture. Temporary test source/launch files were cleaned up
  only within this runner's own generated test directory.
- Final installed bundle repeated topic list/custom echo and the normal
  workspace PC probe. Final normal group remains running on domain 0.

Early candidates exposed missing argcomplete, ament_copyright and pyparsing;
those were added to the bundle and the final 63-check run passed. They are not
counted as successful intermediate validations.

## Reproduce

```bash
./setup/build-python-ros.sh
./tools/ros2android-workspace --workspace examples/android_test_ws build
./tools/ros2android-workspace --workspace examples/android_test_ws deploy
./tools/check-shell-cli
./tools/connect-shell
```

Inside the phone shell, use the commands in ANDROID-SHELL.md. Stop an existing
copy or select another namespace before launching duplicate example nodes.
Exit/reconnect an old shell after installing an updated APK.

## Size and boundaries

Final debug ARM64 full-core CLI APK: **35,741,354 bytes / 34.09 MiB**. This includes
launch/resources and custom interface bindings. Release/download/installed sizes
are different and not claimed here. SDK/bundle/dependency additions affect size.

Bag/rosbag2 storage, doctor network/platform diagnostics, DDS security and daemon
monitoring still need Android native/backend work and aren't bundled as broken
plugins. Direct discovery supplies normal graph commands without a daemon.
Native LaunchService is present and tested; the packaging step still uses its
supported static adapter for the separate app group plan. Arbitrary Linux launch
packages, mixed Python workspaces, disconnection/background supervision, wireless
ADB, further phones/Android versions and long-duration operation remain pending.

Raw logs are ignored in device-logs/full-cli-*.log. No device identifiers,
addresses, preferences, launch logs, APKs or temporary device test files are
committed. Documentation and source are uncommitted on develop.
