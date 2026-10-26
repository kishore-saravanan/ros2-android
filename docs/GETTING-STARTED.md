# First message: phone battery to ROS 2

This walkthrough is for a Linux ROS 2 developer with a source checkout and an
Android ARM64 phone. It uses battery percentage before motion or camera data.
No camera, microphone or storage permission is needed for this example.

**Build prerequisites are required:** complete [DEVELOPMENT.md](DEVELOPMENT.md)
first (ROS 2 Jazzy, Android SDK/NDK, Java and the native ROS SDK build). No public
prebuilt installer/SDK or fresh-machine setup validation is implied by this guide.
The recorded walkthrough uses the prepared reference PC and phone. Community
release licensing, signing and distribution remain separate work.

## 1. Install the app

Open a terminal in the repository folder (the folder containing tools/).
Unlock your phone, enable USB debugging, connect it to the PC and authorize this
computer on the phone. Then run:

```bash
./setup/build.sh
./setup/run-device.sh
```

Expected: build reports BUILD SUCCESSFUL; installation reports Success; ROS2
Android opens. If it does not open on the first install, tap its icon on the
phone. Stop on a build/install error and use the development guide to resolve it.
Installing an update interrupts this app's current sensor session; it does not
start publishers automatically.

## 2. Choose a network path

- **USB:** enable USB tethering while keeping USB debugging enabled. A debugging
  cable alone carries installation/control traffic, not ordinary DDS messages.
- **Wi-Fi:** connect PC and phone to a reachable network that permits traffic
  between devices. Client-isolated guest networks may block discovery/messages.

Open **Settings** in the app. Keep domain **0** for this example, matching the PC.
If you change it, use the changed value in every PC command. Use the network
addresses under Settings → Network addresses and the PC's addresses from:

```bash
ip -4 -brief address
```

When discovery needs help, set PC IPv4 and Phone IPv4 to addresses on the chosen
link, under Advanced connection, then Save. Leave optional addresses blank for automatic selection.
Use Stop all before changing an active profile. A saved profile is configuration,
not proof that a peer has been reached.

## 3. Start battery publishing

Open **Sensors → Battery percentage**. Opening its controls does not start
publishing. The detail screen shows:

- Topic: /phone/battery_percent
- Rate: 1 Hz
- Type: std_msgs/msg/Float64 (a percentage from 0 to 100)

Tap **Publish**. Expected: the button becomes Stop and the detail screen shows a value
such as 85% battery; its local sent-message count is available under Advanced. The phone can
repeat the same percentage for a long time while its charge stays constant.

If you need another topic or rate, edit the topic and rate on the battery detail screen before Publish.
The battery rate supports 0.2–1 Hz. A failure to read the hardware percentage
shows an error; the app does not substitute an invented number.

**The topic is not published while the card is stopped.** Merely opening the app,
opening the card or completing a previous automated test does not publish it.

## 4. Receive it on the PC

Open **Settings → Help → Your first message** for commands based on your saved domain and battery topic; the
Copy commands button copies only when you tap it. The default commands are:

```bash
source /opt/ros/jazzy/setup.bash
export ROS_DOMAIN_ID=0
ros2 topic list -t
ros2 topic echo /phone/battery_percent std_msgs/msg/Float64 --qos-reliability best_effort
```

Expected topic: /phone/battery_percent [std_msgs/msg/Float64]. Expected samples:

```text
data: 85.0
---
data: 85.0
---
```

Use your phone's current percentage, not 85 as a required test value. Receiving
samples proves this communication path; a topic name or local sent count alone
does not. Press Ctrl+C to stop viewing; the phone continues publishing.

The source command assumes Jazzy is installed under /opt/ros/jazzy on the PC.
It does not install ROS. Adapt it if your installation uses another prefix.

## 5. Stop and recover

Tap Stop on the battery card to stop that publisher, or Stop all to release
all main-app sensor and Dashboard endpoints. It does not stop projects or the
micro-ROS Agent; each has its own Stop action. Start again by tapping Publish. Leaving the screen keeps
an active service-owned session running; process death or APK replacement can
stop it. Project runners have their own Stop project action and independent
profiles; see [PROJECTS.md](PROJECTS.md).

If no topic or samples arrive, check these in order:

1. The battery card says publishing and its sent count increases. If stopped,
   tap Publish first. If it shows an error, follow the recovery message.
2. Both PC and phone use the same ROS_DOMAIN_ID and exact saved topic name.
3. Tethering/Wi-Fi is still active and the saved IPv4 addresses are current.
4. Try `ros2 topic list -t --no-daemon` to avoid a stale CLI discovery cache.
5. Check network isolation/firewall configuration. Advanced multi-interface
   checks and scoped middleware helpers are in [V0.1.md](V0.1.md).

Do not start by disabling the PC firewall, changing phone security settings or
installing unrelated phone apps. If it still fails, report app/build version,
Android version, transport, domain, error text and sanitized diagnostic results.
Exclude device identifiers, personal information and camera images from reports.

## Next examples

- Sensors → Motion for IMU/gyro; Sensors → Cameras for the local thumbnail and
  ROS JPEG stream. Camera publishing asks for camera permission.
- Dashboard → Add widget → Subscribe → std_msgs/msg/String for debug text.
- Projects for ordinary workspace runners; the example is
  examples/android_test_ws. Use the workspace guide's matching domain/overlay.

## External onboarding trial still needed

Ask a new contributor to follow this guide without oral setup help. Record their
OS, toolchain versions, phone/Android, transport, elapsed setup time, exact failed
step, recovery and whether real PC samples arrived. Confirm initial installation,
Publish, stop/restart and another session after reconnect. Record licensing/artifact
access obstacles too. A maintainer's prepared-machine walkthrough is not evidence
that this trial or a clean-machine dependency install has passed.

## Next: use the phone as the robot computer

Continue with [Robot operation](ROBOT-OPERATION.md) for the workspace/MCU setup,
start/stop scopes, loss-of-connection checks and recovery. APT distribution work
is deferred while runtime qualification and usability are completed.
