# Physical integration tests

## Dashboard switch

This test exercises actual phone-to-PC ROS traffic and UI requests. Use an
unlocked phone with ROS2 Android foreground, motor power disconnected, and no
active Dashboard session. No cameras or actuator topics are used. The test
backs up/restores the app's saved widget layout and stops its test runtime.
The main app must already be installed through `setup/run-device.sh`.

On a ROS Jazzy Linux host with the project PC middleware prepared, start the
independent peer in one terminal from the repository root:

```bash
ROS_DOMAIN_ID=87 ./tools/pc-ros.sh -- python3 tests/integration/dashboard_switch_peer.py
```

Then run the guarded device test in another terminal:

```bash
./setup/build.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.DashboardSwitchTest \
  -Pandroid.testInstrumentationRunnerArguments.dashboardSwitchPeer=true
```

Both peers use automatic discovery on isolated domain 87. Host routing/firewall
must permit discovery and traffic; see the normal connection troubleshooting
instructions. The device test is skipped unless the explicit peer argument is
present; a skipped test is not interoperability evidence.

Expected: the PC receives exactly `true, false, true, false`. Startup sends
nothing, navigation preserves local switch position without publishing, and
Dashboard restart does not replay the previous ON. The final OFF must reach the
PC and appear in its test telemetry. Local status must still say remote state
unknown: test telemetry is not an acknowledgement from a robot actuator.

The app-content image is a guarded debug artifact in the app's own cache. Keep
it and device test reports out of Git. This test does not simulate packet loss,
late subscriber startup, process death or a powered robot. Pure queue regressions
run through `setup/build.sh` with the normal unit-test suite.

## Main-app process death

Use the isolated, non-actuator C++ protocol fixture with Wi-Fi off, no active main
session and ROS2 Android open/unlocked:

```bash
python3 tests/integration/main_process_recovery.py
# Android Force stop of the active main app:
python3 tests/integration/main_process_recovery.py --mode force-stop
```

The prepare instrumentation intentionally terminates with the main app. The PC
observes process identity separately, then a fresh process verifies settings and
ROS recovery. Only the final driver PASS counts. It leaves the fixture stopped and
keeps raw evidence ignored. See [Testing](../../docs/TESTING.md#main-app-process-death)
for prerequisites, scope and interrupted-checkpoint recovery.

## Own-process observation

`own_process_observer.py` samples the scheduler state and cgroup freezer/cpuset
paths of named ROS2 Android processes while another command runs. It accepts only
`dev.ros2android.android` and its `runner.<id>` packages, so no other app is
queried, and it never signals, freezes, thaws or reconfigures a process. Samples
use the phone boot clock (`boot_ms`), matching `SystemClock.elapsedRealtime()`,
instrumentation phase logs and the fixtures' `stamp_ms` publish times:

```bash
python3 tests/integration/own_process_observer.py \
  --package dev.ros2android.android \
  --package dev.ros2android.android.runner.fixture_cpp \
  --output device-logs/observer/run.jsonl -- \
  ./setup/build.sh :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.DashboardPeerRecoveryTest#cppLateSubscriberAndPeerRecovery' \
  -Pandroid.testInstrumentationRunnerArguments.dashboardRecoveryPackage=dev.ros2android.android.runner.fixture_cpp
```

It writes JSON lines plus a `.summary.json` with contiguous frozen intervals per
process, and exits with the observed command's status. The sampling interval
bounds what it can see: a freeze shorter than one interval can be missed.
The observer also reads cgroup-v2 `cgroup.events` only for each named process's
PID cgroup, plus its CPU ticks and wait channel. `cgroupFrozen: null` means the
state was unavailable, not that freezing was absent. Vendor cgroup paths alone
are insufficient evidence; correlate kernel frozen samples with fixture
timestamps and system policy events scoped to the project package. Sampling and
ADB can affect scheduling, so retain failing runs alongside diagnostic repeats. Keep the output under ignored `device-logs/`.

## Installed-client journey

`installed_client_journey.sh WHEEL WORK_DIR` follows the documented user path without
a phone: install the wheel into a new venv, `setup build-tools`, `setup sources`,
`setup native --with-cpp`, doctor checks, then inspect, host-build and build a copy of
`examples/android_test_ws` into an APK. It stops at the first failing step and keeps one
log per step plus `logs/summary.txt` under `WORK_DIR`. The manual native SDK workflow
runs it on a clean hosted runner. On a prepared machine, isolate it from existing
caches with a fresh `HOME` and `GRADLE_USER_HOME` (Gradle ignores `$HOME`) and an
Android SDK holding only the pinned packages:

```bash
env -i HOME=/fresh/home GRADLE_USER_HOME=/fresh/home/.gradle PATH=/usr/bin:/bin \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/fresh/home/Android/Sdk \
  tests/integration/installed_client_journey.sh dist/ros2_android-1.0.0-py3-none-any.whl /fresh/work
```

Set `PIP_ZIPAPP` to pip's standalone `pip.pyz` when the system Python lacks `ensurepip`.
Same-OS isolation does not replace a clean OS install: system packages are reused.

## Endurance monitoring

`endurance_monitor.py` samples resident memory and CPU time of named ROS2 Android
processes plus device battery and thermal state every `--interval` seconds while a
command runs, then writes `.summary.json` with peak/mean RSS, RSS growth, mean CPU,
battery levels and peak temperatures. Pair it with the opt-in `EnduranceTest`:

```bash
python3 tests/integration/endurance_monitor.py --serial SERIAL \
  --package dev.ros2android.android --package dev.ros2android.android.runner.fixture_cpp \
  --output device-logs/endurance/run.jsonl -- \
  env ANDROID_SERIAL=SERIAL ./setup/build.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.EnduranceTest \
  -Pandroid.testInstrumentationRunnerArguments.enduranceRunnerPackage=dev.ros2android.android.runner.fixture_cpp \
  -Pandroid.testInstrumentationRunnerArguments.enduranceMinutes=60
```

`EnduranceTest` keeps a fixture publishing at 5 Hz to a Dashboard subscriber, logs a
per-minute summary under `ROS2Endurance`, and fails on a receipt gap over 2 s, more than
10% missing samples, or any pause reported by the runner or main app. Emulator thermal
values are synthetic; measure temperature on phones.

## Phone qualification batch

`phone_qualification.py --serial SERIAL --label NAME` checks preconditions, installs the
current app and fixtures, and runs the suite, the Dashboard peer test and optionally an
endurance run on that phone only. See [qualify a phone](../../docs/PHONE-QUALIFICATION.md).
