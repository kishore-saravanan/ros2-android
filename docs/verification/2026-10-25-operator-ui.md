# Operator UI verification — 2026-10-25

Initial 0.1.1-dev2 operator experience, physical Android 12 ARM64 phone and USB IP.
No personal phone apps/data were inspected. Screenshots and device logs stay in
ignored outputs, not this repository.

## Passed

- tools/build.sh: main APK assembly, unit tests and lint; instrumentation APK
  assembly also passed. Installed through tools/run-device.sh.
- Seven physical instrumentation checks: native-library/clock smoke test; actual
  sensor samples and listener release; edited IMU topic with real publication and
  locked fields; sensor draft and selected-screen preservation through navigation
  and Activity recreation; live camera thumbnail, continued publication across
  navigation and thumbnail restoration after recreation; camera/IMU continuation
  through Activity recreation/background and full stop/restart/notification Stop.
- Preview-visible five-second sample reported 28.2–28.8 successful local ROS camera
  publications per second. This is not PC received FPS or a long-duration result.
- A concurrent PC ROS probe received three valid JPEG CompressedImage messages
  on the back-camera topic over the USB IP path. The probe did not retain pixels;
  it establishes traffic during the test session, not a synchronized benchmark
  of the exact preview interval or new Wi-Fi qualification.
- Overview and Sensors were visually inspected in landscape while the allowed project app was foreground
  and the lock screen absent. A compact landscape header was added and the final
  build/unit/lint checks passed. Full screen/font/device visual qualification is
  still pending.

## Limits

This initial pass uses Android Views, not Compose. No installed-project manager,
peer-receipt handshake, preview orientation correction, resolution/quality picker,
full accessibility audit, new-device qualification or long-duration preview soak
is claimed. Existing runner screens were not redesigned. The community-alpha
first-user acceptance trial remains pending. Google Play work is deferred.

## Battery onboarding follow-up

Added BatteryManager capacity publishing as the default first-message example:
/phone/battery_percent, std_msgs/msg/Float64, values 0–100, default 1 Hz.
Build, unit tests and lint passed. An additional BatteryUiTest passed on the
physical phone: actual publication, continued counts across navigation and
Activity recreation, then full session stop. The hardware read was 100 percent;
a concurrent PC ROS topic echo received data: 100.0 over the USB IP link.
No permission prompt was needed. IMU topic names and functionality are unchanged.
