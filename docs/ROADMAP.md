# Platform roadmap

ROS2 Android's goal is a maintained onboard ROS computer platform, independent
of a particular robot. This is an ordered roadmap, not a release-date promise.
The ball follower remains an optional proof of concept; further robot tuning is
outside the current platform milestone.

| Priority | Deliverable | Acceptance evidence |
| --- | --- | --- |
| Now | Contributor-ready repository and baseline CI | Clear entry points, reproducible host/JVM checks, public design/test/release boundaries |
| Next | Lifecycle, configuration and communication reliability | Fresh/stale state, explicit stop scopes, permission/network/USB/process recovery tests |
| Next | Runner trust for independently built workspaces | Official app can pair with/revoke a user-built runner without sharing release secrets; unauthorized calls rejected |
| Then | Stable workspace/runtime contracts | Versioned manifest and IPC compatibility checks; documented dependency subset and actionable failures |
| Then | External developer journey | Fresh Linux setup and external workspace inspect/build/deploy/run/stop without editing platform source |
| Then | Incremental modularization | Shared runner lifecycle helpers, narrow session/controllers and reusable host backends with regression coverage |
| Then | Example separation | External examples checkout consumes pinned platform artifacts; history/license and working baseline preserved |
| Release gate | Qualified community alpha | Additional phone, sustained operation/resource tests, reproducible signed artifacts and rollback |

Feature backlog includes raw `sensor_msgs/msg/Image` Dashboard viewing, broader
message/service/action interaction, supported workspace dependencies, more USB
adapters and camera performance work. Each needs an explicit user journey,
bounded resource behavior and interoperability tests. They do not displace the
runtime and onboarding gates. APT and Play Store publication remain deferred.

Baseline CI cannot establish physical reliability. Keep the current evidence and
remaining limitations in [Status](STATUS.md). Detailed work sequencing and design
rationale are in the [maintenance plan](design/MAINTENANCE-PLAN.md).

Current progress (2026-11-01): contributor docs/CI are committed; the independent
runner authorization preview and installed-client external C++ workspace path
have reference-device evidence. Review/update/rotation/multiple-author/API gates
remain open. Next implement versioned manifest/management compatibility checks
and improve dependency/failure diagnostics before extracting robot examples.

Progress (2026-11-11): host source-manifest validation and offline project
inspection are implemented with contract regression tests. Management-message
negotiation, visible incompatible-runner discovery and target dependency reports
remain open; this is not completion of the workspace/runtime contract gate.
