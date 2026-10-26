# Dashboard persistence and Home activity summary

Dashboard endpoints remain owned by RobotSessionService across tab navigation,
Activity pause and recreation. Navigation/pause requests zero for all joysticks;
view release/cancel/focus loss/detachment and the 300 ms command lease remain.
Explicit Stop dashboard/Stop all removes endpoints and clears readings.

## Checks

- `./setup/build.sh`: assembly, unit tests and lint passed.
- `./setup/build.sh :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.ControlsTest`:
  three cases passed, zero skipped/failed. Editor persistence, joystick release/
  focus loss, native publisher messages, navigation/pause persistence, ongoing
  battery-topic reception and explicit Stop are exercised.
- Independent PC `tools/pc-controls-probe.py` received Bool, UTF-8 String,
  Float64 and Twist, including two motion-to-zero transitions (command lease and
  navigation). Reserved test topics were used; no robot command topic was driven.
- Home summary separates Sensor topics and Dashboard topics, projects and nodes,
  and Agent state. These are local endpoint/runtime counts, not services/actions
  or proof that a remote subscriber received data.

Both physical ThemeUiTest cases passed with no skips/failures. App-only light/dark
portrait captures were visually inspected; the Home summary reported one running
project with two nodes, separately from stopped phone endpoints. All five tabs
and the author credit remained visible. Preview configuration did not change the
system theme. Test APK removed and normal Home reopened.

No physical motor, extended background/endurance, process-death recovery or
second-device qualification is claimed. A receiving robot still needs command
expiry; transport/process failure can prevent the final zero reaching it.
Screenshots, transient network addresses and device logs remain ignored.
