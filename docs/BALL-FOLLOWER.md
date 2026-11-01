# Python red-ball detection and following

This example runs two real `rclpy` nodes on Android: camera perception and a
visual-servo controller. A Linux PC builds/deploys the APK and observes results;
it does not compute detections when the phone camera is the input.

The Android Python runner hosts both nodes in one process with a three-thread
ROS executor. This is a Python project bundle with a normal `ament_python`
package inside it, **not** general `ament_python` support in the ordinary C++
workspace builder. No change to ESP32 firmware is required.

## Data flow

```text
Phone Sensors camera → JPEG CompressedImage
    → /ball/ball_vision: Pillow decode + NumPy colour mask + circle fitting
    → /ball/target: timestamped JSON in std_msgs/String
    → /ball/ball_follower: steering + apparent-size approach + freshness check
    → /ball_demo/cmd_vel: geometry_msgs/Twist (TEST TOPIC by default)
```

`/ball/detected` is Bool; `/ball/metrics` is a JSON String containing input and
processing rates, drops/errors and recent mean/p95 processing time.
`/ball/image/compressed` is the default image output: a downsampled JPEG with
 the fitted green circle, encoded only while subscribed at 5 Hz by default.
Mask generation/publication is off (`publish_mask:=false`), and raw image output
is off (`raw_image_hz:=0.0`) to keep the control path responsive.
Optional debug outputs are `/ball/image` (raw rgb8), `/ball/mask` (raw rgb8) and
`/ball/mask/compressed` (selected ball on black). Enable raw output with a positive
`raw_image_hz` and masks with `publish_mask:=true` at startup. Additional image
streams add CPU/network load; verify command continuity under the intended load.
Images are processed in memory, not saved. Test images and user-supplied photos
are not shipped as project assets.

The detector recognises a roughly circular red region. It tolerates seams and
lettering using morphology and bounded RANSAC circle fitting. It cannot identify
a specific tennis ball or avoid obstacles. Do not treat its output as a general
navigation or safety system.

## Build and deploy

Prepare the Python SDK with [Python development prerequisites](PYTHON-PROJECTS.md).
The existing SDK can be reused; project requirements add the Android Pillow
11.0.0 wheel alongside pinned NumPy. From the repository root:

```bash
./tools/ros2android --project examples/ball_follower build
./tools/ros2android --project examples/ball_follower deploy
./tools/ros2android --project examples/ball_follower start --namespace /ball --domain 0
./tools/ros2android --project examples/ball_follower status
```

When multiple ADB devices are listed, add `--serial DEVICE` before the subcommand.
Use explicit `--peer PC_IP --local PHONE_IP` on start if needed for discovery.
Use the current addresses, not those from another machine/session.

Expected status lists `/ball/ball_vision` and `/ball/ball_follower`, runtime
`python`, and a Python version. New runner UI defaults to namespace `/python`;
the commands above select `/ball`. Use that same namespace when starting from
Projects, or adjust observer topics to the saved namespace.

## First test: observe, do not drive

1. Keep motor power off and stop other velocity publishers/Dashboard controls.
2. On the robot phone, open Sensors → Back camera. Publish on
   `/phone/camera_back_0/image/compressed`. Start at 640×480 and automatic FPS.
3. Hold the red ball in view, move it left/right, and remove it.
4. View `/ball/image/compressed` with a Dashboard CompressedImage subscriber or
   an image_transport-compatible ROS viewer. Confirm the circle surrounds the
   ball and that the image orientation matches the robot's forward-facing view.
5. For optional PC debugging, restart with `-- -p raw_image_hz:=1.0`, then view the standard raw output:

```bash
ros2 run image_tools showimage --ros-args -r image:=/ball/image -p reliability:=best_effort
```

If you also started with `-p publish_mask:=true`, view the optional mask:

```bash
ros2 run image_tools showimage --ros-args -r image:=/ball/mask -p reliability:=best_effort
```

Use Ctrl+C in each terminal to stop its viewer. These receive `sensor_msgs/Image`
with `rgb8` encoding; no project-specific viewing script or PC relay is required.
Dashboard continues to use the companion CompressedImage topics. Both forms
publish only when subscribed. `annotated_hz` sets the output callback rate and
`raw_image_hz` independently limits the larger raw previews; both are configurable
startup defaults, not application-wide camera limits.

Inspect outputs on the PC:

```bash
ros2 topic echo /ball/target --qos-reliability best_effort
ros2 topic echo /ball/metrics --qos-reliability best_effort
ros2 topic echo /ball_demo/cmd_vel --qos-reliability best_effort
```

