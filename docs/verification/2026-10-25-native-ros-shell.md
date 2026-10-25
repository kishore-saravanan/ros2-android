# Native Android ROS shell proof — 2026-10-25

Reference Android 12 ARM64 phone and prepared Linux Jazzy development machine.
Shell is optional: workspace build --with-shell. Deployment used the existing
project installer. No container/root/Ubuntu/Termux/SSH daemon was installed.

## Implementation

Standalone NDK-built CPython executable packaged as an APK native-library asset;
actual CPython 3.12.15 target and checksum-verified stdlib. ros2cli, ros2run and
ros2pkg 0.32.13 copied with upstream Python implementations intact. CLI plugin
metadata advertises selected run/package features, excluding daemon and package
creation. Cross-built rclpy dependencies come from the existing Python SDK.
Bundle provenance records versions/stdlib hash; all staged JNI ELF dependencies
are checked. Private ament-prefix executable links resolve to read-only APK PIE
executables. A debug DUMP-permission receiver prepares only this runner's files.
The ADB run-as connection opens Android's system shell under this app UID.

## Actual checks

- Workspace cross-build, APK assembly and lint passed after final changes.
- Shared C++ and Python runner APK assembly/lint passed after the debug receiver
  API changes. They were not physically redeployed in this pass.
- Existing 24 host tooling tests passed. Python helpers compiled; diff check passed.
- Native Python reported 3.12.15, aarch64, and its app-private executable path.
- `ros2 --help` ran on Android; final usage identifies ros2 normally.
- `ros2 pkg executables ws_nodes` found processor and talker through the native
  ament index and installed-prefix links. Final deployed APK repeated this check.
- Interactive ADB PTY: `ros2 run ws_nodes talker --ros-args -r __ns:=/shell_test`
  created /shell_test/talker. PC received typed ws_interfaces/Sample (sequence/value).
- Second connected shell started processor. Functional PC probe passed custom
  input, correct computation, both nodes, parameter service with 3 matching pairs.
- Ctrl+C in talker's shell removed talker while processor stayed visible.
  Talker could start again independently. Both shells were stopped/exited afterward.
- One early interpreter exit segfaulted after Ctrl+C. The ros2 executable now
  calls upstream CLI main, flushes output, and terminates the disposable process
  without CPython/Android extension unloading. Upstream ros2run already waits for
  the child. Four subsequent Ctrl+C cycles returned to the shell without a fault.
  Broader crash/disconnect qualification remains pending; no claim of universal
  fatal-crash recovery is made.
- Unknown package returned a readable error and exit 1; the shell remained usable.
- Final APK deployed; native package discovery passed. Group controls restarted
  the normal example on domain 0; real PC probe again passed all four checks.
  No test shell nodes were left running. Main sensor app was not reinstalled.

## Size and scope

Measured debug files: app 4.26 MiB, C++ runner 4.80 MiB, Python runner 32.54 MiB,
workspace with native shell 21.65 MiB. Prior workspace without shell ~8.2 MiB.
APK file size is distinct from installed storage. The observed extracted shell
files were ~24 MiB; ament workspace resources and APK libraries add more storage.
No release build/signing/download-size claim is made.

Physical shell testing used authorized USB ADB, with DDS over Wi-Fi. Wireless ADB
selection is supported by the connector but not tested here. Sessions need a
connected development terminal; disconnect/background lifetime, unattended robot
operation, additional phones, and other Android versions remain unqualified.
Main/runner UI group status does not enumerate shell processes. Native launch,
other ROS CLI plugins and arbitrary Python workspace dependencies remain pending.
See ANDROID-SHELL.md for commands and user-facing limits.

Raw build logs are ignored under device-logs/native-shell-*.log. Device identifiers,
addresses and terminal logs are not added to Git. No commit or push in this pass.
