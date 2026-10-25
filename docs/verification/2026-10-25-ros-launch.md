# ROS launch adapter verification — 2026-10-25

Physical Android 12 ARM64 phone, ROS 2 Jazzy PC and USB tethering IP link.
C++ workspace source fingerprint: 2eaa361a70a8. Logs, device identifiers,
APKs and resolved generated plans stay outside Git in ignored output.

## Passed

- 19 tests ran in the sourced Jazzy Python environment, with no skipped ROS
  adapter tests. They cover the real upstream launch-description loader and
  substitution/parameter utilities, example equivalence to the existing JSON
  pipeline, arguments/conditions/includes, namespace scoping, node import
  restoration, required/unknown arguments, invalid choices, recursive includes,
  and unsupported process actions, fields, substitutions and parameter forms.
- Test ExecuteProcess('touch ...') was rejected and its marker was not created.
  This does not make Python launch files a sandbox: their own Python statements
  still execute on the PC during description generation.
- APK assembly and lint passed after compiling the default ROS description.
- Build arguments robot_namespace:=launchbot and message:="ROS launch on Android"
  were baked into an APK. After deploy, start without a launch override created
  /launchbot/producer and /launchbot/processor on the phone, proving it used the
  packaged resolved plan rather than resolving source defaults at start.
- PC probe confirmed the two nodes, matching remapped input/result messages,
  and each node's parameter through actual ROS services.
- Runtime override enable_processor:=false created only /robot/producer in
  native status; its /robot/pipeline/input topic carried real counter messages.
- Explicit source-default launch override restored both /robot nodes and passed
  the complete pipeline/parameter probe.
- Final default build/deploy restored the normal packaged /robot configuration.

The code uses Jazzy's LaunchDescription/LaunchContext/actions and standard
PythonLaunchDescriptionSource loader with a scoped Android Node constructor
adapter. Node.execute and ExecuteProcess are never invoked by the traversal.
Only registered C++ ABI-2 components are mapped into the phone runtime.

## Scope boundaries

Description evaluation happens on the PC at build/override time. The phone
executes the packaged static component plan. This is not a complete on-phone
LaunchService. Python/mixed launch workers, timers/events, parameter YAML files,
per-node stdout/stderr redirection, automatic respawn, XML/YAML launch frontends,
independent process supervision, physical PC disconnection and MCU autonomy are
not established by these results. See ROS-LAUNCH.md for the supported matrix.
