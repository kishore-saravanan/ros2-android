# Prepare another Linux development machine

Goal: use the installed host client to build an external workspace, with no
platform source edits. **This is the documented setup order.** On the reference OS,
the [scripted journey](../tests/integration/README.md#installed-client-journey) passes
from an empty home directory, Gradle home and Android SDK: managed build tools, locked
sources, the native C++ SDK and an external workspace APK. That run found and fixed a
native build step that only worked when an older install already existed. The same
journey also passes on a clean hosted Ubuntu 24.04 runner. Deploying to a phone from
such a freshly prepared machine remains to be shown.

For a guided, step-by-step setup see the [starter guide](STARTER-GUIDE.md). After installing the
client, `ros2-android setup workspace-tools` performs steps 1–3 below in one command; this page
is the detailed reference for doing them by hand. Install the client from the release (`.deb`, or the wheel with pipx).
The client contains tools, templates and sources, not Java, Android SDK/NDK or native
ROS binaries; the native SDK is a separate release download (step 3). Installing the
client does not install anything on a phone.

## 1. Prepare the Linux prerequisites

Use the reference Ubuntu 24.04 x86_64/Jazzy environment from the
[compatibility matrix](COMPATIBILITY.md). Install ROS 2 Jazzy and the host
C++/CMake/Ninja/Git, Python development and colcon prerequisites described in
[Development](DEVELOPMENT.md). Do not install ROS's Linux binaries into Android.

Install the supplied wheel using pipx:

```bash
pipx install /absolute/path/ros2_android-1.0.0-py3-none-any.whl
ros2-android --version
ros2-android compatibility
```

The build backends use a managed colcon/vcstool venv, unless `COLCON_BIN`
explicitly selects another executable. Prepare it, then check the host:

```bash
ros2-android setup build-tools
ros2-android doctor --profile host
```

This creates `~/.local/share/ros2-android-tools/build-venv` from `/usr/bin/python3`
with system site packages and installs `colcon-common-extensions==0.3.0` and
`vcstool==0.3.0`. If your host already has colcon/vcstool (for example ROS's
`ros-dev-tools` packages), it reuses those versions and only adds the venv's
`colcon`/`vcs` commands. It needs the OS Python venv package (Ubuntu 24.04:
`python3.12-venv`) and reports clearly when that is missing. Keep project Python
dependencies out of system pip. The reference build uses `/usr/bin/python3` 3.12
and the host ROS build generators, even when the client itself runs in a separate
environment.

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

**From a release (recommended):** download `ros2-android-native-sdk-VERSION-arm64-v8a.tar.xz`
and `SHA256SUMS` from the same release as your `ros2-android` client into one folder, then:

```bash
ros2-android setup sdk --archive ~/Downloads/ros2-android-native-sdk-VERSION-arm64-v8a.tar.xz
ros2-android doctor --profile android
```

This verifies the checksum and that the SDK was built from the same pinned inputs as
your client, then installs the native C++, Python/shell and micro-ROS Agent SDK (about
450 MB unpacked) into `~/.cache/ros2-android-native`. It replaces every build command
below; skip to step 4. An SDK from another release is refused. To swap an installed SDK,
add `--replace`; your sources and build trees are kept.

**From source:** these commands explicitly download pinned source/build inputs and
compile native ROS for Android. They may take substantial time and disk space:

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
path. Then deploy, start, observe and stop it. Observation needs the PC on the
phone's network (USB tethering or the same Wi-Fi) and the same ROS domain:

```bash
ws=/absolute/path/my_ws
ros2-android workspace --workspace "$ws" deploy
ros2-android workspace --workspace "$ws" start --domain 42
ros2-android workspace --workspace "$ws" status    # JSON; pause/battery hints on stderr
ROS_DOMAIN_ID=42 ros2-android workspace --workspace "$ws" ros -- topic list
ros2-android workspace --workspace "$ws" stop
```

`ros` runs the PC's ROS 2 CLI with the project's Cyclone DDS middleware and, after
`host-build`, this workspace's message types. `ros2-android workspace --help` lists
every step. See [workspace build/deployment](COLCON-WORKSPACES.md) for approval,
launch arguments and traffic probes. A diagnostic pass or successful APK build does
not establish ROS traffic or actuator behavior.

## Qualification record still required

For a fresh machine, record the client/matrix, OS/tool versions, SDK source locks,
actual build results, app/runner signing setup, device/Android version and an
independent ROS traffic probe. Test restart and rollback as well as success.
Keep private paths, keys, addresses and raw device logs out of public reports.
The release gate remains open until that complete journey is reproduced.

## Automated package slice

Before attempting a long native SDK build, maintainers can run the isolated
[installed-client smoke check](HOST-PACKAGE.md). It installs a wheel into a fresh
venv without pip, uses an empty tooling cache and masks existing SDK/ROS build
inputs. It validates diagnostic/help/inspection behavior without device access.
CI definitions cover Python 3.10 and 3.12; only the local Python 3.12 execution
has passed so far. Neither this check nor an ordinary venv substitutes for a
fresh Ubuntu machine's full SDK build/deploy qualification.
