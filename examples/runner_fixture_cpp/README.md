# C++ runtime fixture

A small, hardware-free factory runner for platform qualification. It subscribes
to relative `request` (`std_msgs/msg/Bool`) with volatile durability and publishes
relative `observed` (`std_msgs/msg/String`) every 200 ms:

```json
{"tick": 1, "count": 0, "last": null}
```

`tick` advances while running. `count` increments only when a request is received;
`last` becomes that Bool value. Every fresh node starts with count zero/last null.
There are no camera, file, sensor or actuator operations. These fields are a test
protocol, not a robot-control API or a real-time performance measurement.

See [Dashboard recovery qualification](../../docs/TESTING.md#dashboard-peer-recovery).
The Python fixture implements the same wire contract in a separate embedded runtime.
