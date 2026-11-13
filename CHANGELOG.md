# Changelog

Changes below are unreleased source changes, not a published APK/package promise.
Host-client, app, runner protocol and native SDK versions are tracked separately;
see the [release checklist](docs/RELEASING.md).

## Unreleased

- Prebuilt native SDK: `ros2-android setup sdk --archive FILE` installs the release's
  native C++, Python/shell and micro-ROS Agent SDK instead of building it from source
  for hours. It verifies the checksum and that the archive was built from the client's
  pinned inputs, refuses unsafe archive paths, rewrites the build path in text files and
  keeps existing sources. `setup/package-native-sdk.py` makes the archive; the native CI
  workflow now builds the full SDK, packages it, installs it in a fresh home and builds
  a workspace APK with it, and uploads it. Release assembly accepts it with `--sdk`.

- Home's ROS activity card names each running project with its node count, so the
  operator sees what is active without opening Projects.

- The USB reliability test can also run a disposable fixture project on the same
  domain (`robotRunnerPackage`) and requires its telemetry to stay fresh through every
  Agent restart and the soak; it now stops the Agent when finished. Fixture control
  shared by the endurance, runner-update and USB tests lives in one test helper. On the
  reference phone it passes with Wi-Fi off and no PC link (901 of 901 project messages
  through five Agent restarts and a 3-minute soak).

- Runner status (app controls, debug control and `ros2-android project status`) now
  reports the saved profile as `config` and the profile of the current or last run as
  `runConfig`; the debug path previously showed only the last run, which looked like
  an unrestored profile. Endurance and runner-update tests now fail on rejected
  commands and verify that the runner profile is restored.

- Add an operator-guided USB Agent recovery test: unplug, replug, deny and allow USB
  access, with the sensor session surviving and only explicit restarts. It stops at once
  if access is allowed at the deny step and asks again if it is denied at the allow step.
  It passes on the reference phone.

- Link GitHub Sponsors from the README and the repository's Sponsor button.

- Document runner authorization behavior for every signer change, reinstall and
  revocation, and why main-app key rotation requires rebuilding paired runners.

- Verify that updating a running runner while the app is bound fails closed, keeps
  the runner Stopped with its profile and replays nothing (C++ and Python runners);
  the phone qualification batch includes this check.

- Add an opt-in endurance test and resource monitor (memory, CPU, battery, thermal),
  and a phone qualification driver and guide for checking additional phones.

- Fix fresh native SDK builds: `rcl`'s test dependency `test_msgs` was skipped
  rather than ignored, so colcon required an earlier install of it and a new
  machine failed. Add a scripted installed-client journey (wheel to external
  workspace APK, no phone) and run it in the manual native workflow.

- Run Google's Accessibility Test Framework on core screens in light and dark. Fix
  what it found: text fields are now named by their visible labels instead of
  machine identifiers, expandable sections no longer announce their state twice,
  per-project controls name their project, and selectable Home text meets the
  48 dp touch target.

- Qualify Android 15 compatibility on an emulator with ARM translation: UI, native
  ROS session, headless Dashboard peers for both runner runtimes and project
  discovery pass. Add a test runner that grants the app's own notification
  permission on Android 13+, let the project discovery test target any runtime,
  and show the background-activity instruction only when it is still needed.

- Add `workspace ... ros -- <ros2 args>` to observe a deployed workspace from the
  PC with the project middleware and the workspace's host-build message overlay.
  Describe every workspace step and the build→observe journey in `--help`, print
  pause/battery hints with `status`, name the installed commands correctly, and run
  the module CLI from a source checkout instead of failing on a missing bundled SDK.

- Declare and request notification permission (once, at the first session or
  project start) on Android 13+, where foreground-service notifications and their
  Stop actions are otherwise hidden. Add a pre-release security/privacy review and
  document the Wi-Fi micro-ROS Agent's network exposure and disabled DDS Security.

- Ship upstream license texts for every bundled native component: the ROS 2 stack,
  Cyclone DDS, libyaml and the NDK's libc++ in the app and all runners, and the
  previously empty TinyXML-2/android-ifaddrs texts of the micro-ROS Agent. Add
  Settings → About → Open-source licenses and a generated THIRD-PARTY-NOTICES.md
  that a host test keeps in step with the source locks.
- Add release assembly and verification: sign the main APK with an owner-held key
  (passwords from the environment), refuse dirty trees, debuggable or mismatched
  APKs, and write a provenance manifest plus SHA256SUMS; verify checksums, signer,
  identity and the bundled APK inside an optional Debian package.

- Add `ros2-android setup build-tools` (source checkout:
  `setup/prepare-build-tools.py`) to prepare the managed colcon/vcstool venv. On
  hosts that already provide colcon through APT, it adds the missing venv commands
  without replacing host dependencies. The native SDK workflow and setup guides
  use it, and doctor points to it.

- Detect process-wide execution pauses (for example a vendor app freezer) in the
  main app and all runner backends, and report Android's battery policy. Home,
  project cards and runner screens show a concise warning with the pause count and
  longest pause; Details and Settings show whether battery optimization applies.
  Runner status adds an optional `health` object; older runners omit it.
- Make the Dashboard peer qualification require background activity and zero
  reported pauses, add boot-clock fixture timestamps, phase logs and stall
  diagnostics, and add an own-process freezer/scheduler observer for device tests.

- Reject connection-profile changes while a sensor/Dashboard runtime is active.
  Keep runtime peer-recovery tests headless, with per-start telemetry correlation
  and automatic cleanup; UI tests retain their own Activity lifecycle.

- Add hardware-free C++ and Python runtime peers and opt-in phone-local Dashboard
  qualification for late subscribers, lost-peer Stale state, explicit recovery and
  no command replay after runner/Dashboard restarts.

- Package shell connection, phone-mediated ESP32 programming and pinned native-source
  preparation behind the installed CLI, retaining thin source-checkout wrappers.
  Reject unbuilt shell caches, invalid cache IDs and unknown source-fetch options
  before device/network access.

- Add a separate manual native-SDK workflow for locked Jazzy ARM64 sources and
  C++ workspace APK builds; hosted execution and runtime qualification stay separate.

- Validate start domains, namespaces, IPv4 fields and ROS argument bounds before
  invoking build/device tools; keep blank-address offline discovery available.

- Return a CLI error when cooperative stop reaches its deadline with a backend
  still transitioning, or reports a cleanup failure; never force-stop automatically.

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
