# Continuous camera and runtime service verification

Date: 2026-10-25. Build: 0.1.1-dev1 (versionCode 3), ARM64, Android 12 on
the connected Realme RMX2085. PC: ROS 2 Jazzy with Fast DDS; phone: native Jazzy
rcl with Cyclone DDS. This report covers camera/lifecycle work, not general user
nodes, launch files, Python support or the micro-ROS Agent.

## Implementation

- Replaced 1 fps periodic JPEG still capture with repeating YUV capture.
- Default publish rate is 15 fps; user-selectable range is 0.2–30 fps. A one-time
  upgrade migrates the original 1 fps camera default to 15; other saved values
  remain. Camera IDs, topics and ROS JPEG CompressedImage type are preserved.
- A three-buffer NV21 pool feeds a one-frame encoder queue and a separate
  one-frame JPEG DDS queue. Old queued frames are replaced; no unbounded queue.
- Camera plane conversion handles row/pixel strides, overlapping chroma buffers
  and even crops. Capture timestamps use the camera sensor clock. REALTIME
  clocks map through elapsed realtime; unknown clocks are explicitly approximate.
- UI shows capture/publish FPS, sent count, application pipeline drops, intentional
  rate skips and average JPEG encoding time. Local counts do not prove delivery.
- RobotSessionService owns native ROS, sensors, camera and runtime wake resources.
  The Activity binds to state and does not stop hardware onPause/onDestroy.
- Explicit sessions use foreground notification + Stop action, correct service
  types, a partial wake lock and multicast lock. Stop closes hardware, waits for
  camera workers before releasing ROS publishers, and releases the locks.
- Added PC viewer FPS display, metadata-only benchmarking and VS Code build,
  install and camera-view tasks. No camera images are saved by these tools.

## Measured PC delivery

Every counted frame in the benchmarks below passed JPEG marker checks and a
real OpenCV decode. Test processes were restricted to the named PC IP interface;
the phone selected the matching interface and discovery peer. Wi-Fi stayed enabled
during USB tests, so an interface whitelist was necessary to establish the path.

| Path | Duration | Resolution | Requested | Received | Valid frames | Invalid | JPEG payload |
| --- | --- | --- | --- | --- | --- | --- | --- |
| USB IP, back camera, service background | 600 s | 640×480 | 30 fps | 29.677 fps | 17806 | 0 | 3.869 Mbit/s |
| Wi-Fi, back camera, service background | 30 s | 640×480 | 30 fps | 29.733 fps | 892 | 0 | 4.358 Mbit/s |

USB interarrival gap: 95th percentile 57.01 ms, maximum
109.62 ms. Wi-Fi: 61.52 ms and
101.40 ms respectively. These are arrival gaps, not end-to-end
latency. No synchronized clock or optical latency measurement was performed.

Short front-camera USB benchmark: 599 valid frames / 20 seconds = 29.95 fps,
640×480, zero invalid JPEGs. A short back-camera run measured 29.6 fps; phone
metrics in that run reported zero application queue drops or ROS write failures
and approximately 3.3 ms average JPEG encoding time.

Final actual UI flow: edited the back-camera rate to 30, tapped Publish, observed
live phone metrics, and opened the PC viewer without interface overrides. A
separate ten-second decoded receive check counted 293 frames = 29.3 fps, zero
invalid JPEGs. The installed app and PC viewer were left running for the user.

## Lifecycle and regression evidence

The initial long run exposed a real prototype lifecycle limitation: leaving the
Activity stopped its native ROS context. The USB link remained healthy. That
interrupted run was not counted as a successful soak test.

After moving runtime ownership to the service:

- Started real camera and IMU, recreated MainActivity, moved the Activity to
  CREATED/background for 610 seconds, resumed, verified both counts advanced,
  then explicitly stopped. Physical test passed. PC camera delivery was measured
  independently throughout the 600-second window above; IMU also reached the PC.
- Short updated lifecycle test additionally restarted the camera after a full
  stop and sent the exact PendingIntent used by notification Stop. It verified
  that ROS stopped again. The combined regression suite and the Wi-Fi lifecycle
  run passed with this check.
- Front/back camera regression received valid JPEGs over USB, and existing
  edited-topic UI, native loading/sensor, graph/restart and exact PC text-return
  checks passed. Performance-only direct camera test was skipped in the broad
  regression run because its opt-in argument was absent; it passed separately.
- JVM tests: 10 passed, including four stride/crop/clock tests. Build and lint
  passed; remaining warnings concern existing dependency updates, localization,
  backup metadata and ChromeOS ABI support.
- After explicit stop, no active ROS2Android session partial wake lock was found.
  The app remained installed with camera permission retained.

Battery sensor samples during the USB background run rose from about 35.8°C to
38.7°C. Observed app PSS samples were approximately 51–70 MiB. These snapshots
are not CPU temperature readings, a complete memory profile or a thermal safety
qualification. No performance collapse occurred in the measured run.

## Limits and remaining work

- One phone/Android version tested. Service types are guarded for older APIs;
  newer Android permissions, OEM power restrictions and screen-off behavior still
  need physical tests. The screen was not deliberately locked during development.
- A ten-minute soak is not the planned one-hour unattended qualification.
- No in-app viewfinder yet. Camera size/quality remain fixed initial defaults;
  camera settings, calibration/CameraInfo and hardware video encoding follow.
- Native ROS writes are successful local calls; rate skips and app queue drops
  cannot account for all camera HAL or network losses.
- No general automatic transport failover, process-death/reboot camera restart,
  full ROS launch compatibility, user-node hosting, Compose redesign or embedded
  micro-ROS Agent is claimed here. These remain roadmap milestones.
- Device logs/screenshots and benchmark raw output remain ignored under
  device-logs. No image/video capture was saved to the phone or repository.
