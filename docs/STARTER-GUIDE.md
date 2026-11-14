# ROS2 Android starter guide

One path from nothing to a phone running your own ROS 2 nodes and an ESP32. Each part
ends with what you should see. Stop at any part: parts 1–3 need no build tools at all.

| Part | You get | Time |
| --- | --- | --- |
| [1. Install](#1-install-the-app-and-the-pc-tool) | The app on the phone, the `ros2-android` tool on the PC | 10 min |
| [2. First message](#2-first-message-phone-battery-on-the-pc) | Phone battery level arriving on the PC | 5 min |
| [3. Dashboard](#3-dashboard-a-button-the-pc-can-hear) | A phone button the PC receives | 5 min |
| [4. Build PC](#4-prepare-the-pc-to-build-for-the-phone) | Tools to build ROS workspaces for the phone | 30–60 min |
| [5. Your nodes](#5-build-deploy-and-run-a-workspace-on-the-phone) | A C++ workspace running on the phone, seen from the PC | 15 min |
| [6. On the robot](#6-work-wirelessly-while-the-phone-is-on-the-robot) | Deploy over Wi-Fi with the phone mounted | 10 min |
| [7. ESP32](#7-esp32-flash-it-through-the-phone-and-talk-to-it) | Flash an ESP32 through the phone and exchange messages with it | 30 min |

## What you need

- An **Android phone**, ARM64, Android 8.0 or newer, with **Developer options** and
  **USB debugging** turned on (Settings → About phone → tap *Build number* 7 times,
  then Developer options → USB debugging), and a USB data cable.
- An **Ubuntu 24.04 PC** with **ROS 2 Jazzy** ([install guide](https://docs.ros.org/en/jazzy/Installation/Ubuntu-Install-Debs.html)),
  on the **same Wi-Fi network** as the phone. Guest networks that isolate devices won't work.
- For part 7: an ESP32 dev board with a **CP2102** USB chip and a **USB OTG adapter**
  for the phone. Keep any motor power disconnected.

## 1. Install the app and the PC tool

1. Download these into one folder, for example `~/Downloads`:
   [ros2-android-1.0.0.apk](https://github.com/kishore-saravanan/ros2-android/releases/download/v1.0.0/ros2-android-1.0.0.apk),
   [ros2-android_1.0.0-1_all.deb](https://github.com/kishore-saravanan/ros2-android/releases/download/v1.0.0/ros2-android_1.0.0-1_all.deb),
   [ros2-android-native-sdk-1.0.0-arm64-v8a.tar.xz](https://github.com/kishore-saravanan/ros2-android/releases/download/v1.0.0/ros2-android-native-sdk-1.0.0-arm64-v8a.tar.xz)
   (only needed from part 4) and [SHA256SUMS](https://github.com/kishore-saravanan/ros2-android/releases/download/v1.0.0/SHA256SUMS).
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

   **You should see** `1.0.0`, then a successful install. If the phone has an older
   development build of ROS2 Android, uninstall it first: the release is signed with a
   different key.
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
   [troubleshooting](GETTING-STARTED.md#5-stop-and-recover).

## 3. Dashboard: a button the PC can hear

1. In the app: **Dashboard → Add widget**. Keep *Publish* and *Button · Bool*, set the
   topic to `/phone/control/button`, **Save**, then **Start dashboard**.
2. On the PC:

   ```bash
   ros2 topic echo /phone/control/button std_msgs/msg/Bool
   ```

3. Tap the button in the app. **You should see** `data: true` on the PC.

More widgets (switch, slider, joystick, live values, camera images): [Dashboard](CONTROLS.md).

## 4. Prepare the PC to build for the phone

Only needed to run your own nodes on the phone (parts 5–7).

1. Build packages, Java and ROS developer tools:

   ```bash
   sudo apt install build-essential cmake ninja-build git python3-dev python3-venv \
     python3-numpy pybind11-dev openjdk-21-jdk ros-dev-tools
   ```

2. The Android SDK (no Android Studio needed). Download the **Command line tools only**
   zip for Linux from [developer.android.com/studio](https://developer.android.com/studio#command-line-tools-only), then:

   ```bash
   mkdir -p ~/Android/Sdk/cmdline-tools
   unzip ~/Downloads/commandlinetools-linux-*.zip -d ~/Android/Sdk/cmdline-tools
   mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
   export ANDROID_HOME=~/Android/Sdk          # also add this line to ~/.bashrc
   "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
   "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'platform-tools' 'platforms;android-35' \
     'build-tools;35.0.0' 'ndk;27.2.12479018' 'cmake;3.22.1'
   ```

3. The build tools and the prebuilt native SDK (about 450 MB unpacked, installs in seconds):

   ```bash
   ros2-android setup build-tools
   ros2-android setup sdk --archive ~/Downloads/ros2-android-native-sdk-1.0.0-arm64-v8a.tar.xz
   ros2-android doctor --profile android
   ```

   **You should see** every doctor check pass. Fix any failed check before continuing;
   each one says what to install.

## 5. Build, deploy and run a workspace on the phone

The repository's example workspace has a C++ *talker* and a *processor* node.

1. Get it and build it for the phone. `--controller-apk` points at the app you installed,
   so your workspace can be managed by it:

   ```bash
   git clone https://github.com/kishore-saravanan/ros2-android.git ~/ros2-android
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

Your own workspaces work the same way; see [Workspaces](COLCON-WORKSPACES.md) for
supported packages and launch files.

## 6. Work wirelessly while the phone is on the robot

1. On the phone (Android 11+): Developer options → **Wireless debugging** → on →
   **Pair device with pairing code**. On the PC, using the address and code it shows:

   ```bash
   adb pair PHONE_IP:PAIRING_PORT
   adb connect PHONE_IP:PORT          # the IP and port shown on the Wireless debugging screen
   ```

2. Unplug the cable. Deploy and manage exactly as before, naming the phone:

   ```bash
   ros2-android workspace --workspace $WS --serial PHONE_IP:PORT deploy
   ```

**You should see** the deploy succeed with no cable. The nodes keep running on the phone
when the PC disconnects. For long unattended runs keep ROS2 Android open with the screen
on (it keeps the screen awake), and allow it background activity in Android's app settings.

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
   USB access. Then **Sensors → Battery percentage → Publish**, and in **Dashboard → Add
   widget** choose *Subscribe*, type `std_msgs/msg/String`, topic `/phone/display/text`,
   **Save** and **Start dashboard**. **You should see** `ESP32 received phone battery: …`
   in the widget: phone → ESP32 → phone over USB.
4. **Update its firmware through the phone**, without unplugging anything. Create the
   esptool environment once, then read the chip and write the firmware you built:

   ```bash
   python3 -m venv ~/.local/share/ros2-android-esptool
   ~/.local/share/ros2-android-esptool/bin/pip install 'esptool>=4,<6' pyserial
   E="ros2-android esp32 --python $HOME/.local/share/ros2-android-esptool/bin/python --serial PHONE_IP:PORT"
   $E flash-id
   $E write-flash 0x10000 ~/.cache/ros2-android-esp32/firmware-serial-project/.pio/build/esp32dev/firmware.bin
   $E verify-flash 0x10000 ~/.cache/ros2-android-esp32/firmware-serial-project/.pio/build/esp32dev/firmware.bin
   ```

   Tap **Allow** if the phone asks for USB access. **You should see** the chip identified,
   *Hash of data verified* and *verify OK*. The Agent stops while flashing; start it again
   from Settings → Microcontrollers. If a write fails, run it again: the ESP32's boot ROM
   can't be overwritten.

More: [ESP32 over USB](ESP32-USB.md) · [over Wi-Fi](ESP32-WIFI.md) ·
[flashing details](ESP32-PROGRAMMING.md) · [running without a PC](STANDALONE-OPERATION.md).

## Something didn't work?

Note the part and step, what you saw, the app version (Settings → About), your phone model
and Android version, then [open a bug report](https://github.com/kishore-saravanan/ros2-android/issues/new?template=bug_report.md).
Leave out serial numbers, IP addresses, photos and raw phone logs.
