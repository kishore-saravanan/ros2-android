# ROS2 Android community release handoff

Updated 2026-10-25. Start with AGENTS.md and PROJECT-KNOWLEDGE.md, then inspect
`git status` and `git log`. Work on develop. This document records the handoff;
it does not assert a public release or Google Play approval.

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
./tools/build.sh
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

## Next implementation milestone

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

Ship a coherent first-run experience alongside runtime expansion:

1. Welcome/setup: supported devices, permissions with purpose, USB/Wi-Fi choice,
   connection diagnostics and a visible first successful ROS message.
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
Linux machine, install the app, see IMU/camera topics and deploy the example using
only the guide. Verify from a clean environment, add repeatable CI checks and
publish a tested phone/Android/ROS/transport matrix. No universal support claim.

Before public source distribution: owner chooses a top-level license; audit
upstream licenses/notices and sample Proprietary metadata. Add CONTRIBUTING,
issue templates and a private security-reporting route. Prepare release signing,
checksums and reproducible build instructions. No license was chosen and no
repository visibility, remote publishing or signing credentials were changed here.

## Distribution and Google Play assessment

Recommendation: documented community alpha APKs and PC CLI first; prepare the
main sensor/operator app for Play testing separately. Developers continue building
and deploying their own project-runner APKs from the PC. This is a proposed
product split, not a guarantee of Play approval. Do not turn the store app into
a downloader of native ROS binaries without a new policy assessment.

Checked 2026-10-25 against official sources:

- Current new-app submission requirement is target API 36; app presently targets
  35. Upgrade and test target-related behavior across main and runner modules.
  https://developer.android.com/google/play/requirements/target-sdk
- Native apps must satisfy applicable 16 KB page-size requirements. Build flags
  alone are insufficient: inspect every shipped native library and test on a
  16 KB environment, including Python dependencies if shipped.
  https://developer.android.com/guide/practices/page-sizes
- Play prohibits app self-updates outside Play and downloading executable native
  code from outside Play; interpreted code has qualified exceptions, not blanket
  permission for arbitrary runtime deployment.
  https://support.google.com/googleplay/android-developer/answer/16559646?hl=en
- Personal developer accounts created after 2023-11-13 require at least 12 opted-in
  closed testers continuously for 14 days before applying for production access.
  This is a minimum testing gate, not an approval timeline.
  https://support.google.com/googleplay/android-developer/answer/14151465?hl=en

Remaining Play preparation: release AAB/signing, current target/foreground-service
and permission behavior, privacy policy and accurate Data safety declarations,
store listing, account verification and testing/review. Audit the Sponsors link
against applicable payments rules before submission. Full workspace portability
and MCU support need not block a clearly scoped sensor/operator release.

A near-term testing release is plausible after these gates; a production date
cannot be promised from the current single-device development build. Recheck
policies at submission. No Play Console or GitHub release was created.
