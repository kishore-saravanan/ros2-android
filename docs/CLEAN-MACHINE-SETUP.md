# Prepare another Linux development machine

Goal: use the installed host client to build an external workspace, with no
platform source edits. **This is the documented setup order; a full fresh-machine
build/deploy qualification is still pending.** Installed-client inspection and an
external C++ build using a prepared SDK have passed separate tests. Those are not
evidence of a turnkey fresh installation.

There is no public APT/PyPI installer yet. Obtain a maintainer-built local alpha
wheel, or use the contributor [build guide](DEVELOPMENT.md) to produce one. The
wheel contains client/templates/sources, not Java, Android SDK/NDK, native ROS
binaries or a main APK. Installing it does not install anything on a phone.

## 1. Prepare the Linux prerequisites

Use the reference Ubuntu 24.04 x86_64/Jazzy environment from the
[compatibility matrix](COMPATIBILITY.md). Install ROS 2 Jazzy and the host
C++/CMake/Ninja/Git, Python development and colcon prerequisites described in
[Development](DEVELOPMENT.md). Do not install ROS's Linux binaries into Android.

The current backends use a managed build venv, unless `COLCON_BIN` explicitly
selects another executable:

```bash
python3 -m venv --system-site-packages "$HOME/.local/share/ros2-android-tools/build-venv"
"$HOME/.local/share/ros2-android-tools/build-venv/bin/python" -m pip install \
  colcon-common-extensions==0.3.0 vcstool==0.3.0
```

If venv/pip support is absent, install the OS's Python venv prerequisite first.
Keep project Python dependencies out of system pip. The reference build uses
`/usr/bin/python3` 3.12 and the host ROS build generators, even when the client
itself runs in a separate environment.

Install the supplied wheel using pipx:

```bash
pipx install /absolute/path/ros2_android-0.1.0a1-py3-none-any.whl
ros2-android --version
ros2-android compatibility
ros2-android doctor --profile host
```

Fix failed checks before proceeding. Doctor does not source a shell: source
`/opt/ros/jazzy/setup.bash` separately when using PC ROS commands. Most build
backends source their configured/default Jazzy setup internally.

## 2. Prepare Android tools

Install JDK 21 and the official Android command-line tools outside the repository.
Set `JAVA_HOME` to that JDK and `ANDROID_HOME` to your SDK (default `~/Android/Sdk`).
After reviewing/accepting SDK licenses with the official tools, install the pins:

```bash
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
  'platform-tools' 'platforms;android-35' 'build-tools;35.0.0' \
  'ndk;27.2.12479018' 'cmake;3.22.1'
ros2-android doctor --profile android
```

Native SDK/source checks will still fail at this point on a clean machine. That
is expected. Fix Java/Android/host-tool failures first; the next step creates the
missing project SDK artifacts. Another NDK revision is not a substitute for the
pinned one.

## 3. Prepare the project SDK

These commands explicitly download pinned source/build inputs and compile native
ROS for Android. They may take substantial time and disk space:

```bash
ros2-android setup sources
ros2-android setup native --with-cpp
ros2-android doctor --profile android
```

This prepares the native C++ workspace path. Stop on the first build error. The
client uses a content-keyed tooling cache and shared native build cache; your
workspace source remains separate. Native cache paths must avoid spaces because
some upstream generators cannot handle them.

For the default workspace build **with the on-phone ROS CLI**, first install the
additional host requirements in [Python projects](PYTHON-PROJECTS.md), then:

```bash
ros2-android setup python
ros2-android doctor --profile shell
```

This prepares the embedded Python/shell SDK. It does not enable arbitrary
`ament_python` colcon workspaces. To omit the shell, use `--without-shell` on both
workspace inspect and build.

## 4. Prepare the main app and external workspace

If a compatible main APK is already supplied/installed, use its actual public
certificate for independently signed runner builds. Otherwise, building the main
APK from the client requires the Agent SDK too:

```bash
ros2-android setup agent
ros2-android doctor --profile app
ros2-android setup app-build
```

Only when ready to access a development phone, enable/authorize its USB debugging,
keep it unlocked, and explicitly install via `ros2-android setup app-install`.
No device operation above is automatic. See [first message](GETTING-STARTED.md)
and [runner authorization](RUNNER-AUTHORIZATION.md).

Point the client at your external workspace. Inspect the declared providers and
unsupported types before starting a build:

```bash
ros2-android workspace --workspace /absolute/path/my_ws inspect --without-shell
ros2-android workspace --workspace /absolute/path/my_ws host-build
ros2-android workspace --workspace /absolute/path/my_ws build --without-shell \
  --controller-apk /absolute/path/to/the-main-app.apk
```

The `--controller-apk` file must be the main APK installed on the phone; only its
public certificate is used. Omit it for the existing same-signature development
path. Use [workspace build/deployment](COLCON-WORKSPACES.md) for deploy, approval,
start, status, traffic verification and stop. A diagnostic pass or successful APK
build does not establish ROS traffic or actuator behavior.

## Qualification record still required

For a fresh machine, record the client/matrix, OS/tool versions, SDK source locks,
actual build results, app/runner signing setup, device/Android version and an
independent ROS traffic probe. Test restart and rollback as well as success.
Keep private paths, keys, addresses and raw device logs out of public reports.
The release gate remains open until that complete journey is reproduced.
