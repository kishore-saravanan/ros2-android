# Dashboard

Open the **Dashboard** bottom tab, then **Add widget**. Give it a label, choose
**Publish** or **Subscribe**, enter its topic and relevant settings, then Save.
Use the widget menu to edit, move or delete it. One layout is saved privately on
the phone; existing control layouts are preserved.

Tap **Start dashboard** to connect its widgets using the current phone session's
ROS domain/network profile. Nothing publishes just because the dashboard opens
or starts. Publisher controls send only when operated. Subscriber cards wait for
incoming messages. Dashboard endpoints and subscriber readings continue when you
leave the tab or background the app. Joystick motion stops on leaving; returning
does not resume a drive command. **Stop dashboard** explicitly removes its
endpoints and clears readings. **Stop all** also stops it. Sensors and the MCU
Agent keep their independent lifetimes. Stop dashboard before editing.

## Publish

| Widget | ROS type | Behavior |
| --- | --- | --- |
| Button · Bool | std_msgs/msg/Bool | Send configured true/false on tap |
| Button · String | std_msgs/msg/String | Send configured UTF-8 text on tap |
| Switch | std_msgs/msg/Bool | Send true/false when operated |
| Slider | std_msgs/msg/Float64 | Send a value within configured limits |
| Joystick | geometry_msgs/msg/Twist | Forward/back → linear.x; turn → angular.z |

Joystick limits use m/s and rad/s (defaults 0.3 and 0.8). It publishes up to 20 Hz
while held. Release/cancel, focus loss, navigation and app pause request zero.
The service requests zero if UI renewals stop for over 300 ms. A receiving robot
still needs its own command timeout: phone/process/network failure can prevent a
final stop message. Keyboard arrows drive while held; release/click stops.

## Subscribe

One read-only value card covers these standard types:

- `std_msgs/msg/String`: text.
- `std_msgs/msg/Bool`: On/Off indicator, including a text label.
- `std_msgs/msg/Int32`, `Int64`, `Float32`, `Float64`: numeric value. Int64 remains
  exact and is not converted through floating point.

A card shows **Waiting** until its first sample, **Live** after reception, and
**Stale** after five seconds without a new sample. A stale value remains visible
with its state; it is never presented as a fresh reading. Stop clears the values.
Slow topics can naturally show Stale between updates; there is no claim that the
publisher has disconnected. Text display is bounded to four visible lines/4 KiB.

For a first subscriber, choose Float64 and `/phone/battery_percent`, start Battery
in Sensors, then start Dashboard. Or publish test data from a PC with matching
ROS domain/network configuration:

```bash
ros2 topic pub --rate 1 /demo/status std_msgs/msg/String "{data: 'Ready'}"
```

Choose Subscribe, String and `/demo/status` in the dashboard. Stop the PC command
to see the card become Stale. For publisher controls, first use an unused topic
and a PC `ros2 topic echo` rather than a motor topic.

## Scope

One dashboard, at most 32 widgets, one topic per widget. No charts, gauges,
drag-and-drop designer, named pages or custom ROSIDL schema editor in this pass.
Switch/slider position is local input, not confirmed robot state. Switch input
is service-owned across navigation and Dashboard Stop/Start; reconstruction
does not publish a value. A service/device restart can reset state independently. Subscribe uses
best-effort/volatile depth 1 for compatibility with sensor publishers; publish
controls remain reliable/volatile depth 1. Latest values can replace rapid
updates, so buttons are not an exactly-once event or service-call interface.
For debug text, add a Subscribe widget with std_msgs/msg/String and the desired
topic (for example /phone/display/text). The former Sensors → Display UI has
been removed; Dashboard handles all user-configured subscriptions.

Workflow inspiration: [ROS-Mobile](https://github.com/ROS-Mobile/ROS-Mobile-Android).
The implementation uses this project's native ROS 2 runtime and Android Views,
with no ROS 1 master, rosbridge or extra runner APK.
