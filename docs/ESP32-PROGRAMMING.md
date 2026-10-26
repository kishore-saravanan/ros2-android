# Program an ESP32 through the phone

Connection: PC and phone on the same Wi-Fi; ESP32 attached to the phone through
USB host/OTG and a data cable. This reference implementation supports the tested
CP2102 UART adapter (10c4:ea60) and classic ESP32. USB-C alone does not establish
host/data roles. It does not support native USB/JTAG or other serial adapters.

## Prepare

1. Build/install the current **debug** APK using setup/build.sh and
   setup/run-device.sh while the phone is attached to the PC.
2. Establish authorized wireless ADB, verify `adb devices -l`, and keep ROS2 Android
   unlocked/open for the development trial. Select that device explicitly.
3. Disconnect the phone from the PC and connect ESP32 directly to the phone.
   Allow this app's Android USB permission prompt when requested.
4. Prepare firmware on the PC: `./setup/esp32-usb.sh build` builds our micro-ROS
   serial example and prepares the Python/esptool toolchain in the user cache.

No LAN flashing listener is exposed. Authorized ADB forwards a phone loopback-only
port, and a random per-session token authenticates the UART data connection.
The debug control receiver requires Android's DUMP permission, held by ADB shell;
ordinary apps cannot invoke it. The receiver is absent from release APKs.

## Inspect, back up, upload

From the repository root, use the authorized wireless ADB target from
`$HOME/Android/Sdk/platform-tools/adb devices -l`:

```bash
./tools/esp32-via-phone --serial PHONE_ADB_ID status
./tools/esp32-via-phone --serial PHONE_ADB_ID flash-id
./tools/esp32-via-phone --serial PHONE_ADB_ID read-flash 0x10000 0x60000 /path/to/new-application-backup.bin
./tools/esp32-via-phone --serial PHONE_ADB_ID write-flash 0x10000 /path/to/firmware.bin
./tools/esp32-via-phone --serial PHONE_ADB_ID verify-flash 0x10000 /path/to/firmware.bin
```

`status` only reads the app's programming state, Agent state and USB byte
counters. It does not launch the Activity, open UART, reset the board or stop the
Agent, and does not require the PC esptool environment. It still requires an
authorized ADB connection and the current debug APK. A Running state/counter
proves neither a healthy micro-ROS session nor message receipt; check fresh
heartbeat/acknowledgement topics for that.

Offsets and backup sizes above are examples for the existing ESP32 reference app,
not universal partition layouts. Use your build's flash layout. Back up every
sector an upload will touch. read-flash refuses an existing output file. The
command accepts esptool v5 and positional operation arguments only. It does not
erase the full chip or automatically write bootloader/partition-table images.
For a complete image, pass the build's required OFFSET FILE pairs to write-flash.

Programming stops the existing Agent and takes exclusive USB ownership. The phone
performs the CP2102 DTR/RTS bootloader reset locally and fixes UART speed at
115200. PC esptool uses a raw socket over the ADB tunnel; do not pass baud/port/
reset overrides. write-flash checks written data using esptool's normal checks.
Only after a successful operation does cleanup reboot the ESP32 and restore the
previous Agent transport if it had been running. Failed/cancelled operations
leave the Agent stopped for explicit recovery. Programming mode does not start
an Agent that was previously stopped.

Settings → Microcontrollers shows programming state and Stop USB programming
while active. Notification Stop also releases it. Cable loss stops the service;
PC connection/idle timeouts and a 15-minute limit bound abandoned sessions.
USB permission is required again if Android revokes it after reconnecting.
The PC removes its temporary ADB forwarding rule during cleanup. A failed flash
can leave incomplete firmware; retry with the correct images before running ROS.

## Qualification

See PROJECT-KNOWLEDGE.md for actual physical test results and remaining gates.
Binary relay round trip and cancellation have automated PC coverage. A successful
build or UART bridge alone does not prove actual ESP32 flashing or micro-ROS
recovery. Other phones, USB adapters and long-duration transfers are unqualified.

The raw-socket restriction and UART control requirements follow
[Espressif remote-serial documentation](https://docs.espressif.com/projects/esptool/en/latest/esp32/remote-serial-ports.html).

## Observed transfer finding

The first sustained application backup failed with esptool's "Read more than
expected" error. A 4096-byte serial read at 115200 needs about 356 ms, exceeding
the bridge's 100 ms USB timeout. The shared driver now requests a single USB
endpoint packet per read, allowing continuous traffic to complete within that
window. The subsequent 393216-byte backup completed with its integrity check,
and its application bytes matched the reference binary before reflashing.
The original failed read wrote no firmware.

A coordinated physical test then reflashed the backed-up reference application
through the phone, verified the flash hash, and confirmed automatic restoration
of the previous USB Agent with fresh typed MCU acknowledgement and heartbeat
received in Dashboard subscriptions. The PC never took direct USB ownership of
the ESP32 during these operations. Detailed evidence is in PROJECT-KNOWLEDGE.md;
temporary captures, firmware backups and device logs remain ignored.
