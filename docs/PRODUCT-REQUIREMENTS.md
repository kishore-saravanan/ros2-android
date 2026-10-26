# ROS2 Android: robot-computer product requirements

Owner direction, 2026-10-26: Android is the robot's primary onboard computer.
The project is intended for open-source community use, delivered as installable
packages rather than requiring operators to clone a repository and run scripts.
This document sets priorities; it does not mark proposed features implemented.

## Product critique

We have credible component demonstrations: phone sensors, native C++/Python,
ordinary C++ workspaces, native ROS commands/launch, Dashboard controls and a
phone-hosted MCU Agent. The weak point is the complete robot operating contract:
what keeps running without the PC, how failures become visible, how a robot
returns to a known state and how another person reproduces the setup.

Adding more widgets or sensors before closing those gaps increases support cost.
A clean screen cannot substitute for a reliable session. A running process or a
USB byte counter cannot substitute for an application-level message check.

The primary user is a ROS developer with a supported ARM64 Android phone, an
Ubuntu/Jazzy PC and a microcontroller that owns motor timing and command expiry.
The first supported outcome is a modest mobile robot with phone-based computation,
phone camera/IMU, MCU I/O and PC-side development. Universal phone/Linux-package
compatibility, hard real-time Android motor control and turnkey production fleet
management are outside the initial commitment.

## Responsibility boundaries

| Layer | Responsibility |
| --- | --- |
| PC development package | Inspect compatibility, cross-build, package, deploy, select an authorized device, shell, logs and diagnostics |
| Android operator app | Hardware permissions, concise status, session controls, installed workspaces and Dashboard |
| Workspace runner | Packaged native code, ROS launch/CLI, user-node execution, exit reporting and logs |
| Runtime SDK | Versioned NDK/ROS/Python dependencies, ABI and checksummed artifacts |
| MCU firmware | Deterministic motor/I/O control, command timeout, safe output state and hardware feedback |

Android is the main application computer. The MCU remains necessary where robot
hardware requires deterministic timing or safe outputs when the phone fails.
A Dashboard Stop button is not a certified emergency stop.

## Required robot journey

1. Install the host client and Android app. Diagnose missing prerequisites without
   changing the system. Select a phone explicitly when more than one is attached.
2. Inspect a normal ROS workspace. Report supported packages, unsupported build
   types/dependencies and the selected ROS/Android ABI before a long build.
3. Build/deploy using one documented command family. Retain build provenance and
   the previous known-working artifact. Never modify the source workspace to build.
4. Start a robot configuration: workspace/launch, connection profile, selected
   sensors and MCU transport. Show which parts are ready, waiting or failed.
5. Remove the development PC. The phone and MCU continue real computation and
   communication; Wi-Fi/internet are not required for the USB MCU path itself.
6. Observe fresh health, troubleshoot a failed part, stop deliberately and recover
   without reflashing everything or losing saved settings.

## Prioritized requirements and acceptance

| Priority | Requirement | Acceptance evidence |
| --- | --- | --- |
| P0 | Installable host client | Wheel installs into an isolated environment; help and external workspace inspection work outside the checkout; no private/generated files in artifact |
| P0 | Reproducible runtime setup | Pinned dependencies, actionable diagnostics and version/ABI checks; a new machine follows the guide without maintainer intervention |
| P0 | PC-independent operation | Actual node output and MCU application acknowledgements continue for a recorded interval with PC disconnected; reconnect observes fresh results |
| P0 | Failure and recovery | Process exit, Agent restart and cable removal produce distinct states; repeated recovery requires fresh typed traffic, without duplicate endpoints or leaked processes |
| P0 | Safe motion contract | Reference motor firmware expires commands locally; lost phone/Agent/network cannot leave motion latched; measured stop latency documented |
| P0 | Supported configuration matrix | Phone/Android/ABI, ROS version, package features and USB adapters listed as tested, unsupported or unqualified |
| P0 | Concise operator UX | Existing five tabs; Home shows robot-relevant state, actionable failures and readiness; diagnostics collapsed; no setup essays on primary screens |
| P1 | Mixed ordinary workspaces | ament_cmake + ament_python + interfaces + bringup, same sources/launch on Linux/Android; parameters/remaps, faults and restart verified |
| P1 | Robot session profile | Explicitly associate launch, sensors, Agent and ROS profile; coordinated start/stop with partial-failure visibility; preserve independent debug controls |
| P1 | Resource health | Thermal/battery/memory and stale-data reporting with bounded logs; one-hour runtime and second-phone trials |
| P1 | Rollback/update | Preserve prior artifact and config; reject incompatible SDK/runner versions; recover from failed update |
| P2 | Additional robot interfaces | Services/actions UX, calibrated camera information, more USB adapters and audio only against concrete robot use cases |

The LED switch proves typed command transport only; the current clone's LED wiring
is unknown and its physical output has not been verified. It does not qualify a
motor driver or a robot stop mechanism.

## Delivery strategy

Use three deliverables: Android APK, Python host CLI wheel and a versioned native
SDK. The CLI's public command is `ros2-android`; pipx is the intended isolated
installation path. ROS package sources remain ordinary colcon workspaces. A ROS
package/deb can later wrap distribution if community demand warrants it; it does
not remove Android ABI/cross-compilation requirements.

The first local alpha wheel bundles existing tooling/templates and runs them in
an artifact-keyed user cache. This is an incremental packaging boundary, not a
rewrite or a prebuilt SDK. Users run installed commands from their own workspace.
Next separate the build SDK into checksummed release artifacts and replace
repository-relative error hints with installed-command guidance. Do not silently
download unpinned code or assume Linux apt packages work on Android.

No public package-name reservation, PyPI upload, GitHub release or Play submission
has been performed. Owner selected Apache-2.0 on 2026-10-26. Before public distribution:
review bundled third-party licenses/notices, signing, dependency provenance,
security reporting and contribution policy. Public source visibility alone does
not establish open-source licensing. LICENSE, NOTICE and CITATION.cff record the
selected project terms and requested citation; upstream terms remain independent.

## Ordered implementation milestones

1. **Host package foundation (current):** installed command, packaged backends,
   cache isolation and artifact smoke test; document exact limits.
2. **Reliability reference:** repeated Agent restarts/unplug/replug, stale-session
   diagnosis, PC-disconnection trial, bounded logs and reference MCU timeout test.
   Start with observable failures; add automatic restart only after policy is clear.
3. **Ordinary mixed workspace:** preserve standard console entry points/resources,
   dependencies and upstream launch; maintain Linux/Android parity probes.
4. **Robot session:** unify operator readiness/start/stop without coupling unrelated
   debug lifetimes. No large UI redesign before the session model is implemented.
5. **Community alpha:** clean-machine walkthrough, second phone, endurance,
   accessibility, license/notices and versioned downloadable artifacts.

For every milestone update PROJECT-KNOWLEDGE.md with implementation, exact checks,
failures, remaining gates and commit. Keep physical qualification separate from
unit/packaging tests. Avoid expanding this into a generic dashboard designer,
cloud service, on-phone IDE or container platform.

## Platform constraints informing this plan

Android restricts background foreground-service starts and camera/microphone
startup. Consequently boot-start and screen-off operation must be qualified per
capability/device rather than promised universally. See
[Android foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
and [service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

The installable CLI approach follows the Python Packaging Authority's
[command-line tool guide](https://packaging.python.org/en/latest/guides/creating-command-line-tools/).
