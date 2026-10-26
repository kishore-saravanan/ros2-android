# ESP32 programming via phone and Dashboard LED

## Connection

PC used authorized wireless ADB to the phone; ESP32 CP2102 UART adapter was
connected only to the phone's USB host/OTG port. No PC USB flashing connection
and no public LAN programming listener were used.

## Actual results

- Android assembly/unit/lint passed; 26 tooling tests passed, including a real
  binary socket-relay/auth-prelude round trip and cancellation.
- Remote chip/flash inspection succeeded. 4 MB flash reported.
- The first long backup failed without writing firmware. Fixed oversized USB
  reads exceeding the read timeout; requests now use endpoint-sized packets.
- Application backup: 393216 bytes. Integrity check passed; application bytes
  matched the reference binary before upload. Backup remains ignored/private.
- Reflash of the backed-up reference application: 343248 bytes, 198747 compressed,
  at application offset 0x10000. esptool verified its written hash.
- Esp32ProgrammingTest passed the running USB Agent→programming→automatic Agent
  restore path, with fresh typed MCU acknowledgement and heartbeat returning
  after completion. Sensors and Dashboard endpoints remained active throughout.
- LED firmware built with the pinned serial toolchain, uploaded over the same
  phone path and passed hash verification. Bool true on /esp32/led_flash requests
  a one-second GPIO2 pulse; false cancels it. Nonblocking timer, configurable
  pin/polarity. /esp32/led_state publishes commanded on/off state.
- Esp32LedTest passed an actual Dashboard button click, native Bool publication,
  fresh MCU heartbeat and returned on/off state. Saved Blink LED alongside the
  existing layout. Independent read-only PC observer measured a 0.922 s interval
  between MCU on/off messages. That is message timing, not optical measurement.
- Final light/dark UI and USB PTY/packaged-Agent regression: four device cases
  passed with zero skips/failures. About retains plain author credit/Sponsors
  only. App-only captures were inspected and remain ignored.
- Test APK removed, normal app reopened and USB Agent left running. User visual
  confirmation of actual LED output is recorded in PROJECT-KNOWLEDGE.md when
  received.

## Findings and limits

Early LED attempts had missing MCU feedback; a stale test dashboard registration
and a session requiring a clean restart were investigated. Scoped UART capture
confirmed normal ESP32 boot and XRCE pings. Final tests require fresh heartbeat
rather than treating a running Agent process as connectivity proof. Reliable
recovery from every rapid Agent/app restart is still unqualified.

No interrupted-write/power-loss, other-board/adapter/phone, long-duration or
Wi-Fi-firmware LED qualification is claimed. GPIO state telemetry does not prove
an LED is physically fitted or wired to the configured pin. Firmware backups,
raw MCU UART captures, screenshots and transient addresses/identifiers stay out
of Git. Original phone data and other apps were untouched.

## Repeat the physical tests

Use setup/build.sh :app:connectedDebugAndroidTest and explicit ANDROID_SERIAL for
wireless ADB. Select Esp32ProgrammingTest with programmingRoundTrip=true only
when an external PC upload is ready: it waits for a fresh MCU acknowledgement,
writes its own cache/esp32-programming-ready marker, then waits for the PC command.
Select Esp32LedTest with usbLedConnected=true to exercise/save the LED button.
Select UsbAgentTransportTest for binary PTY/packaged-Agent regression. These are
opt-in hardware trials, not a substitute for target-device setup and backups.

## Current toggle behavior (supersedes pulse)

At the owner's request the one-second pulse was replaced with a latched Bool
output: /esp32/led true keeps GPIO2 high; false makes it low. Saved widget is
ESP32 LED / Switch, migrated in place. Firmware starts off after boot. The final
physical Switch test passed with no skips/failures: on, >3-second hold, navigation,
Activity recreation without unsolicited publication, then off. Independent PC
telemetry interval was 4.577 seconds. Generic switch input now lives in the phone
service and synchronizes UI checks without invoking the publisher listener.
This remains requested input, not a remote-state acknowledgement UI.

The physical LED itself is still unconfirmed. User reported a pre-existing blue
blink; a 12-second idle observation received 12 heartbeats but no LED commands or
state edges. Photo shows a DevKit-style USB-C board but no verified LED net.
Do not label the reported onboard light as GPIO2-controlled without a schematic,
trace/pin test or physical observation. The reference DevKitC has a documented
[power-on indicator](https://docs.espressif.com/projects/esp-dev-kits/en/latest/esp32/esp32-devkitc/user_guide.html),
which does not establish the wiring of this third-party USB-C variant.
