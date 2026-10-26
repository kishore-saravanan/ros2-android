# Robot computer readiness — qualification ledger

This is a test ledger, not a blanket ready-for-production statement. Current
reference: one ARM64 Android 12 phone and one CP2102/classic ESP32 board. Do not
include device identifiers, addresses or private diagnostics in Git.

| Gate | Current evidence | Remaining qualification |
| --- | --- | --- |
| Main native ROS/sensors | Earlier typed IMU/battery/camera PC trials | Fresh regression after refinements |
| Workspace computation | Earlier Linux/Android custom-message, parameters and child-failure recovery | Fresh regression, physical PC-link removal, mixed ament_python packages |
| USB MCU round trip | Earlier phone battery → MCU → phone acknowledgement | Repeated no-delay Agent restarts and sustained application data |
| Dashboard safety | Earlier release/navigation/focus zero tests and real Gazebo movement | Fresh regression; real MCU motor command expiry/hardware stop |
| Camera operation | Earlier ~30 received FPS, ten-minute USB run | New background/lifecycle check, one-hour/thermal and another device |
| Offline autonomy | Architecture runs native nodes on phone | Physical PC-link removal with continued application computation and post-reconnect evidence |
| UI/usability | Earlier light/dark/large-text and navigation checks | Fresh relevant regressions; full TalkBack/keyboard, second phone, external onboarding |
| Fault handling | Earlier process exit and explicit restart tests | Endurance, cable/repeated-restart trials and failure-state clarity |

## New repeatable phone-only USB test

RobotReliabilityTest starts a real phone battery publisher and temporary Dashboard
subscribers, then repeatedly stops/starts the USB Agent and requires fresh typed
MCU heartbeat and battery acknowledgements after every restart. It then checks
both streams every second, failing when either exceeds ten seconds without a
sample. No PC ROS publisher/Agent is part of the message path. Test writes bounded
JSON progress/results to the project's own cache/robot-reliability.json. Saved
Dashboard layouts are not edited; test-created runtime endpoints stop at cleanup.
Use robotUsbConnected=true; recoveryCycles accepts 1–30, soakSeconds accepts
30–7200. A three-minute run is not a one-hour endurance trial. A connected PC
running the test is not proof of physical PC-disconnection autonomy.

Initial actual trial: five no-delay Agent restart recoveries passed in
3008–3401 ms. Continuous MCU replies then exceeded the ten-second stale limit
at about 90 seconds during the owner-performed Wi-Fi-off interval. User reported
Power Saving had been on; the Android low_power diagnostic subsequently read 1.
The failure establishes lost application data under those combined conditions,
not a proven DDS root cause. Owner disabled Power Saving for a controlled repeat;
no networking patch has been applied. The updated test records only the count of
samples with no Wi-Fi transport, without network identifiers/addresses.

Further results will be recorded after actual execution. Physical LED mapping remains
unresolved; no random-pin probes or motor-output claims are authorized by this
qualification plan.

## Build and UI regression pass

Main build/unit/lint and the refreshed ordinary three-package Android workspace
build passed. The host workspace build also passed. The 32 PC tooling tests
passed. A fresh 21-case physical batch had 17 passes and four failures at camera
permission assertions (permission denied); those cases did not exercise capture.
Passing cases cover native runtime recreation, real sensor listener lifecycle,
battery, endpoint edits, Dashboard persistence/zero-on-release, sensor navigation,
collapsed robot-operation Help, light/dark UI, 200% text and binary PTY/Agent.
Independent PC observer received Bool, Unicode String, Float64 and Twist and two
motion-to-zero transitions on reserved test topics. No actual motors were driven.

Power-saving-off USB repeat was blocked before telemetry by renewed CP2102
permission; it is not evidence for or against the network/power hypothesis.
Final main UI APK installed, including explicit Sensors & dashboard status scope
and collapsed power diagnostics. User permission approval and relevant device
reruns follow; do not convert permission-blocked cases into successes.

Fresh host parity passed custom input, numerical computation, both nodes and
parameter service. Refreshed Android workspace build and install-only deployment
passed; phone computation probe still pending. Last preflight: Power Saving off,
camera permission denied, ROS2 Android not foreground. User has been asked to
approve camera and CP2102 permissions and reopen the app. No UI input/capture was
attempted while another app was foreground. This is a pending prerequisite, not
completion of the remaining camera/offline/endurance qualification.

## Permission-approved camera rerun and preview finding

After user granted camera permission, five of seven cases passed: live preview
navigation, robot Help, front/back JPEG publication, 30-second continuous camera,
and camera/IMU background/recreation/Stop. Independent PC observed valid JPEGs
from back/front streams without retaining images. Two theme cases failed at
own-window focus/readiness guards; no guard was bypassed or other-app capture
made. They require rerun. Logs remain ignored.

Owner then reported local preview visibly slow. Identified/fixed the intentional
2 FPS preview callback and 500 ms view polling, using a dedicated latest-frame
decoder and animation-frame UI updates. New ten-second preview/publish FPS and
estimated timestamp-age assertions compile; actual performance is not yet
qualified. New main APK installed. At last preflight, camera permission granted
but own app not foreground; user asked to reopen it. No measured post-fix FPS is
claimed. Camera clock mapping estimates age and does not measure panel latency.

## Smooth preview actual result

