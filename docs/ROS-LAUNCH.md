# ROS launch files for Android components

For native on-phone LaunchService and ROS commands, see [Android shell](ANDROID-SHELL.md).
This document describes the separate PC-side adapter used to generate app group plans.

This milestone accepts a supported subset of ordinary Jazzy `.launch.py` files.
The PC evaluates arguments, substitutions, conditions and includes, then compiles
the description into an Android component plan. That resolved default is packaged
in the APK. The phone starts its native nodes without evaluating the launch file
on the PC again. It is not a full on-phone ROS LaunchService.

## Try the example

The example uses ordinary imports from `launch` and `launch_ros`. Its root
`launch/robot.launch.py` declares arguments, creates a scoped namespace and
includes `launch/nodes.launch.py`, which declares two Node actions.

```bash
./tools/ros2android --project examples/cpp_workspace launch-plan
./tools/ros2android --project examples/cpp_workspace build
./tools/ros2android --project examples/cpp_workspace deploy
./tools/ros2android --project examples/cpp_workspace start --peer PC_IP --local PHONE_IP
./tools/ros2android --project examples/cpp_workspace status
./tools/pc-ros.sh --interface PC_IP -- python3 tools/pc-workspace-probe.py
```

Use current USB-tethering/Wi-Fi addresses. The phone must be unlocked for startup.
The default creates `/robot/producer` and `/robot/processor` and the remapped
`/robot/pipeline/input` → `/robot/pipeline/result` flow.

Build with different defaults:

```bash
./tools/ros2android --project examples/cpp_workspace build \
  --launch-arg robot_namespace:=launchbot --launch-arg 'message:=Hello robot'
./tools/ros2android --project examples/cpp_workspace deploy
./tools/ros2android --project examples/cpp_workspace start --peer PC_IP --local PHONE_IP
```

Start without overrides uses the exact plan baked into that APK, including those
build arguments. Build metadata includes a `launchId` hash of the resolved plan.
`launch-plan` prints the plan for review and does not start ROS node processes.

To override launch configuration for one run, stop first, then pass arguments or
an explicit launch file before the optional `--` common ROS arguments:

```bash
./tools/ros2android --project examples/cpp_workspace stop
./tools/ros2android --project examples/cpp_workspace start --peer PC_IP --local PHONE_IP \
  --launch-arg enable_processor:=false
./tools/ros2android --project examples/cpp_workspace stop
./tools/ros2android --project examples/cpp_workspace start --peer PC_IP --local PHONE_IP \
  --launch examples/cpp_workspace/launch/robot.launch.py
```

The first override resolves the source file's defaults plus the provided value;
it does not merge arguments from a previous build. It starts only the producer.
The second explicitly resolves the file's default two-node plan. Overrides are
not saved as the phone UI's default. Editing a file inside the project changes
its source fingerprint and requires a rebuild/deploy before starting.

## Register Android implementations

The project manifest declares the launch file and compiled component registry:

```json
{
  "runtimeAbi": 2,
  "defaultLaunch": "launch/robot.launch.py",
  "launchComponents": {
    "cpp_workspace/producer": "producer",
    "cpp_workspace/processor": "processor"
  }
}
```

The registry maps ROS `package/executable` names to components already compiled
into this project's APK. No corresponding executable needs to be installed on
the PC. The adapter intercepts Node construction and never invokes Node.execute
or ExecuteProcess. Unregistered nodes fail before deployment/start; this does not
port arbitrary ROS packages. The C++ factory remains responsible for creating a
new node with the supplied NodeOptions.

## Supported subset

| ROS feature | Current mapping |
| --- | --- |
| LaunchDescription / PythonLaunchDescriptionSource | PC loads standard Python launch descriptions |
| Node | Explicit package/executable/name; optional namespace, scalar inline parameters, remappings, condition; output="screen" accepts the Android runner logging policy |
| DeclareLaunchArgument | Defaults, required values, choices, explicit NAME:=VALUE overrides; unknown override names fail |
| LaunchConfiguration, TextSubstitution | Resolved on the PC |
| PathJoinSubstitution, ThisLaunchFileDir | Locate included files on the PC |
| IfCondition, UnlessCondition | Include/exclude actions in the resolved plan |
| GroupAction | Default forwarding/scoped configuration and environment push/pop |
| PushROSNamespace, SetLaunchConfiguration | Apply during description traversal; scoped group restoration |
| IncludeLaunchDescription | Python sources with argument forwarding; recursion/depth bounded |
| ParameterValue | Explicit scalar type evaluation through upstream ROS utilities |
| Existing bounded JSON plans | Continue to work as default or explicit overrides |

Per-node stdout/stderr redirection is not implemented; `output="screen"` uses
the runner logging policy rather than a ROS process console. Only inline finite
scalar parameters are accepted. Arrays, YAML parameter files,
Parameter objects, wildcard remaps, node arguments/ros_arguments, respawn,
per-node worker options, timers/events, OpaqueFunction, ExecuteProcess, shell
Command/PythonExpression substitutions, non-Python description sources, custom
actions and XML/YAML launch frontends are unsupported. Unsupported fields/actions
are errors, rather than silently ignored. A false condition may omit a supported
node; it does not make an unsupported action part of the supported subset.

GroupAction with forwarding=False produces reset actions that are currently
rejected. Conditional argument declarations must execute before being used.
No enabled nodes is rejected by the Android plan schema. Limits: 16 nodes,
16-deep include stack, 512 visited actions, 64 KiB plan and a 30-second compiler
timeout. Duplicate node names and reserved identity remaps are rejected.

Python launch files are executable developer code on the PC, as with ordinary
ROS Python launch files. This adapter is not a sandbox. Package trusted project
code; rejection of ExecuteProcess actions does not prevent arbitrary Python
statements in generate_launch_description from executing on the PC.

## Runtime boundary and next work

The current adapter targets registered C++ ABI-2 components sharing one Android
process and executor. Launch-time conditions/arguments become a static resolved
plan; timers and dynamic launch events are not being emulated. Python rclpy
projects already run on Android, but mapping them and mixed projects into ROS
launch workers remains pending. Independent worker isolation, restart policies,
parameter files, general package workspace builds and full ROS launch support
need separate implementation and qualification.

See [compatibility and validation](STATUS.md) for the full project status,
findings and resume checklist, and [WORKSPACE-LAUNCH.md](WORKSPACE-LAUNCH.md) for
the underlying runtime/JSON schema.

## Verification

```bash
./tools/pc-ros.sh -- /usr/bin/python3 -m unittest discover -s tools/tests -v
```

The ROS adapter tests require Jazzy's Python environment; an unsourced interpreter
skips them. Run the command above to execute the whole suite. Tests verify the
real example against the existing JSON pipeline, include/argument/condition
resolution, scoped namespace restoration, import restoration, recursion bounds,
unknown/missing arguments and explicit rejection of unsupported process actions,
fields, substitutions and parameter forms.

References: [ROS 2 launch tutorial](https://docs.ros.org/en/jazzy/Tutorials/Intermediate/Launch/Launch-Main.html),
[launch_ros Node API](https://docs.ros.org/en/jazzy/p/launch_ros/launch_ros.actions.node.html).
