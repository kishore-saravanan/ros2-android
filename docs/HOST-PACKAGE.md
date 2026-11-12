# Installable host client — local alpha

The [Debian package](DEBIAN-PACKAGE.md) bundles the Ubuntu host client and main
APK. The wheel described here provides the reusable host tooling.

The Python distribution is `ros2-android`; the installed command is
`ros2-android`. It contains the current tooling and Android build templates.
Users of a built wheel do not need a project checkout. This is a local alpha,
not a published PyPI package or a complete prebuilt runtime SDK.

## Install and use a built wheel

With pipx available, install the supplied artifact:

```bash
pipx install /absolute/path/ros2_android-0.1.0a1-py3-none-any.whl
ros2-android --version
ros2-android compatibility
ros2-android doctor --profile host
ros2-android workspace --workspace /absolute/path/android_test_ws inspect
```

`doctor` provides task-scoped JSON checks, pinned version checks and next actions;
it returns 1 for missing/incompatible prerequisites without installing anything
or contacting a phone. Use `host`, `android`, `shell` or `app` profiles. See the
[compatibility matrix](COMPATIBILITY.md) and [clean-machine setup order](CLEAN-MACHINE-SETUP.md).
Source ROS Jazzy in the terminal for commands that need PC ROS.

Once the documented native/Python SDK and Android build prerequisites are ready:

```bash
ros2-android workspace --workspace /absolute/path/android_test_ws host-build
ros2-android workspace --workspace /absolute/path/android_test_ws build
ros2-android workspace --workspace /absolute/path/android_test_ws deploy
ros2-android workspace --workspace /absolute/path/android_test_ws start --domain 0
ros2-android workspace --workspace /absolute/path/android_test_ws status
ros2-android shell --workspace /absolute/path/android_test_ws
ros2-android esp32 --serial PHONE_ADB_ID status
```

Pass `--serial PHONE_ADB_ID` before the workspace operation to select a phone.
Host and phone must share the intended ROS domain and discovery configuration.
The existing compatibility restrictions and backup/flash rules still apply.
See COLCON-WORKSPACES.md, ANDROID-SHELL.md and ESP32-PROGRAMMING.md.

`ros2-android project --help` exposes the older component/Python project backend.
`ros2-android setup --help` lists explicit setup operations: sources, native,
python, agent, app-build and app-install. They may download/build dependencies or
install project APKs only when explicitly invoked. Full prerequisites remain in
DEVELOPMENT.md. For example `setup build-tools` prepares the managed colcon venv,
`setup sources` fetches pinned native sources and links the shared SDK cache, and
`setup native --with-cpp` builds the native SDK.
Python/shell dependencies need their separate documented preparation.

For read-only APK identity, signature and replacement checks, use
`ros2-android apk inspect APK` or `ros2-android apk compare PREVIOUS REPLACEMENT`.
These commands do not extract the SDK, contact a phone or install an artifact.
See [upgrades and signing](UPGRADING.md) for requirements and decision limits.

## Maintainer build and verification

Use a Python environment with pip >=22.3, setuptools >=68 and wheel installed.
CI currently pins pip 26.2.2, setuptools 84.2.1 and wheel 0.48.0:

```bash
python setup/build-python-package.py
python setup/verify-python-package.py dist/ros2_android-0.1.0a1-py3-none-any.whl \
  --report dist/installed-client-report.json
```

The build helper stages the Python package and creates a snapshot of Git-tracked
SDK inputs only. Add intended new backend files to Git before building; untracked
files are deliberately excluded. Only android/native/setup/tools/examples/gradle
and root Gradle inputs enter the SDK snapshot. Device logs, local.properties,
SDK symlinks, credentials and generated outputs are not included. Use this helper
rather than plain `pip wheel .`, which lacks the generated SDK snapshot.

The wheel installs a console entry point. On first backend command it extracts
bundled files into `$XDG_CACHE_HOME/ros2-android/tooling/CONTENT_HASH` (default
`~/.cache`). Permissions for executable scripts are restored; extraction checks
paths and stages atomically. Existing workspace/native SDK caches remain in their
previous locations. Upgrading the wheel selects a separate content-keyed tooling
cache; it does not delete old builds, installed apps or user workspaces.

Verification creates a fresh venv without pip and uses the invoking pip's
`--python` option to install the supplied wheel offline (`--no-index --no-deps`).
It therefore does not need ensurepip inside the test environment. It removes
checkout imports/ROS overlays, masks Android/native prerequisites and isolates
the tooling cache. It checks the installed version/matrix, diagnostic failure
behavior, eight help screens and repeated read-only external-workspace inspection.

Archive gates reject unsafe/private/generated paths and signing-key extensions,
check legal files and compare matrix/SDK hashes before installation. Tool guards
fail the check if help/inspection invokes ADB, ROS CLI, colcon or source-fetch
commands. The optional JSON report contains versions, a snapshot hash and named
checks, without local paths or device data. The temporary venv is removed.

The CI workflow defines installed-client jobs for Python 3.10 and 3.12 on Ubuntu
24.04. Local verification currently covers Python 3.12; the new hosted jobs need
their first run after pushing. This check does not cross-build the SDK, deploy a
phone, prove ROS traffic or qualify a complete fresh-machine installation.

## Current limits

- A tested Ubuntu/Jazzy development machine is still required for builds. The
  wheel contains build sources/templates, not Android SDK/NDK, native ROS binaries,
  Java, colcon or the APK. No turnkey clean-machine build is claimed.
- Backend error messages may still mention repository script paths; public command
  migration is incremental. Existing scripts remain for contributors.
- SDK preparation, full build/deploy, shell and flashing are forwarded to existing
  backends. Their packaging-specific end-to-end qualification is pending; their
  earlier checkout tests do not establish wheel parity.
- Apache-2.0 is selected for original project material; LICENSE/NOTICE are
  included in wheel metadata and the backend snapshot, with CITATION.cff in the
  snapshot. No public publishing. Third-party notices and release gates still
  require review before public distribution.

## Implementation boundaries

Project/workspace orchestration, shell connection, phone-mediated programming and
pinned native-source preparation live in the installed Python package. Source
checkout entry points forward to those modules. Native compiler/build, staging
and Android installation drivers remain bundled SDK scripts; the client invokes
them explicitly. Installing the wheel does not run those drivers or flash a board.
