# Development

## First build

The reference SDK is installed outside the repository at `~/Android/Sdk`.
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
The current PC already has the colcon tool environment. For another Ubuntu
machine with the ROS Python build dependencies installed, prepare one with:

```bash
python3 -m venv --system-site-packages "$HOME/.local/share/ros2-android-tools/build-venv"
"$HOME/.local/share/ros2-android-tools/build-venv/bin/python" -m pip install \
  colcon-common-extensions==0.3.0 vcstool==0.3.0
python3 tools/fetch-native-ros.py
./tools/build-native-ros.sh
```

If the OS lacks `python3-venv`, install that prerequisite locally or bootstrap
pip into a `--without-pip` venv; do not change the system Python environment.
The native build sources the PC's Jazzy environment for build-time generators.
It cross-compiles 68 packages with the NDK, selects Cyclone DDS introspection
typesupport, uses no-op file logging and disables desktop tracing/security
dependencies. Host pkg-config libraries are excluded from the cross-build.

`native/ros-sources.lock.json` pins 23 upstream repositories. The fetch helper
applies the checked-in Android patches idempotently. Source/build output lives
in an ignored `native-deps` link to a cache directory without spaces, because
upstream generators do not consistently handle spaces in their workspace path.
`stage-native-libs.py` verifies ARM64 ELF headers and stages the ROS libraries
with checksums. The app and ROS dependencies use the same shared NDK C++ runtime.

```bash
./tools/build.sh
```

This builds the debug APK, runs configuration unit tests, and runs Android lint.
Output: `android/app/build/outputs/apk/debug/app-debug.apk`.

## Physical phone

Enable USB debugging, connect and unlock the phone, then allow this computer's
debugging key on the phone. Install and launch:

```bash
./tools/run-device.sh
```

This installs the development package `dev.robotbrain.android`. It uses ADB's
single-physical-device selector; it does not guess when multiple phones exist.

The app sets `FLAG_KEEP_SCREEN_ON` while its activity is visible, so the phone
stays awake during development. Switching away restores normal system sleep
behavior. Realme denied ADB modification of the global stay-awake setting;
its original value of `0` was preserved. To keep it awake outside this app too,
enable **Settings → Developer options → Stay awake** on the phone if available.

Device access is limited to this project app, its debug artifacts, hardware
capability diagnostics, and development settings. Do not inspect or modify
personal files or other apps.

Use **Publishers**, **Subscribers**, and **Connection** as described in
`docs/V0.1.md`. Leaving the activity stops capture and ROS endpoints; returning
requires an explicit restart. The sensor frame uses Android device axes and
does not supply a calibrated robot mounting transform.

Device tests:

```bash
./tools/build.sh :app:connectedDebugAndroidTest
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
./tools/build.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.robotbrain.android.RosRuntimeTest#actualImuReachesPcAndDisplayMessageReturns \
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

The phone-hosted micro-ROS Agent is a separate v0.2 milestone. Native Jazzy
topic exchange does not establish Agent portability or MCU USB support.

## Git

The working branch is `develop`. Author configuration and commits are deferred
at the user's request. No remote has been configured or content pushed.
When ready, configure an author locally and review before committing:

```bash
git config user.name 'Your name'
git config user.email 'your-email@example.com'
git status
git add .
git commit -m 'Start Kotlin Android robot brain app'
```

Build output, device logs, SDKs, native dependency builds, and machine-local SDK
paths are ignored. Never commit device serials, signing keys, or credentials.
