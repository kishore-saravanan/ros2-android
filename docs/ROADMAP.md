# Platform roadmap

ROS2 Android turns supported Android phones into onboard ROS 2 computers.
This roadmap prioritizes a dependable platform and a clear developer experience.
It is ordered by exit gates, not promised release dates. Updated 2026-11-12.

## Foundation available today

These are development-preview capabilities with reference-device evidence, not
universal compatibility guarantees. See [Status](STATUS.md) for the boundaries.

- Native Jazzy ROS traffic between Android and Linux over Wi-Fi/USB networking.
- Phone sensor publishing, camera controls and compressed-image transport.
- Configurable Dashboard publishers and subscribers, including joystick and images.
- C++ workspace cross-build/deploy/run and separate embedded Python examples.
- Phone-hosted micro-ROS Agent with reference ESP32 USB/UDP interoperability.
- Independent runner authorization preview and installed-client external C++ build.
- Contributor docs, architecture decisions, host/JVM tests and baseline CI workflow.
- Host manifest validation and a version/capability envelope for runner controls;
  current-service rejection tests pass, with the wider compatibility matrix open.

## 1. Make core operation dependable — current focus

**Outcome:** explainable state and recovery, with phone-local work independent of
a PC or an external network.

- [ ] Qualify fresh startup without Wi-Fi across runner backends and USB Agent.
      Battery/Dashboard and workspace Live/Stale checks have operator confirmation;
      a physical C++ workspace result/stop/restart test also passes. Other backends
      and USB coexistence remain open.
- [x] Share phone-local Cyclone startup settings across app, runners and shell; reject stale pins with recovery guidance.
- [ ] Qualify network changes and reconnects consistently on devices.
- [ ] Qualify USB detach/reconnect, permission denial and app-update recovery.
- [ ] Exercise screen-off, task removal, process death and vendor power management.
      A five-minute Wi-Fi-off C++ workspace background trial passes on the reference
      phone with background activity allowed for both apps. A separate 60-second
      screen-off trial also passes while USB-connected to the PC. Own-task removal,
      native-child exit, runner-process death and runner Force stop tests pass for
      the C++ fixture.
      Two-phase main-app SIGKILL and Android Force stop tests also pass with outside process observations,
      saved settings and fresh ROS recovery. Vendor Recents swipes,
      unplugged idle, other devices/backends and endurance remain open.
- [ ] Test Dashboard lost-link/late-subscriber behavior and distinguish local writes
      from remote acknowledgement.
- [x] Isolate control requests and reject stale replies; unit-test timeout/reconnect rules.
- [x] Prepare an opt-in stopped-fixture test for legacy requests and version rejection without state changes.
- [x] Qualify current-service legacy/versioned status and invalid request rejection without state changes.
- [ ] Complete old-runner, delayed-reply, service-death and update/reconnect qualification.
      Current runner-process SIGKILL invalidates controls and reconnects to Stopped;
      the broader compatibility/update matrix remains open.
- [ ] Review independent signing, update/reinstall, revocation and key rotation.

**Exit gate:** repeatable fault/recovery matrix with independent ROS traffic
observations, preserved configuration and clear stop scopes. No UI status label
alone counts as proof of a working robot.

## 2. Make an external workspace easy to use

**Outcome:** a developer builds their own supported ROS workspace without editing
platform source or relying on this maintainer's prepared PC.

- [x] Inspect declared dependency providers and unsupported package types.
- [x] Report target dependency conditions, declared provider versions and known
      unsupported launch syntax without executing workspace code. Conditions use
      the optional upstream evaluator; unknown context remains unresolved. Dynamic
      launch behavior and full dependency resolution remain build-time concerns.
- [x] Define a bundled compatibility matrix tying CLI, APK, protocol, ABI and SDK locks.
- [x] Add task-scoped preflight checks and document clean-machine setup order.
- [x] Define isolated installed-wheel CI for Python 3.10/3.12; hosted runs pending.
- [ ] Validate the installed-client journey on a clean Linux machine.
- [ ] Refine inspect → build → deploy → start → observe → stop errors and guides.
- [x] Document signed runner upgrades, rollback limits and development-key ownership.
- [x] Compare local APK identity/signature/version before installation without contacting a phone.
- [ ] Qualify actual same-key upgrades, profile preservation and rollback on devices.
      A reference C++ runner code-1 → code-2 update preserves its profile and passes
      fresh ROS stop/restart checks; a rejected downgrade preserves code 2/data.
      Successful rollback, other backends and broader signing cases remain open.

