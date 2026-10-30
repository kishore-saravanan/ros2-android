# ESP32 ↔ ROS2 Android over Wi-Fi

Reference firmware targets a classic ESP32 development board (ESP32-D0WD-V3,
4 MB flash, CP2102 USB bridge). It uses micro-ROS Jazzy via PlatformIO/Arduino.
The phone hosts the UDP XRCE-DDS Agent and the native ROS sensor node. The PC
builds/flashes software and can observe ROS traffic; it does not need to host an
Agent for this topology.

## Dependencies and configuration

Use the Android prerequisites in DEVELOPMENT.md, plus CMake, Git, Python venv/pip
and access to the board's serial port. Ubuntu packages python3-venv/python3-pip
provide the normal Python tooling. First-time builds download substantial vendor
source/toolchains. The setup commands use a private cache under
~/.cache/ros2-android-esp32. They preserve the checkout's source files.

```bash
python3 -m venv "$HOME/.cache/ros2-android-esp32/toolchain-venv"
"$HOME/.cache/ros2-android-esp32/toolchain-venv/bin/pip" install platformio esptool patchelf
./setup/build-micro-ros-agent.sh
./setup/build.sh
./setup/run-device.sh
```

The Agent build uses NDK r27c for ARM64/API 26, shares libc++ with the app,
packages only the verified ELF dependency closure and carries vendor licenses in
APK assets. Its optional generated libraries live in native-deps/agent-apk-libs,
separate from the Cyclone ROS staging directory. A build without these artifacts
can still run the sensor app, but cannot start the Agent.

Find the phone Wi-Fi IPv4 in Settings → Network addresses. Configure the ESP32
with that address; it is the Agent host, not the PC address:

```bash
python3 setup/configure-esp32-wifi.py --agent-ip PHONE_WIFI_IP --domain 0
./setup/esp32-wifi.sh build
```

Configuration reads the active PC NetworkManager Wi-Fi profile. If its password
is unavailable, it requests it locally without echo. Credentials are written
only to ignored device-logs/esp32-private and the private firmware cache, never
to checked-in source. Firmware images contain the configured credentials; keep
these artifacts private. The classic ESP32 needs a 2.4 GHz network. PC/phone may
use 5 GHz on the same LAN if the router allows traffic between bands/devices.
Changing the phone's DHCP address requires reconfiguration/reflashing for now.

## Flash and run

Flashing replaces the board's current firmware. Preserve a flash backup first
if needed; the development session's original backup stays in ignored
device-logs/esp32-private/original-flash.bin.

For a temporary Linux serial permission grant, run this locally if your account
cannot open the port (requires administrator access):

```bash
sudo setfacl -m u:$(id -un):rw /dev/ttyUSB0
./setup/esp32-wifi.sh upload /dev/ttyUSB0
./setup/esp32-wifi.sh monitor /dev/ttyUSB0
```

On the phone:

1. Settings → Microcontrollers → Start Wi-Fi agent. UDP port is 8888.
2. Match Settings ROS domain with firmware (example: 0), then Sensors → Battery percentage → Publish
   to send battery percentage on /phone/battery_percent.
3. Dashboard → Add widget → Subscribe → std_msgs/msg/String, topic
   /phone/display/text; Save, then Start dashboard.
4. The ESP32 acknowledgement reports the phone battery and heartbeat count.

The ESP32 publishes /esp32/heartbeat (std_msgs/msg/Int32) each second. It subscribes
to /phone/battery_percent (std_msgs/msg/Float64), then publishes a reliable String
acknowledgement on /phone/display/text. Battery/heartbeat use best-effort QoS.
The board restarts to rejoin Wi-Fi/recreate entities after a lost Agent session.
No GPIO actuator or motor output is configured.

Monitor from a ROS PC in the same domain:

```bash
export ROS_DOMAIN_ID=0
ros2 topic echo /esp32/heartbeat std_msgs/msg/Int32 --qos-reliability best_effort
ros2 topic echo /phone/display/text std_msgs/msg/String
```

Stop Wi-Fi agent releases the Agent independently of phone sensors and project
runners. It is not included in sensor Stop all. Agent output is bounded under
Settings → Microcontrollers → Agent logs. This is a UDP demo, not the Android
USB-serial MCU integration; USB here programs/powers the ESP32.

## Scope

The backend is upstream Micro-XRCE-DDS-Agent v2.4.3 with Fast DDS. It supports the
typed micro-ROS client data path; the micro_ros_agent ROS graph-manager wrapper
is not bundled. ESP32 topics can be visible even when ros2 node list does not
show the MCU node. Full graph metadata, Android USB transport, persistent board
provisioning/address discovery, broader board/phone qualification and control
watchdogs remain separate work. See STATUS.md for actual verification.

Sources: [micro-ROS PlatformIO](https://github.com/micro-ROS/micro_ros_platformio),
[XRCE-DDS Agent](https://github.com/eProsima/Micro-XRCE-DDS-Agent).

For direct phone-to-board USB instead, see [ESP32-USB.md](ESP32-USB.md).
The USB profile replaces the Wi-Fi firmware; keep these transport profiles separate.
