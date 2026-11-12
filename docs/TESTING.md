# Testing and CI scope

Tests protect platform contracts; robot demonstrations are additional evidence,
not the main acceptance suite. Run checks for the boundary you changed and record
what was actually tested. A skipped check is not a pass for its capability.

## Test conventions

- Keep most logic checks in fast host/JVM unit tests. Use device integration tests
  for JNI, DDS, services and runner boundaries; reserve UI tests for visible user journeys.
- Each test owns its fixtures, uses explicit inputs and assertions, and restores
  modified configuration in cleanup even when an assertion fails. Never depend on
  test order or a previous test leaving a service running.
- Wait for observable states with bounded deadlines. Correlate messages to the
  current run so queued samples cannot produce a false pass. Avoid fixed delays
  as a substitute for a readiness condition.
- UI tests may launch and close their own Activity for isolation. Batch compatible
  UI checks, keeping the device unlocked and the project app visible before input
  or capture. Runtime-only tests should not launch an Activity or require reopening
  a screen. Headless does not authorize access to other apps or personal data.
- Hardware and disruptive lifecycle checks are opt-in. Keep harmless test topics
  separate from robot commands, and report skips, failures and qualification scope.

## Baseline checks: no phone or native ROS SDK

From the repository root:

```bash
python3 -m unittest discover -s tools/tests -v
python3 -m unittest discover -s tests -p 'test_*.py' -v
./setup/build.sh :app:testDebugUnitTest :app:lintDebug
```

Host tests need Python 3.10+ and a C++ compiler for the command-guard fixture.
Most use synthetic files/processes. The ROS launch-adapter and Python lifecycle suites are
explicitly skipped when ROS Python packages are absent. To run it, source Jazzy
and repeat the host suite. Review executed and skipped counts in the test
output; the current validation summary is in [Status](STATUS.md).

Android JVM tests/lint need Java 21 and Android platform/build tools 35 as
specified in [Development](DEVELOPMENT.md). These selected tasks work without
`native-deps`, an NDK build or a phone; this path was checked in a clean source
export. They do not assemble a functioning native APK or exercise JNI/DDS.
The public documentation test checks local links in tracked Markdown; stage new
documentation before running it so it participates in the check.

