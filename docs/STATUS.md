# Compatibility, validation and remaining work

Development preview: **0.1.1-dev6**, status updated 2026-11-11. Apache-2.0.
This is a source/development preview, not a broadly qualified robot appliance.
No public APT repository or stable package release is available yet.

## Verified reference paths

| Capability | Evidence and boundary |
| --- | --- |
| Native ROS 2 | Android ARM64 Jazzy publishers/subscribers exchanged real typed data with Linux Jazzy over Wi-Fi and USB networking. |
| Standalone startup policy | One Kotlin Cyclone configuration is shared by the app, C++/Python/workspace runners and shell. Loopback, interface selection and stale-pin errors pass seven JVM tests; both offline profiles initialize participants with pinned Cyclone on Linux. Fresh Android Wi-Fi-off startup, USB Agent coexistence and reconnect/endurance remain pending. |
| Automatic discovery | Real phone and USB-Agent traffic reached Linux ROS peers without explicit discovery peers on the reference LAN. Network multicast policy still matters. |
| Phone-hosted micro-ROS | ESP32 exchanged typed topics through the phone Agent over UDP and CP2102 USB serial. Broader boards/drivers remain unqualified. |
| Independent runner authorization | Opt-in certificate pinning and local approval/revocation passed on the reference phone using a separate runner key; unauthorized isolated UID got no response. Installed wheel built/deployed an external C++ workspace against the prepared SDK cache. Security/update/rotation/multiple-author and broader device gates remain open. |
| Runner compatibility contract | Host manifest validation, offline project inspection and read-only workspace dependency-provider diagnostics are tested. Additive v1 control envelopes, legacy fallback and capability gating have JVM tests and all runner Kotlin variants compile; Request tickets and connection guards reject stale replies, serialize operator commands and fail closed on timeout; pure-state tests pass. Dedicated physical envelope/recovery/upgrade tests remain pending. |
| Host setup diagnostics | Bundled compatibility matrix and task-scoped doctor checks are tested. Installed-wheel checks cover missing prerequisites and the prepared-host shell profile without SDK extraction or phone access. Full clean-machine build/deploy remains pending. |
| Installed-client packaging | Built wheel passed the verifier in a fresh Python 3.12 venv without pip, offline installation, masked SDK/ROS inputs, help/inspection and archive privacy/hash checks. Python 3.10/3.12 CI definitions added; new hosted runs and full fresh-machine native setup remain pending. |
| Workspace execution | Example ordinary C++ workspace cross-built, deployed and ran native processes on Android, with custom messages, parameters and stop/restart checks. |
| Python and launch | Separate Python/rclpy examples and supported launch paths passed reference tests. Arbitrary mixed workspaces and Linux dependencies are not guaranteed. |
| Dashboard | Scalar subscriptions, joystick release/focus-loss zero commands and JPEG/PNG image receipt/recovery passed physical tests. Two-phone camera viewing and reference robot control were also confirmed by the operator. |
| Camera options | Supported 1280×720, quality 80, automatic FPS produced about 29.9 capture/publish FPS in a reference sample; a PC received 20 valid JPEGs. This is not an endurance or latency measurement. |
| Latest platform regression | Build/lint and 44 JVM unit tests passed. Independent phone/PC Switch ON/OFF, navigation/no restart replay and joystick release/focus-loss tests passed. 69 host tests passed with Jazzy available. Baseline JVM/lint tasks also passed from a clean source export without native-deps. Hosted host/documentation checks passed; the owner confirmed the corrected Android CI now passes. Local tests here include additional inspection/preflight/package regressions not yet pushed. |
| ESP32 programming through phone | ROM-loader application writes passed hash verification and micro-ROS recovery checks. Sustained stub transfers remain unreliable; see the programming guide. |

The reference phone advertises normal capture modes up to 30 FPS. Higher FPS
requests fall back to hardware support. 4K availability is capability-dependent
and has not been verified. Android video-recording support does not establish
support through this YUV/JPEG publishing path.

## Operating limits

- One active camera; bounded capture/encode/decode queues and heap checks.
- Dashboard supports up to four compressed-image widgets, 2 MiB encoded frames,
  16 MP source images and an 8192-pixel per-side guard. Display decoding also
  respects screen dimensions and available heap.
- Non-camera sensor rates retain a 100 Hz configuration ceiling; battery is 1 Hz.
- Android USB permission can be required after reconnects or app updates.
- Workspace runners remain separate installed APKs, even though hidden from the
  app drawer. Developer-started shell processes are outside project supervision.
- Reference MCU firmware includes a 300 ms stale-command timeout. Physical stop
  timing and behavior must be qualified for each robot.

## Optional application evidence

The ball follower is an onboard Python/ROS proof of concept, not the platform's
primary goal. Its operator-confirmed ground demonstration and synthetic/control
probe results are documented in the [example guide](BALL-FOLLOWER.md). The later
adaptive-pulse experiment is not physically qualified. Robot calibration/tuning
must not determine generic platform APIs or readiness.

## Feature backlog

- Add Dashboard subscription/display for standard `sensor_msgs/msg/Image`
  (raw images), alongside current `CompressedImage`. Not implemented yet.
  Define supported encodings, bounded decode and payload handling, stale/recovery behavior and physical interoperability tests.

## Community-readiness priorities

See the [platform roadmap](ROADMAP.md), [test scope](TESTING.md) and
[release checklist](RELEASING.md). [Independent runner authorization](RUNNER-AUTHORIZATION.md)
is an opt-in preview; broader signing/device review is still a release gate. Dashboard local request status
is implemented, but the earlier intermittent missed OFF has no confirmed root
cause; network-loss/late-subscriber fault tests remain open.

1. Reliable USB attach/reconnect and clearer permission/recovery flows.
2. Long-duration, thermal, screen-off and vendor battery-management qualification.
   A short Wi-Fi-off USB trial passed; combined app-task removal and network
   interruption previously produced a data gap. Unattended recovery is not proven.
3. Fresh-machine onboarding, additional phones/Android versions, accessibility
   and light/dark UI checks.
4. Broader workspace/package compatibility, mixed Python/C++ packaging and
   per-node supervision.
5. Sustained camera throughput and end-to-end latency measurements, including
   larger resolutions on capable devices.
6. Stable signed releases and installable host-package distribution after the
   runtime and onboarding gates are met.

See [getting started](GETTING-STARTED.md), [robot operation](ROBOT-OPERATION.md)
and [development](DEVELOPMENT.md). Public status summarizes reproducible results;
local device logs, identifiers, backups and maintainer journals are not shipped.
