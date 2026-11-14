# Contributing to ROS2 Android

ROS2 Android is a platform for using a phone as a robot's onboard computer.
Robot applications demonstrate that platform; they must not add their topic
names, algorithms, motor wiring or tuning to its reusable runtime.

## Before changing code

Read [current architecture](ARCHITECTURE.md), [design](docs/design/HIGH-LEVEL-DESIGN.md),
[low-level boundaries](docs/design/LOW-LEVEL-DESIGN.md) and [roadmap](docs/ROADMAP.md).
Follow [development setup](docs/DEVELOPMENT.md) and [test scope](docs/TESTING.md).
Use the `develop` branch as the contribution base. Keep one problem per PR;
discuss public API, dependency, signing or repository-boundary changes first.

Start with existing modules and patterns. Screens render state; services and
controllers own resource lifetimes; hardware/native/process adapters own cleanup.
Keep queues and logs bounded. Preserve standard ROS message meanings and report
unsupported capabilities explicitly. Prefer small interfaces at real external
boundaries over new frameworks, deep inheritance or a class for every operation.

Host implementation belongs in reusable Python modules as it is extracted;
retain one public `ros2-android` command family. Do not add a new operator shell
wrapper for a task already supported by standard ROS commands.

## Code style

Run `setup/format.sh` before committing; CI runs `setup/format.sh --check`. It
applies pinned formatters: ruff for Python (ROS 2 style: 99 columns, single
quotes), clang-format for C/C++ (ROS 2 style: 100 columns, braces on every
block), ktfmt for Kotlin (Kotlin coding conventions) and shfmt for shell. It
needs `python3-venv`, Java and network access on its first run.

Formatters do not split a function into readable steps, so also:

- Put one statement on a line, and separate logical steps with a blank line,
  for example after a block ends and before an `if`, loop or `try`.
- Prefer a named variable or helper over a long expression or nested call.
- Write comments for intent and constraints, not for what the code says.

The scripts in `native_sdk.INPUTS` (`setup/build-native-ros.sh` and the other
native SDK build inputs) are skipped: the published SDK only installs with
byte-identical copies, and a host test enforces that. Change them only together
with a new native SDK build.

## Verification

Run checks appropriate to the change using the commands in [Testing](docs/TESTING.md).
Android changes use `setup/build.sh`; deployment uses `setup/run-device.sh`.
No camera, USB hardware, connected phone or signing secrets are needed for the
baseline CI checks. Native SDK builds and physical interoperability are separate
gates. A skipped test or successful JNI call is not evidence of ROS traffic.

For device tests, use only project apps and their own artifacts. Keep the phone
unlocked with the project foreground before UI interaction/capture. Never access
personal apps/files, change security settings or include identifiers, addresses,
recordings, raw device logs or signing keys in commits. Hardware trials require
an explicit operator-controlled setup; PR automation must not drive a robot.

## Pull requests and handoff

Explain the problem, resulting behavior, checks actually run and remaining limits.
Update the relevant public guide, compatibility status and [changelog](CHANGELOG.md)
when user-visible behavior changes.
Use an [architecture decision record](docs/decisions/0001-platform-and-examples.md)
for a consequential design choice. Leave enough build/configuration detail for
someone outside the original chat to reproduce the result. Separate measured
results from operator reports and pending work.

Maintainers may keep additional private journals; public instructions and
contracts must be sufficient for an external contributor. Preserve existing
license/notices and citation metadata. Contributions are under the project's
Apache-2.0 license. No CLA or automatic publication step is introduced here.