[Platform checks](../.github/workflows/checks.yml) runs these two groups on hosted
runners for PRs and pushes to develop/main. Actions are pinned, permissions are
read-only and no hardware/signing secrets are used, following
[GitHub workflow security guidance](https://docs.github.com/en/actions/reference/security/secure-use). The Android job resolves `sdkmanager` from the configured Android SDK root.
Hosted checks cover only their declared jobs; a local source-export check is
not a hosted CI result or proof of native/device interoperability.

## Installed client: isolated package checks

With the package-build prerequisites installed, run:

```bash
python setup/build-python-package.py
python setup/verify-python-package.py dist/*.whl \
  --report dist/installed-client-report.json
```

The invoking Python needs pip >=22.3, setuptools >=68 and wheel. The verifier
creates a new venv without pip, installs the wheel offline using the invoking
pip and runs outside the checkout. It does not use checkout imports, ROS overlays,
an Android SDK or a prepared native SDK. It checks missing-prerequisite diagnostics,
eight command help screens, repeatable external inspection, unchanged sources,
archive/legal/hash/private-input rules and absence of guarded device/build/source
fetch calls. See [host package](HOST-PACKAGE.md) for exact boundaries.

The workflow now defines Python 3.10 and 3.12 installed-client jobs in addition to
the source-host and Android JVM/lint jobs. Package tool versions are pinned;
permissions remain read-only and no phone/signing secret is used. Local Python
3.12 passed. Both Python 3.10 and 3.12 hosted installed-client jobs passed at
[a9940cd](https://github.com/kishore-saravanan/ros2-android/actions/runs/43232633359);
newer revisions still need their own hosted checks. A successful package smoke
test is not a clean-machine native build, DDS or phone-deployment result.

## Full Android/native checks

Prepare the pinned native SDK, then run the normal build:

```bash
./setup/build.sh
```

This assembles the debug app and runs JVM tests/lint. Native build success alone
does not establish interoperability. Follow [Development](DEVELOPMENT.md) for
native SDK preparation and [Status](STATUS.md) for qualification boundaries.

## Native SDK build workflow

[Native Android SDK build](../.github/workflows/native-sdk.yml) is a manual
`workflow_dispatch` job on Ubuntu 24.04, separate from PR checks. It prepares
Jazzy and the pinned Android tools, fetches locked sources into a fresh build
cache, cross-builds the C++ ROS SDK, and packages `android_test_ws` without the
Python shell. It checks required library hashes and the resulting workspace APK.
There is no restored native cache, phone connection, signing secret or publishing
step. Its managed colcon venv is prepared with `setup/prepare-build-tools.py`,
because the hosted ROS setup already installs APT colcon and plain pip would then
create no venv `colcon` command; a hosted run that stopped there is what exposed
this. A successful hosted execution remains pending. `tools/tests/test_build_tools.py`
covers the host-provided and fresh cases without network access.

Run it from **Actions → Native Android SDK build → Run workflow** on the intended
branch once that branch contains the workflow. A successful job establishes build
qualification for that runner environment, not Android ROS traffic or performance.
The Python SDK, micro-ROS Agent and physical recovery matrix are separate checks.
Pinned upstream source revisions do not pin every host APT package or guarantee
bit-for-bit binary reproduction.

## Runner request regression without hardware

Host `test_project_stop.py` checks CLI stop outcomes with controlled time and
runner replies: an unfinished backend transition or cleanup failure returns an
error, successful completion is accepted, and an already stopped runner remains
idempotent. No failure automatically sends a force-stop command.

`ProjectRequestsTest` uses controlled elapsed times to cover deadline boundaries,
status-poll replacement by operator commands, no command queue/replay, duplicate
and wrong-command replies, and invalidation across close/reconnect. The controller
assigns a distinct Messenger reply endpoint per ticket, authenticates the sender
before reading the envelope, and rejects endpoints that no longer match.

These JVM tests exercise request-state logic, not Android Binder delivery, UID
checks or a running project. Physical tests still need delayed replies, service
restart/update, legacy runners and configuration preservation. A timeout means the
outcome is unknown; it does not prove a command failed or a project stopped.

## Physical and independent-peer checks

Install through `setup/run-device.sh`. With an authorized, unlocked project
screen and the required test hardware/permissions:

```bash
./setup/build.sh :app:connectedDebugAndroidTest
```

Review the selected tests before running: this suite can use sensors and has
opt-in camera/peer tests. Do not run it unattended on a powered robot. Device
checks stay separate from public PR automation. Keep logs, screenshots and
identifiers ignored. Check failure and skip counts in generated reports.

An independent PC observer is required to claim phone/PC ROS interoperability.
See the [Dashboard switch regression](../tests/integration/README.md) for an
isolated ON/OFF/navigation/restart test. Other network, USB, camera and process
recovery probes are described in the relevant feature guides.

## Application examples

Optional example tests are separate from platform CI. For the ball proof of
concept, with Pillow/NumPy installed in a development environment:

```bash
PYTHONPATH=examples/ball_follower/src/ball_follower \
  python3 -m unittest discover -s examples/ball_follower/tests -v
```

Passing its synthetic images/controller tests does not qualify a motor driver,
physical timing or autonomous following. Avoid coupling generic runtime changes
to application tuning. See [example boundaries](../examples/README.md).

## Independent runner authorization regression

`RunnerTrustTest` is opt-in and restricted to the disposable fixture package
`dev.ros2android.android.runner.ws_auth_workspace`. Copy the small
`examples/android_test_ws` outside the checkout, use `--id auth_workspace`, build
with `--controller-apk` and `--without-shell`, and sign the runner with a separate
throwaway key using Android's signing tools before deployment. Keep test keys
and screenshots ignored; do not use a release key. The normal debug key may be
the same as the locally built main app, which would not prove signer independence.

```bash
./setup/build.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.RunnerTrustTest \
  -Pandroid.testInstrumentationRunnerArguments.trustTestPackage=dev.ros2android.android.runner.ws_auth_workspace
```

The fixture is configured to ROS domain87, started and stopped, and its local
approval is revoked at completion. It must contain only the talker/processor
example, never robot actuator nodes. The test checks an isolated caller via a
private debug-only probe, as well as approval/identity/revocation. No camera or
personal apps/data are accessed. Use a separate Linux ROS subscriber to verify
`/android_test/result`; runner status alone is not traffic evidence.

## Runner protocol fixture

`RunnerProtocolTest` is opt-in and restricted to the stopped disposable
`ws_protocol_workspace` talker fixture. Build the small
`examples/android_test_ws` workspace with `--id protocol_workspace` and
`--without-shell`, then deploy it before testing. The independently signed
`ws_auth_workspace` fixture is also accepted; preserve an older copy when testing
legacy compatibility. Leave the main app unlocked and visible; no
robot hardware is required. The test temporarily grants local approval if needed
and restores its prior approval state. It requires a versioned service response
before sending any malformed command, so an old service cannot execute a request
that it would otherwise ignore version fields on.

```bash
./setup/build.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.RunnerProtocolTest \
  -Pandroid.testInstrumentationRunnerArguments.protocolTestPackage=dev.ros2android.android.runner.ws_protocol_workspace
```

It checks authenticated legacy and versioned status requests, then unsupported,
string and null protocol versions for all four commands and an unknown command.
Each rejection must leave the stopped state and saved configuration unchanged.
It never sends a valid start/stop/configure request. The current-service fixture
passed on the reference Android device. This checks a new service accepting
legacy requests, not an old runner APK or an APK upgrade.

Remaining fixture matrix: new controller/old runner, old controller/new runner,
incompatible installed metadata, reply delay beyond deadline, service death,
runner update while bound and revoked/replaced signing identity. Verify clear
errors, no stale status or command replay and preserved configuration. Use only
isolated talker fixtures and qualify each case before marking it complete.

## Phone-local workspace traffic

`WorkspaceLocalLifecycleTest` is opt-in and restricted to the disposable
`ws_protocol_workspace` fixture. Use the two-node `android_test_ws` example,
with `--id protocol_workspace` and `--without-shell`. Stop the fixture and main
sensor/Dashboard session first. Turn Wi-Fi off before the test and leave the
main app open and unlocked. No MCU, camera or motor commands are used.

```bash
./setup/build.sh :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.WorkspaceLocalLifecycleTest#offlineResultsRecoverAfterRestart' \
  -Pandroid.testInstrumentationRunnerArguments.workspaceLocalTestPackage=dev.ros2android.android.runner.ws_protocol_workspace
```

The foreground method passed on the reference device with Wi-Fi off.
It receives `/android_test/result` through the main app's Dashboard service,
checks advancing sequence numbers and processor calculations, stops the fixture,
checks Stale, then restarts it and checks fresh results. It uses an isolated ROS
domain, restores the fixture profile and leaves it stopped. Saved Dashboard
widgets and main connection preferences are not changed.

The separate `offlineResultsContinueInBackgroundAndRecoverAfterRestart` method
adds ten seconds with the Activity stopped and the controller unbound. Set
`workspaceBackgroundSeconds=60` as an instrumentation argument for a one-minute
trial (accepted range 1–300 seconds). Both result freshness and observer timing
must stay within two seconds; a frozen observer or overlong trial must fail.
This is a qualification threshold, not a real-time guarantee.

On the reference phone, the five-minute background trial passed with Wi-Fi off
and background activity allowed for both the main app and runner: 1,497 distinct
verified results, followed by stop/Stale and restart/fresh recovery. Observer
pauses and result ages stayed below the test's two-second threshold.
Default background policy previously stalled native children even while their
parent service remained active. Configure both packages separately; see
[background setup](STANDALONE-OPERATION.md#allow-background-operation).
This result covers one device and a C++ workspace, not every backend or vendor.

Optional diagnostics are `workspaceLocalOnly=true` for loopback-only selection
and `workspaceKeepControllerBound=true` for a retained management binding. An
isolated String self-probe distinguishes main-app reception from workspace output.
Before the background-policy change, the self-probe remained fresh while
workspace results stalled; retaining the binding did not prevent the gap.

These are explicit device tests, not unattended CI. The bounded interruption
cases below, USB coexistence and longer endurance require separate qualification.

### Manual screen-off trial

Use the background method with `workspaceScreenOff=true` and, for example,
`workspaceBackgroundSeconds=60`. Start with the app unlocked and visible, Wi-Fi
off, and the non-actuator fixture stopped. In a second terminal, follow only the
qualification tag with `adb logcat -s ROS2Qualification:I '*:S'`. The test logs
`Ready for manual screen-off` after fresh foreground results arrive. Press the
phone's power button within 90 seconds, leave the screen off for the full observation interval, then
wake and unlock the phone within the next two minutes. Return to ROS2 Android.
No lock-screen or global screen-timeout setting needs to change.

The test requires the screen to remain non-interactive throughout observation,
fresh calculation results and an observer that does not pause over two seconds.
After unlock it checks stop/Stale and restart/fresh recovery. A timeout, early
wake or frozen observer fails the test. This is a short USB-connected screen-off
case, not forced Doze, unplugged deep idle or endurance qualification.

The reference device passed a 60-second screen-off trial with Wi-Fi off and both
apps allowed background activity: 301 distinct verified results, followed by
stop/Stale and restart/fresh recovery after normal unlock. The phone remained
USB-connected to the development PC; repeat unplugged idle separately.


### Task removal and abrupt process failure

Three opt-in methods use the same allowlisted disposable workspace and offline
ROS result checks:

- `offlineResultsSurviveOwnTaskRemoval`: removes the main app's own task through
  `finishAndRemoveTask`, checks 30 seconds of fresh results without a project
  management binding, then reopens the app and checks stop/restart recovery.
- `offlineResultsRecoverAfterNativeChildExit`: sends SIGKILL to the fixture's
  talker through a debug-only self-failure hook, requires the supervisor to report
  failure and remove the sibling, checks Stale, then explicitly starts and verifies fresh results.
- `offlineResultsRecoverAfterRunnerDeath`: kills the fixture's runner process,
  requires the control connection to invalidate, checks no workspace children
  remain, reconnects to Stopped with the saved profile, and verifies fresh data
  only after an explicit Start.

Select each method using the same `class=...WorkspaceLocalLifecycleTest#METHOD`
and `workspaceLocalTestPackage` arguments above. Process injection requires a
**debuggable disposable runner**; never use a real robot workspace. The test reads
only that fixture UID's processes. Rebuild/install the current debug runner before
running these methods. Its existing DUMP-protected debug receiver accepts the two
fixed test actions only for `ws_protocol_workspace` while Running. The runner
finds its own talker child, or its own PID; callers cannot supply a PID, command
or component. The hook is absent from release source sets and rejects ordinary
workspace package names. External `run-as` signals are not required.

The task-removal case leaves instrumentation and the bound observer service
alive. It does not qualify vendor-specific swiping behavior, main-app process
death, Force stop or automatic restart. Runner SIGKILL is an abrupt process-death
case, not a low-memory-killer, native-crash or app-upgrade simulation.

All three methods passed on the reference device with Wi-Fi off. Task removal
received 151 distinct verified results over 30 seconds and recovered stop/restart
controls. Child exit reported failure and removed the sibling; runner death
invalidated the control session and left no workspace children. Both failure cases
preserved the profile, became Stale and received fresh verified results after an
explicit restart. This qualifies the disposable C++ fixture, not every backend.


### Main-app process death

With the same disposable fixture stopped, Wi-Fi off and ROS2 Android open and
unlocked, run from the repository root:

```bash
python3 tests/integration/main_process_recovery.py
# Separate Android Force stop trial, applied while the main app is still alive:
python3 tests/integration/main_process_recovery.py --mode force-stop
```

Use `--serial` if a specific USB ADB target is needed. The driver targets that same
device for Gradle and outside observations; it never selects another app or runner.
It requires current debug artifacts and the Android/native build prerequisites.

The prepare phase receives verified workspace results, saves a private checkpoint,
and deliberately terminates the main app process. In Force stop mode it waits
for the PC to apply `am force-stop` to the confirmed-live main process instead. Its instrumentation run is
**expected to abort**, not pass as a JUnit test. The PC driver requires that abort,
a valid checkpoint, 15 seconds with no main process and unchanged runner/native
process identities. A new instrumentation process then requires inactive local
hardware/Dashboard state, unchanged saved connection/widget settings and fresh
correct workspace results with a sequence beyond the checkpoint. It explicitly
restarts the subscriber, tests project stop/Stale/restart, restores the runner
profile and removes the completed phone checkpoint.

Only the full driver result counts as this qualification. Process survival is not
proof of uninterrupted ROS reception while the main app's subscriber is absent.
Private checkpoints, device IDs and logs stay under ignored `device-logs/`.
This test does not add a kill endpoint to the app or change automatic-start policy.

If interrupted, the driver refuses to overwrite an existing checkpoint. Review its
private report before proceeding. Within ten minutes, the recovery phase can be
resumed with the app open/unlocked and the fixture still Running:

```bash
./setup/build.sh :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.WorkspaceLocalLifecycleTest#recoverAfterMainProcessDeath' \
  -Pandroid.testInstrumentationRunnerArguments.workspaceLocalTestPackage=dev.ros2android.android.runner.ws_protocol_workspace \
  -Pandroid.testInstrumentationRunnerArguments.workspaceMainDeathPhase=recover
```

Do not delete a failed checkpoint until its saved fixture profile has been
restored. For a Force stop checkpoint, also pass
`-Pandroid.testInstrumentationRunnerArguments.workspaceMainDeathMode=force-stop`
to the recovery command. Expired checkpoints require explicit fixture
cleanup/reconfiguration. Low-memory eviction and vendor Recents swipes remain
separate cases; a main-app Force stop does not stop a separate runner.

## Offline network policy

`CycloneNetworkTest` parses the actual shared Kotlin-generated XML and checks
loopback interface/peer availability, external selection, explicit local-only
mode, stale pins, unreachable peer hints and malformed/XML-injection inputs.
The main app, all three backends and workspace shell compile this same source.
Native JNI code consumes its XML; it no longer builds different configurations.

Both generated offline policies were also accepted by a Linux host build of the
pinned Cyclone revision, creating/deleting two participants per policy. This
checks native parsing/initialization, not Android networking, ROS samples or
micro-ROS interoperability. The [standalone checklist](STANDALONE-OPERATION.md)
separates fresh startup from network interruption and lists pending device trials.

## Protocol and documentation captures

`ProjectProtocolTest` exercises strict version types, supported command subsets,
legacy v1 compatibility and malformed capability rejection. Runner service
authorization still precedes envelope parsing. Unit tests do not prove actual
Binder request rejection, installed metadata presentation or update/reconnect
races; those remain dedicated device-qualification gates.

The README's intentionally curated media is separate from private device evidence.
[Media provenance](assets/README.md) documents the opt-in content-only capture,
address redaction and temporary inactive Dashboard layout. That test does not
start ROS sessions, user projects, camera publishing or motor commands.

`WorkspaceInspectionTest` uses synthetic source/SDK/host prefixes to check provider
precedence, missing SDKs, conditions/versions/groups, unsupported package types,
malformed manifests, ignored/generated paths and shell-profile isolation. Its
external-path CLI check verifies inspection does not write a cache or execute
workspace CMake. These tests do not cross-build the dependencies they inspect.

`HostDiagnosticsTest` checks task-scoped prerequisites with synthetic SDK/tool
inputs, version mismatches, managed colcon selection, missing roots, bounded
version probes and no SDK extraction/device access. Its matrix regression ties
client/app/build/contract versions and source-lock hashes to the current source.
An installed wheel includes its tooling snapshot hash; preflight success is not
a clean-machine build or native-runtime qualification.

`PackageVerificationTest` checks rejection of unsafe/private/generated/key paths
in both wheel and SDK payloads, as well as legal/version/hash mismatches, before
installation. The actual offline-install verifier runs separately against a built
wheel so the ordinary host unit suite does not need pip or an Android SDK.

`ApkArtifactsTest` covers metadata/current-signer parsing, major version ordering,
wrong namespaces, malformed fields, missing/multiple signers, file changes during
verification, missing/hanging tools, SDK selection and CLI errors/no extraction.
It checks identical/candidate/blocked decisions without installing anything. Real
local main/runner APK inspection uses SDK aapt/apksigner; file-level evidence does
not qualify installed upgrades or profile/data preservation. See [Upgrading](UPGRADING.md).

### Runner Android Force stop

With the same stopped protocol fixture and offline test prerequisites, run:

```bash
./setup/build.sh :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.WorkspaceLocalLifecycleTest#offlineResultsRecoverAfterRunnerForceStop' \
  -Pandroid.testInstrumentationRunnerArguments.workspaceLocalTestPackage=dev.ros2android.android.runner.ws_protocol_workspace
```

The test applies Android Force stop only to the allowlisted disposable runner. It
requires control disconnection, no remaining native workspace processes, stale ROS
readings, unchanged runner profile, and fresh correct results after explicit
restart. This passes on the reference C++ fixture; it does not qualify other
backends, vendor Recents behavior, reboot or automatic restart.

## Dashboard peer recovery

With the prepared native/Python SDK, build and install the harmless C++ fixture:

```bash
./setup/build.sh :runner:assembleDebug :runner:lintDebug \
  -ProbotProjectDir=examples/runner_fixture_cpp
./setup/run-device.sh --runner dev.ros2android.android.runner.fixture_cpp --install-only
```

Stop the main session and fixture, and turn Wi-Fi off manually. Allow background
activity (battery optimization off) for ROS2 Android and the fixture in their
Android app settings; the test checks Android's standard exemption first. This
test binds only project services; it does not launch or close an Activity, enter
UI input or capture the screen, so the app screen need not remain open. No MCU
connection or motor power is needed. Run:

```bash
./setup/build.sh :app:connectedDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.DashboardPeerRecoveryTest#cppLateSubscriberAndPeerRecovery' \
  -Pandroid.testInstrumentationRunnerArguments.dashboardRecoveryPackage=dev.ros2android.android.runner.fixture_cpp
```

For the embedded Python backend, build/install `:pythonRunner:assembleDebug
:pythonRunner:lintDebug` with `-ProbotProjectDir=examples/runner_fixture_python`,
then install with `--python-runner dev.ros2android.android.runner.fixture_python
--install-only`. Run the same class with method `pythonLateSubscriberAndPeerRecovery`
and `dashboardRecoveryPackage=dev.ros2android.android.runner.fixture_python`.
The same headless prerequisites apply to both backends.

The opt-in test accepts only those disposable fixture package names. It observes
actual DDS traffic between the main app and a separate runner process: a volatile
subscriber joining after a write receives no old command; stopped-peer telemetry
becomes Stale while Dashboard stays active; runner and Dashboard restarts do not
replay ON/OFF; fresh explicit commands are counted by the peer. A per-start trial
identifier excludes queued telemetry from older peers. A conflicting connection
profile is rejected without interrupting the active Dashboard. Local write status
continues to say remote state unknown. After each peer run it requires zero
process pauses reported by the runner and by the main app, so a run that merely
survived a short observation window cannot pass. It restores the runner profile
and stops its test sessions without changing saved widgets. To run both methods in
one invocation, select the whole class and set `dashboardRecoveryPackage=both`,
with both fixtures installed and stopped. This qualifies phone-local peer loss,
not router isolation, Wi-Fi handover, all message types or a remote robot
acknowledgement protocol.

Diagnostics: each phase is logged under the `ROS2Qualification` tag with its boot
clock time. The fixtures' `stamp_ms` lets a failure report whether the publisher
paused (stamps jump) or delivery stalled (stamps continue). On a telemetry stall
the test keeps observing for up to ten seconds, records the runner's executor
heartbeat and `health`, and retries a fresh Dashboard reader, then still fails.
Passing runs log publish-to-receipt statistics. For a vendor-policy reproduction,
add `-Pandroid.testInstrumentationRunnerArguments.dashboardRecoveryAllowBatteryOptimized=true`;
such runs are diagnostics, never qualification. Pair them with
[own-process observation](../tests/integration/README.md#own-process-observation).

On the reference phone, the C++ factory peer passed six consecutive strict
headless trials with background activity allowed and no reported pauses. With the
default vendor battery policy, the runner process was frozen for 1–20 seconds
(confirmed by independent process observation and by the runner's own pause
report) and the test failed, which is the intended result. Both backends passed
the earlier Activity-hosted run; the strict Python rerun is pending.

## Newer Android on an emulator

The reference phone runs Android 12. To exercise Android 13–15 behavior (notification
permission, foreground-service rules, edge-to-edge layout and package visibility)
without another phone, use an x86_64 **Google APIs** emulator image for API 35. Its
ARM translation runs the ARM64-only APKs. Keep it in its own SDK root and AVD folder,
and always select it explicitly, because Gradle otherwise tests every connected device:

```bash
sdkmanager --sdk_root="$HOME/.cache/ros2-android-emulator-sdk" emulator "system-images;android-35;google_apis;x86_64"
ANDROID_SERIAL=emulator-5584 ./setup/build.sh :app:connectedDebugAndroidTest
```

The emulator needs a `platform-tools` folder in its SDK root (a link to the existing
one works). `Ros2AndroidTestRunner` grants the app's own notification permission on
Android 13+, so the one-time prompt cannot pause an Activity under test; the prompt
itself is checked by hand. On the emulator you may turn Wi-Fi off and exempt the test
apps from battery optimization (`dumpsys deviceidle whitelist +<package>`); do not use
such commands on a personal phone.

Android 15 results: all UI, theme, accessibility, navigation, endpoint, Dashboard
controls and native ROS session checks pass; the headless Dashboard peer test passes
for both the C++ and Python fixtures with zero reported pauses; Projects discovery and
recreation pass for both runtimes. A deliberate 2.5-second `SIGSTOP` of a running
runner was reported as one pause of 2.27 s, and the project card, runner screen and
Details showed the expected text in light and dark mode. The two micro-ROS Agent
executable checks fail only because the emulator's ARM translation ignores
`LD_LIBRARY_PATH` for standalone ARM executables; the Agent and workspace child
nodes therefore need ARM hardware. Emulator results are compatibility evidence, not
phone qualification: there is no vendor power policy, real radio or USB.

## Core layout and navigation checks

`AccessibilityChecksTest` runs Google's Accessibility Test Framework (latest preset:
touch targets, labels, editable-field naming, contrast with an in-memory screenshot
and more) on Home, Sensors, the battery detail, Dashboard, Projects and Settings in
app-local light and dark previews. It fails on errors and logs warnings under
`ROS2Accessibility`; screenshots are taken only while the app's own window has focus
without a lock screen, and are never saved. On the Android 15 emulator it passes with
no errors; remaining warnings are tab names that match their page titles and the
fixed-size back glyph. A screen-reader walkthrough is still manual.

`ResponsiveNavigationTest` rotates only the app Activity between landscape and
portrait, verifies all five tab touch targets/labels and restores its orientation.
`ThemeUiTest` uses app-local light/dark preview contexts; `AccessibilityUiTest`
uses a large-text preview context and checks stable status rendering. None changes
global appearance or accessibility settings. These and the ordinary navigation
checks pass on the reference phone. They do not qualify every dialog, image widget,
screen reader, display size or Android version. Camera navigation remains opt-in.
