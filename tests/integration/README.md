# Physical Dashboard switch regression

This test exercises actual phone-to-PC ROS traffic and UI requests. Use an
unlocked phone with ROS2 Android foreground, motor power disconnected, and no
active Dashboard session. No cameras or actuator topics are used. The test
backs up/restores the app's saved widget layout and stops its test runtime.
The main app must already be installed through `setup/run-device.sh`.

On a ROS Jazzy Linux host with the project PC middleware prepared, start the
independent peer in one terminal from the repository root:

```bash
ROS_DOMAIN_ID=87 ./tools/pc-ros.sh -- python3 tests/integration/dashboard_switch_peer.py
```

Then run the guarded device test in another terminal:

```bash
./setup/build.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.DashboardSwitchTest \
  -Pandroid.testInstrumentationRunnerArguments.dashboardSwitchPeer=true
```

Both peers use automatic discovery on isolated domain 87. Host routing/firewall
must permit discovery and traffic; see the normal connection troubleshooting
instructions. The device test is skipped unless the explicit peer argument is
present; a skipped test is not interoperability evidence.

Expected: the PC receives exactly `true, false, true, false`. Startup sends
nothing, navigation preserves local switch position without publishing, and
Dashboard restart does not replay the previous ON. The final OFF must reach the
PC and appear in its test telemetry. Local status must still say remote state
unknown: test telemetry is not an acknowledgement from a robot actuator.

The app-content image is a guarded debug artifact in the app's own cache. Keep
it and device test reports out of Git. This test does not simulate packet loss,
late subscriber startup, process death or a powered robot. Pure queue regressions
run through `setup/build.sh` with the normal unit-test suite.
