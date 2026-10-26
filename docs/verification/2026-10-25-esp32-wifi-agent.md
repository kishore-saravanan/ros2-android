# ESP32 ↔ phone UDP XRCE-DDS proof — 2026-10-25

## Topology and scope

A reference ESP32-D0WD-V3 development board with 4 MB flash runs micro-ROS Jazzy
via PlatformIO/Arduino. Wi-Fi UDP targets the phone's packaged ARM64
Micro-XRCE-DDS-Agent v2.4.3 at port 8888. Fast DDS bridges the client's entities
to the same ROS domain as the phone's native Jazzy/Cyclone sensor node. The PC
builds/flashes and may observe topics; no PC-hosted Agent is used.

This is the micro-ROS client data path using upstream XRCE-DDS Agent, not a port
of the micro_ros_agent ROS graph-manager wrapper. The MCU's topics can exist
without its name appearing in ros2 node list. Android USB MCU transport, graph
metadata, broader board/phone coverage and endurance remain pending.

## Implemented and verified

- Read and preserved the board's entire original 4 MB flash before programming;
  backup stays ignored/private. Serial permission was granted locally by owner.
- ESP32 joined the configured Wi-Fi. The router advertises the same SSID on both
  bands; the classic ESP32 uses 2.4 GHz while PC/phone can use the other band.
- Firmware compiled: about 67,012 bytes RAM (20.5%) and 811,689 bytes application
  flash (61.9%). Upload completed and esptool verified the written data hash.
- Main build/unit tests/lint passed, including the final source/lint sweep.
  All 24 existing host tooling tests passed; new setup shell syntax and Python
  compilation checks passed. The Agent's four ELF files were validated
  as AArch64, normalized for Android APK library names, stripped, and packaged
  with upstream license notices. Main APK with the Agent measured 8.66 MiB.
- Final physical instrumentation: two tests passed, zero skipped/failures.
  agentProcessStarts checks process startup against the real connected board.
  esp32ReceivesPhoneBatteryAndRepliesAfterAgentRestart starts the phone battery
  publisher and String subscriber and requires multiple actual ESP32
  acknowledgements in the phone's NativeRuntime receive path. It then stops the
  Agent, asserts the sensor session remains active, drains earlier messages,
  restarts the Agent and requires additional acknowledgements from the new
  session. This verifies real ROS traffic and reconnect behavior, not JNI-only
  diagnostics or a PC echo publisher.
- Final result: BUILD SUCCESSFUL; 2 tests, 0 failures/errors/skips. Device logs
  and XML stay in ignored build/device-log directories. Instrumentation APK was
  removed after the trial.
- Normal-use startup after instrumentation was also verified: phone Agent,
  battery publisher and display subscriber were restored. A PC subscriber in
  domain 0 received an ESP32 heartbeat and the real acknowledgement
  `ESP32 received phone battery: 100.0% (heartbeat 0)`. No PC Agent or echo
  publisher participated. Device/address details remain in ignored logs.

Topics: phone publishes /phone/battery_percent (std_msgs/msg/Float64, best effort).
ESP32 publishes /esp32/heartbeat (Int32, best effort) and replies reliably on
/phone/display/text (String). Acknowledgements report the received battery value
and heartbeat count. No actuator GPIO behavior is configured.

## Build/runtime findings

- Pass arm64-v8a, API 26 and shared libc++ into all superbuild dependencies.
  Merely setting the top-level Android ABI is insufficient for this upstream
  superbuild; static copies of the C++ runtime initially caused exceptions.
- Force target TinyXML2 include paths and package discovery. Host /opt/ros or
  /usr/include must not satisfy Android target dependencies.
- Foonathan's generated node-size header survives CMake cache/ABI changes.
  Stale 32-bit sizes caused bad_node_size at runtime despite ARM64 ELF headers.
  Regenerating the header and rebuilding its consumers fixed the failure; the
  setup helper records the target ABI/runtime stamp.
- The upstream PlatformIO builder assembles unquoted shell paths. Build in a
  private cache without spaces, with its expected venv activation path and Linux
  ROS PATH/prefix entries removed. Otherwise it selects host C++ typesupport or
  fails git clone in the repository's path containing spaces.
- Temporary exception tracing was removed. Agent log text strips ANSI/control
  characters, which otherwise corrupt instrumentation result XML.
- An instrumentation run stops the target app at completion. Restart normal
  app services afterward; a passing test does not leave the demo running.

## Resume

Use docs/ESP32-WIFI.md. Keep credentials, compiled firmware (which embeds Wi-Fi
credentials), original flash dumps, identifiers and transient network addresses
out of Git. The optional Agent staging directory is separate from the existing
ROS/Cyclone staging closure so setup/stage-native-libs.py does not remove it.
