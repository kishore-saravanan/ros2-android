# C++ runtime fixture

A small, hardware-free factory runner for platform qualification. It subscribes
to relative `request` (`std_msgs/msg/Bool`) with volatile durability and publishes
relative `observed` (`std_msgs/msg/String`) every 200 ms:

```json
{"trial": "default", "tick": 1, "count": 0, "last": null, "stamp_ms": 123456}
```

The bounded `trial` string parameter identifies one test run (1–64 letters, digits,
underscores or hyphens). Set a fresh value on each start to distinguish queued
telemetry from the previous process. `tick` advances while running. `count` increments only when a request is received;
`last` becomes that Bool value. Every fresh node starts with count zero/last null.
`stamp_ms` is the phone's boot clock (`CLOCK_BOOTTIME`) when the sample was
published, the same clock as Android `SystemClock.elapsedRealtime()`. A test on
the same phone can therefore tell whether a telemetry gap started at the
publisher (stamps pause) or during delivery (stamps continue). It is not
comparable with another device's clock.
There are no camera, file, sensor or actuator operations. These fields are a test
protocol, not a robot-control API or a real-time performance measurement.

See [Dashboard recovery qualification](../../docs/TESTING.md#dashboard-peer-recovery).
The Python fixture implements the same wire contract in a separate embedded runtime.
