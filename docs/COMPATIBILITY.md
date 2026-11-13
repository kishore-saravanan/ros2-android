# Compatibility matrix

This is the 1.0.0-beta.1 combination, not a stable release or a
promise that every Linux ROS package/Android phone works. The machine-readable
[source matrix](../python/ros2android/compatibility.json) is included in the host
wheel. Tests check it against the app, build pins, contracts and source locks.

```bash
ros2-android compatibility
```

From a source checkout, prefix any command with `PYTHONPATH=python python3 -m
ros2android.cli` (for example `... compatibility` or `... doctor`). Workspace and
project commands then run their backends in place, like the `tools/` wrappers.

This command prints the matrix without extracting the SDK or contacting a phone.
It describes the client's expected inputs, not what is installed on the phone.
Built wheels also include `artifact.sdkSnapshotSha256`, identifying the exact
bundled tooling content. This matters because preview versions may repeat across
source builds. The source-checkout matrix has no artifact stamp. A content hash
is identification, not a signature or proof of binary provenance.

| Layer | Current expected input |
| --- | --- |
| Host client | `ros2-android` 1.0.0b1; client Python 3.10+ |
| Reference build machine | Linux x86_64, Ubuntu 24.04, system ROS-build Python 3.12 |
| Host/target ROS | ROS 2 Jazzy; native `rmw_cyclonedds_cpp` |
| Main app | 1.0.0-beta.1, versionCode 9 |
| Android target | ARM64 (`arm64-v8a`), build minimum API 26, compile/target API 35 |
| Android SDK tools | Build tools 35.0.0, NDK 27.2.12479018, SDK CMake 3.22.1 |
| Build system | JDK 21 runs Gradle 8.9; AGP 8.7.3, Kotlin 2.2.21; bytecode target 17 |
| Project manifest | schemaVersion 1 |
| Management protocol | version 1; additive version/capability envelope with legacy v1 support |
| C++ component runtime | ABI 1 or 2 |
| Embedded Python project | ABI 1; CPython 3.12.15 |
| Ordinary C++ workspace | ABI 2; native process runner; optional shell bundle |
| Native inputs | Exact hashes for checked-in ROS, Python, shell and PC-Cyclone lock files |

An API minimum is not a tested-device list. To add a phone, follow
[qualify a phone](PHONE-QUALIFICATION.md). Physical evidence comes from one Android
12 reference phone; an Android 15 emulator run with ARM translation adds UI, ROS
session and runner compatibility checks, not phone qualification. Reference traffic/device evidence,
including limits of the independent-signing preview and new protocol envelopes,
is in [Status](STATUS.md). Mixed Python/C++ colcon workspaces remain unsupported;
standalone Python examples use their separate backend.

## Physical and emulator qualification

| Environment | Current qualification |
| --- | --- |
| Reference ARM64 Android 12 phone | Native ROS, C++/Python runners, USB micro-ROS and recovery checks; bounded endurance results in [Status](STATUS.md). |
| Android 15 emulator (ARM translation) | UI, accessibility and phone-local ROS/runner checks; ARM Agent executables are not qualified under translation. |
| Samsung SM-S926B, Android 17 / API 37 | Initial beta suite: 19 passed, 4 accessibility-window/dialog checks failed, 35 opt-in checks skipped. After rebuilding native libraries for 16 KB alignment and replacing fixed focus delays with bounded waits, all 4 accessibility checks pass. The full-suite repeat passes (23 pass, 35 opt-in skips), plus C++ peer recovery and runner update. Python peer recovery also passes. Sustained background operation remains unqualified: Samsung OLAF freezes the runner during app/system UI transitions despite its foreground service and battery exemption, correlating with a failed 2.6-second pause. A later passing short run does not supersede those failures; support is scoped to these results. |

## Native memory-page compatibility

Every bundled ARM64 ELF must have load segments aligned for 16 KB pages.
The APK checker includes native code inside nested Python `.imy`/ZIP assets. The
release assembler/verifier and SDK packager reject incompatible binaries. YAML
vendor and micro-ROS Agent sub-builds receive the Android page-size flags explicitly,
including when cached Agent dependencies are rebuilt. This follows
[Android's page-size guidance](https://developer.android.com/guide/practices/page-sizes).

```bash
python3 setup/check-native-alignment.py --apk path/to/app.apk
```

This inspects ELF layout without executing the code. It does not establish runtime
compatibility on a 16 KB kernel; keep that device test separate. The Samsung used
for the Android 17 checks currently reports 4 KB pages, so it cannot qualify 16 KB
runtime behavior. Apps retain extracted native-library packaging for runner executables.

The current Chaquopy NumPy wheels include 4 KB native extensions in Python assets;
the ball follower also brings a 4 KB FreeType dependency. Those Python APKs remain
restricted to the tested 4 KB-page environments and fail the complete alignment
gate. A successful Python build or a warning-free main app does not establish
16 KB Python support. These dependencies need compatible replacements/rebuilds
before that claim can be made; no compatibility warning is suppressed.

## What must match

- Use client/templates from the same supported source snapshot. The matrix is
  bundled with the client; it does not silently update from the network.
- SDK source locks must match that client. Compiled SDK artifacts must be rebuilt
  when required by source/runtime changes. Matching lock files alone do not prove
  which inputs produced existing binaries.
- Controller and runner must use compatible management metadata/envelopes and
  satisfy certificate authorization. Version compatibility never grants trust.
- A manifest's runtime ABI must match its runtime backend. Changing a number is
  not a migration; rebuild/deploy the runner using compatible inputs.
- ROS peers need matching domain, supported types and compatible QoS/discovery.
  Host/phone client versions alone do not establish network communication.

## Preflight

```bash
ros2-android doctor --profile host
ros2-android doctor --profile android
ros2-android doctor --profile shell
ros2-android doctor --profile app
```

| Profile | Journey checked |
| --- | --- |
| `host` | Linux reference prerequisites, system Python, ROS setup, configured colcon and host build tools |
| `android` (default) | Host prerequisites plus JDK/Android tools, source locks and representative native C++ SDK artifacts |
| `shell` | Android workspace prerequisites plus representative Python/shell SDK artifacts |
| `app` | Android prerequisites with native/staged ROS and phone Agent artifacts for the main APK |

Each report is JSON with `schemaVersion: 1`, `checks`, `missingOrIncompatible`
and `prerequisitesPresent`. Exit 0 means the selected checks pass; exit 1 means
some are missing/incompatible. Each failed row gives a next action. This replaces
the old unversioned presence-only `checks` map; callers should use the report
schema and profile.

Doctor does not install anything, accept licenses, source ROS, build code,
connect to ADB or query a phone. It only probes system Python, Java and SDK CMake
with version arguments and reads expected files. It checks `JAVA_HOME` and the
configured managed colcon path rather than trusting any unrelated PATH entry.
SDK Manager/ADB/build tools may be installed outside PATH.

For an installed client, doctor locates an already extracted content-keyed SDK
without extracting it. Before `setup sources`, absent prepared inputs are expected
and reported. For contributors, the source checkout is detected; an explicit
matching path can also be selected:

```bash
ros2-android doctor --profile android --tooling-root /absolute/path/to/ros2-android
```

Checks do not validate every host Python module/system library, SDK license,
native binary provenance, dependency closure, signing identity or runtime behavior.
A pass is a preflight result, not a successful build or a robot qualification.
Follow [clean-machine setup](CLEAN-MACHINE-SETUP.md), then verify actual build and
ROS traffic. Version combinations beyond this preview remain unqualified.