Physical preview/navigation/recreation/Stop test passed, zero skips/failures.
Requested camera rate 30 Hz: ten-second window displayed 26.9446 FPS and locally
published 26.9446 FPS simultaneously. Estimated capture-timestamp-to-view age:
p95 110 ms, maximum116 ms. Later five-second preview-visible local publication:
29.6 FPS. No images were saved by this benchmark. These are local measurements,
not PC received FPS, optically measured display latency or a hard-real-time bound.

Final light/dark ThemeUiTest rerun passed both cases; guarded Home captures were
visually inspected with readable scoped status, five tabs and independent ROS
activity groups. Initial combined USB test failed only its immediate own-focus
assertion before telemetry; that attempt provided no continuity evidence.
Controlled no-saver repeat started with powerSavingAtStart=false; three Agent
restarts recovered in3997/2969/3365 ms and baseline data stayed fresh. Awaiting
owner Wi-Fi-off interval and recorded outcome before causal conclusions.

## Controlled Wi-Fi-off repeat, Power Saving off

The owner repeated60 seconds without phone Wi-Fi, Power Saving off. The test
recorded powerSavingAtStart=false, three successful recoveries, ten Wi-Fi-absent
samples and failure when MCU data age reached10861 ms. This reproduces the
application-data failure without Power Saving; no claim that power management
alone caused the first failure is supported.

Explicit local loopback DDS interfaces/peers and an Agent UDP interface profile
were then implemented. Main/native/ordinary-workspace build checks pass; three
new-profile Agent restarts recovered in2990/3904/3000 ms and baseline is healthy.
The next actual Wi-Fi-off continuity trial is pending owner completion/evidence.
No success is inferred merely from profile XML or Running/byte counters.


## Delayed phone result recovered after USB reconnect

The latest phone-cache record contradicts the host UTP interruption: the device
finished and saved result=passed, elapsedMs=120080, samples=119,
withoutWifiSamples=98, maxAgeMs=881, powerSavingAtStart=false. Recoveries:
3992/2869/2970 ms. This is one short phone/USB MCU continuity pass, not a one-hour
qualification or workspace computation result. Installed APK and result preserved
under ignored device-logs before replacement. Tested APK SHA-256:
`d208973fb7b1bb2150ce08a10eb5e6b43534c4364df7a2eae511b67efed28c76`.

Experimental networking source had already been rolled back when the pass was
recovered. Current main-app source reconstructs its loopback + redundant Cyclone
and explicit Fast DDS locator mechanism; build/unit/lint pass, but this rebuilt
revision requires its own physical repeat. Runner networking is unchanged.

A debug-only service now provides a separate recorder independent of UTP.
Use an explicitly selected authorized ADB device (replace SERIAL):

```bash
adb -s SERIAL shell am broadcast --include-stopped-packages \
  -n dev.ros2android.android/.ProgrammingControlReceiver \
  --es command autonomy-start --ei seconds 180 --ei minimumOfflineSamples 30
adb -s SERIAL shell am broadcast --include-stopped-packages \
  -n dev.ros2android.android/.ProgrammingControlReceiver --es command autonomy-status
adb -s SERIAL shell am broadcast --include-stopped-packages \
  -n dev.ros2android.android/.ProgrammingControlReceiver --es command autonomy-stop
```

Start only with project screen unlocked, USB ESP32 attached/approved, Power Saving
off and no programming session. This deliberately stops the current sensor and
Dashboard session and uses temporary battery/heartbeat/reply endpoints; saved
layouts remain intact. Wait for phase=recording before removing Wi-Fi. A terminal
result=passed requires sustained freshness and the requested offline sample count.
Incomplete means the required offline interval was not observed. Retrieve results
only after reconnecting. Completion/cancel stops test endpoints, leaves the Agent
independently controlled, and does not restart previously active user endpoints.
A missing/in-progress record is not a pass. Current recorder device test pending.


## Independent service recorder: combined-change trial

USB approval restored baseline. Owner turned Wi-Fi off, swiped main and runner
apps from Recents, restored Wi-Fi and reopened main. Recorder result failed:
110 samples over160904 ms, 33 without Wi-Fi, both MCU reading ages49705 ms,
114 battery publications. Agent running and byte counters are not freshness proof.
There was a large sampling gap too; root cause remains unresolved. Own-package
exit history showed no new process kill for this interval. Current revision is
not offline-qualified by the earlier APK's pass. Testing main-task removal alone
with Wi-Fi left on next. Neither Force stop nor personal-app control was used.


## Wi-Fi-on baseline and explicit-peer external probe

Current APK independent recorder passed120801 ms:119 samples, zero without Wi-Fi,
max age1720 ms and124 battery publications. Requested swipe event confirmation
is still pending; this result alone establishes only continuous Wi-Fi-on traffic.
A separate baseline then supported a PC probe with explicit peer=phone in
Cyclone XML. Passed with12 Float64 battery,3 Int32 heartbeat and3 String replies,
inter-arrival maxima approximately1 s. Multicast/interface-only probes received
zero samples, so automatic discovery remains unresolved; explicit-peer success
must not be presented as automatic discovery success. No private addresses are
stored here. Tool pc-usb-robot-probe.py --continuous observes its full duration,
records max gaps/last ages and requires each stream's freshness below10 s.
