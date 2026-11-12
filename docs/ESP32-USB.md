# ESP32 connected directly to the phone by USB

The reference implementation uses a CP2102 ESP32 board (single interface,
USB VID:PID 10c4:ea60), Android USB host permission, and the phone-hosted
Micro-XRCE-DDS Agent. It needs no Linux Agent, root, Ubuntu or second APK.
Other serial adapters and native USB ESP32 variants are not supported by this driver.

## Prepare on the PC

Build the optional Agent once if it is not already bundled:

```bash
./setup/build-micro-ros-agent.sh
./setup/build.sh
./setup/run-device.sh
```

With the board connected to the PC, build and flash the serial profile:

```bash
./setup/esp32-usb.sh build
./setup/esp32-usb.sh upload /dev/ttyUSB0
```

If Linux reports permission denied, grant access locally with
`sudo setfacl -m u:$(id -un):rw /dev/ttyUSB0`, then retry. This temporary device
permission resets on reconnect. The reference board's original flash was backed
up before initial development; back up an unfamiliar board before replacing its firmware.

Default ROS domain is 0. To use another domain (0–101), set
`ROS2_ANDROID_ESP32_DOMAIN=42` before **both** build and upload commands. Match the
phone's sensor domain. Serial builds use a separate private cache and need no Wi-Fi
credentials. Upload replaces the board's Wi-Fi firmware; it does not support both
transports simultaneously. Restore Wi-Fi with `./setup/esp32-wifi.sh upload /dev/ttyUSB0`
after configuring that profile as described in [ESP32-WIFI.md](ESP32-WIFI.md).

## Connect and run

1. Disconnect the board and phone from the PC. Connect the board to the phone
   with a USB host/OTG adapter and a USB data cable. Type-C describes the connector;
   host-role negotiation and data wiring must also work. Enable the phone's OTG
   option manually if the vendor requires it. A powered USB hub can help if the
   phone cannot supply enough current.
2. Open ROS2 Android → Settings → Microcontrollers. Stop the Wi-Fi Agent if active,
   then tap **Start USB agent**. Accept Android's USB permission prompt.
3. In Sensors, publish Battery on `/phone/battery_percent`; add a Dashboard Subscribe widget
   (std_msgs/msg/String) on `/phone/display/text` and Start dashboard. Use the firmware's ROS domain (default 0).
4. The Dashboard text widget should receive `ESP32 received phone battery: …`. This verifies a
   phone publisher → USB → MCU subscriber → USB → phone subscriber round trip.
   A running Agent alone does not prove communication.
5. Stop the Agent before switching transport. After unplugging, the Agent stops
   and shows Disconnected; reconnect and tap Start USB agent again. Sensors remain
   independent. The board retries/restarts if its Agent session is lost.

The MCU serial transport does not use Wi-Fi. The recorded trial kept phone
Wi-Fi enabled for development/PC observation; fully offline phone DDS discovery
is not yet qualified. Clear stale explicit network addresses before trying an
offline sensor profile. Phone-to-PC DDS monitoring can still use Wi-Fi
separately; it is not the MCU transport. Remove the PC dependency when qualifying
standalone operation. ADB need not be running for normal use. Follow the
[standalone checklist](STANDALONE-OPERATION.md) for a fresh Wi-Fi-off trial.

## Transport and bandwidth

The app claims only the supported CP2102 interface, configures 115200 baud, 8N1,
no flow control and deasserted DTR/RTS, and pumps bulk bytes into an app-owned raw
PTY. The packaged upstream Agent opens that PTY with its standard serial backend.
XRCE framing is provided by upstream serial transports; the bridge does not parse
or alter frames. All resources belong to the foreground Agent service. Detach,
permission denial and transfer failures surface through its status. Reattach needs
an explicit Start action; there is no background automatic board access.

115200 baud / 10 bits per 8N1 byte = **11,520 bytes/second per direction** before
XRCE framing and ROS overhead. This is a configured line-rate ceiling, not measured
payload throughput. It is lower than typical ESP32 Wi-Fi throughput. USB avoids
radio contention on the MCU link; latency and reliability still require measurement.
Higher baud rates need joint board/driver/firmware qualification. This profile is
for control/status/small sensors rather than video. Full-speed USB's headline rate
is not the rate of the UART behind this bridge.

## Guided recovery check

`UsbAgentRecoveryTest` qualifies the recovery paths above with an operator. The ESP32
occupies the phone's USB port, so connect ADB over Wi-Fi first (`adb tcpip 5555` while
still on USB, then `adb connect PHONE_IP:5555`; `adb usb` restores USB mode afterwards).
With ROS2 Android open and unlocked and motor power off, run:

```bash
ANDROID_SERIAL=PHONE_IP:5555 ./setup/build.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.UsbAgentRecoveryTest \
  -Pandroid.testInstrumentationRunnerArguments.usbRecoveryGuided=true
```

Toasts on the phone (and `ROS2Guided` log lines) say when to unplug, replug, **deny**
and then **allow** USB access. The test requires the Disconnected state on detach, the
battery publisher and Dashboard to keep running, no automatic Agent restart after
reattach, the denial to be reported without a crash, and fresh battery replies only
after an explicit start. If Android remembers access for the board, the denial step is
recorded as not exercised rather than passed. It uses the reference serial firmware's
battery reply and heartbeat; no motion topic is published.

## Verification boundary

See [compatibility and validation](STATUS.md) for actual
checks and pending physical trials. Agent graph-manager integration, arbitrary
MCU/USB drivers, sustained throughput, command-expiry safeguards and endurance
remain separate work.

Implementation references: [Android USB host API](https://developer.android.com/develop/connectivity/usb/host),
[CP21xx protocol reference implementation](https://github.com/mik3y/usb-serial-for-android/blob/master/usbSerialForAndroid/src/main/java/com/hoho/android/usbserial/driver/Cp21xxSerialDriver.java),
[pinned micro-ROS PlatformIO transport](https://github.com/micro-ROS/micro_ros_platformio/tree/cfee17faffaa532363b7151dd13af6a85c69d3c1/platform_code/arduino/serial).

For PC firmware uploads while the ESP32 remains attached to the phone, see
[ESP32 programming through phone](ESP32-PROGRAMMING.md). Refer to its actual
qualification boundaries before relying on it.

## Dashboard LED switch

The current reference firmware subscribes to `/esp32/led` (`std_msgs/msg/Bool`).
`true` drives the configured LED output on until a `false` command turns it off.
There is no one-second timer. Boot/reset starts with the output off.
`/esp32/led_state` publishes Bool on/off changes; it reports commanded GPIO state,
not an optical measurement.

In Dashboard add:

- Label: **ESP32 LED**
- Direction: **Publish**
- Widget: **Switch**
- Topic: `/esp32/led`

Start the USB Agent in Settings → Microcontrollers, then **Start dashboard**.
Tap the switch once for on and again for off. Optionally add a Subscribe/Bool
widget on `/esp32/led_state`. Switch input survives tab navigation and stopping/
starting Dashboard while the phone service remains alive. It records the last
requested input, not confirmed device state, and does not automatically publish
on return. A firmware/device restart can reset the actual output independently.

GPIO2 active high is the reference default, not a verified pin mapping for every
ESP32 board. A board's USB/activity LED can be separate from the controllable LED. Identify
the PCB or schematic before changing `ROS2_ANDROID_LED_PIN` and
`ROS2_ANDROID_LED_ACTIVE_HIGH`. Boards without a controllable onboard LED need
an appropriate external LED/circuit. Physical LED mapping remains unconfirmed.
