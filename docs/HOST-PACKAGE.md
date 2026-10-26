# Installable host client — local alpha

The Python distribution is `ros2-android`; the installed command is
`ros2-android`. It contains the current tooling and Android build templates.
Users of a built wheel do not need a project checkout. This is a local alpha,
not a published PyPI package or a complete prebuilt runtime SDK.

## Install and use a built wheel

With pipx available, install the supplied artifact:

```bash
pipx install /absolute/path/ros2_android-0.1.0a1-py3-none-any.whl
ros2-android --version
ros2-android doctor
ros2-android workspace --workspace /absolute/path/android_test_ws inspect
```

`doctor` reports prerequisite presence as JSON and returns 1 if something is
missing. It does not install anything, verify tool versions or test a phone.
Source ROS Jazzy in the terminal for commands that need PC ROS/colcon.

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
DEVELOPMENT.md. For example `setup sources` fetches pinned native sources and
links the shared SDK cache; `setup native --with-cpp` builds the native SDK.
Python/shell dependencies need their separate documented preparation.

## Maintainer build and verification

Use a Python environment with pip, setuptools >=68 and wheel installed:

```bash
python setup/build-python-package.py
python setup/verify-python-package.py dist/ros2_android-0.1.0a1-py3-none-any.whl
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

Verification installs offline into a disposable venv, runs five backend help
commands and inspects a temporary external workspace. It rejects private/generated
SDK paths and removes its own temporary environment. It does not cross-build or
flash hardware.

## Current limits

- A tested Ubuntu/Jazzy development machine is still required for builds. The
  wheel contains build sources/templates, not Android SDK/NDK, native ROS binaries,
  Java, colcon or the APK. No turnkey clean-machine build is claimed.
- Backend error messages may still mention repository script paths; public command
  migration is incremental. Existing scripts remain for contributors.
- SDK preparation, full build/deploy, shell and flashing are forwarded to existing
  backends. Their packaging-specific end-to-end qualification is pending; their
  earlier checkout tests do not establish wheel parity.
- No public publishing or license selection. This local artifact is for maintainer
  validation until licensing/notices and release gates are complete.
