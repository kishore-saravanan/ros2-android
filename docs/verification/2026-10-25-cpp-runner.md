# Android C++ project runner verification — 2026-10-25

Physical Android 12 / ARM64 phone, ROS 2 Jazzy PC, USB tethering IP link.
No personal apps or shared phone storage were accessed. Generated output and
device logs are ignored by Git.

## Passed

- Pinned Android C++ SDK build: 73 packages; rclcpp and custom ROSIDL C++ types.
- Runner APK assembly and lint; main app assembly, unit tests and lint.
- Install through tools/run-device.sh; source fingerprint c377209f2444.
- Native node /demo/cpp_demo executed on the phone in its own app/process.
- PC received status and custom demo_interfaces/msg/RuntimeStatus.
- PC command nonce returned as the exact C++ processed reply.
- Phone node consumed live /phone/imu and published acceleration magnitude;
  PC compared the result to recent raw samples within 0.05 m/s² tolerance.
- All five probe criteria passed for both default and remapped configurations.
- PC Cyclone parameter service set the message parameter successfully.
- Remapping created /demo/phone_worker and /demo/worker_status, with initial
  message parameter "Remapping verified"; custom telemetry and command echo passed.
- Force-stopping the runner left /phone/imu publishing. A subsequent explicit
  start restored project traffic. Cooperative stop/start also passed.
- A different app UID could not invoke the DUMP-protected debug Stop receiver;
  privileged shell control worked. Release manifest omits the receiver.
- Stale installed source fingerprint was rejected before project execution.

The source update from the first installed runner to this build changed the
C++ default message and added a custom interface. The updated binary was
installed and its new payloads received on the PC.

## Observations and limits

PC Fast DDS topic traffic passed in earlier runner probes, but parameter service
calls did not complete across the tested middleware combination. The scoped
PC Cyclone wrapper resolved parameter calls without changing system defaults.
A fresh-install foreground launch race was observed; CLI startup now waits for
its activity to become resumed before requesting the foreground service.

Physical PC link removal, deliberate native crash recovery, hung callbacks,
one-hour soak, Wi-Fi custom-message qualification, multiple phones, Python,
launch files and embedded micro-ROS Agent are not validated by these results.
Force-stop recovery does not prove native crash handling. Initial runner UI is
a developer console; the full Compose operator UI remains pending.
