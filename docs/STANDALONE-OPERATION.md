# Run on the phone without a PC

The PC builds and deploys code. Installed projects, phone sensors, Dashboard and
USB micro-ROS execute on the phone; a PC connection or ADB session is not a start
requirement. A controller on another phone still needs a network link to the
robot phone. USB between the robot phone and MCU is a separate link.

This is the intended operating path and a qualification procedure, not a claim
that fresh offline startup or unattended operation has passed on all devices.
See [Status](STATUS.md) for recorded results.

## Prepare once while connected

1. Install the main APK and deploy the supported project. Use the
   [workspace guide](COLCON-WORKSPACES.md) or [Python guide](PYTHON-PROJECTS.md).
   Rebuild/deploy older runners to include the shared phone-local network policy;
   updating the main APK alone does not update their bundled code.
2. Authorize an independently signed runner in Projects → Details if required.
   Authorization is local; the PC is not consulted when starting a project.
3. Match the ROS domain across the main app, each project and MCU firmware.
   Main settings and project Connection settings are separate profiles.
4. For USB, prepare compatible serial firmware and the
   [USB Agent](ESP32-USB.md). Normal operation needs the main app plus the project
   runner APK; the Agent is bundled in the main APK.

## Use automatic addresses

Stop the affected project before changing its Connection settings. For the main
app, Stop all before editing Settings → Connection → Advanced connection.
Leave **PC IPv4 (optional)** and **Phone IPv4 (optional)** blank, then Save.
Repeat in each project profile that needs automatic operation.

Blank means automatic, including phone-local communication. A peer address is a
remote discovery hint, not a command server or permission to start a node. A phone
address pins a local interface; it must actually belong to the phone when starting.
An address saved on an old Wi-Fi network can therefore block startup even when
the PC is irrelevant. Clear that pin or enter a current phone IPv4 address.
Do not put the PC's address in the Phone IPv4 field.

The shared Cyclone configuration always includes `127.0.0.1` and a local discovery
peer. With blank Phone IPv4 it also selects an external interface when one is up.
With no external IPv4 it selects loopback only. Explicit `127.0.0.1` selects only
the local interface; it is useful for isolated developer checks, not communication
with another phone. The USB Agent separately advertises loopback using Fast DDS.

Settings are sampled when a runtime or shell environment is prepared. This change
does not implement live interface rebinding. After switching networks, stop and
restart affected sessions/projects and prepare a new shell environment if using
one. Restart the Agent if its external network locators need refreshing. Do not
expect an existing shell's environment to rewrite itself. A network change between
selection and node creation can still fail; retry with the current profile.

## First check: phone-local sensor and Dashboard

This check needs only the phone, with no robot or motor commands. Use the current
app build. Stop existing sessions first and leave the app visible during this
check so background restrictions are tested separately.

1. In Settings → Connection, leave PC IPv4 and Phone IPv4 blank and Save.
2. Turn Wi-Fi off before starting any publisher or Dashboard. Disconnect the PC.
3. In Sensors → Battery, publish `/phone/battery_percent`.
4. In Dashboard, add a Subscribe widget with type `std_msgs/msg/Float64` and
   topic `/phone/battery_percent`, then Start dashboard.
5. Observe it for one minute. Expect **Live** and a plausible battery percentage;
   the percentage itself need not change during the test.
6. Stop Battery publishing. After more than five seconds, expect **Stale**.
   Publish Battery again and expect **Live** to return.

Report any error text, Waiting/Stale result, or failure to recover. A pass checks
fresh offline startup and recovery of the main app’s local publisher/subscriber.
It does not qualify workspace runners, USB Agent or background execution; run
those cases separately below. Stop the test widget/publisher when finished.

## Allow background operation

Some devices suspend native workspace processes when the app leaves the
foreground. Configure the main app and each runner separately:

1. Open Settings → Background operation → Android app settings for the main app.
2. For a runner, open Projects → Details → Android app settings.
3. In Android's battery settings, allow background activity or choose Unrestricted
   if available. Labels and additional vendor controls vary by device.
4. Return to ROS2 Android and repeat the background reception test with a
   non-actuator workspace before relying on it.

These shortcuts open settings for the selected app; they do not change power
policy automatically. Power Saving being off alone does not establish permission
for background execution. App-specific settings do not guarantee uninterrupted
execution or recovery after Force stop, task removal or a reboot.

## Start without a PC

1. Disconnect the PC. For a fresh offline trial, turn Wi-Fi off **before** starting
   the components; losing Wi-Fi after startup is a different test.
2. Open ROS2 Android → Projects → Start project. Check state and Logs. A controller
   timeout means the outcome is unknown: Refresh reads status; it does not replay
   the previous command. Opening a runner does not start it automatically.
3. Start the sensors the project needs in Sensors.
4. If using a serial MCU, attach its USB data/OTG cable and start Settings →
   Microcontrollers → Start USB agent. Android may require USB permission again.
