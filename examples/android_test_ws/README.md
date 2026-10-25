# android_test_ws

A normal three-package ROS 2 Jazzy workspace. The same source builds on Linux
and Android: no Android headers, node factory or special main() is required.
See [the workspace guide](../../docs/COLCON-WORKSPACES.md) for the verified
host-build/cross-build/deploy/run commands and current compatibility boundaries.

- ws_interfaces defines Sample.msg.
- ws_nodes installs talker and processor native executables.
- ws_bringup installs test.launch.py.

This example tests custom messages, computation, namespaces, parameters and
supervised child-process failure. It is not evidence for arbitrary workspace
package compatibility or full ROS launch support.
