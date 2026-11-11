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
and repeat the host suite; the recorded local run has all 69 host tests passing.
The ROS-independent run has 54 executed tests and 15 explicitly skipped
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
[GitHub workflow security guidance](https://docs.github.com/en/actions/reference/security/secure-use). The hosted host/documentation job passed on 2026-11-11. The Android job failed
in SDK setup (`sdkmanager` was not on PATH), before tests/lint ran. The workflow
now resolves it from the Android SDK root. The corrected workflow still needs a
new hosted run; a clean-source local JVM/lint pass is not a hosted CI result.

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
seven command help screens, repeatable external inspection, unchanged sources,
archive/legal/hash/private-input rules and absence of guarded device/build/source
fetch calls. See [host package](HOST-PACKAGE.md) for exact boundaries.

The workflow now defines Python 3.10 and 3.12 installed-client jobs in addition to
the source-host and Android JVM/lint jobs. Package tool versions are pinned;
permissions remain read-only and no phone/signing secret is used. Local Python
3.12 passed; new hosted jobs are not yet verified. A successful package smoke
test is not a clean-machine native build, DDS or phone-deployment result.

## Full Android/native checks

Prepare the pinned native SDK, then run the normal build:

```bash
./setup/build.sh
```

This assembles the debug app and runs JVM tests/lint. Native build success alone
does not establish interoperability. Follow [Development](DEVELOPMENT.md) for
native SDK preparation and [Status](STATUS.md) for qualification boundaries.

## Runner request regression without hardware

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
