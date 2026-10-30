# Compatibility, validation and remaining work

Development preview: **0.1.1-dev6**, 2026-10-30. Apache-2.0.
This is a source/development preview, not a broadly qualified robot appliance.
No public APT repository or stable package release is available yet.

## Verified reference paths

| Capability | Evidence and boundary |
| --- | --- |
| Native ROS 2 | Android ARM64 Jazzy publishers/subscribers exchanged real typed data with Linux Jazzy over Wi-Fi and USB networking. |
| Automatic discovery | Real phone and USB-Agent traffic reached Linux ROS peers without explicit discovery peers on the reference LAN. Network multicast policy still matters. |
| Phone-hosted micro-ROS | ESP32 exchanged typed topics through the phone Agent over UDP and CP2102 USB serial. Broader boards/drivers remain unqualified. |
| Workspace execution | Example ordinary C++ workspace cross-built, deployed and ran native processes on Android, with custom messages, parameters and stop/restart checks. |
| Python and launch | Separate Python/rclpy examples and supported launch paths passed reference tests. Arbitrary mixed workspaces and Linux dependencies are not guaranteed. |
| Dashboard | Scalar subscriptions, joystick release/focus-loss zero commands and JPEG/PNG image receipt/recovery passed physical tests. Two-phone camera viewing and reference robot control were also confirmed by the operator. |
| Camera options | Supported 1280×720, quality 80, automatic FPS produced about 29.9 capture/publish FPS in a reference sample; a PC received 20 valid JPEGs. This is not an endurance or latency measurement. |
| Python onboard vision | Two rclpy nodes process phone camera JPEGs and compute following commands locally. Short live run: 161/161 ball detections, 8.05 results/sec, 61.95 ms mean / 92.39 ms p95 processing. Annotated/masked JPEG output verified; physical following and calibrated depth remain pending. |
| Latest regression | Build, lint and 16 unit tests passed; camera-options and Dashboard-image device tests each passed with zero skips. |
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

## Next after the ball-following test

- Add Dashboard subscription/display for standard `sensor_msgs/msg/Image`
  (raw images), alongside current `CompressedImage`. Requested after completing
  this test; not implemented yet. Define supported encodings, bounded decode and
  payload handling, stale/recovery behavior and physical interoperability tests.

## Community-readiness priorities

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