**Exit gate:** a fresh-machine user completes the external-workspace walkthrough
using documented artifacts and prerequisites; an independent peer observes ROS
messages and lifecycle behavior.

## 3. Keep the codebase maintainable

**Outcome:** contributors can change one feature without understanding every
sensor, Dashboard widget and runtime backend.

- [ ] Extract focused controllers from the main Activity/session service incrementally.
      Project discovery and UI-scoped bindings now have a focused owner; session
      and other screen responsibilities remain to be separated.
- [x] Share runner lifecycle/configuration contracts across the three backends.
      One foreground supervisor consumes a compiler-checked backend interface and
      validated project configuration; backend isolation and qualification still differ.
- [ ] Move host implementation into the Python package behind one CLI family.
      Diagnostics, APK checks, workspace/project orchestration, manifest validation
      bounded launch validation, shell entry and phone-mediated MCU programming are
      packaged. Pinned native-source preparation is packaged too; native compiler,
      staging and platform-specific installation adapters remain SDK scripts.
- [x] Guard installed host-module boundaries and expand package/artifact checks in CI.
      Thin compatibility wrappers and package imports have host regressions; Android
      feature-module extraction remains incremental.
- [x] Add native SDK build automation separately from lightweight PR checks.
      A manual hosted workflow builds locked ARM64 sources and a C++ workspace APK;
      its first hosted execution remains pending.

**Exit gate:** focused modules with clear owners, unchanged supported journeys and
regression coverage. Keep the modular app and isolated runners; no microservices
or large rewrite solely for architectural patterns.

## 4. Separate applications from the platform

**Outcome:** robot demos demonstrate the public SDK instead of defining it.

- [ ] Create the companion examples repository after its SDK consumption path works.
      A local companion preserves application history and builds with the installed
      pinned client/SDK. Generated-image tests and standalone USB firmware build
      pass. Public repository publication and separate deployment remain pending.
- [ ] Move ball following and robot-specific firmware/configuration with history,
      licensing, citation and a recoverable working baseline.
- [ ] Keep minimal compatibility fixtures in this platform repository.
- [x] Add reviewed, compressed maintainer-provided robot demo media.
- [ ] Validate a reproducible companion-example walkthrough.

**Exit gate:** a separate examples checkout builds/deploys against a pinned platform
package/SDK without editing the platform checkout. No mandatory submodule.

## 5. Qualify and ship a community alpha

**Outcome:** a documented, installable release with honest support boundaries.

- [ ] Test an additional phone/Android version and publish the compatibility matrix.
- [ ] Measure sustained operation, memory, thermal behavior and camera latency.
- [ ] Check portrait/landscape, light/dark mode and accessibility on core journeys.
- [ ] Produce signed, checksummed APK/client/SDK artifacts and dependency notices.
- [ ] Verify installation, upgrade and rollback from clean environments.
- [ ] Complete public-history/privacy and security review before wider publication.

**Exit gate:** the [release checklist](RELEASING.md) passes with linked evidence;
previous artifacts remain available for rollback. Local wheel/Debian alpha builds
are foundations, not a currently available public installer.

## Later, driven by user needs

- Raw `sensor_msgs/msg/Image` Dashboard subscription alongside CompressedImage.
- Broader standard/custom messages, services and actions UX.
- Mixed Python/C++ workspaces and additional Android-compatible dependencies.
- More USB adapters, boards and measured camera-performance improvements.
- Per-node supervision and carefully scoped restart policies.
- Public APT distribution; Play Store evaluation after the core release gates.

Each extension needs a user journey, resource bounds, failure behavior and
interoperability tests. We do not promise every Linux ROS package, every Android
phone or hard real-time scheduling.

See the [high-level design](design/HIGH-LEVEL-DESIGN.md),
[maintenance plan](design/MAINTENANCE-PLAN.md), [test scope](TESTING.md) and
[changelog](../CHANGELOG.md). The ball follower remains an optional application;
calibrating a particular robot is not a platform milestone.