5. Start Dashboard subscribers for application feedback. Check fresh received
   data rather than only Running labels, discovered names or local send counts.

Use non-actuator examples for qualification. The example `android_test_ws` publishes
String results on `/android_test/result` by default. A Dashboard Subscribe/String
card should receive JSON containing an increasing `sequence` and a `squared`
value. This observes the talker → custom message → processor → main-app path.
Adapt the topic if you changed the launch namespace. For the reference USB firmware,
Battery publishing plus a Subscribe/String card on `/phone/display/text` checks
MCU replies; `/esp32/heartbeat` is Int32. Do not flash over an existing robot
application merely to perform this test.

## Recover deliberately

| Symptom | Action |
| --- | --- |
| Selected phone address is unavailable | Stop the affected component, clear Phone IPv4 in its profile, Save and start again |
| Project controls time out | Refresh, read actual state/Logs, then decide whether to start or stop; commands are not replayed |
| Project Running, no result | Check topic/namespace/domain and fresh subscriber data; inspect child errors; Running is not traffic proof |
| USB permission requested | Approve access for ROS2 Android; denial or a detached cable cannot be bypassed by the app |
| USB detached | Reconnect, Start USB agent and approve if requested; do not assume automatic recovery |
| Remote controller stops receiving | Check Wi-Fi/domain on both phones, their saved address pins and session restarts |
| Process/app update or Force stop | Reopen and explicitly start intended components; automatic boot/restart is not provided |

Background execution is owned by foreground services, not by whether a card
appears in Recent apps. Vendor policies can still suspend native children; qualify
the selected app settings on the target device. A five-minute Wi-Fi-off workspace
trial passed after background activity was allowed for both apps on the reference
setup; a separate 60-second screen-off trial also passed while USB-connected to
the PC. The default policy previously stalled results. These checks do not
establish unplugged deep-idle endurance or hard real-time scheduling.
No uninstall, app-data reset or global security-setting change is a recovery step.

## Qualification checklist

Record results privately with build versions, backend, domain, starting network
state, test duration, fresh sample counts/sequence and failures. Keep device IDs,
network addresses and raw logs out of Git. Do not combine several disruptions in
one test before each is understood.

| Case | Required observation | Current boundary |
| --- | --- | --- |
| PC absent, Wi-Fi present | Start deployed talker/processor and receive advancing results on phone | Repeat for current builds |
| Fresh Wi-Fi-off startup | Two workspace nodes exchange data and Dashboard receives advancing results | C++ workspace physical test passes, including stop/Stale and restart/fresh recovery; other backends remain open |
| Embedded Python and C++ factory offline | Start/stop without PC and receive application results in phone Dashboard | Physical test pending per backend |
| Fresh USB Agent startup, Wi-Fi off | Fresh typed MCU replies continue alongside project results | Physical test pending |
| Wi-Fi off/on after startup | Local sequences continue; remote reception recovers after intended restarts | Earlier USB interruption evidence is narrower than fresh offline startup |
| USB detach/reattach or denial | Clear status, explicit restart, fresh MCU replies, unchanged project profile | Repeat on current build; permission approval may require operator |
| Activity background | Fresh results continue with management controller unbound | Five-minute Wi-Fi-off C++ workspace trial passes with both apps allowed background activity |
| Screen off | Fresh results continue; stop/restart works after normal unlock | 60-second Wi-Fi-off C++ workspace trial passes while USB-connected to PC; unplugged deep idle remains open |
| Own-task removal | Fresh results continue; controls reconnect after reopening | 30-second C++ fixture test passes with instrumentation/observer alive; vendor Recents swipes remain open |
| Native child / runner death | Report failure or disconnect, remove workspace children, preserve profile, explicitly restart and verify fresh data | Both SIGKILL cases and runner Android Force stop pass for the C++ fixture; other backends remain open |
| Main-app death | Separate workspace survives; reopen, preserve settings and explicitly restore local ROS reception | Two-phase SIGKILL and Android Force stop tests pass with a 15-second outside process check; no reception claim during the subscriber's absence |
| App/runner upgrade and control reconnect | Clear unknown/failed state, no automatic command replay, preserved profile | Dedicated physical matrix pending |

For protocol fixture commands and compatibility tests, see [Testing](TESTING.md).
For component stop scopes, see [Robot operation](ROBOT-OPERATION.md).

Configuration references: the pinned [Cyclone DDS interface policy source](https://github.com/eclipse-cyclonedds/cyclonedds/blob/5041f3560c088c99e5088b2b8520b69169621196/src/core/ddsi/src/q_init.c)
and [current upstream configuration reference](https://cyclonedds.io/docs/cyclonedds/latest/config/config_file_reference.html).
The source lock and tested configuration govern this preview; newer upstream
options are not automatically supported by its pinned native libraries.
