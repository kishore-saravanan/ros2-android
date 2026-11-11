# Platform roadmap

ROS2 Android turns supported Android phones into onboard ROS 2 computers.
This roadmap prioritizes a dependable platform and a clear developer experience.
It is ordered by exit gates, not promised release dates. Updated 2026-11-11.

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
  envelope device qualification remains pending.

## 1. Make core operation dependable — current focus

**Outcome:** explainable state and recovery, with phone-local work independent of
a PC or an external network.

- [ ] Qualify fresh startup without Wi-Fi, including phone-local nodes and USB Agent.
- [ ] Handle stale interface settings, network changes and reconnects consistently.
- [ ] Qualify USB detach/reconnect, permission denial and app-update recovery.
- [ ] Exercise screen-off, task removal, process death and vendor power management.
- [ ] Test Dashboard lost-link/late-subscriber behavior and distinguish local writes
      from remote acknowledgement.
- [ ] Qualify protocol envelopes against updated, legacy and incompatible runners.
- [ ] Review independent signing, update/reinstall, revocation and key rotation.

**Exit gate:** repeatable fault/recovery matrix with independent ROS traffic
observations, preserved configuration and clear stop scopes. No UI status label
alone counts as proof of a working robot.

## 2. Make an external workspace easy to use

**Outcome:** a developer builds their own supported ROS workspace without editing
platform source or relying on this maintainer's prepared PC.

- [x] Inspect declared dependency providers and unsupported package types.
- [ ] Resolve dependency/version conditions and report unsupported launch features.
- [x] Define a bundled compatibility matrix tying CLI, APK, protocol, ABI and SDK locks.
- [x] Add task-scoped preflight checks and document clean-machine setup order.
- [x] Define isolated installed-wheel CI for Python 3.10/3.12; hosted runs pending.
- [ ] Validate the installed-client journey on a clean Linux machine.
- [ ] Refine inspect → build → deploy → start → observe → stop errors and guides.
- [ ] Document signed runner upgrades, rollback and development-key ownership.

**Exit gate:** a fresh-machine user completes the external-workspace walkthrough
using documented artifacts and prerequisites; an independent peer observes ROS
messages and lifecycle behavior.

## 3. Keep the codebase maintainable

**Outcome:** contributors can change one feature without understanding every
sensor, Dashboard widget and runtime backend.

- [ ] Extract focused controllers from the main Activity/session service incrementally.
- [ ] Share runner lifecycle/configuration contracts across the three backends.
- [ ] Move host implementation into the Python package behind one CLI family.
- [ ] Test module boundaries and expand package/artifact checks in CI.
- [ ] Add native SDK build automation separately from lightweight PR checks.

**Exit gate:** focused modules with clear owners, unchanged supported journeys and
regression coverage. Keep the modular app and isolated runners; no microservices
or large rewrite solely for architectural patterns.

## 4. Separate applications from the platform

**Outcome:** robot demos demonstrate the public SDK instead of defining it.

- [ ] Create the companion examples repository after its SDK consumption path works.
- [ ] Move ball following and robot-specific firmware/configuration with history,
      licensing, citation and a recoverable working baseline.
- [ ] Keep minimal compatibility fixtures in this platform repository.
- [ ] Add the maintainer-provided demo videos and a reproducible example walkthrough.

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
