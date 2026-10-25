# Minimal operator UI verification — 2026-10-25

Version 0.1.1-dev3, reference Realme Android 12 ARM64 device. This supersedes
older screen descriptions, not the scope of ROS workspace compatibility.

## Implemented and checked

Home, Sensors, Projects, Settings form four bottom navigation destinations.
Sensors list hardware before opening a detail form. Advanced ROS/network data,
help sections and runner diagnostics are collapsed. About is a visible footer
at the bottom of Settings, with version, Built by Kishore Saravanan, and the
Support development link. No separate About page or About disclosure.

Both app and shared runner use values/values-night resources. Light mode uses
ROS navy #22314E; dark mode has lighter accents. Device appearance selects the
resources. App-local debug activities tested both modes without changing global
phone settings. Reviewed light/dark Home, Sensors and Settings app-view snapshots;
About footer was readable and visible without expansion. Screenshots are ignored
in device-logs, never committed. The new workspace runner was also visually
reviewed in its actual dark landscape configuration with two running nodes.

## Checks passed

- tools/build.sh: main app assembly, unit tests and lint.
- tools/build.sh runner/pythonRunner/workspaceRunner assembly and lint.
- All 10 physical tests passed in the final connectedDebugAndroidTest run:
  ThemeUiTest, AccessibilityUiTest, EndpointUiTest, BatteryUiTest,
  NavigationUiTest and ProjectBrowserUiTest. Camera test was enabled.
- Tests covered light/dark text contrast >=4.5 for the checked resource pairs,
  collapsed extras, zero sensor-list editors, topic/rate detail fields, About
  footer, 200% app-local portrait text, quiet status updates, navigation/drafts,
  battery publication, edited IMU topic, display controls and camera lifecycle.
- Newly built workspace runner deployed via tools/ros2android. Started domain 0
  with the current USB peer/local addresses. pc-colcon-probe.py verified custom
  input, correct computation, both nodes and parameter service with 3 matching
  samples. This is real PC/phone ROS traffic.
- Restored Home battery publishing after tests. Plain PC ros2 topic echo of
  /phone/battery_percent as std_msgs/msg/Float64, best_effort, returned data:100.0.
- git diff --check passed. No commit or push made for this UI milestone.

An earlier run was blocked by the lock screen; no locked-screen inspection or
input was used. User unlocked the app before the final passing run. Foreground
project app and absent keyguard were verified before manual input/capture.

## Boundaries and next qualification

App-local theme previews verify resources, not a manual global device theme
transition. TalkBack, full keyboard navigation, an external first-user trial,
other phones, fresh-machine setup and long-duration/PC-removal operation remain
pending. Shared C++/Python runners built and linted; this pass physically reran
the ordinary C++ workspace runner only. Full arbitrary ROS workspaces, mixed
ament_python packages, complete on-phone launch and phone-hosted micro-ROS Agent
remain separate pending work. Camera lifecycle/local FPS checks do not replace
new network received-FPS measurements.

Raw local logs: clean-ui-build.log, clean-ui-other-builds.log,
clean-ui-workspace-build.log, clean-ui-device-tests.log under ignored device-logs/.
