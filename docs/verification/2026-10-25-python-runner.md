# Embedded Python ROS verification — 2026-10-25

Physical Android 12 ARM64 phone, ROS 2 Jazzy PC, USB tethering IP link.
Project source fingerprint: 1159872739c6. Device logs, identifiers, screenshots,
APKs and generated SDK outputs remain ignored by Git. Only the project's apps,
private generated resources and hardware diagnostics were accessed.

## Build and runtime

- Separate Python SDK: 77 packages, including rclpy 7.1.12 and custom interfaces.
- Staged 105 ARM64 libraries and 29 Python extension modules.
- CPython 3.12.15 embedded with Chaquopy 17.1.0; NumPy 1.26.2, PyYAML 6.0.3.
- PC headers: NumPy 1.26.4, pybind11 2.11.1, Android NDK 27.2.12479018.
- Native bindings compile against Android CPython headers and libpython, rather
  than linking host Python binaries. Staging checks architecture and dependency
  closure and rejects absolute DT_NEEDED paths.
- Python APK build and lint passed. Main app assembly, unit tests and lint passed.
- C++ runner assembly/lint passed after separating the native backend from shared
  runner lifecycle/UI sources. Eight existing launch-validation tests passed.

## Physical interoperability passed

The native Python node `/python/python_demo` executed inside its own Android
runner app. Runtime diagnostics reported Python 3.12.15 and an advancing executor
heartbeat. It remained active while the main sensor app and C++ runner were in
front.

The final PC probe passed all six criteria:

1. Expected Python status topic content.
2. Exact nonce command reply from Python.
3. Actual `/phone/imu` reception.
4. Python-computed acceleration magnitude matching recent raw IMU within 0.05 m/s².
5. Custom `demo_interfaces/msg/RuntimeStatus` message content and sample count.
6. Python → phone C++ demo → Python reply matching the same nonce, without a PC
   bridge executing either node's callbacks.

A PC ROS parameter service set the Python message to "Python parameter verified".
All six probe criteria passed again with that text, including custom telemetry.
The default message was restored afterwards.

## Recovery and regression passed

The recovery tool triggered a deliberate Python callback exception after three
seconds. The runner reported the traceback, stopped its native context/node,
removed its foreground service, and restarted explicitly with a fresh heartbeat
and no retained active error. This test covers caught Python exceptions, not a
fatal native signal or a callback that cannot return.

A restart-tool race was found: a previous traceback could be visible during the
new Starting state. The CLI now uses lifecycle failure states to decide startup
failure instead of treating retained diagnostics as a new failure. The repeated
physical recovery test passed after this fix.

The rebuilt C++ ABI-2 workspace also ran both nodes and passed its remapped
pipeline and parameter-service probe. The C++ SDK output directory remained
separate from the Python SDK. The existing main sensor app supplied the IMU
throughout the final Python interoperability probes.

## Scope boundaries

This is native rclpy feasibility on one Android phone. Arbitrary pip/native
packages, Python multi-node launch, ROS Python launch-file execution, long runs,
physical PC disconnection, Wi-Fi Python qualification, fatal native crashes and
micro-ROS Agent integration remain pending. Packaging/import failures encountered
during development were corrected before the successful traffic tests; library
compilation or JNI diagnostics alone were not used as interoperability evidence.
