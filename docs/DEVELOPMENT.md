# Development

For the installed-client journey on another machine, follow
[clean-machine setup order](CLEAN-MACHINE-SETUP.md) and inspect the
[compatibility matrix](COMPATIBILITY.md). That full fresh-machine journey still
needs qualification.

## First build

Install the Android SDK outside the repository (the default is `~/Android/Sdk`).
For contribution and test boundaries, start with [Contributing](../CONTRIBUTING.md)
and [Testing](TESTING.md).
The project uses Java 21 to run Gradle and emits Java/Kotlin 17 bytecode.

Pinned build dependencies:

| Component | Version |
| --- | --- |
| Gradle | 8.9, wrapper distribution SHA-256 checked |
| Android Gradle plugin | 8.7.3 |
| Kotlin | 2.2.21 |
| Compile / target SDK | 35 / 35 |
| Minimum SDK | 26 |
| NDK | 27.2.12479018 (r27c) |
| CMake | 3.22.1 |
| Initial native ABI | arm64-v8a |

For another machine, install the official Android command-line tools and accept
their licenses, then use `sdkmanager` (or the equivalent Android CLI command)
to install these packages:

```bash
sdkmanager 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' \
  'ndk;27.2.12479018' 'cmake;3.22.1'
```

Set `ANDROID_HOME` to that SDK directory, or create a local, ignored
`local.properties` containing `sdk.dir=/absolute/path/to/Android/Sdk`.
Android Studio is optional for command-line builds.

The native ROS dependencies must be built before the first Android build.
On an Ubuntu machine with ROS 2 Jazzy and its Python build dependencies installed,
prepare the colcon tool environment with:

```bash
python3 setup/prepare-build-tools.py
python3 setup/fetch-native-ros.py
./setup/build-native-ros.sh
```

`prepare-build-tools.py` (installed client: `ros2-android setup build-tools`)
creates `~/.local/share/ros2-android-tools/build-venv` with system site packages
and installs `colcon-common-extensions==0.3.0` and `vcstool==0.3.0`. When the host
already provides colcon/vcstool, for example through ROS's `ros-dev-tools` APT
packages, pip treats the pins as satisfied and would create no `colcon` command in
the venv; the helper then reinstalls only `colcon-core` and `vcstool` at the
versions already visible, without dependencies. Use `--venv` for another location
and set `COLCON_BIN` accordingly. If the OS lacks `python3-venv`, install that
prerequisite locally or bootstrap pip into a `--without-pip` venv, then run the
helper with `--venv`; do not change the system Python environment.
The native build sources the PC's Jazzy environment for build-time generators.
It cross-compiles the pinned ROS dependencies with the NDK, selects Cyclone DDS introspection
typesupport, uses no-op file logging and disables desktop tracing/security
dependencies. Host pkg-config libraries are excluded from the cross-build.

`native/ros-sources.lock.json` pins the upstream repositories. The fetch helper
applies the checked-in Android patches idempotently. Source/build output lives
in an ignored `native-deps` link to a cache directory without spaces, because
upstream generators do not consistently handle spaces in their workspace path.
`stage-native-libs.py` verifies ARM64 ELF headers and stages the ROS libraries
with checksums. `stage-licenses.py` then stages each locked source's license and
notice files, the vendored libyaml license and the NDK libc++ (LLVM) license into
`native-deps/ros-assets`, which the app and all runners package under
`assets/licenses/`. For an SDK prepared before this step existed, run
`python3 setup/stage-licenses.py` once. It fails if a locked source has no license
file. After changing a lock, regenerate the inventory with
`python3 setup/generate-notices.py`; a host test fails when it is out of date. The app and ROS dependencies use the same shared NDK C++ runtime.

```bash
./setup/build.sh
```

This builds the debug APK, runs configuration unit tests, and runs Android lint.
Output: `android/app/build/outputs/apk/debug/app-debug.apk`.

## Physical phone

Enable USB debugging, connect and unlock the phone, then allow this computer's
debugging key on the phone. Install and launch:

```bash
./setup/run-device.sh
```

This installs the development package `dev.ros2android.android`. It uses ADB's
single-physical-device selector; it does not guess when multiple phones exist.

The app sets `FLAG_KEEP_SCREEN_ON` while its activity is visible, so the phone
stays awake during development. Switching away restores normal system sleep
behavior. Realme denied ADB modification of the global stay-awake setting;
its original value of `0` was preserved. Do not bypass that restriction or change
global screen-awake/security settings. Active sessions use service-owned CPU wake
resources; this does not force the screen on outside a project Activity.

Device access is limited to this project app, its debug artifacts, hardware
capability diagnostics, and development settings. Do not inspect or modify
personal files or other apps.

Use **Home**, **Sensors**, **Dashboard**, **Projects**, **Settings**, and **Help** as described in
`docs/V0.1.md`. The foreground service owns active sessions independently of the
Activity; navigation and Activity recreation do not request a runtime stop.
Use Stop session or the notification Stop action to release the session. The sensor frame uses Android device axes and
does not supply a calibrated robot mounting transform.

Device tests:

```bash
./setup/build.sh :app:connectedDebugAndroidTest
```

Tests cover native loading, actual sensor delivery, listener cleanup, ROS node
creation/restart, local typed endpoints, ROS graph discovery, and starting a
real publisher from an edited UI topic. Keep the phone unlocked during tests.
The PC round-trip test is opt-in and skipped unless its expected-text argument
is supplied.

