# Composed C++ launch verification — 2026-10-25

Physical Android 12 ARM64 phone, ROS 2 Jazzy PC, Cyclone DDS, USB IP link.
The example project source fingerprint is a004b3c5c21c. No personal phone apps or
storage were accessed. Logs and APK outputs remain ignored.

## Passed

- ABI-2 example assembled and passed Android lint.
- Main app assembly, unit tests and lint passed.
- Eight PC launch-validation tests passed, covering unsupported actions,
  duplicate names, namespace resolution, bounds, scalar types and reserved remaps.
- Installed example ran two actual rclcpp nodes on the phone:
  `/robot/producer` and `/robot/processor`.
- PC received matching counter messages on `/robot/pipeline/input` and
  `/robot/pipeline/result`; the latter was transformed by the second phone node.
- ROS GetParameters services returned the configured producer message and
  processor prefix. These were real service calls, not diagnostic metadata.
- Native status listed both launched names and an advancing shared heartbeat.
- Unsupported launch input was rejected without disturbing the active group.
- Deliberately missing second component rolled back the partially created group;
  native status had no running node or residual node names.
- Deliberate producer callback exception stopped the group and preserved the
  failure reason; explicit restart restored both nodes and cleared the error.
- Original ABI-1 example rebuilt/deployed under the new runner and passed status,
  command-reply and custom-message probes. IMU was disabled for this regression;
  no new IMU interoperability claim is made from that probe.

An initial physical test caught namespace precedence: rcl selects the first
matching remap. The runner now places per-node identity/remaps before common
CLI defaults. A repeated final probe confirmed the requested /robot names.
A device-specific first-launch-after-install race was reproduced and the CLI
now retries opening only its selected runner activity once, without bypassing
lock-screen checks.

## Boundaries

Nodes share one process and a SingleThreadedExecutor. A callback exception fails
the group; there is no independent node worker restart. This CMake workspace and
bounded JSON launch schema do not implement arbitrary colcon builds or ROS Python
launch files, includes, conditions, timed starts, parameter files, substitutions,
actions, rclpy or an embedded micro-ROS Agent. Physical PC disconnection,
long-duration qualification, fatal native signals and blocked callbacks remain
untested. See WORKSPACE-LAUNCH.md for supported configuration and commands.
