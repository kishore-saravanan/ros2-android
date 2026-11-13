# Operate a robot from the phone

The phone runs native ROS nodes and captures phone sensors; a microcontroller
owns motor/I/O timing. The PC edits, builds, deploys and diagnoses. Once deployed,
ordinary app-owned nodes do not require a PC process to execute. Actual offline,
power and recovery qualification is recorded separately below; not every Android
phone or ROS package is supported.

For address settings, offline startup and a repeatable qualification checklist,
see [Standalone operation](STANDALONE-OPERATION.md).

## Prepare once

1. Complete DEVELOPMENT.md and the battery walkthrough in GETTING-STARTED.md.
2. Build/deploy your compatible workspace using COLCON-WORKSPACES.md. The
   ordinary builder currently supports ament_cmake C++ workspaces; embedded
   Python examples do not establish arbitrary mixed ament_python support.
3. For the reference ESP32, follow ESP32-USB.md. Connect it directly to the phone
   through USB OTG/data cable, allow this app's USB permission and use the matching
   serial firmware. A USB-C plug alone does not establish host/data capability.
4. Keep the main app and project ROS domain/profile consistent. The reference
   ESP32 firmware uses domain 0. Changing a phone setting does not reflash an MCU
   domain. Explicit peer/local addresses must match the actual network link.

## Start and verify

1. In Projects, select the deployed workspace and tap Start project. Confirm
   Running, expected node names and fresh output in its collapsed Logs.
2. In Sensors, open each needed sensor, check topic/rate and tap Publish.
3. For an MCU: Settings → Microcontrollers → Start USB agent (or Wi-Fi agent for
   firmware configured for UDP). Allow USB access if Android requests it.
4. In Dashboard, add the required publishers/subscribers and Start dashboard.
   Commands publish only when operated. Subscribers begin Waiting; Live means
   recent receipt, Stale means no sample within five seconds. An event-only topic
   can legitimately be Stale; this does not itself prove a disconnected device.
5. Verify application feedback, not only discovery/process labels. For the
   reference MCU: publish battery and subscribe to /phone/display/text (String)
   and /esp32/heartbeat (Int32). Replies/heartbeat must keep changing. The LED
   command/state demo is not physical-output confirmation for the unknown board.

Use the PC to inspect real topic samples, node/parameter behaviour and package
compatibility before operating a robot. PC monitoring can stop without stopping
phone-owned services. Shell-started nodes have a different lifecycle: Projects
only supervises its own launched processes; do not treat an interactive shell
process as an independently supervised robot session.

## Understand Home status

Sensors & dashboard active/stopped describes only the main app's ROS session.
It does not describe the whole robot computer. Projects and the micro-ROS Agent
have independent states in Home → ROS activity, which names each running project
with its node count. Read fresh topic data to verify
communication; active status alone is not evidence of delivery.

## What each Stop does

| Action | Stops | Does not stop |
| --- | --- | --- |
| Sensor detail Stop | That sensor publisher | Other sensors, Dashboard, projects, Agent |
| Stop dashboard | Dashboard endpoints and controls | Sensors, projects, Agent |
| Main Stop all | Main sensor and Dashboard endpoints | Project runners, Agent |
| Stop project | That runner's supervised node group | Main sensors/Dashboard, other projects, Agent, independently started shell nodes |
| Stop agent | Agent and its USB bridge | Main sensors/Dashboard and workspace execution |

Joystick release/cancel/focus loss/navigation/pause requests zero; it does not
replace a robot-side command deadline. The phone cannot deliver a stop message
through a dead link. Motors require an independently tested MCU watchdog and
appropriate physical stop mechanism. The reference LED firmware is not a motor
controller and does not establish motor-stop timing.

## Recovery

| Symptom | Check and action |
| --- | --- |
| Topic missing on PC | Publisher is started; domain and message type match; current network/interface and QoS are correct |
| Agent Running but no heartbeat | Check fresh MCU feedback; serial/UDP firmware and domain match; inspect bounded Agent logs; stop/start Agent |
| USB Disconnected | Reconnect data/OTG cable; Start USB agent; allow USB access if prompted |
| USB permission denied | Retry Start USB agent and approve access; no app-data reset is needed |
| Project Failed | Read that project's Logs/error, stop it, fix/rebuild/redeploy if needed, restart deliberately |
| Camera permission denied | Grant camera access for this app through Android's permission flow, then retry Publish |
| Intermittent background data | Note Settings → Runtime diagnostics → Power saving / Device idle; compare controlled conditions before attributing failures to DDS or power management |
| Phone app update | Sensor/Dashboard/Agent processes may stop; reopen and start intended parts explicitly |

Do not uninstall/clear app data as the first recovery step. Avoid changing
security, global battery or stay-awake settings as a workaround. Keep personal
apps/files outside development diagnostics.

## Qualification boundaries

The reference phone/USB ESP32 round trip passed a three-minute run including
60 seconds of Wi-Fi interruption, followed by recovered PC topic receipt using
an explicit discovery peer. A combined Wi-Fi interruption and removal of both
app tasks produced a long data gap and remains unresolved. For current operation,
keep ROS2 Android visible; unattended/screen-off endurance is not qualified.

See STATUS.md for the current test ledger.
Camera frames/local sent counts, MCU typed replies, actual robot motion and
hardware stop timing are different evidence. A simulated command/telemetry trial
cannot qualify a physical motor controller. APT publishing is deferred.
