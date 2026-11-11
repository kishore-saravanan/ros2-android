# Reference two-wheel L298N robot

Prepared 2026-10-30. Firmware is opt-in; the existing ESP32 battery/LED example
continues to build by default. Motor output and physical stop timing require
on-robot verification; a successful build is not a completed motor test.

## Wiring supplied by the owner

| Wheel | ESP32 outputs | L298N function |
| --- | --- | --- |
| Left (motor 1) | GPIO25 / GPIO26 | IN1 / IN2 |
| Right (motor 2) | GPIO27 / GPIO14 | IN3 / IN4 |

Keep a common signal ground between ESP32 and L298N. ENA and ENB must be enabled;
this four-wire implementation assumes their existing module jumpers are fitted.
The owner's photo appears to show them, but labels/continuity have not been
verified. Do not connect an enabled module's 5 V jumper header to ESP32 GPIOs.
The separate regulator-enable jumper is not an ENA/ENB jumper. Verify the actual
module's power requirements before changing power wiring; the photo does not
establish its circuit or supply voltages. Motor supply goes to the driver, not
an ESP32 GPIO. See the [ST L298 datasheet](https://www.st.com/resource/en/datasheet/l298.pdf).

## Firmware behaviour

- Retains phone-battery acknowledgements, heartbeat and the earlier LED topics.
- `/cmd_vel`: best-effort, keep-last-1 `geometry_msgs/msg/Twist`; uses `linear.x`
  and `angular.z`. This limits queued commands, but unstamped Twist cannot prove
  the original sender timestamp or eliminate transport latency.
- `/robot/enable` and `/robot/armed` have been removed at the owner's request.
  Fresh `/cmd_vel` commands are accepted as soon as the Agent is connected;
  no separate enable step is required. Other publishers on the same topic/domain
  can also command the robot, so stop unrelated teleop/navigation publishers.
- No command received within 300 ms requests zero PWM. A separate 20 ms FreeRTOS
  task checks expiry even while the micro-ROS executor or Agent ping is blocked.
  The physical stop latency is unmeasured; a software deadline is not a hard
  real-time or mechanical stop guarantee.
- Agent disconnection/restart clears the command. A new velocity message is
  needed after reconnection; previous commands are not replayed.
- Non-finite velocity values clear the current command. Finite linear/angular commands are
  limited to +/-0.2 and +/-3.0 by default respectively, mixed as a
  differential drive. The angular ceiling is configurable; these are open-loop
  command limits, not calibrated vehicle speeds.
- Maximum PWM defaults to **255/255**. Publish an Int32 from0–255 to
  `/robot/pwm_limit` while the commanded wheels are zero or the command has expired; invalid
  values and changes during an active nonzero command are ignored. `/robot/pwm_limit/current` reports the applied value once
  per second (best-effort). The setting survives an Agent reconnect in the same
  MCU boot, but resets to255 after an MCU restart; it is not stored in flash.
- PWM is applied to direction inputs with enable jumpers retained. Both
  direction inputs are low for stop. Left polarity is inverted in firmware following the owner's observation of
  reverse left-wheel rotation. Physical confirmation of the correction is pending.
  Each wheel's sign can be overridden before rebuilding.

This is open-loop control without encoders. The provisional 0.16 m wheel track
and 0.25 m/s full-command scaling are commissioning defaults, not measured robot
geometry or calibrated speed. Measure the wheel-centre separation and calibrate
before expecting metric motion. These settings and sign macros are in
examples/esp32_wifi/include/robot_motors.h and drive_guard.hpp. Supply appropriate
hardware stop facilities; GPIO pull-downs/driver enables are needed to control
behaviour before firmware starts or after a processor fault.

## Programming isolation

A switched-off motor battery does not establish electrical isolation when USB
and other power paths remain connected. Verify the complete supply arrangement
before powering the driver; firmware cannot correct a hardware back-feed path.

Before programming, unplug USB and physically disconnect the motor battery.
Disconnect the ESP32-to-driver leads, then connect only the ESP32 to USB for
identification, backup and flashing. Reconnect the driver after verifying its
power arrangement. The example does not certify a particular wiring layout,
supply capacity or motor-driver installation.

## Build and deploy

From the repository root:

```bash
ROS2_ANDROID_ESP32_ROBOT=1 ./setup/esp32-usb.sh build
```

This builds but does not flash. To flash through a Wi-Fi-connected phone, first
switch off/disconnect motor battery power, raise the wheels and attach ESP32
to the phone by USB OTG. Leave ROS2 Android open, approve USB permission and use
the existing programming workflow in ESP32-PROGRAMMING.md. The application image
is ~/.cache/ros2-android-esp32/firmware-serial-project/.pio/build/esp32dev/firmware.bin.
For the existing compatible partition layout, program that image at 0x10000;
preserve a fresh board backup first. Do not overwrite bootloader/partitions
without identifying and preserving the existing layout.

The opt-in environment variable must also be present when using the direct-PC
upload command; otherwise the normal battery/LED firmware is selected:

```bash
ROS2_ANDROID_ESP32_ROBOT=1 ./setup/esp32-usb.sh upload /dev/ttyUSB0
```

Confirm the actual serial device before uploading. Initial preparation was build-only. On2026-10-30, after electrical isolation,
the application was flashed via direct PC USB using pinned esptool4.5.1 at115200.
Affected-sector backup passed digest verification; application write and separate
verify passed. Subsequent phone USB Agent and independent PC checks passed:
fresh heartbeat, battery round trip and matching command subscriptions,
using automatic subnet discovery. Current firmware also reports PWM configuration.
No enable or velocity commands were sent. Physical motor checks remain pending;
supply-routing precautions above still apply.

For a read-only repeat while the phone is publishing battery percentage and the
USB Agent is running, use the same ROS domain on both sides (0 in this check):

```bash
ROS_DOMAIN_ID=0 ./tools/pc-ros.sh -- python3 tools/pc-robot-readiness-probe.py
```

Expected: `"passed": true` with all five checks true. The probe subscribes only;
it does not enable motors or publish velocity. It does not prove zero electrical motor output or physical standstill. See [compatibility and validation](STATUS.md).

## PC keyboard control (phone stays on the robot)

1. Keep wheels raised for direction tests and the battery switch within reach.
2. Start the phone USB Agent. Stop other `/cmd_vel` publishers.
3. With ROS sourced and domain matching the phone, run:

```bash
ros2 run teleop_twist_keyboard teleop_twist_keyboard --ros-args -p turn:=2.5
```

`i` commands forward, `,` backward, `j`/`l` rotation, `k` zero, Ctrl+C exits.
No enable command is needed. Start with a brief keypress and confirm both wheel
signs and stopping before floor driving. The default linear command0.5 is clamped
by firmware to0.2; default angular1.0 yields about82/255 PWM per wheel for the
provisional0.16 m track. Angular2.5 yields about204/255, matching the working
straight command's duty; it is the next controlled turning trial. A smaller
turn0.3 yields only24/255 and may fail to start these motors. This is calculated
command duty, not measured torque or speed. Left polarity correction and
forward/backward operation are owner-confirmed; turning and physical stop timing
remain unverified. No automatic minimum-duty boost is applied.

Installed Jazzy keyboard teleop publishes on keypress/repeat; it does not expose
key-release events or support repeat_rate/key_timeout parameters. Firmware
expiry requests zero300 ms after the last message. Physical stop timing,
USB/Agent-loss stopping and reconnect trials remain pending.

## Configure PWM from the PC

With ROS sourced and the same domain as the phone, stop teleop first:

```bash
ros2 topic pub --once /cmd_vel geometry_msgs/msg/Twist '{}'
```

Wait at least300 ms with no other velocity publishers. Set a maximum and inspect its readback:

```bash
ros2 topic pub --once /robot/pwm_limit std_msgs/msg/Int32 '{data: 255}'
ros2 topic echo /robot/pwm_limit/current --qos-reliability best_effort
```

The maximum scales the wheel command;255 does not force full power on every
nonzero command. At the provisional0.25 scaling, straight linear.x=0.05 gives
about51/255 duty at maximum255 (the old maximum60 produced only12/255).
This explains a possible lack of starting torque, not a measured diagnosis of
battery condition or wiring. No minimum-duty boost is applied; zero remains zero.
Use raised wheels to establish direction and the lowest command that reliably
starts each motor. Record actual start/stop observations; commands are not
calibrated physical speeds. Do not add constant nonzero publication for unattended
calibration. Keep the physical battery switch within reach.

## Configure the angular command limit

`/robot/angular_limit` accepts `std_msgs/msg/Float64` values from0 through6 while
commanded wheels are zero or the previous command has expired. Negative,
out-of-range and non-finite values are ignored. Changes during active nonzero
commands are ignored. `/robot/angular_limit/current` publishes the accepted
value every second (best-effort). Default is3.0 after MCU reboot; reconnecting
only the Agent preserves it. There is no flash persistence.

Stop teleop and any other velocity publisher before changing it:

```bash
ros2 topic pub --once /cmd_vel geometry_msgs/msg/Twist '{}'
# Wait at least 300 ms with no velocity publisher sending further commands.
ros2 topic pub --once /robot/angular_limit std_msgs/msg/Float64 '{data: 3.0}'
ros2 topic echo /robot/angular_limit/current --qos-reliability best_effort
```

The setting is a ceiling, not a gain: increasing it alone does not change an
angular1.0 command. Teleop must request the higher angular value (`turn:=2.5`)
to obtain the higher duty. Standard differential-drive mixing and ratio-preserving
PWM saturation remain; geometry has not been changed to force a stronger turn.
The300 ms expiry applies equally to the higher angular commands.

## Optional direct-duty reference mode

Build using the following single command:

```bash
ROS2_ANDROID_ESP32_ROBOT=1 ROS2_ANDROID_ESP32_FULL_PWM=1 ./setup/esp32-usb.sh build
```

This bypasses the proportional Twist ceilings and interprets linear/turn requests
as signed duty fractions: left = linear − turn, right = linear + turn. Normalize
only if the largest magnitude exceeds 1.0. Small requests stay small, allowing
smooth proportional steering; magnitude 1.0 reaches PWM 255 by default. Mixed
steering preserves wheel ratios. This replaces the earlier experimental mode
that forced every nonzero turn to full PWM.

Explicit zero, invalid input, disconnection and the 300 ms watchdog still stop
output. These are open-loop duty requests, not calibrated vehicle velocities.
The default build keeps the earlier proportional mapping. Use matching follower
`full_pwm:=true` mode. Disconnect the motor battery before programming and test
with wheels clear first. Preserve the previously verified firmware for rollback.
