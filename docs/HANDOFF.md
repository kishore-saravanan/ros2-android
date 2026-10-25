# ROS2 Android community release handoff

Updated 2026-10-25. Start with AGENTS.md and PROJECT-KNOWLEDGE.md, then inspect
`git status` and `git log`. Work on develop. This document records the handoff;
it does not assert a public release. Google Play work is deferred by the owner.

Build/install scripts now live in `setup/`; viewers and developer commands remain
in `tools/`. Use `./setup/build.sh` and `./setup/run-device.sh`. Current guides
and CLI delegation paths are updated; historical verification paths are unchanged.

The runner connection form now synchronizes accepted PC start settings and
retains them for phone-side restarts; CLI status exposes `config`. Workspace
physical checks passed for PC domain changes and phone restart on domain 0.
See PROJECT-KNOWLEDGE.md for scope and current evidence.

Current namespace: dev.ros2android.android (sensor app) and
dev.ros2android.android.runner.<id> (runners). Main/example workspace migrated
with settings retained. Exit/reconnect old shells. Rebuild other legacy runners.
Native ros2 node list/info now works through direct discovery; Android NumPy and
packaging fixes raise the shell APK to 32.23 MiB. Seven new-package device checks
passed; ROS graph inspection and PC interoperability passed. Camera wasn't
requalified after the new-UID install. See the knowledge base for migration scope.

Optional native CLI milestone: build a workspace with `--with-shell`, deploy,
then `./tools/connect-shell`. Upstream `ros2 run` and package inspection execute
on Android. Two terminals independently ran talker/processor, selective Ctrl+C
worked, and PC probes passed. Initial run/pkg-only shell APK measured 21.65 MiB. This is a debug ADB
shell; group status doesn't list shell processes. Native launch, extra CLI
plugins, disconnect/background supervision and wireless ADB trials are pending.
Read ANDROID-SHELL.md and verification/2026-10-25-native-ros-shell.md.

Current CLI bundle supersedes the earlier run/pkg/node subset: workspace build
includes standard core CLI and native LaunchService by default. --without-shell
opts into minimal C++ mode. See native/ros-shell-packages.lock.json and
ANDROID-SHELL.md. Typed custom-message echo/pub, parameters/services, native
example launch and Python/XML/YAML frontends passed; full APK is 34.09 MiB.
Bag/doctor/security/daemon backends and broad Linux package parity remain pending.

Unified Projects now provides authenticated cross-app start/stop, live state/node
names, saved connection settings and collapsed bounded workspace output logs.
The separate runner APK model is retained. Read PROJECTS.md for signing keys,
older-runner rebuilds, lifecycle and shell-node limitations. The custom icon is
now eight navy dots around an original teal robot, shared by main/runners and visible
on Home; it supersedes the earlier ROS/Android marks. See BRANDING.md and
verification/2026-10-25-unified-project-controls.md for evidence and limits.

Current priority: concise, familiar Android UI/UX and clear community onboarding.
The 0.1.1-dev3 redesign supersedes the earlier text-heavy screens: Home, Sensors,
Projects, Settings; sensor list → detail; extras collapsed; device light/dark
mode with ROS navy. About stays visibly at the bottom of Settings (no dropdown
or separate page), per the latest owner instruction. Read UI-UX.md before edits. Treat usability as
a release requirement, not polish to add after runtime features.

## Product outcome

Make Android phones useful as native ROS 2 robot computers for the robotics
open-source community. Developers edit ordinary ROS packages on a Linux PC,
cross-build and deploy over USB or Wi-Fi, and run computation on the phone.
No Ubuntu installation, root or required Termux. Preserve the owner's phone
privacy and Android security model. Do not claim support for every phone,
Android version or Linux ROS package.

## Checkpoint and evidence

- Native Jazzy/Cyclone DDS sensor app: real IMU and text traffic over USB IP/Wi-Fi.
- Camera: approximately 29.7 received FPS in the recorded USB/Wi-Fi trials.
- Native rclcpp and embedded rclpy runners verified separately.
- Ordinary example: examples/android_test_ws has ws_interfaces, ws_nodes and
  ws_bringup. The same standard C++ sources build for Linux and Android.
