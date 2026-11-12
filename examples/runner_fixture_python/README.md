# Python runtime fixture

Embedded `rclpy` implementation of the hardware-free
[C++ fixture contract](../runner_fixture_cpp/README.md). It provides the same
volatile Bool request and advancing String observations for offline startup,
Dashboard late-subscriber, lost-peer and explicit recovery qualification.

It uses only standard ROS message bindings already supplied by the Python SDK;
no third-party project pip dependency is required. This is a test peer, not an
application or hardware driver. Follow the [test instructions](../../docs/TESTING.md#dashboard-peer-recovery).
