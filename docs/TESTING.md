# Testing and CI scope

Tests protect platform contracts; robot demonstrations are additional evidence,
not the main acceptance suite. Run checks for the boundary you changed and record
what was actually tested. A skipped check is not a pass for its capability.

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
and repeat the host suite; the recorded local run has all 41 host tests passing.
The ROS-independent run has 26 executed tests and 15 explicitly skipped
ROS-dependent tests.

Android JVM tests/lint need Java 21 and Android platform/build tools 35 as
specified in [Development](DEVELOPMENT.md). These selected tasks work without
`native-deps`, an NDK build or a phone; this path was checked in a clean source
export. They do not assemble a functioning native APK or exercise JNI/DDS.
The public documentation test checks local links in tracked Markdown; stage new
documentation before running it so it participates in the check.

[Platform checks](../.github/workflows/checks.yml) runs these two groups on hosted
runners for PRs and pushes to develop/main. Actions are pinned, permissions are
read-only and no hardware/signing secrets are used, following
[GitHub workflow security guidance](https://docs.github.com/en/actions/reference/security/secure-use). The workflow still needs its
first GitHub-hosted run; local equivalents are not a hosted CI result.

## Full Android/native checks

Prepare the pinned native SDK, then run the normal build:

```bash
./setup/build.sh
```

This assembles the debug app and runs JVM tests/lint. Native build success alone
does not establish interoperability. Follow [Development](DEVELOPMENT.md) for
native SDK preparation and [Status](STATUS.md) for qualification boundaries.

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
