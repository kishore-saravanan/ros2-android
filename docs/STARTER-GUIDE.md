# ROS2 Android starter guide

One path from nothing to a phone running your own ROS 2 nodes and an ESP32. Each part
ends with what you should see. Stop at any part: parts 1–3 need no build tools at all.

| Part | You get | Time |
| --- | --- | --- |
| [1. Install](#1-install-the-app-and-the-pc-tool) | The app on the phone, the `ros2-android` tool on the PC | 10 min |
| [2. First message](#2-first-message-phone-battery-on-the-pc) | Phone battery level arriving on the PC | 5 min |
| [3. Dashboard](#3-dashboard-a-button-the-pc-can-hear) | A phone button the PC receives | 5 min |
| [4. Build PC](#4-prepare-the-pc-to-build-for-the-phone) | One command installs the tools to build for the phone | 10–30 min |
| [5. Your nodes](#5-build-deploy-and-run-a-workspace-on-the-phone) | A C++ workspace running on the phone, seen from the PC | 15 min |
| [6. On the robot](#6-work-wirelessly-while-the-phone-is-on-the-robot) | Deploy over Wi-Fi with the phone mounted | 10 min |
| [7. ESP32](#7-esp32-flash-it-through-the-phone-and-talk-to-it) | Flash an ESP32 through the phone and exchange messages with it | 30 min |

## What you need

- An **Android phone**, ARM64, Android 8.0 or newer (tested on Android 12 and 17;
  parts 6–7 need Android 11 or newer), with **Developer options** and **USB debugging**
  turned on (Settings → About phone → tap *Build number* 7 times, then Developer
  options → USB debugging), and a USB data cable.
- An **Ubuntu 24.04 PC** with **ROS 2 Jazzy** ([install guide](https://docs.ros.org/en/jazzy/Installation/Ubuntu-Install-Debs.html)),
  on the **same Wi-Fi network** as the phone. Guest networks that isolate devices won't work.
  Parts 4–7 also need an internet connection and about 10 GB of free disk space.
- For part 7: an ESP32 dev board with a **CP2102** USB chip and a **USB OTG adapter**
  for the phone. Keep any motor power disconnected.

## 1. Install the app and the PC tool

1. Download these into one folder, for example `~/Downloads`:
   [ros2-android-1.0.0.apk](https://github.com/kishore-saravanan/ros2-android/releases/download/v1.0.0/ros2-android-1.0.0.apk),
   [ros2-android_1.0.0-1_all.deb](https://github.com/kishore-saravanan/ros2-android/releases/download/v1.0.0/ros2-android_1.0.0-1_all.deb)
   and [SHA256SUMS](https://github.com/kishore-saravanan/ros2-android/releases/download/v1.0.0/SHA256SUMS). Part 4 downloads everything else.
2. Check them:

   ```bash
   cd ~/Downloads
   sha256sum -c SHA256SUMS --ignore-missing
   ```

   **You should see** `OK` for each downloaded file.
3. Install the PC tool, then connect the phone by USB, unlock it, and tap **Allow** on
   the *Allow USB debugging?* prompt:

   ```bash
   sudo apt install ./ros2-android_1.0.0-1_all.deb
   ros2-android --version
   ros2-android device setup
   ```

   **You should see** `1.0.0`, then a successful install. apt may end with a note that
   the download *is performed unsandboxed as root*: that's harmless for a local file.
   If the phone has an older development build of ROS2 Android, uninstall it first: the
   release is signed with a different key.
4. Open **ROS2 Android** on the phone. Allow notifications if asked; the notification
   carries each session's **Stop** button.

## 2. First message: phone battery on the PC

1. In the app: **Sensors → Battery percentage → Publish**.
2. On the PC:

   ```bash
   source /opt/ros/jazzy/setup.bash
   ros2 topic echo /phone/battery_percent std_msgs/msg/Float64 --qos-reliability best_effort
   ```

   **You should see** `data: 85.0` (your battery level) once a second.
3. Tap **Stop** in the app when done. Nothing arrives? See
   [connection and troubleshooting](TROUBLESHOOTING.md#when-nothing-arrives).

## 3. Dashboard: a button the PC can hear

1. In the app: **Dashboard → Add widget**. Keep *Publish* and *Button · Bool*, set the
   topic to `/phone/control/button`, **Save**, then **Start dashboard**.
2. On the PC:

   ```bash
   ros2 topic echo /phone/control/button std_msgs/msg/Bool
   ```

3. Tap the button in the app. **You should see** `data: true` on the PC.
4. Tap **Stop dashboard** when done.

More widgets (switch, slider, joystick, live values, camera images): [Dashboard](CONTROLS.md).

## 4. Prepare the PC to build for the phone

Only needed to run your own nodes on the phone (parts 5–7). One command sets up everything
else the `ros2-android` tool needs:

```bash
ros2-android setup workspace-tools
```

It downloads Google's Android SDK tools and asks you to accept Google's licences (type `y`),
installs the pinned Android SDK and NDK into `~/Android/Sdk` (about 1.5 GB), downloads and
checks this release's native SDK, prepares the build tools and finishes with a health check.
The Ubuntu build packages it needs came with the `.deb`; if any are missing it prints the one
`sudo apt install …` line to run, then run it again. Running it again is always safe:
finished steps are skipped.

**You should see** `Ready. Build and deploy a workspace:`. Anything else names the step to fix.

## 5. Build, deploy and run a workspace on the phone

The repository's example workspace has a C++ *talker* and a *processor* node.

1. Get it and build it for the phone. `--controller-apk` points at the app you installed,
   so your workspace can be managed by it:

   ```bash
   git clone --depth 1 --branch v1.0.0 https://github.com/kishore-saravanan/ros2-android.git ~/ros2-android
   WS=~/ros2-android/examples/android_test_ws
   ros2-android workspace --workspace $WS inspect
   ros2-android workspace --workspace $WS build --controller-apk ~/Downloads/ros2-android-1.0.0.apk
   ros2-android workspace --workspace $WS deploy
   ```

   **You should see** each step finish without errors; deploy installs a project app.
2. In the app: **Projects → Refresh**. On the new project card open **Details → Authorize
   runner** and confirm, then tap **Start project**. **You should see** *Running* with
   two node names, and Home lists it under *Running*.
3. On the PC, build the same message types for the PC once, then watch the results:

   ```bash
   ros2-android workspace --workspace $WS host-build
   ros2-android workspace --workspace $WS ros -- topic echo /android_test/result
   ```

   **You should see** a new result every 200 ms, computed on the phone.
4. Tap **Stop project** when done.

Your own workspaces work the same way when their packages are C++ (`ament_cmake`) and
they install one launch file (or you pick one with `build --launch FILE`); see
[Workspaces](COLCON-WORKSPACES.md) for the details.

## 6. Work wirelessly while the phone is on the robot

1. On the phone (Android 11+): Developer options → **Wireless debugging** → on →
   **Pair device with pairing code**. On the PC, using the address and code it shows:

   ```bash
   adb pair PHONE_IP:PAIRING_PORT
   adb connect PHONE_IP:PORT          # the IP and port shown on the Wireless debugging screen
   ```

2. Unplug the cable. Deploy and manage exactly as before, naming the phone:

   ```bash
   WS=~/ros2-android/examples/android_test_ws
   ros2-android workspace --workspace $WS --serial PHONE_IP:PORT deploy
   ```

**You should see** the deploy succeed with no cable. The nodes keep running on the phone
when the PC disconnects. For long unattended runs keep ROS2 Android open with the screen
on (it keeps the screen awake), and in Android's app settings allow background activity
for ROS2 Android and for each project app.

## 7. ESP32: flash it through the phone and talk to it

1. **First flash, from the PC by USB** (once per board). Build the reference micro-ROS
   firmware and upload it with the board plugged into the PC:

   ```bash
   cd ~/ros2-android
   ./setup/esp32-usb.sh build
   ./setup/esp32-usb.sh upload /dev/ttyUSB0
   ```

   If Linux denies access to `/dev/ttyUSB0`, run `sudo setfacl -m u:$(id -un):rw /dev/ttyUSB0` and retry.
2. **Connect the board to the phone** with the OTG adapter (motor power off). Keep the PC
   on wireless debugging (part 6).
3. **Talk to it:** in the app, **Settings → Microcontrollers → Start USB agent** and allow
   USB access. Then **Sensors → Battery percentage → Publish**. In **Dashboard**, tap
   **Stop dashboard** if it is running, then **Add widget**: choose *Subscribe*, type
   `std_msgs/msg/String`, topic `/phone/display/text`, **Save** and **Start dashboard**. **You should see** `ESP32 received phone battery: …`
   in the widget: phone → ESP32 → phone over USB.
4. **Update its firmware through the phone**, without unplugging anything. Keep the
   phone unlocked with ROS2 Android open, then read the chip and write the firmware you
   built (step 1 also installed esptool):

   ```bash
   E="ros2-android esp32 --serial PHONE_IP:PORT"
   $E flash-id
   $E write-flash 0x10000 ~/.cache/ros2-android-esp32/firmware-serial-project/.pio/build/esp32dev/firmware.bin
   $E verify-flash 0x10000 ~/.cache/ros2-android-esp32/firmware-serial-project/.pio/build/esp32dev/firmware.bin
   ```

   Tap **Allow** if the phone asks for USB access. **You should see** the chip identified,
   *Hash of data verified* and *verify OK*. The Agent pauses while flashing and starts
   again by itself; if it doesn't, start it from Settings → Microcontrollers. If a write
   fails, run it again: the ESP32's boot ROM can't be overwritten.

More: [ESP32 over USB](ESP32-USB.md) · [over Wi-Fi](ESP32-WIFI.md) ·
[flashing details](ESP32-PROGRAMMING.md) · [running without a PC](STANDALONE-OPERATION.md).

## Something didn't work?

Note the part and step, what you saw, the app version (Settings → About), your phone model
and Android version, then [open a bug report](https://github.com/kishore-saravanan/ros2-android/issues/new?template=bug_report.md).
Leave out serial numbers, IP addresses, photos and raw phone logs.
