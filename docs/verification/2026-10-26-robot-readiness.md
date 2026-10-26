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
