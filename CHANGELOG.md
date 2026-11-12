# Changelog

Changes below are unreleased source changes, not a published APK/package promise.
Host-client, app, runner protocol and native SDK versions are tracked separately;
see the [release checklist](docs/RELEASING.md).

## Unreleased

- Make the shared runner execution/configuration boundary compiler-checked across
  factory C++, embedded Python and workspace backends, retaining service-owned
  lifecycle and existing execution models.

- Package project orchestration, bounded launch validation and controller identity
  inspection. Bundle matching helper sources for system-Python launch compilation;
  retain existing command entry points as compatibility wrappers.

- Canonicalize SDK snapshot archive metadata and package only tracked Python
  sources; exclude untracked modules and reject tracked source symlinks.

- Extend read-only workspace inspection with optional upstream target-condition
  evaluation, declared provider-version checks and bounded launch syntax warnings.
  Preserve unresolved context instead of silently accepting or dropping it.

- Extract UI-scoped project discovery/bindings into `ProjectConnections`; keep
  runner execution independent of screen lifetime. Guard packaged host-module
  boundaries and compatibility wrappers in the host checks.

- Share validated runner version properties across all three backends. Qualify
  a same-key C++ runner update, profile preservation and rejected downgrade.

- Qualify explicit main-app Android Force stop separately from SIGKILL, with
  outside workspace process observations, saved settings and fresh ROS recovery.

- Package workspace orchestration and manifest validation behind both CLI entry points.
  Reject invalid build options and unbuilt workspace operations without cache writes.

- Add a two-phase main-app process-death test with PC-side process observations,
  saved connection/Dashboard settings checks and verified ROS recovery. Its prepare
  phase deliberately ends instrumentation; only the full integration result passes.

- Add disposable C++ workspace qualification for own-task removal, native-child
  failure and abrupt runner death, including profile preservation and explicit
  restart with verified ROS results. Failure hooks are debug-only, DUMP-protected
  and restricted to the protocol fixture; release APKs exclude them.

- Add app-specific Android settings shortcuts for main-app and runner background
  operation. Keep battery-policy changes user-controlled. Extend offline workspace
  qualification with longer trials and frozen-observer detection.
- Add an opt-in manual screen-off workspace test. Reference-device checks cover
  five-minute background operation and one-minute screen-off reception/recovery
  with app-specific background activity enabled.

- Add a reviewed, compressed ball-following showcase and real-frame cover to the
  README, with source/edit provenance and recorded-demo qualification limits.
  Embed a GitHub video attachment and distinguish the remote Android phone from
  onboard perception/control.

- Add read-only `apk inspect` and `apk compare` host commands using verified
  signatures, package/version metadata and stable file hashes. Compare same-key
  non-downgrade candidates without phone access, SDK extraction or installation.
  Document signing-key ownership, deliberate upgrades and rollback limits.

- Share phone-local Cyclone network configuration across the main app, all runner
  backends and workspace shell. Preserve loopback discovery with no external IPv4,
  reject stale address pins with recovery steps, and document offline startup.
  Add seven policy regressions and a compiled opt-in stopped-runner protocol test;
  physical offline/recovery qualification remains pending.

- Isolate runner replies by request and connection, including legacy services.
  Prevent polling from postponing command deadlines or overwriting command state.
  Disconnect on timeout/invalid reply and require Refresh without command replay.
  Six pure request-state regressions pass; physical recovery tests remain pending.

- Add isolated installed-wheel CI definitions for Python 3.10/3.12. Verify offline
  installation in a fresh venv without pip, masked SDK/ROS inputs, diagnostics,
  command help and read-only external inspection. Check wheel/SDK legal, version,
  hash and private-input gates and emit an optional path-free result report.

- Bundle a compatibility matrix tied to source/build contracts and pinned input
  locks; stamp built wheels with their tooling snapshot hash. Add task-scoped
  `doctor` profiles with version checks and next actions, and document the setup
  order for another Linux machine. Full clean-machine build/deploy remains pending.

- Add read-only workspace inspection of declared dependency providers across
  workspace sources, selected Android SDK indexes and Linux ROS indexes. Report
  unsupported package types, unresolved groups, conditions and version constraints
  without treating a manifest scan as proof of Android compatibility.

- Fix Android CI bootstrap by resolving SDK Manager from the Android SDK root
  rather than assuming it is on PATH. Clean-source JVM/lint checks pass locally;
  hosted verification of the corrected workflow remains pending.

- Refresh the community README with the custom logo, real app screenshots,
  a system overview and a staged roadmap with explicit acceptance gates.
  Document media provenance and leave room for maintainer-provided demo videos.

- Add an additive runner-control version/capability envelope with strict rejection
  before state changes, legacy v1 compatibility and capability-gated controls.
  Show incompatible protocol metadata and binding timeouts instead of silently
  hiding a runner or waiting indefinitely. Physical envelope qualification pending.

- Validate source manifest/runtime compatibility before host build or device
  commands; add offline `project inspect` and actionable missing-build errors.
  Document the source/ABI/management contracts and remaining IPC negotiation gate.

- Add opt-in independently signed runner controls: pin the verified controller
  APK public certificate at build time and approve/revoke each runner locally.
  Keep legacy signature controls; authenticate requests/replies by actual UID
  and current certificate. This remains a preview pending security/device gates.
- Verify an installed client building/deploying an external C++ workspace using
  the prepared SDK cache; fix controller binding lifetime across Activity recreation.

- Document platform architecture, ownership boundaries and staged maintenance.
- Make the README, contributor guides and roadmap platform-focused; keep robot
  applications as optional proof-of-concept examples pending external extraction.
- Add baseline host/documentation and Android JVM/lint CI. Hosted execution pending.
- Show Dashboard switch requests as queued, written locally or inactive, with
  remote state explicitly unknown. Guard command completion and restart replay;
  add queue and independent phone/PC regression coverage.

The existing development preview is described in [Status](docs/STATUS.md).
Earlier development history remains in Git; it has not been reconstructed into
unverified release notes. There is no stable release yet.
