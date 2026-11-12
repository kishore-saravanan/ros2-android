# Maintenance strategy

ROS2 Android is a modular Android application with isolated workspace runners and
one host CLI. Prefer small changes that preserve supported workflows. The
[roadmap](../ROADMAP.md) defines delivery order and acceptance gates.

## Module boundaries

- Android UI presents configuration and observed state; services own hardware and
  ROS sessions independently of screen navigation.
- Sensor, Dashboard, project and MCU controllers own their respective resources.
  `ProjectConnections` owns discovery reconciliation and UI management bindings;
  `ProjectController` owns each IPC connection and runners retain execution.
  Closing the UI binding collection never sends a Stop command.
  Streaming paths use bounded queues and discard stale frames.
- Runner backends share lifecycle and configuration contracts while retaining
  backend-specific execution code.
- Host implementation belongs in the `ros2android` Python package. Contributor
  setup scripts bootstrap prerequisites; compatibility wrappers forward to the
  same implementation. Workspace orchestration and manifest validation live in
  `python/ros2android`; project orchestration, launch validation and controller
  certificate inspection use that package too. Commands take an explicit tooling
  root so the source checkout and installed SDK use the same code. The SDK snapshot
  includes matching Python helper sources for system-Python launch compilation. Setup and native build
  adapters remain separate subprocesses.
- Robot applications consume the public SDK. Move them to a companion examples
  repository once a separate checkout can build and deploy without platform edits.
  Keep small compatibility fixtures in the platform repository.

See the [high-level design](HIGH-LEVEL-DESIGN.md),
[low-level contracts](LOW-LEVEL-DESIGN.md) and
[platform/examples decision](../decisions/0001-platform-and-examples.md).

## Contributor journeys

| Audience | Supported path |
| --- | --- |
| Operator | Install, configure, start, observe, stop and recover |
| ROS developer | Inspect, build, deploy and run an external workspace |
| Platform contributor | Build, test and change a focused module |

User guides describe current behavior. Design documents distinguish proposed
contracts from implemented APIs. The status page records measured results and
remaining compatibility limits.

## Change discipline

- Give each feature a resource owner, data contract, failure behavior and test gate.
- Keep behavioral fixes, file moves, signing changes and UI redesigns in separate
  reviewable commits.
- Add modules when ownership or testing requires them; avoid a large rewrite or
  microservices introduced solely for architectural patterns.
- Preserve one authoritative observed state per component. Requested settings and
  locally written messages do not establish remote application state.
- Keep one owner per camera, ROS context, process group and USB connection.

## Validation and releases

Run host and documentation checks plus Android JVM tests and lint on ordinary
changes. Keep pinned native SDK builds separate from lightweight checks. A missing
SDK is not a passing integration test. Installed-package tests must exercise the
installed client outside its source checkout. Host boundary checks reject imports
of checkout wrappers and prevent migrated wrappers from accumulating new logic.

Physical qualification covers offline startup, network and USB recovery, runtime
lifetimes and supported upgrade paths. Tests involving actuators require explicit
operator setup; public pull requests must not run unattended against robots.
See [testing](../TESTING.md) and [release requirements](../RELEASING.md).

Keep CLI, APK, protocol, ABI and SDK versions in one compatibility matrix. Releases
need checksums, dependency notices, documented upgrades and an available previous
artifact. Signing keys remain private. Support one maintained release line before
expanding compatibility promises. APT and Play Store distribution follow runtime
and onboarding qualification.
