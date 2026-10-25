# Launch several C++ nodes on Android

The ABI-2 runner composes 1–16 compiled C++ nodes in one Android project process.
Each entry gets its own node name, namespace, scalar parameters and explicit
remappings. The phone executes every callback; the PC builds and deploys.
This is an initial CMake project workspace, not general cross-colcon support.

## Run the example

Build the Android C++ SDK first as described in [CPP-PROJECTS.md](CPP-PROJECTS.md).
Then, from the repository:

```bash
./tools/ros2android --project examples/cpp_workspace build
./tools/ros2android --project examples/cpp_workspace deploy
./tools/ros2android --project examples/cpp_workspace start --peer PC_IP --local PHONE_IP
./tools/ros2android --project examples/cpp_workspace status
```

Use current Wi-Fi or USB-tethering interface addresses. The phone must be unlocked.
Status lists `/robot/producer` and `/robot/processor` and their shared executor
heartbeat. The producer sends a counter; the processor transforms each message:

```text
/robot/producer → /robot/pipeline/input → /robot/processor → /robot/pipeline/result
```

Monitor the result and change a node's parameter:

```bash
./tools/ros2android --project examples/cpp_workspace ros --interface PC_IP -- topic echo /robot/pipeline/result
./tools/ros2android --project examples/cpp_workspace ros --interface PC_IP -- param set /robot/producer message 'Hello from my robot'
./tools/ros2android --project examples/cpp_workspace stop
```

## Launch format

`robot-project.json` declares `runtimeAbi: 2` and the project-relative
`defaultLaunch: "launch/robot.launch.json"`. That launch file is embedded in the
APK so the phone's Start button can run it without a PC.

```json
{
  "schemaVersion": 1,
  "nodes": [
    {
      "component": "producer",
      "name": "producer",
      "namespace": "/robot",
      "parameters": {"message": "Computed on Android", "period_ms": 200},
      "remappings": {"output": "pipeline/input"}
    },
    {
      "component": "processor",
      "name": "processor",
      "namespace": "/robot",
      "parameters": {"prefix": "Phone processed: "},
      "remappings": {"input": "pipeline/input", "output": "pipeline/result"}
    }
  ]
}
```

`component` selects a factory compiled into the APK; it does not load a binary
from phone storage. The exported ABI-2 factory is
`ros2_android_create_component(component, options)`. Each call must return a new
node and honor its supplied NodeOptions. The runner owns the resulting nodes.
Existing ABI-1 single-node projects keep their original factory.

Node names must be unique within their resolved namespaces. Namespace defaults
to the CLI `--namespace` value if omitted. Per-node identity and topic remaps take precedence over common CLI remaps.
Configure per-node parameters in JSON; avoid conflicting parameter names in
common CLI ROS arguments. Parameters support strings, booleans,
int64 integers and finite floating-point scalars. Arrays, parameter YAML files,
wildcards, reserved name remaps and unknown fields are rejected. Use `name` and
`namespace` to set node identity.

Use `start --launch /absolute/config.json` before the optional `--` ROS arguments
to supply a different configuration. External configurations can change runtime
settings without a rebuild; a launch file inside the project contributes to its
source fingerprint and requires rebuilding after edits. The phone UI's Start
button uses the packaged default, not a previous CLI override.

## Lifecycle and limits

Start constructs all nodes, then starts the shared executor. A factory error
rolls back the partial group. A caught callback exception stops the entire group
and reports failure. Stop tears down the complete group; restart is explicit.
The hardware/sensor application remains a separate process.

These nodes share a single executor thread and Android process. A blocking
callback can stall the group; a fatal native crash can terminate it. Independent
node workers, automatic restart policies and per-worker fault isolation are
future work. This is not a complete ROS launch interpreter: Python launch files,
includes, substitutions, conditions, timers, external executables and rclpy are
unsupported. Full colcon package workspaces and MCU autonomy remain pending.

## Verification commands

```bash
python3 -m unittest discover -s tools/tests -v
./tools/pc-ros.sh --interface PC_IP -- python3 tools/pc-workspace-probe.py
python3 tools/test-workspace-recovery.py --peer PC_IP --local PHONE_IP
```

The traffic probe requires the two default nodes and remapped pipeline, checks
matching input/output counters, and reads each node's parameter through actual
ROS services. The recovery test checks validation without disturbing an active
group, rollback after a second factory fails, callback failure cleanup and restart.
Physical PC link disconnection remains a separate qualification test.
