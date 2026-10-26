# Drive simulated TurtleBot 4 from the phone

This demo uses a local empty world, the installed official TurtleBot 4 standard
model/Create 3 stack, and ROS domain 0 by default. All robot topics are scoped
under `/tb4_demo`. No physical robot or maze workspace is modified.

## Start on the PC

From this repository, in separate terminals:

```bash
./tools/turtlebot4-demo start
./tools/turtlebot4-demo telemetry
./tools/turtlebot4-demo undock
```

Gazebo must finish loading the robot before undocking. The undock command should
report SUCCEEDED. The telemetry process converts the simulated odometry speed
into a Float64 so the current simple Dashboard can display it. It stops emitting
fresh values if odometry stops. Keep simulator and telemetry terminals running;
Ctrl+C stops each. Use `--interface PC_IPV4` before the action to select a NIC.
The wrapper derives its default IPv4 from the PC route and scopes middleware and
graphics environment changes to this process. Phone and PC need a reachable IP
link (usually the same Wi-Fi); the cable can remain for ADB development.

Do not start a second instance over the same demo. Close the current simulator
first. Earlier failed launches were cleaned up by their own process trees.

## Phone connection

In Settings use ROS domain **0** (or match an explicitly chosen ROS_DOMAIN_ID on
the PC). Advanced PC/phone address fields can remain blank on a multicast-enabled
LAN. Stop any active phone session before changing connection settings.

Open Dashboard. Stop it before editing. Add these two widgets:

| Field | Drive | Speed (m/s) |
| --- | --- | --- |
| Direction | Publish | Subscribe |
| Widget | Joystick | Subscribed value (automatic) |
| Topic | `/tb4_demo/cmd_vel_unstamped` | `/tb4_demo/dashboard/speed` |
| Message type | geometry_msgs/msg/Twist (automatic) | std_msgs/msg/Float64 |
| Maximum linear speed | 0.15 m/s | — |
| Maximum angular speed | 0.5 rad/s | — |

Save each, then **Start dashboard**. Speed should show Live near zero. Hold the
joystick slightly forward and watch the robot move in Gazebo; release to stop.
The speed value should rise while moving and return near zero afterward.
Waiting means no sample has arrived; Stale means none has arrived for five
seconds. Check the telemetry terminal and network/domain if it does not go Live.

The installed Create 3 motion_control node already accepts unstamped Twist at
`/tb4_demo/cmd_vel_unstamped`. Do not configure the current joystick on
`/tb4_demo/cmd_vel`: that topic expects TwistStamped in this Jazzy installation.
Robot odometry itself is nav_msgs/Odometry, which the current value widget cannot
show directly; the small PC telemetry node extracts speed for this demo.

## Troubleshooting and measured scope

- The full robot stack starts many ROS processes. The demo's scoped Cyclone DDS
  configuration permits 120 auto participant indices; the earlier default limit
  was exhausted during startup.
- Gazebo receives the world **file path** separately from the world **name**
  (`depot`). Passing a path as the official `world` launch argument also inserts
  it into bridge topic names, producing invalid ROS remaps.
- The robot description already embeds Sensors and Contact system plugins. This
  demo world omits an extra Sensors plugin; Contact remains in both descriptions.
  The working maze package explicitly removes embedded Sensors and Contact
  plugins when its own world owns those systems.
- Ogre 2/duplicate-renderer experiments crashed here. The current demo uses the
  documented `--render-engine ogre` GUI fallback and the robot's one embedded
  Ogre sensor system. It avoids Fuel downloads; global graphics settings and
  installed packages were unchanged. This is a scoped reference demo, not a
  claim that all simulator cameras/sensors are qualified.

Actual PC verification: GUI camera RPC responded, simulation clock and odometry
updated, undock succeeded, and a bounded 0.1 m/s command moved the simulated robot
0.122 m before the measured speed returned to 0.0. The Float64 speed publisher
also produced real odometry-derived messages.

The phone round trip subsequently passed: the user confirmed Speed Live, robot
movement from Drive, stopping on release and changing speed feedback. An
independent subscriber-only observer received phone-originated Twist commands,
measured 0.238 m odometry displacement and final speed 0.0 m/s. This verifies
phone control and returned telemetry for this simulation; physical robot motors,
long-duration operation and complete simulator sensor coverage remain untested.

References: [Gazebo rendering troubleshooting](https://gazebosim.org/docs/harmonic/troubleshooting/),
[TurtleBot 4 simulator](https://turtlebot.github.io/turtlebot4-user-manual/software/turtlebot4_simulator.html).