The project enables `android.injected.androidTest.leaveApksInstalledAfterRun`
so Gradle keeps the development app installed after device tests. This avoids
test cleanup removing the app and resetting its granted permissions.

## PC round-trip verification

Start `tools/pc-probe.py` in a sourced Jazzy terminal. It subscribes to actual
`/phone/imu` and only sends the specified display response after receiving IMU
data. Run the device test with matching expected text and the current interface
addresses:

```bash
python3 tools/pc-probe.py --duration 60 --text 'Round trip verified'
```

In another terminal, replace `PC_IP` and `PHONE_IP` with actual IPv4 addresses:

```bash
./setup/build.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.RosRuntimeTest#actualImuReachesPcAndDisplayMessageReturns \
  -Pandroid.testInstrumentationRunnerArguments.pcPeer=PC_IP \
  -Pandroid.testInstrumentationRunnerArguments.localAddress=PHONE_IP \
  '-Pandroid.testInstrumentationRunnerArguments.expectedText=Round trip verified'
```

For an isolated USB proof, generate a PC Fast DDS profile restricted to its USB
IPv4 address with `tools/fastdds-interface-profile.py`, pass that file through
`FASTRTPS_DEFAULT_PROFILES_FILE` to the PC probe, and select the phone USB address
in the device-test arguments. This proves the USB path with Wi-Fi still enabled.
All probe processes are temporary and do not change global ROS middleware
settings. Camera verification uses `tools/pc-camera-probe.py`, which validates
message bytes without retaining camera images.

Phone-hosted micro-ROS Agent and reference ESP32 UDP/USB exchange have separate
verification paths; see [Status](STATUS.md). Native Jazzy topic exchange alone
does not establish Agent or MCU interoperability.

## Git

The working branch is `develop`. The repository is
`https://github.com/kishore-saravanan/ros2-android`. Git author details
are local user configuration. On a new checkout, configure an author and review
before committing:

```bash
git config user.name 'Your name'
git config user.email 'your-email@example.com'
git status
git add .
git commit -m 'Describe the change'
```

Format the code before committing; see [code style](../CONTRIBUTING.md#code-style):

```bash
./setup/format.sh
```

The one-time commit that formatted the whole codebase is listed in
`.git-blame-ignore-revs`. GitHub's blame view skips it; for local `git blame`, run
`git config blame.ignoreRevsFile .git-blame-ignore-revs` once.

Build output, device logs, SDKs, native dependency builds, and machine-local SDK
paths are ignored. Never commit device serials, signing keys, or credentials.


## Continuous camera verification

The camera uses repeating YUV capture, a three-buffer NV21 pool, a one-frame
encoder queue and a one-frame JPEG publish queue. Encoding and DDS calls run on
separate threads from acquisition. Shutdown marks the stream stopped and closes
capture resources; the ROS control worker waits for all retired camera workers
before removing or reusing the publisher. Do not call `awaitStopped` on the UI
thread. Camera pipeline metrics are not proof of remote delivery.

JVM tests include padded/overlapping YUV planes, pixel strides, cropped rows and
sensor-clock mapping. The physical performance test is explicitly opt-in:

```bash
./setup/build.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.CameraRosTest#continuousCameraSustainsRequestedRate \
  -Pandroid.testInstrumentationRunnerArguments.testContinuousCamera=true \
  -Pandroid.testInstrumentationRunnerArguments.cameraRate=30 \
  -Pandroid.testInstrumentationRunnerArguments.cameraSeconds=30 \
  -Pandroid.testInstrumentationRunnerArguments.pcPeer=PC_IP \
  -Pandroid.testInstrumentationRunnerArguments.localAddress=PHONE_IP
```

Use `cameraFacing=Front` for the front camera. `minimumCameraFps` defaults to 15
and applies to successful local ROS writes; a separate PC benchmark must verify
received FPS. For a ten-minute PC run, start the benchmark first with
`--duration 600 --warmup 5 --decode` and hold the device test for at least 610
seconds. The phone must be unlocked and have user-granted camera permission.
`ROS2CameraBenchmark` logs contain aggregate metrics only, with no image bytes.


## VS Code tasks

Use **Terminal → Run Task** for Build and check, Install and open, and front/back
camera viewers. **Ctrl+Shift+B** runs the default build/check task. Viewer tasks
source Jazzy through `tools/view-camera.sh`; they expect the matching publisher
to be running on the phone. Topics may need editing for a different camera ID.
No extension is required. Deployment still uses the authorized physical device.

## Session lifecycle verification

`RobotSessionService` is private to the app and starts a foreground session
only after an explicit user action in the visible Activity. The runtime
notification offers Stop. A partial wake lock keeps CPU work active during the
session; it does not force the display on outside the app. Multicast and wake
resources are released on Stop. The service does not automatically restart
camera capture after process death or reboot.

The opt-in `SessionLifecycleTest` starts camera + IMU, recreates the Activity,
moves it to the background, returns and explicitly stops. Run it with
`testCameraLifecycle=true`, optional `backgroundSeconds` (default 10), and
`pcPeer`/`localAddress` for the selected network. A separate PC subscriber must
verify received data. Newer Android releases and vendor power managers still
require physical validation; passing Android 12 does not prove all versions.
