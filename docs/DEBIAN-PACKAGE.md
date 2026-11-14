# Debian package

Distribution target: Ubuntu 24.04/Jazzy developer PCs. The package contains the
installed `ros2-android` client, build templates and the ARM64 main Android APK.
The host files are architecture independent; the phone APK requires ARM64 and
Android API 26 or newer. This minimum does not mean every such phone is tested.

## User workflow

Download the `.deb` from the [latest release](https://github.com/kishore-saravanan/ros2-android/releases), then:

```bash
sudo apt install ./ros2-android_1.0.0-1_all.deb
ros2-android doctor
ros2-android device setup --serial PHONE_ADB_ID
```

Enable USB debugging and authorize the PC first. Use an authorized USB or wireless
ADB target. Omit `--serial` only for a single USB phone. Setup validates bundled
identity metadata/checksum and phone API/ABI, then uses `adb install -r` to update
only dev.ros2android.android. It neither uninstalls the app nor clears its data,
grants runtime permissions or opens it. Open ROS2 Android on the phone afterward.
A signing-key mismatch is an error; there is no automatic uninstall workaround.

PC package installation/removal has no phone side effects: no maintainer scripts,
ADB calls, app deletion or user-cache cleanup. Device operations are explicit.
The package depends on Python 3, bash and ADB; installing the controller APK does
not require Android SDK/NDK or aapt. Building workspaces still requires the full
Android/Jazzy toolchain; doctor is a prerequisite presence check only.

Release packages bundle the release-signed APK from the same release; its
certificate is published in [SECURITY.md](../SECURITY.md#verify-a-downloaded-apk).
A locally built package can bundle a debug APK only with `--allow-debug-apk`, for
testing. The project is Apache-2.0; third-party components are listed in
THIRD-PARTY-NOTICES.md, installed under `/usr/share/doc/ros2-android/`.
Remove the PC tool with `sudo apt remove ros2-android`; this does not touch phones.

## Maintainer build

Build/unit/lint first using setup/build.sh. Prepare the wheel in a Python
environment containing pip, setuptools >=68 and wheel:

```bash
./setup/build.sh
python setup/build-python-package.py
python setup/build-deb.py \
  --wheel dist/ros2_android-1.0.0-py3-none-any.whl \
  --apk android/app/build/outputs/apk/debug/app-debug.apk \
  --allow-debug-apk
python setup/verify-deb.py dist/ros2-android_1.0.0-1_all.deb
```

The wheel snapshot includes tracked backend files only; stage intended inputs
before building. The Debian builder uses the configured Git maintainer identity
(or explicit --maintainer), validates APK identity/SDK/ABI using build-tools aapt,
checks its signature with apksigner, and records its SHA-256 manifest. It requires
the maintainer's Android build-tools 35.0.0 and dpkg-deb. No administrator access,
signing-key creation or repository publication is performed. Private Android
signing keys, personal files and device logs are not included.

The client/backend live under /usr/lib/ros2-android; the launcher is in /usr/bin;
project license/citation documents are in /usr/share/doc/ros2-android. The APK is
private package data under the client. Existing user SDK/workspace caches remain
outside these system paths. APT/dpkg control package integrity after repository
signature verification; the APK checksum detects accidental bundle mismatch and
is not an independent trust root.

## Actual validation and remaining gates

The rootless verifier extracts the actual .deb and runs its launcher outside the
checkout with a disposable home/cache. It checks there are no maintainer scripts,
backend help works, and device setup performs only the expected version/ABI checks
and update through fake ADB. This does not prove real dpkg/APT install, upgrade or
uninstall on a clean machine, or deployment from a system-installed Debian package.

A separate real phone trial successfully updated the bundled main APK using the
extracted Debian launcher's device setup over authorized wireless ADB. No Activity
launch, permission grant, uninstall or data clear was performed. Android
assembly/unit/lint, wheel smoke, Debian extraction smoke and all 32 tooling tests
passed. The local artifact is about 8.6 MiB. No new ROS traffic trial is implied.

Before public APT: clean Ubuntu install/upgrade/removal trials, actual bundled
phone deployment, source-package/reproducible-build and Debian policy/lint review,
production APK signing, upstream notices, supported matrix, signing-key custody,
HTTPS repository and Signed-By setup instructions. No public repo exists yet.