- Custom message delivery, computation, node graph and parameter reads passed
  the same probe on both platforms. Child abort detection and group recovery
  passed on the phone. See verification/2026-10-25-colcon-workspace.md.
- 24 tooling tests and the recorded Android build/lint checks passed at this
  milestone. Previous device evidence is not a fresh device test at handoff.
- The workspace builder currently accepts ament_cmake native executables only.
  Python feasibility is not mixed ament_python workspace support.
- Launch is a supported static PC-side adapter, not full on-phone LaunchService.
- PC removal, one-hour operation, another phone, automatic restart and phone-hosted
  micro-ROS Agent remain unqualified or unimplemented.

## Resume commands

Run from the repository root. Read DEVELOPMENT.md for the pinned SDK/NDK and
ROS SDK prerequisites; these commands do not install all prerequisites.

```bash
git status --short
git branch --show-current
./setup/build.sh
./tools/pc-ros.sh -- /usr/bin/python3 -m unittest discover -s tools/tests -v
./tools/ros2android-workspace --workspace examples/android_test_ws inspect
./tools/ros2android-workspace --workspace examples/android_test_ws host-build
./tools/ros2android-workspace --workspace examples/android_test_ws build
./tools/ros2android-workspace --workspace examples/android_test_ws deploy
./tools/ros2android-workspace --workspace examples/android_test_ws start \
  --domain 42 --peer PC_IP --local PHONE_IP
```

Replace addresses with current diagnostics. Full commands, monitoring, stopping
and expected topics are in COLCON-WORKSPACES.md. Build outputs are cached at
~/.cache/ros2-android-workspaces/android_test_ws; source is in examples/android_test_ws.
Do not inspect phone personal data/apps; use only project apps and own artifacts.
Do not change global stay-awake/security settings. Use the Activity keep-screen flag.

## Current implementation milestone: clean UI/UX

The latest 0.1.1-dev3 build passed all 10 physical UI tests, main app build/unit
tests/lint, and all three runner build/lint checks. Light/dark Settings snapshots
confirm the visible About footer. The newly deployed workspace runner passed
real custom-message/computation/graph/parameter checks. See
[latest evidence](verification/2026-10-25-minimal-ui.md). Changes are uncommitted.


Initial pass implemented in 0.1.1-dev2: Overview/Sensors/Connect/Help, expandable
settings, topic/rate summaries, fixed Stop action, setup guidance, saved navigation
and a bounded local camera preview. Battery percentage on /phone/battery_percent
is the first sensor and default onboarding example; IMU remains available. Seven device UI/lifecycle checks passed. See
[UI design and remaining work](UI-UX.md) and
[evidence](verification/2026-10-25-operator-ui.md). Platform Views are retained;
Compose has not been introduced. Installed project discovery/navigation and readable shared runner controls now
work; see PROJECTS.md. Continue with cross-app live-state integration,
TalkBack/keyboard/device qualification and an external first-user onboarding trial.
The first app-local 200% portrait layout and quiet-status checks passed;
GETTING-STARTED.md now supplies the battery-first walkthrough.


Continue designing and implementing the core operator experience before expanding workspace
features. Refine the implemented main app and runner screens using consistent
spacing, typography, colors, controls and loading/error/empty states. Kotlin
remains the platform choice. Views are retained; Compose is an option for a
separately scoped migration, not a prerequisite for this usability milestone.

Current navigation: Home, Sensors, Projects and Settings. Help opens from Settings;
About is visible at its bottom. Keep advanced
ROS/network settings available through progressive disclosure. Home should
make the current session state and primary Start/Stop action obvious. Sensor
configuration must clearly connect selected hardware, topic and publishing state.
Keep configuration across navigation and recreation; UI changes must preserve the
service-owned hardware/runtime lifecycle.

Acceptance criteria:

- A first-time robotics user can follow USB or Wi-Fi setup, understand required
  permissions and verify a real received ROS message with the guide.
