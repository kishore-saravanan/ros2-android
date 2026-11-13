# Compatibility, validation and remaining work

Beta: **1.0.0-beta.1**, status updated 2026-11-13. Apache-2.0.
This is a beta, not a broadly qualified robot appliance.
No public APT repository or stable package release is available yet.

## Verified reference paths

| Capability | Evidence and boundary |
| --- | --- |
| Native ROS 2 | Android ARM64 Jazzy publishers/subscribers exchanged real typed data with Linux Jazzy over Wi-Fi and USB networking. |
| Standalone startup policy | One Kotlin Cyclone configuration is shared by the app, C++/Python/workspace runners and shell. Loopback, interface selection and stale-pin errors pass seven JVM tests; both offline profiles initialize participants with pinned Cyclone on Linux. Operator tests confirmed Wi-Fi-off Battery/Dashboard reception and workspace Live/Stale behavior after clearing saved interface pins. A physical C++ workspace test also verified Wi-Fi-off processed results, stop/Stale and restart/fresh recovery through the main Dashboard service. A five-minute Activity-background trial passed with Wi-Fi off and background activity allowed for both main app and runner: 1,497 verified results, stop/Stale and restart/fresh recovery within the test thresholds. A separate 60-second screen-off trial passed with 301 verified results and recovery after unlock while USB-connected to the PC. Default vendor policy previously froze native children. Separate factory-C++ and embedded-Python fixtures also pass fresh offline start, real Dashboard traffic, stop/restart and late-subscriber/no-replay checks. With Wi-Fi off and no PC link, a USB micro-ROS Agent and a C++ fixture project also ran together: fresh ESP32 replies through five Agent restarts and a 3-minute soak while all 901 project messages arrived. Unplugged idle, screen-off USB and longer USB runs remain open; see runtime recovery below for bounded interruption checks. |
| Runtime recovery | Reference C++ fixture passed 30 seconds after removing the main app's own task, plus child SIGKILL, runner SIGKILL and runner Android Force stop recovery tests. Child failure stopped its sibling; runner death invalidated controls and left no workspace children. Profiles survived and fresh results returned after explicit restart. Task removal kept instrumentation/observer alive. Separate two-phase main-app SIGKILL and Android Force stop tests passed: the PC observed the main app absent for 15 seconds with unchanged workspace processes, then fresh ROS data, saved settings and explicit stop/restart recovery passed in a new main process. This does not measure reception while its subscriber is absent. Vendor Recents swipes, other backends and automatic recovery remain unqualified. |
| Automatic discovery | Real phone and USB-Agent traffic reached Linux ROS peers without explicit discovery peers on the reference LAN. Network multicast policy still matters. |
| Phone-hosted micro-ROS | ESP32 exchanged typed topics through the phone Agent over UDP and CP2102 USB serial. Broader boards/drivers remain unqualified. |
| Independent runner authorization | Opt-in certificate pinning and local approval/revocation passed on the reference phone using a separate runner key; unauthorized isolated UID got no response. Installed wheel built/deployed an external C++ workspace against the prepared SDK cache. Security/update/rotation/multiple-author and broader device gates remain open. |
| Runner compatibility contract | Host manifest validation, offline project inspection and read-only workspace dependency-provider diagnostics are tested, including optional target-condition evaluation, declared version checks and bounded syntax-only launch diagnostics. These are advisory, not a full dependency solver or launch portability verdict. Additive v1 control envelopes, legacy fallback and capability gating have JVM tests and all runner Kotlin variants compile; Request tickets and connection guards reject stale replies, serialize operator commands and fail closed on timeout; pure-state tests pass. The current-service fixture passed physical legacy/versioned status and malformed/unsupported request rejection without configuration changes. Runner UI start/stop and Activity-recreation reconnection also passed. Runner-process death invalidation/reconnection passed with the disposable fixture. Old-service, delayed replies, updates and signing-upgrade matrices remain pending. |
| Host setup diagnostics | Bundled compatibility matrix and task-scoped doctor checks are tested. Installed-wheel checks cover missing prerequisites and the prepared-host shell profile without SDK extraction or phone access. `setup build-tools` prepares the managed colcon venv, including hosts with APT colcon (simulated host-package and pip-less cases tested). A scripted installed-client journey passes from an empty HOME, Gradle home and Android SDK on the reference OS through the native SDK build and an external workspace APK; it found and fixed a step that depended on an older native install. The hosted native workflow runs the same journey and passes on a clean Ubuntu 24.04 runner. Deploying to a phone from such a machine remains pending. |
| APK upgrade preflight | Host CLI verifies local project APK metadata/current signature and compares package, numeric version and signer. File checks never install or inspect a phone; a reference C++ runner same-key code-1 → code-2 update preserves its profile and passes fresh offline ROS lifecycle checks. Android rejects the older APK while preserving code 2/data. Other update pairs, signer migration and successful rollback remain unqualified. |
| Installed-client packaging | Built wheel passed the verifier in a fresh Python 3.12 venv without pip, offline installation, masked SDK/ROS inputs, help/inspection and archive privacy/hash checks. The installed client also resolves a supported C++ launch plan and cross-builds the C++ fixture with a prepared SDK; the separate companion Python app builds through the client. These reuse prepared inputs. Python 3.10/3.12 CI definitions exist; new hosted runs and full fresh-machine native setup remain pending. |
| Release assembly and notices | `setup/assemble-release.py` signs the main APK with an owner-held key, refuses dirty trees, debuggable or version-mismatched APKs and APKs missing license texts, and writes a provenance manifest and SHA256SUMS; `setup/verify-release.py` re-checks them. An unsigned dry run of the current release APK and wheel assembled and verified, and tampering was detected. A throwaway-key run from a clean commit signed the APK (v2/v3), built the Debian package from it and passed the full verifier, including the bundled-APK check; that key was deleted. APKs now include license texts for all 39 staged components. The owner holds a release key (public certificate in SECURITY.md). The full native-SDK build, archive packaging and fresh-home install/build journey pass on hosted Ubuntu at d24cc4a; that artifact predates the beta version. Beta-version artifacts and the actual signed public release remain pending; the inventory is not legal review. |
| Workspace execution | Example ordinary C++ workspace cross-built, deployed and ran native processes on Android, with custom messages, parameters and stop/restart checks. |
| Python and launch | Separate Python/rclpy examples and supported launch paths passed reference tests. Arbitrary mixed workspaces and Linux dependencies are not guaranteed. |
| Dashboard | Separate C++ and Python peers pass Wi-Fi-off late-subscriber, lost-peer/Stale, explicit recovery and no-replay tests. Headless runs (no Activity) of the C++ factory peer pass six consecutive strict trials once background activity is allowed for both apps, with no reported process pauses; publish-to-receipt on the same phone averaged 5 ms (max 12 ms) through the Dashboard poll. With the default vendor battery policy the runner process was frozen for 1–20 s and the test fails, as intended. The strict headless rerun with the current build passes for both C++ and Python on the reference phone (mean publish-to-receipt 5 and 7 ms). Local write status stays distinct from observed peer state. These are phone-local process interruptions, not network-handover qualification. Scalar subscriptions, joystick release/focus-loss zero commands and JPEG/PNG image receipt/recovery passed physical tests. Two-phone camera viewing and reference robot control were also confirmed by the operator. |
| Native page alignment | Rebuilt main/C++/Python fixture APKs pass static 16 KB ELF alignment checks (87/83/122 libraries). SDK packaging and release verification reject incompatible ELF layout. This is not runtime qualification on a 16 KB kernel. |
| Camera options | Supported 1280×720, quality 80, automatic FPS produced about 29.9 capture/publish FPS in a reference sample; a PC received 20 valid JPEGs. This is not an endurance or latency measurement. |
| Latest platform regression | Build/lint and 53 JVM unit tests pass; all three runner backends build and lint; 162 host tests pass with Jazzy available; third-party notices are current and both workflows validate. On an Android 15 emulator the instrumentation suite passes except the two micro-ROS Agent executable checks that ARM translation cannot run, including automated accessibility checks, headless Dashboard peers and runner updates for both runtimes. On the reference phone this build passes the instrumentation suite (21 pass, 34 opt-in hardware skips, no failures, including the accessibility checks and micro-ROS Agent process tests), both strict Dashboard peer runs and the runner update check. New changes require their own CI and integration results. |
| Second physical phone | Samsung SM-S926B reports Android 17/API 37. The first beta suite had 19 passes, 4 accessibility focus/dialog failures and 35 opt-in skips. After native alignment and test-readiness fixes, all 4 accessibility checks pass. A full-suite repeat and strict background runner qualification remain pending. |
| Android 15 emulator | On an x86_64 API 35 Google APIs emulator with ARM translation, 17 instrumentation checks pass (UI, theme, accessibility, endpoints, Dashboard controls, native ROS session), the headless Dashboard peer test passes for C++ and Python runners, and runner discovery passes for both runtimes. A deliberate 2.5 s runner `SIGSTOP` was reported as one 2.27 s pause with the expected warnings. Agent/workspace ARM executables cannot link under that translation and still need ARM hardware. Not phone qualification. |
| Sustained operation | On the reference phone (USB power, Wi-Fi off) a 60-minute phone-local run delivered all 18,000 runner samples at 5 Hz to the main app with a 219 ms largest gap, 7 ms mean publish-to-receipt and no reported pauses. A one-hour run on battery with Wi-Fi off and no PC link, the phone powering a USB ESP32, kept MCU replies under 1.2 s old through five Agent restarts and delivered all 18,001 project messages (227 ms largest gap, no pauses); battery temperature stayed at 32-33 °C, charge fell from 92% to 80% with the screen on, and app memory stayed near 108-119 MB. Screen-off and idle runs, camera load and other phones remain unmeasured. |
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
   Short Wi-Fi-off USB trials passed, including USB Agent restarts alongside a running
   project and an operator-guided unplug/deny/allow check; combined app-task removal
   and network interruption previously produced a data gap. Unattended recovery is not proven.
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