Stop each echo with Ctrl+C. Match the PC's ROS domain and use the project-local
Cyclone wrapper when needed; see [getting started](GETTING-STARTED.md).

Targets include source `stamp_ns`, pixel centre/radius, `error_x` in [-1,1],
ball diameter as a fraction of image width, processing time and frame age.
Tracking is **OFF after every project start**. The follower publishes at 20 Hz.
It turns first when the ball is well off-centre,
approaches while centred, and stops when apparent diameter reaches 0.30 of image
width. Missing/invalid/older-than-300-ms targets produce zero velocity. It does
not search by rotating after losing the ball and never commands reverse.
Input clocks must be aligned if images originate on another device.

## Controller-phone Dashboard

Install the current main ROS2 Android app on the controller phone. Use the same
Wi-Fi and ROS domain as the robot phone. Only the robot phone needs this Python
runner and the ESP32 USB connection.

Add these widgets under Dashboard:

| Label | Direction / widget | Message type | Topic |
| --- | --- | --- | --- |
| Ball view | Subscribe / Image | sensor_msgs/msg/CompressedImage | /ball/image/compressed |
| Track ball | Publish / Switch | std_msgs/msg/Bool | /ball/track_ball |
| Tracking state (optional) | Subscribe / Bool | std_msgs/msg/Bool | /ball/tracking |

Start Dashboard. Switch ON sends true, enabling image processing and following.
OFF sends false, immediately requests zero velocity, clears pending detection
frames and pauses JPEG decoding, detection and annotated output. The last image
may remain visible in Dashboard while paused; the camera publisher is controlled
separately in Sensors. The follower also stops on missing/stale
detections while ON, and resumes when fresh detections return. `/ball/tracking`
reports whether tracking is enabled, not whether motors are currently moving.
Switch position is the controller phone's last requested input; after a runner
restart it can differ from the robot's actual state. Use the optional readback
and toggle OFF/ON deliberately. Stopping Dashboard is not a substitute for
sending OFF or stopping the project: the robot owns autonomous following.

At the default test topic, switching ON lets you inspect computed commands but
cannot drive the reference ESP32. Complete the stationary checks first, then
choose the real command topic below.

## Drive only after the stationary checks

Stop the project and all teleop/Dashboard command publishers first. With wheels
raised and the physical power switch within reach, explicitly opt into the real
motor topic:

```bash
./tools/ros2android --project examples/ball_follower stop
./tools/ros2android --project examples/ball_follower start --namespace /ball --domain 0 \
  -- -p command_topic:=/cmd_vel
```

Tracking remains OFF after restart. Use the Dashboard Track ball switch only
when ready for the raised-wheel check. Check turn direction and stopping before
any floor test. Camera image horizontal
axis must align with the robot's left/right; a sideways/mirrored mounting needs
an image transform before using this controller. Stop the project or motor power
if direction is wrong. Do not run this with competing `/cmd_vel` publishers.
The reference MCU's own stale-command timeout remains enabled. Software stops
and camera detection do not replace the physical motor-power switch.

Defaults: forward ceiling 0.12, angular ceiling 2.5. These are ROS command values,
not measured/calibrated wheel speeds. The reference low-cost motors may not move
reliably at small commands. Do not infer calibrated motion from a Twist value.

## Calibrate the aim point and stopping distance

Keep Track ball OFF. Mount the camera in its final position and place the ball on
the robot's centreline at the stopping distance you want. Observe the annotated
image and read a fresh detection:

```bash
ros2 topic echo /ball/target --once --field data --qos-reliability best_effort
```

Calculate `target_center_x = center_x / width` from that detection. Copy its
`diameter_fraction` into `stop_diameter_fraction`. For example only (not a
calibration of your robot):

```bash
ros2 param set /ball/ball_follower target_center_x 0.55
ros2 param set /ball/ball_follower stop_diameter_fraction 0.28
ros2 param get /ball/ball_follower target_center_x
ros2 param get /ball/ball_follower stop_diameter_fraction
```

These settings update immediately and validate their ranges. They do not enable
tracking. Record your values and pass them with `-p` on the next start; ROS
parameter-service updates are not automatically persisted by this example. Save
with standard `ros2 param dump /ball/ball_follower > calibration.yaml` and restore
with `ros2 param load /ball/ball_follower calibration.yaml`, or include the values
in the runner's startup ROS arguments. Projects retains an accepted startup
profile; a later PC start without those arguments replaces them.
A fixed aim bias is accurate near the calibration distance; a physically offset
or angled camera can require distance-dependent camera-to-base compensation for
longer approaches. Centre calibration is not a camera-intrinsic or metric-depth
calibration. Do not use a tabletop measurement from an unmounted phone.

