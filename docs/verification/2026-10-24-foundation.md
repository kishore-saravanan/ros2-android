# Kotlin/NDK foundation verification — 2026-10-24

## Reference environment

- Physical phone: Realme RMX2085, Android 12, API 31.
- Phone reports ARM64 and 32-bit ARM support; this APK packages ARM64 only.
- ADB authorization granted by the user; ADB 37.0.1 installed outside the repo.
- Host: Java 21; ROS 2 Jazzy installation present at `/opt/ros/jazzy`.
- Build versions are pinned in `docs/DEVELOPMENT.md` and Gradle files.

## Executed checks

```bash
./tools/build.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest
./tools/run-device.sh
```

Results:

- Debug APK compiled and installed successfully through ADB.
- Three JVM configuration tests passed: accepted namespaces/domain boundaries,
  rejection of invalid namespaces, rejection of unsupported prototype domains.
- Two physical-device instrumentation tests passed, with no failures/skips.
- Native test loaded the packaged JNI library, queried its diagnostic string,
  and observed the native monotonic clock advance.
- Motion-sensor test received actual events, exercised repeated start,
  unregistered listeners, and confirmed no further samples after stopping.
- Android lint completed with zero errors. Remaining warnings include prototype
  English strings, newer available test dependencies, ChromeOS ABI coverage,
  and Android backup policy configuration. These are not compatibility proofs.
- Shell syntax checks passed for the build/install helper scripts.
- Machine-local SDK configuration and generated APK are excluded by `.gitignore`.

Reports are generated under `android/app/build/reports/`; detailed device test
output under `android/app/build/outputs/androidTest-results/`. These generated
directories are ignored and may contain device identifiers; do not commit them.

## Boundaries of this evidence

This proves the Kotlin/NDK toolchain, JNI loading, physical sensor access, and
basic monitor lifecycle on one Android phone. It does not prove ROS discovery,
ROS topic exchange, USB MCU communication, Agent portability, background capture,
camera/audio capture, robot-frame calibration, or support for other phones.

The app reports ROS and the Agent as not integrated. Its saved ROS profile is
configuration for the next milestone and currently starts no ROS node.

## Follow-up: keep awake and visual check

- Added activity `FLAG_KEEP_SCREEN_ON` and a visible development notice.
- Rebuilt, reran existing unit tests/lint, installed, and launched successfully.
- Visually verified the running app on the unlocked phone in landscape mode.
- Live IMU display showed both sensors registered and 4,452 actual sensor events,
  with acceleration and gyroscope values visible. This is a single observation,
  not a rate or latency benchmark.
- Phone power diagnostics reported awake with the display suspend blocker held.
- Realme denied changing the global stay-awake setting through ADB. Its value
  remains `0`; keep-awake applies while Robot Brain is foreground.
- Screenshots are local debug artifacts under ignored `device-logs/`.