- A user can find front/back cameras or available motion sensors, edit a topic,
  start publishing, see measured activity and stop it without searching logs.
- Camera preview and publishing controls show capture/publish state, resolution,
  rate and errors clearly. Do not confuse preview FPS with received ROS FPS.
- Project controls distinguish installed, starting, running, stopped and failed;
  only expose actions actually supported by the backend. Planned features must
  not appear as working controls.
- Permission denial, no peer, unavailable sensor, invalid topic and runner failure
  each have readable feedback and a concrete recovery action.
- Layouts support larger text, readable contrast, accessible labels, appropriate
  touch targets, keyboard-safe forms and consistent back navigation.
- Navigation/recreation do not interrupt an active session. Recheck real camera,
  IMU, text subscriber and runner start/stop behavior after the UI migration.
- Review screens on the allowed foreground project app only; record evidence and
  remaining usability gaps in the knowledge base.

## Following runtime milestone: mixed workspaces

Add standard ament_python packaging and mixed C++/Python launch to the ordinary
workspace backend. Reuse embedded Python work, but preserve console-script entry
points, package resources and generated custom-message imports. Keep native code
packaged in APKs; do not assume writable app directories can execute binaries.

Acceptance: one normal workspace with interface, C++, Python and bringup packages;
the same source tree and launch file run on Linux and Android; typed C++/Python
round trip, parameter/remap behavior, stop, worker failure and restart pass real
ROS probes. Reject unsupported dependencies clearly. Update the compatibility
matrix and knowledge base with actual evidence.

## Community product work

Clean UI/UX is a primary community-alpha gate. Build these flows before treating
additional runtime features as release readiness:

1. Welcome/setup: supported devices, permissions with purpose, USB/Wi-Fi choice,
   connection diagnostics and a visible first successful ROS message (battery percentage by default).
2. Sensors: available hardware list, front/back camera preview, editable topic,
   rate/quality controls and start/stop; explain unavailable capabilities.
3. Projects: installed project and launch selection, arguments, per-node state,
   parameters, bounded logs, actionable errors and explicit recovery controls.
4. Status: distinguish app running, ROS participant active, peer discovered and
   measured message flow; show rates and dropped frames without false success.
5. Help/About: short guided examples, troubleshooting, version/compatibility,
   privacy information, author and support link. Preserve accessible text sizes,
   contrast and clear navigation. Record design decisions before UI migration.

Community alpha acceptance: a new contributor can set up a documented supported
Linux machine, install the app, receive battery percentage, then see IMU/camera topics and deploy the example using
only the guide. Verify from a clean environment, add repeatable CI checks and
publish a tested phone/Android/ROS/transport matrix. No universal support claim.

Before public source distribution: owner chooses a top-level license; audit
upstream licenses/notices and sample Proprietary metadata. Add CONTRIBUTING,
issue templates and a private security-reporting route. Prepare release signing,
checksums and reproducible build instructions. No license was chosen and no
repository visibility, remote publishing or signing credentials were changed here.

## Distribution: community alpha first

Owner decision: skip Google Play for now. Do not spend the current milestone on
Play Console setup, store listings, store-specific testing, billing-policy work
or submission. Revisit distribution requirements only if the owner reopens it.
Android platform compatibility, permissions and native-library correctness still
matter for sideloaded builds.

Prepare versioned community alpha APKs with the Linux PC CLI, reproducible build
instructions, checksums, release notes and a known-limitations/support matrix.
Developers build and deploy their own project-runner APKs from the PC. Choose and
document release signing and upgrades before distributing artifacts; keep keys
outside Git. Publishing artifacts or changing repository visibility remains a
separate owner-authorized action; neither has happened here.

Release order: usable sensor/operator experience and clear first-run guide;
external contributor trial on supported hardware; address usability/reliability
findings; then publish a clearly scoped community alpha once licensing and release
preparation are resolved. Do not require full ROS/Linux equivalence to release,
but state exactly which workspace and launch features are supported.