## Parameters and monocular distance

Set parameters at start with `-- -p NAME:=VALUE`, then rebuild/deploy only when
source changes. Most vision/output parameters are read at startup; restart to apply them.
Follower aim/stop/speed parameters can also be changed through standard ROS
parameter services while running. Available values include `image_topic`, `processing_width` (320),
`processing_hz` (15.0), `annotated_hz` (5.0), `raw_image_hz` (0.0), `publish_mask` (false), `hue_degrees` (14.0),
`min_saturation` (0.60), `confirmation_frames` (3), `target_center_x` (0.5),
`stop_diameter_fraction` (0.30),
`max_linear` (0.12), `linear_gain` (1.2), `max_angular` (2.5),
`full_pwm` (false), `angular_pwm_limit` (0.8), `turn_pulse_seconds` (0.04), `turn_settle_seconds` (0.15) and `command_topic`.

`full_pwm:=true` selects direct duty-fraction requests for the reference MCU
firmware built with `ROS2_ANDROID_ESP32_FULL_PWM=1`. These values are mixing/duty
requests, not measured m/s or rad/s. The current firmware preserves fractional
requests; it does not turn every small request into full PWM. Full duty 255 remains
available at request magnitude 1.0. The default MCU build retains proportional
Twist scaling; use matching APK/firmware versions.

In full-duty mode, the reference controller turns first, then drives straight.
It never combines forward and angular requests: combining full forward with an
80% turn made the inner wheel receive only about 11% duty in the mixer, which
could stall it under ground load.

Turns use `angular_pwm_limit` (default 0.8, PWM204/255 for a pure turn) immediately,
in short pulses. Each pulse is followed by zero commands and a settling interval.
Movement resumes only after a detection from an image captured after that interval.
Straight driving remains immediate full duty when aligned; close-enough, lost,
stale and OFF stops still apply. This replaces the experimental angular ramp,
which spent time below the motors' starting duty.

The pulse duration is a software request, not a hard real-time motor deadline.
The 20 Hz ROS timer nominally makes the 0.04 s request about one control tick;
Android scheduling and transport delays can extend it. The MCU's independent
300 ms watchdog remains the fallback. Physical turn size/overshoot must be tested;
this approach makes controlled steps, not continuous smooth steering. Encoders or
IMU feedback would be needed for a measured turn-rate controller.

Adjust duty using `ros2 param set /ball/ball_follower angular_pwm_limit 0.8`.
Pulse/settling times are startup parameters; keep startup overrides for restart.

Approach uses apparent size and requires no metric depth. For an approximate
range, measure actual ball diameter and calibrate camera intrinsics at the chosen
resolution/crop. With focal length f in pixels and ball image diameter d:

```text
range ≈ actual_ball_diameter × f / d
```

The optional `horizontal_fov_degrees` and `ball_diameter_m` parameters expose this
approximation as `estimated_range_m`. FOV defaults to zero, disabling the estimate;
0.066 m is only a placeholder ball diameter, not a measurement of your ball.
Range is not used for motion. Occlusion, circle errors and uncalibrated FOV can
make this estimate inaccurate. Intrinsic CameraInfo integration, distortion and
metric range validation remain future work.

## Reproduce tests

Pure processing/control tests:

```bash
PYTHONPATH=examples/ball_follower/src/ball_follower \
  /usr/bin/python3 -m unittest discover -s examples/ball_follower/tests -v
```

For the actual phone ROS test, stop/restart the project with a reserved image
topic and leave motor power off:

```bash
./tools/ros2android --project examples/ball_follower stop
./tools/ros2android --project examples/ball_follower start --namespace /ball --domain 0 \
  -- -p image_topic:=/ball_test/image/compressed
./tools/pc-ros.sh -- /usr/bin/python3 tools/pc-ball-follower-probe.py --annotated-only
```

The PC sends generated JPEGs, observes phone-produced detections/annotations,
and checks disabled-by-default, forward, both turns, near, wood-background rejection, lost, malformed,
stale-image and switch-OFF behavior, plus the annotated image. To test optional
mask/raw outputs, enable them explicitly and run the probe without `--annotated-only`.
It subscribes to `/ball_demo/cmd_vel`; it never publishes motor commands. Revert
`image_topic` by restarting without that parameter before using the phone camera.
A passing generated-image test does not prove live-camera rate, robot motion,
metric depth or hard real-time deadlines. See [current validation](STATUS.md).

For the direct-duty turn/brake controller, start the test runner with
`-p full_pwm:=true` and add `--turn-pulses` to the probe. Turn phases then require
both correctly directed motion samples and zero/braking samples; their success
fraction does not mean continuous turning.
