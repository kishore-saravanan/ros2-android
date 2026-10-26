# ROS2 Android development knowledge base

Last updated: 2026-10-26. Read this before resuming development, then read
[AGENTS.md](../AGENTS.md) and check the actual Git/device state. This file is the
resume index; detailed architecture, usage and measurements remain in linked
files. Update it whenever a milestone, finding or verification boundary changes.

## Debian distribution and explicit device setup (2026-10-26)

Owner selected Ubuntu .deb/APT distribution and authorized proceeding with the
planned work. Added setup/build-deb.py using the existing wheel payload plus a
validated signed main APK. Package is Architecture all (Python host client;
ARM64 APK is phone data), depends on python3/adb/bash and uses configured Git
maintainer identity. Files are under /usr/lib/ros2-android and /usr/share/doc;
/usr/bin/ros2-android is the entry point. No maintainer scripts, automatic phone
operations, user-cache cleanup or private signing keys are packaged.

Added `ros2-android device setup --serial PHONE_ADB_ID`. Uses bundled artifact
manifest and SHA-256, validates fixed main-app identity/API26/ARM64 target, then
delegates setup/run-device.sh with install-only and explicit selection. Installer
supports system adb fallback and checks phone API/ABI before install -r. There is
no uninstall/clear-data/downgrade fallback, permission grant or automatic launch.
Build-time aapt identity and apksigner checks precede packaging; debug APK requires
explicit --allow-debug-apk. This is local development packaging, not production
signing. A checksum detects mismatches, not malicious replacement of a package.

Four new tests cover successful scoped update, wrong app identity, APK tamper
and incompatible phone rejection using fake ADB. Rootless Debian verifier checks
actual extracted launcher/help/bundled update path and absent maintainer scripts.
Actual verification: setup/build.sh assembly/unit/lint passed; wheel rebuilt and
its offline installation smoke passed; local Debian alpha built (about 8.6 MiB).
Rootless extraction verifier passed launcher/backend help and fake-ADB device
setup. Full tooling regression passed 32 tests. A real authorized wireless-ADB
trial ran device setup through the extracted Debian launcher outside the checkout
and successfully updated the bundled main APK with install -r. No app launch,
permission grant, uninstall or data clear. This is extracted-package device
qualification, not a system-installed .deb or fresh ROS interoperability trial.
Clean-machine APT/dpkg install/upgrade/removal,
policy/lint/source-package review, third-party notices and production signing
remain public-release gates. No APT repository or signing credentials created.
Read DEBIAN-PACKAGE.md. Wheel remains the reusable payload; .deb is the primary
Ubuntu delivery path. Robot reliability/mixed-workspace milestones remain next.

## Apache-2.0 selection (2026-10-26)

Owner explicitly selected Apache License 2.0, including commercial use. Added
unaltered official LICENSE text, NOTICE crediting Kishore Saravanan (2026), and
CITATION.cff with author/project/repository metadata. Citation is requested, not
a new license condition; no DOI, publication or release date invented. README
and host package metadata state Apache-2.0 for original project material;
third-party material retains its own terms. Five original example ROS package
manifests now use Apache-2.0 instead of Proprietary. This changes example source
fingerprints; rebuild before deployment rather than assuming old APK parity.
Wheel builder includes LICENSE/NOTICE in distribution metadata and SDK snapshot,
plus CITATION.cff in the snapshot; package verification checks legal-file presence.
Verification passed: rebuilt wheel, offline installed CLI/SDK legal-file checks
and all 28 tooling regression tests. Updated current product/handoff licensing
status. Dependency license inventory,
complete third-party notices and legal-file inclusion in Android release APKs
remain public-release gates; top-level licensing is not a full dependency audit.
No phone changes, repository publication or APT publishing performed.

## Product requirements and installed host CLI foundation (2026-10-26)

Owner reaffirmed the phone as the primary onboard computer and requested product
critique, requirements and package-based open-source delivery. PRODUCT-REQUIREMENTS.md
now defines the complete robot journey, PC/Android/runner/SDK/MCU responsibilities,
P0/P1/P2 acceptance criteria and ordered milestones. Independent operation,
failure/recovery and safe MCU command expiry outrank more UI features. Android
startup/background restrictions remain capability-specific gates. License choice,
third-party notices and public publishing are pending owner/release decisions.

Added Python distribution ros2-android 0.1.0a1 with installed ros2-android command:
doctor, workspace, project, shell, esp32 and setup. Wheel bundles tracked backend
sources/templates, extracts atomically into a content-keyed cache and preserves
script modes. Existing build backends and workspace/native caches are reused;
no user clone is needed to run the wheel. Doctor checks prerequisite presence
only. Setup operations explicitly invoke documented dependency/app tasks.
Maintainer build helper excludes untracked/local/device files; plain incomplete
wheel builds fail with the helper command. No dependency on Git at user runtime
for snapshot extraction; backend source fetch/build still needs developer tools.

Verification: a local wheel built; offline install into a disposable venv outside
the repository passed version, backend help, external C++ workspace inspection
doctor JSON and private/generated artifact exclusion. The full 28-test tooling
regression suite passed again. This is packaging qualification, not
fresh build/deploy/ROS/device parity. Full wheel cross-build/deploy, prebuilt SDK
artifacts, clean-machine setup, license/notices and distribution publication
remain pending. See HOST-PACKAGE.md for commands and limits. Existing 28 tooling
tests remain the regression suite. Product plan is linked from README, platform
plan and handoff. No phone input, firmware changes or public upload in this step.

## Non-disruptive PC Agent/programming status (2026-10-26)

Added `tools/esp32-via-phone --serial PHONE_ADB_ID status`: reads the protected
debug receiver without Activity launch, UART ownership/reset, tunnel creation or
esptool dependency. The full PC tooling suite passed 28 tests. Automated CLI tests assert only get-state/status ADB calls
and rejection of unexpected arguments before device access. Live read-only
trial returned programming inactive, USB Agent Running at 115200 and RX/TX byte
counts. This is process/transport diagnostics, not a fresh ROS interoperability
trial. Prior LED optical failure remains unresolved. Updated HANDOFF.md to put
current five-tab UI, persistent Dashboard, USB programming, launch support and
remaining priorities ahead of historical milestone descriptions. Checkpoint
`a5a0ce2` contains the earlier implementation; this follow-up is separately
committed. No push requested/performed.

## Development checkpoint (2026-10-26)

Owner authorized commits and continued roadmap work. Fresh setup/build.sh
assembly/unit/lint passed; the scoped PC ROS tooling suite passed all 26 tests.
Checkpoint includes Dashboard pub/sub and persistent joystick safety behavior,
phone-hosted UDP/CP2102 micro-ROS Agent, programming through authorized ADB and
the latched LED command example. Earlier physical evidence remains historical;
no fresh device trial is implied by these build checks. User reports no optical
LED change and has no external LED or multimeter; physical GPIO/LED mapping is
blocked on hardware identification or measurement. Do not probe arbitrary pins.
Next maintenance step: non-disruptive PC programming/Agent status and current
handoff, then address restart/reconnect qualification and mixed workspaces.

## LED toggle follow-up — supersedes the pulse demo (2026-10-26)

Owner changed the requested behavior to on-until-off. Firmware now subscribes to
/esp32/led Bool, drives true/false directly and has no automatic-off timer. Boot
starts off; /esp32/led_state remains commanded GPIO telemetry. The Dashboard demo
is ESP32 LED, type Switch. Physical test migrates the requested old Blink LED
widget/topic in place and preserves other widgets. GPIO2 active-high remains an
unverified board assumption; the supplied marking did not establish PCB wiring.
User reported a blue LED already flashing. An independent 12-second idle check
saw zero LED commands/state changes and 12 heartbeats, so background blink was
not caused by Dashboard LED publishes. Do not claim physical optical success.

Generic Switch requested values are now service-owned, retained across tab
navigation and Dashboard Stop/Start, initialized before listeners attach, with
no unsolicited publication during reconstruction. This is requested input,
not remote-state synchronization; device/service restarts remain independent.
Firmware build, Android assembly/unit/lint and verified remote flash passed.
Esp32LedTest passed an actual Switch ON, >3-second hold, tab navigation and
Activity recreation with no unsolicited publish, then explicit Switch OFF and
fresh MCU state. Independent PC observer measured 4.577 seconds between on/off
state messages. The first toggle test incorrectly asserted the return value of
CompoundButton.performClick; cleanup was also corrected to bind the test via
applicationContext so Activity recreation does not auto-unbind it. The final
trial passed with zero skips/failures. Test APK removed; normal app/USB Agent
restored. Physical LED mapping remains unconfirmed: the user's photo resembles
a 38-pin DevKit-style USB-C variant, but does not establish the LED connection.
Standard Espressif DevKitC documentation identifies a power-on indicator; do not
infer a GPIO2-controlled LED for this clone. A back-side model/schematic or an
external LED is needed to settle the physical output. No image/device data is
committed. See ESP32-USB.md. No commit/push requested.

## ESP32 programming through phone — real upload/recovery passed (2026-10-26)

Added debug-only DUMP-protected ProgrammingControlReceiver, exclusive foreground
Esp32ProgrammingService, shared CP2102 UART/modem driver and PC
`tools/esp32-via-phone`. PC esptool v5 reaches a per-session-token-authenticated
loopback UART socket through authorized ADB forwarding; no LAN listener. Phone
owns 115200 baud and bootloader/normal DTR/RTS reset. Existing Agent is stopped
before UART opens; successful completion restores its previous transport only if
it was running. Failure/cancellation leaves Agent stopped. Detach/connect/idle
and 15-minute deadlines bound sessions. Settings Microcontrollers/notification
provide Stop. Release APK has no exported programming receiver.

Actual checks: assembly/unit/lint passed; all 26 tooling tests passed, including
two binary relay/cancellation cases. Real remote flash-id identified the ESP32
and 4 MB flash while ESP32 was attached only to the phone and PC used wireless
ADB. Initial sustained backup failed: 4096-byte reads exceeded the 100 ms USB
transfer timeout at 115200 baud. Fixed shared UART reads to request endpoint-size
packets. Subsequent 393216-byte application backup completed with integrity check
and matched the reference application before writing. Python venv launcher must
preserve its symlink path (do not resolve it to the base interpreter).

Reflashed the backed-up reference application (343248 bytes, 198747 compressed)
through phone; esptool verified flash hash. The coordinated physical
Esp32ProgrammingTest passed: pre-upload battery→MCU acknowledgement, Agent stops
for programming, sensors/Dashboard remain active, previous USB Agent restores,
and fresh typed acknowledgement plus heartbeat return after completion. A later
LED-enabled firmware upload also passed hash verification. Esp32LedTest then
passed the actual Dashboard button→Bool→MCU on/off→phone typed state path with a
fresh MCU heartbeat required before the click. The independent PC observer saw
the command and a 0.922 s MCU on/off message interval. This is ROS telemetry, not
an optical LED measurement; user visual confirmation remains separate. The test
saved the requested Blink LED Bool/true widget on /esp32/led_flash without
replacing existing Dashboard layout. GPIO2 active-high is the reference default;
/esp32/led_state reports commanded GPIO state. True renews a nonblocking one-second
pulse; false turns it off. LED source is shared by USB and Wi-Fi examples, but
only USB firmware was built/flashed/physically tested here.

Early LED attempts failed on missing MCU feedback: stale test endpoint setup and
an Agent/MCU session that needed a clean restart. A scoped MCU UART boot capture
confirmed normal boot and XRCE ping frames. USB byte counters and protected debug
Agent status/start help diagnose process-running versus real session traffic.
The final LED trial gates on fresh heartbeat rather than the Agent process label.
Reliable automatic recovery after all rapid restarts remains a qualification gap.
Two UsbAgentTransportTest device cases also passed. Final light/dark ThemeUiTest
and USB PTY/packaged-Agent tests passed (four cases, zero skips/failures). Guarded
About captures confirmed plain Built by credit, Sponsors only and no repository
button. Test APK removed; normal main app reopened, MCU rebooted via the protected
programming path, and USB Agent started with bidirectional byte counts. User has
been asked to confirm the saved button's physical LED blink. See ESP32-PROGRAMMING.md and ESP32-USB.md. Backups/logs
and transient device/network targets remain ignored. Other adapters/phones,
interrupted-write recovery and endurance are unqualified. No commit/push asked.

## About-only credit and device selection (2026-10-26)

Removed the Home credit. Settings → About keeps Built by Kishore Saravanan as
plain text and the existing Sponsors button; no author hyperlink or repository
button. Repository URL resources removed. UI guide reflects the final preference.
Shell selection documented in ANDROID-SHELL.md: connect-shell --serial selects
one authorized ADB device; default -d requires a single USB device. LAN discovery
is independent of shell authorization. Distinct absolute sensor topics avoid
mixing readings from phones on a shared domain. Multi-phone runtime has not been
physically qualified; this selection behavior is verified from the tool code.
Assembly/unit/lint passed and setup/run-device.sh installed the final APK.
Physical UI checks for this About change were not rerun because the phone was
locked; no input/capture was attempted. Existing ThemeUiTest assertions now
expect no Home credit, plain About credit and no repository button. Visual and
link-opening checks remain pending; no commit requested.

## Home footer placement (2026-10-25)

Removed Open sensors from the ROS activity card; Sensors remains directly
available in bottom navigation. Developed by Kishore Saravanan is now a fixed
Home-only footer directly above bottom navigation, outside the page ScrollView.
Main content scrolls independently when space is limited. About in Settings is
unchanged. Assembly/unit/lint and both physical ThemeUiTest cases passed with
zero skips/failures. Guarded light/dark portrait captures were visually inspected:
no activity shortcut button, credit directly above navigation, content readable.
Test APK removed and normal Home reopened. Large-text/landscape qualification
of the fixed footer remains pending; no commit requested.

## Remove duplicate Debug Display UI (2026-10-25)

Sensors now lists hardware publishers only (All/Motion/Cameras/Other). Removed
the Display filter, standalone String subscriber form and its Activity handlers.
A restored legacy Display filter selection falls back to All. Home Sensor topics
shows publishing count only; subscribed-topic counts belong to Dashboard.
Use Dashboard → Add widget → Subscribe → std_msgs/msg/String for debug text,
including /phone/display/text MCU acknowledgements. Existing saved Dashboard
layouts and legacy saved topic preferences are preserved; no automatic widget
or subscription is created. Help, first-message/subscriber and ESP32 guides now
point to Dashboard. The internal legacy service/native subscriber API remains
for existing interoperability tests; it is no longer exposed as a product screen.

Verification: setup/build.sh assembly/unit/lint passed. Three physical UI cases
passed with zero skips/failures: light/dark theme regression and the replacement
Sensors→Dashboard navigation test asserting Display controls are absent. Guarded
app-only sensor-list capture was visually inspected: All/Motion/Cameras/Other,
hardware rows and five-tab navigation. Instrumentation APK removed; normal app
reopened. Existing subscriber type coverage is unchanged; no new ROS message
support or repeated MCU traffic qualification is claimed for this UI removal.
Large-text/other-device checks remain pending. No commit requested.

## Persistent Dashboard and complete Home activity summary (2026-10-25)

Dashboard navigation and Activity pause call stopDashboardMotion rather than
stopControls. All joystick command slots receive zero; view release/cancel,
window focus loss and detachment still send zero. The service's 300 ms renewal
lease remains. Endpoints and readings persist until explicit Stop dashboard,
Stop all or service teardown/error. No held joystick command resumes on return.
Buttons/switches/sliders continue publishing only when operated. Robot-side
command expiry is still required for process/network failures.

Home ROS activity now separates Sensor topics and Dashboard topics (publisher/
subscriber counts), project running/installed totals and active project node
counts, plus micro-ROS Agent status. Counts describe configured local endpoints,
not services/actions or confirmed remote receivers. Home binds/polls signed
project status services while visible; this does not start workspace execution.
Unknown project states are labeled awaiting status; main runtime status remains
separate from project runtime status. Dashboard cards remain service-owned.

Verification: assembly/unit tests/lint passed. All three physical ControlsTest
cases passed without skips/failures, including joystick release/focus loss,
persistent navigation/pause, fresh native battery-topic reception while away and
explicit Stop clearing readings. Independent PC ROS observer received Bool,
Unicode String, Float64 and Twist plus two motion→zero transitions (lease expiry
and navigation). Both physical light/dark ThemeUiTest cases also passed with explicit Home group
assertions. Guarded app-only portrait captures were visually inspected: topic
labels, one running installed project with two nodes, Agent state and author
credit were readable with all five navigation tabs. No system theme changed.
The instrumentation APK was removed and normal Home reopened. See
[verification](verification/2026-10-25-dashboard-persistence.md). Large-text,
second-device and endurance checks remain pending; no commit requested.

## Home connection overview (2026-10-25)

Home no longer pins the battery first-message demo. Battery remains in Sensors
with its topic/rate and Publish/Stop controls. Home shows the saved main-app ROS
domain/namespace, automatic discovery or explicit peer, optional selected phone
address, current IPv4 interfaces (refresh every five seconds), active phone sensor
publisher/debug subscriber counts under ROS activity and shortcuts to
Settings/Sensors. The heading describes endpoint activity rather than hardware. Technical
session details remain collapsed. Footer: Developed by Kishore Saravanan. About
and Sponsors in Settings remain unchanged. Runtime status describes local state,
not verified reachability; installed project runners retain independent profiles.

Verification: setup/build.sh assembly/unit tests/lint passed; both existing physical
ThemeUiTest cases passed without skips/failures, including new Home content and
absence-of-battery/form assertions, plus sensor-detail/Settings regressions. Guarded
app-only light/dark portrait captures were visually inspected: connection details,
sensor summary, collapsed details, credit and all five tabs fit. Previews change
only app configuration, not device theme. Test APK removed and normal main app
reopened. Broader accessibility, large-text and second-device checks remain
pending for this layout. Device captures stay ignored. No commit requested.

## TurtleBot 4 phone-dashboard reference demo (2026-10-25)

See TURTLEBOT4-DASHBOARD.md. tools/turtlebot4-demo scopes domain/NIC/Cyclone
participant limit and Gazebo partition/renderer settings; start uses the installed
official standard robot/Create 3 stack in a simple local world under /tb4_demo.
Its telemetry action runs a small odometry→std_msgs/Float64 speed extractor;
undock calls the demo's own action. Joystick topic is
/tb4_demo/cmd_vel_unstamped (Twist); /tb4_demo/cmd_vel requires TwistStamped.
Current app subscriber cannot directly display nav_msgs/Odometry.

Launch findings: the default auto-participant range exhausted on the full stack;
use MaxAutoParticipantIndex 120 in the demo processes. Never pass a world file
path through the upstream world-name argument, which also forms ROS bridge
names. Keep path/name separate. The official robot already embeds Sensors and
Contact plugins. This demo avoids an additional world Sensors plugin; Contact
is still declared in both the world and robot description. The maze package's
launch explicitly removes the embedded plugins when its world owns them.
An additional Sensors system was present in the failed rendering attempts. Ogre 2 and a
software-EGL fallback also failed; current GUI uses Ogre classic and one embedded
sensor system. No system graphics/packages or maze source were modified.

Actual PC checks: scoped cleanup of failed launch trees; GUI camera RPC replied;
clock/odom live; undock succeeded; 0.1 m/s bounded drive moved 0.122 m and stopped
at measured 0.0 m/s. Odom-derived Float64 speed reached a PC subscriber.
The subsequent phone joystick→simulation→phone feedback trial passed: the user
configured Drive (Twist, maximum 0.15 m/s and 0.5 rad/s) and Speed (Float64),
confirmed Live, then confirmed robot movement, stopping on release and changing
speed on the phone. An independent subscriber-only observer recorded nonzero
phone commands, 0.238 m odometry displacement and final speed 0.0 m/s. The ROS
graph identified the phone publisher and speed subscriber. A LiDAR message also
arrived; complete sensor/camera coverage, endurance and other systems remain
separate qualification. Code/docs remain uncommitted until requested.

## Dashboard tab and standard subscribers (2026-10-25)

Current navigation is Home / Sensors / Dashboard / Projects / Settings. Dashboard
replaces the Home shortcut/subscreen. The owner explicitly requested this fifth
tab and a minimal approach; no chart/gauge/page-designer features were added.
Add widget now chooses Publish or Subscribe. One Start dashboard/Stop dashboard
action connects its endpoints, with no automatic publisher commands. Existing
control_dashboard_v1 layouts remain compatible (optional messageType defaults).

Subscribed value is a single read-only card for String, Bool, Int32, Int64,
Float32 and Float64. It shows Waiting before receipt, Live after receipt and
Stale after five seconds without a new sample. Bool uses On/Off text plus a
symbol. Old values are muted and labeled; Stop clears readings. Native typed
subscription storage is separate from the existing debug display. UTF-8 goes
through byte arrays and 64-bit integers stay exact strings. Best-effort/volatile
depth-1 subscriptions, bounded polling/text and main-thread receive timestamps
keep queues/state small. An epoch rejects readings queued before stop/restart.

RobotSessionService owns the mixed endpoints. Leaving Dashboard or pausing now
requests zero Twist while preserving publishers/subscribers and readings; explicit
Stop dashboard or Stop all tears them down. Sensor/display and MCU Agent ownership
remain independent. Receiving robots still need command expiry.

Assembly/unit/lint and all 24 tooling tests passed. A four-test physical run
passed (zero skips/failures): publisher/lifecycle/editor regression plus PC→phone
samples for all six types, stale detection and recovery, and the editor Subscribe
flow/Waiting state. Exact Int64/Unicode and independent debug-display traffic were
verified. A final real-wire/UI trial also passed Waiting/Live/Stale/recovery
card assertions and clearing on Stop; its guarded app-content image was visually
inspected. Portrait nav label spacing was refined to keep Dashboard on one line; the final
editor test passed and a guarded screenshot confirmed all five labels fit.
Instrumentation APK removed, original layout restored and normal Dashboard left
visible.
Actual checks are recorded in
[verification](verification/2026-10-25-dashboard-subscribers.md). Read
[Dashboard guide](CONTROLS.md) before operating. Custom message schemas, graphs,
gauges, layout import/export, full accessibility/device coverage and motor
qualification remain pending. Changes remain uncommitted until requested.

## Configurable controls dashboard (2026-10-25)

Home → Open dashboard now offers saved Bool/String buttons, Bool switches,
Float64 sliders and a Twist joystick. Add/edit/reorder/delete controls, choose
absolute topics and values/limits, then explicitly Enable controls. Main tabs
remain Home/Sensors/Projects/Settings. Layout lives in app-private
control_dashboard_v1 preferences; it starts empty and publishes nothing on open.
Relevant fields only are visible in the editor. ROS-Mobile's public widget
workflow inspired the design; its installed app, code and artwork were untouched.

RobotSessionService owns reliable/volatile depth-1 control publishers alongside
sensors. A bounded latest-command slot per widget feeds a 20 Hz worker; the
joystick renews at 10 Hz with a 300 ms worker lease. Release/cancel/focus loss
requests zero. The subsequent persistence milestone keeps endpoints across
navigation/pause, requesting zero without removing publishers. Battery and the MCU Agent remain independent.
A receiving robot still needs command expiry: app/network/process failure can
prevent a final zero. No automatic re-enable after return. At most 32 unique
widget topics; schemas/finite bounds/speed limits validated. JNI publishes proper
UTF-8 payload bytes rather than JNI modified UTF-8 strings.

Assembly/unit/lint and 24 existing tooling tests passed. Physical editor
validation/persistence, joystick release/focus loss and service pause with battery
continuation were checked. Independent PC subscribers received Bool, UTF-8
String, Float64 and Twist, including separate zero transitions after lease expiry
and Activity pause. See [guide](CONTROLS.md) and
[verification](verification/2026-10-25-controls.md). Physical tests use reserved
control_test topics; no actuator commands or ESP32 firmware changes were made.

Sources: ControlWidget.kt (schema), ControlDashboard.kt (UI/persistence/joystick),
RobotSessionService.kt (publisher/lease lifetime), NativeRuntime/native ros_runtime
(control serialization), ControlsTest.kt and tools/pc-controls-probe.py (evidence).
Three physical controls tests passed, plus a final editor/recreation check after
compacting the controls action row. A guarded dark/landscape screenshot was
visually inspected; no global theme/orientation settings were changed. Home keeps
the battery first and adds a compact Open dashboard action afterward.
Custom ROSIDL schemas, press/release buttons, telemetry widgets/multiple named
layouts, import/export, cross-device accessibility and motor qualification remain
pending. Changes remain uncommitted until requested.

## Reference phone-to-ESP32 USB serial milestone (2026-10-25)

Implemented Android USB host CP2102 support and a raw app-owned PTY bridge to the
packaged upstream serial XRCE-DDS Agent. Settings → Microcontrollers has Start USB
agent and the shared Stop action; only one transport runs at a time. Permission
is requested through Android, no root/serial device-node access. The service owns
both bulk pumps, USB connection and Agent; detach releases them and asks for an
explicit restart. Sensor/workspace ownership remains separate. Scope is a single
10c4:ea60 CP2102 interface, not generic USB/MCU support.

`setup/esp32-usb.sh build|upload` selects a separate serial cache/profile with
115200 baud, 8N1 and ROS domain 0 by default; ROS2_ANDROID_ESP32_DOMAIN overrides it
(0–101). It shares the source/pinned SDK with Wi-Fi, but uses no credentials and
prints no debug text on the XRCE UART. Upload replaces Wi-Fi firmware; existing
Wi-Fi build/upload remains available. Reference board now has serial firmware.
Original flash backup remains private and ignored.

Main assembly/unit/lint passed; all 24 existing host tooling tests passed. Two
physical PTY tests passed (binary integrity both directions and packaged serial
Agent startup). USB firmware build/upload and hash verification passed. User
connected the MCU directly to the phone, which enumerated its CP2102 endpoints
and requested permission. Real battery → MCU → phone acknowledgement AND fresh
acknowledgements after serial Agent restart passed one physical instrumentation
trial (0 skips/failures). No Linux Agent; wireless ADB was development-only.
See [USB guide](ESP32-USB.md) and
[verification](verification/2026-10-25-esp32-usb.md).

Normal app startup was restored with the instrumentation APK removed. User then
physically unplugged/replugged the board and selected Start USB agent; the app
reported Running USB and a fresh PC ROS subscriber received a new acknowledgement.
No reflash/PC Agent was needed. Battery publishing, USB Agent and the normal phone
Display subscriber remain active; the project screen visibly showed fresh MCU
acknowledgements.
Configured capacity is 11,520 bytes/s per direction before XRCE overhead; no
throughput/latency comparison with Wi-Fi is claimed. Endurance, fully offline
phone DDS discovery, power limits, broader drivers and the graph-manager wrapper
remain pending. Code remains uncommitted until requested. Keep transient addresses,
identifiers, firmware backups and device logs out of Git.

## Phone-hosted ESP32 Wi-Fi micro-ROS data path (2026-10-25)

Reference ESP32 firmware now uses micro-ROS Jazzy/rclc via PlatformIO and joins
the configured Wi-Fi. It publishes /esp32/heartbeat (Int32), subscribes to the
phone's /phone/battery_percent (Float64) and returns an acknowledgement String to
/phone/display/text. The phone hosts the UDP XRCE-DDS Agent on port 8888 in a
separate packaged native process, owned by MicroRosAgentService. Settings →
Microcontrollers provides Start/Stop and collapsed Agent logs. Its foreground
service/wake/multicast locks are independent of sensors and workspace runners.
No Linux Agent is used. See ESP32-WIFI.md for complete reproducible commands.

The backend is upstream Micro-XRCE-DDS-Agent v2.4.3/Fast DDS, not the ROS-specific
micro_ros_agent graph-manager wrapper. Actual typed phone→ESP32→phone traffic
and ESP32 reconnect after stopping/restarting the phone Agent passed physical
instrumentation tests, including a final two-test run with the restart receive
baseline captured after draining old messages. See
[verification](verification/2026-10-25-esp32-wifi-agent.md). Node graph metadata/ros2 node list for the MCU, one-hour endurance and other devices remain pending. USB serial support was subsequently
implemented and verified for the reference CP2102 board; see the USB milestone above. Do
not equate this UDP proof with completed v0.2 USB or arbitrary MCU support.

Setup scripts build the Agent and stage its ARM64 ELF closure separately under
native-deps/agent-apk-libs, with vendor licenses under agent-assets. Agent libs
are optional build artifacts; a clean sensor-only build needs this setup step
before the Agent can start. Normal-use services were restored after tests; PC subscribers received heartbeat
and battery acknowledgement without a PC Agent. The instrumentation APK was
removed. APK with the Agent measured 8.66 MiB. Firmware uses
about 20.5% RAM and 61.9% of the default application flash partition.

Build findings: propagate Android ABI/API and shared libc++ to every dependency;
force target TinyXML2 and prevent host ROS/CMake packages from satisfying cross
builds. Foonathan caches generated container sizes independently of CMake's ABI
cache: stale ARM32 sizes caused runtime allocator exceptions even in an ARM64
ELF. Regenerate its node-size header and rebuild consumers on ABI migration.
The helper records an ABI stamp. APK-native SONAME/NEEDED names are normalized,
ELFs verified and stripped. Temporary throw tracing was removed. Agent log text
strips ANSI/control bytes so logs and test result XML remain readable.

Firmware builds use a private cache path without spaces because the upstream
PlatformIO micro-ROS builder assembles unquoted shell paths. Its isolated
PlatformIO core points at the task venv; inherited Linux ROS PATH/prefix variables
are removed to avoid selecting host C++ typesupport. NetworkManager credentials
are generated locally without printing, stay outside Git, and are embedded only
in private firmware artifacts. The original board flash backup is ignored under
device-logs/esp32-private; do not commit it, device IDs, addresses or passwords.

## Workspace runners hidden from the app drawer (2026-10-25)

Removed MAIN/LAUNCHER from the shared runner manifest, retaining OPEN_PROJECT
metadata/action and the explicit fallback Activity. Applies to C++, Python and
ordinary workspace runner builds. Only the main app advertises a launcher icon;
runners remain installed apps in Android Settings. Current workspace rebuilt and
deployed; Android package queries returned no launcher Activity for that runner,
one OPEN_PROJECT Activity, and the main app's launcher Activity. Explicit PC start
still reached Running with both workspace nodes. All three runner build/lint
checks passed. Installed-project discovery/recreation device test passed with
the hidden runner; the instrumentation APK was removed afterward. No personal
launcher screen or app inventory was inspected.

This changes visibility, not packaging. Main app plus one runner APK per workspace
remains the model. A single combined APK could package workspaces with the main
app, coupling native code deployment to main-app updates. A shared runtime with
independent workspace delivery remains a separate design/qualification project.

## Original project robot logo (2026-10-25)

The owner requested an original center figure. Shared launcher/Home/About vectors
now use an eight-dot navy grid around a teal robot with rectangular visor, square
eyes, side ports and a single diamond antenna. It replaces the Android robot
head described in the previous milestone. Removed that artwork and its Artwork
credits disclosure; Kishore Saravanan credit and sponsor link remain unchanged.
See BRANDING.md for the current asset contract. Main build/unit/lint and current
workspace runner build/lint passed. The installed-icon device test passed for
both main app and current workspace runner; 24 host tooling tests passed. The
instrumentation APK was removed after testing. Earlier verification records
remain historical.

## Unified workspace controls and custom icon (2026-10-25)

Main Projects now binds compatible runner control services: live state/nodes,
Start/Stop, saved connection settings, collapsed Logs and fallback Open runner.
The runtime remains in its own APK/process; navigation/recreation never owns
worker lifetime. Messenger IPC requires a signature permission plus mutual signing
checks and main-package sender-UID validation. It exports only status, start,
stop and validated-profile save. See PROJECTS.md for signing, scope and limits.
Workspace stdout/stderr and lifecycle events feed a bounded process-local log;
prototype C++/Python backends currently expose lifecycle events only. Independent
ADB-shell nodes are outside group status/control. Shared runtime packaging,
per-node supervision and arbitrary launch editing remain pending.

Branding now uses eight navy dots with a green Android robot head in the center,
superseding the official unmodified mark below. Main/runners share native vectors;
Home visibly includes it. Main assembly/unit/lint, all runner assembly/lint and
four device tests passed: installed icons, profile validation, output Logs and
main-app controls/recreation. An unprivileged control-service request was denied.
A PC probe passed real custom-message input, computation, nodes and parameter
service after a main-UI start and again with the final rebuilt workspace. Battery
publishing was restored. Instrumentation APK was removed after testing.
See BRANDING.md and
[verification](verification/2026-10-25-unified-project-controls.md).

## ROS icon and obsolete test-app cleanup (2026-10-25)

Main app and all runner variants now share adaptive launcher resources in
android/branding/res, using the official ROS nine-dot vector paths. About and
notification marks use the same artwork. See BRANDING.md for source/provenance.
Local circle/rounded-square previews were inspected; no personal launcher screen
was captured. Main app and current workspace runner were rebuilt and installed.
Main build/unit/lint and all runner assembly/lint checks passed. Workspace PC
probe passed custom input, computation, both nodes and parameter service; native
shell topic listing still worked. Main battery publishing was restored and a PC
subscriber received a sample after reinstall.

With owner authorization, removed only the three obsolete legacy demo/workspace/
Python runners and the legacy/current main-app instrumentation test APKs. Only
the current main app and android_test_ws runner remain in the project namespaces.
No personal apps or shared storage were inspected or changed.

Current deployment uses one main sensor APK plus one runner APK per workspace.
All workspace packages/nodes share that runner; subsequent builds replace its
existing install. A universal multi-workspace runtime is a future packaging
option, not an Android requirement. See PROJECTS.md.

## Standard core CLI bundle and native launch (2026-10-25)

Workspace builds now include the core ROS CLI by default; --with-shell remains
an explicit alias and --without-shell opts into the small C++-only runner.
Python SDK prerequisite now includes composition_interfaces. Shell and minimal
workspace cross-builds use separate android-shell/ and android/ caches, avoiding
Python-generated library leakage when switching modes. Tools/connect-shell still
only establishes the Android session; commands execute upstream ROS code there.

The single version manifest native/ros-shell-packages.lock.json stages topic,
param, service, action, interface, lifecycle, component, multicast, plugin, node,
pkg, run and launch, along with launch_ros/Python/XML/YAML frontends, all pure
Python dependencies and package resources. This workspace's Android-generated
Python message bindings are included, not just C++ typesupport. Never copy host
ELF modules. Target NumPy/BLAS stays pinned from Android wheel resolution.
check-shell-cli checks every bundled command/verb help for missing imports.

Native topic list and custom-message echo, topic pub received on PC, parameter
get/set/restore, service call and custom interface show passed. Native upstream
LaunchService started the example talker/processor; PC pipeline and parameter
probe passed. Ctrl+C cleanly ended both launched children. Python subprocess
launch plus XML/YAML frontend trials passed. CLI sys.executable now points to
python3, so Python child launches don't accidentally re-enter ros2 argument
parsing. Source-package creation also generated a valid private CMake package.

This remains a core command bundle: bag/rosbag2 storage, doctor platform/network
checks, DDS security and daemon monitoring need separate Android backends.
Components/actions/lifecycle operations require compatible deployed runtimes;
empty list calls alone don't qualify goal execution or transitions. Full Linux
ROS parity, arbitrary launch packaging and mixed Python workspaces aren't proven.
The PC static adapter still generates the separate app group plan during build.

Standard full APK measured 34.09 MiB. Minimal mode also built/linted successfully.
Main sensor app was not reinstalled in this pass. See ANDROID-SHELL.md and the
[full CLI verification](verification/2026-10-25-core-cli-bundle.md) for final
checks and remaining boundaries. The final dependency/import sweep passed all
63 command/verb checks; 24 host tooling tests passed.

## Namespace migration and native node CLI (2026-10-25)

Current IDs supersede earlier records: main app dev.ros2android.android; project
runners dev.ros2android.android.runner.<id>; runner Kotlin/JNI classes use
`dev.ros2android.runner`. Code, package source paths,
Gradle namespaces, manifests, metadata/actions, examples, JNI bindings, installer
allowlists, CLI targets and maintained guides all use the new namespace.
Legacy identifiers remain only in migration rules and historical verification.

Main app and example workspace installed under new IDs. Preserved old project
preferences, restored them into the new apps, backed up old project APKs and
preferences under ignored device-logs/, then removed only the superseded main
and example-workspace installs. Other legacy project APKs were left untouched;
rebuild/redeploy them to adopt new IDs. Old shell sessions must exit/reconnect.

ros2node 0.32.13 now joins the upstream CLI plugin bundle. Native `ros2 node list`
and info work via direct discovery (ROS2CLI_NO_DAEMON=1). Checked compatibility
patches defer daemon-only psutil imports in two upstream modules and let the
strategy honor the opt-out; ros2node command implementations stay upstream.
Android NumPy 1.26.2/BLAS comes from the Python runner's pinned wheel resolution,
never host NumPy ELF. AAPT's default underscore-directory filter dropped _utils;
workspace assets now retain these directories, and assembly asserts required
Python/native CLI entries are present. Shell APK is now 32.23 MiB (initial
run/pkg-only shell was 21.65 MiB).

Verified actual native node list discovers both example nodes plus the new
sensor app; node info reports talker/processor interfaces. PC functional probe
passed custom input, correct computation, both nodes and parameter service.
New main app battery returned data:100.0 to the PC. Seven physical tests passed
(theme, accessibility, edited IMU endpoint, battery lifecycle, project browser);
main assembly/unit/lint and all runner assembly/lint passed; 24 tooling tests
passed. Camera permission/camera publishing were not rerun after new-UID install.
The normal workspace and battery publisher remain active on domain 0.
See [current shell guide](ANDROID-SHELL.md) and
[verification](verification/2026-10-25-namespace-node-cli.md).

## Native ROS development shell (2026-10-25)

Optional `ros2android-workspace build --with-shell` packages standalone Android
CPython and upstream Jazzy ros2cli/ros2run/ros2pkg. `tools/connect-shell` connects
to the installed debug runner via authorized ADB run-as; inside, use ordinary
`ros2 pkg executables` and `ros2 run` syntax. The connector only establishes the
session, not a PC ROS proxy. Python/executables run on Android. Shell setup is
in setup/prepare-ros-shell.py; native entrypoint in native/shell/python_main.c;
private extraction/prefix links in workspaceRunner ShellEnvironment.kt. Sources
and stdlib are version/checksum checked; provenance is in bundle assets.

Verified native package discovery, typed PC message receipt, independent talker
and processor in separate PTYs, selective Ctrl+C stopping/restart and functional
pipeline/parameters. Fixed an early Android Python-extension teardown crash by
not finalizing the disposable CLI interpreter after its upstream command returns;
four subsequent Ctrl+C cycles passed. After final deployment, restored normal
workspace group on domain 0 and its functional probe passed. Initial run/pkg-only APK was 21.65 MiB
vs ~8.2 MiB without shell; sensor app stays 4.26 MiB. Main app not reinstalled.

This is a debug development shell: shell nodes aren't managed/listed by group
controls, and connected foreground terminal trials are qualified. Wireless ADB,
disconnect/background recovery, long-duration execution, other phones and full
on-phone ros2 launch remain pending. CLI bundle enables run/package inspection;
other topic/parameter plugins and daemon are not enabled; node inspection was added in the milestone above. No terminal UI,
SSH server, arbitrary pip or complete Linux workspace compatibility is claimed.
Build without --with-shell omits this optional feature. Read
[Android shell](ANDROID-SHELL.md) and
[physical evidence](verification/2026-10-25-native-ros-shell.md).

## Runner connection settings synchronization (2026-10-25)

Fixed PC-started runner settings differing from the phone's saved form. Shared
ProjectService now records each validated, accepted start profile (domain,
namespace, peer/local IPv4, ROS arguments) with a revision, persists it in the
runner's own preferences, and exposes it as status.config. MainActivity applies
new revisions once and before saving onPause, preserving edits between starts.
Fields remain locked while busy/running; values remain editable after stopping.
The main sensor app retains its independent profile. Launch override persistence
is not added; the existing packaged launch behavior remains.

Workspace runner rebuilt/linted, deployed and PC-started with domain 42 then 0.
Status matched both starts. Own foreground/unlocked UI inspection verified
running domain 0 locked, stopped domain 0 retained/editable. Phone Start project
then used domain 0; real Wi-Fi probe verified custom input, correct computation,
both nodes and parameter service (3 matching samples). Left this workspace
running on domain 0. Shared C++/Python APK assembly/lint passed; they were not
physically redeployed. All UI input used the allowed foreground runner with
keyguard absent. No device data or logs are added to Git.

## Workspace guide clarification (2026-10-25)

PC_IP/PHONE_IP in commands must be replaced, using addresses on the same link.
A stopped status with empty nodes confirms no processes; check start output,
not just deploy/status. After the reported stopped state, retrying with actual
Wi-Fi addresses started both example nodes on domain 42. No root cause of the
prior stop was established from status alone. The guide now states replacement
steps, expected running output, and stopping on command errors explicitly.

## Script folder layout (2026-10-25)

Build/install/dependency scripts moved from tools/ to top-level setup/. Use
`./setup/build.sh` and `./setup/run-device.sh`. Native/Python SDK preparation,
source fetching, workspace/interface build helpers, staging and udev setup are
also in setup/. Tools retains viewers, probes, middleware helpers, launch
adapters and ros2android project command entry points; these delegate build and
installation to setup/. Maintained docs, CMake diagnostics and CLI references
were updated. Historical verification records retain original command paths.
See setup/README.md and tools/README.md for the split. No compatibility wrappers
are left in tools/, so new users see one location for each setup script.

Verification: relocated setup/build.sh passed assembly/unit/lint; all 24 tooling
tests passed; relocated shell scripts passed Bash syntax checks; project and
workspace CLI help and camera-viewer help passed. Installer mocked checks verify
valid APK identity reaches install/start and invalid identity never reaches ADB.
No physical reinstall was performed for this folder-only change.

## Current UI decision (supersedes earlier screen descriptions)

0.1.1-dev3 replaces the text-heavy prototype with familiar Home/Sensors/Projects/
Settings navigation and icons. Home is a small battery/session panel. Sensors
are compact list rows opening topic/rate/detail controls; Publish/Stop is fixed
below the form. Advanced data, ROS types, counters, help and diagnostics are
collapsed. About is a normal visible footer at the bottom of Settings; the user
explicitly rejected a dropdown and a separate About page. Help stays separate.

Use values/values-night ROS-inspired palettes (navy #22314E in light mode), following
device appearance. No forced app mode or global appearance setting changes. All
runner backends share the palette and concise collapsed-control pattern. The
app icon uses navy/white. See [current design](UI-UX.md); older milestone screen
names/layout descriptions below are historical, not the current UI contract.

Latest verification: all 10 physical UI tests passed; main build/unit/lint and
all runner build/lint checks passed. Light/dark About footer snapshots reviewed.
New workspace runner passed the real PC interoperability probe. See
[verification](verification/2026-10-25-minimal-ui.md) for boundaries and commands.

## One-command RViz camera viewer (2026-10-25)

`./tools/view-camera` sources the PC ROS setup and runs pc-camera-rviz.py.
Optional --topic selects a CompressedImage topic; --domain matches the app.
It starts the parameter-based Jazzy decoder with explicit compressed-topic
remapping and Best Effort input, plus RViz with a temporary JSON/YAML config.
A private per-process raw topic prevents cross-session collisions. Closing RViz,
Ctrl+C or termination cleans up only its child process groups. No saved RViz
config, phone settings or image files are modified. Runtime graph confirmed
RViz subscribed to its private raw Image topic with Best Effort and real frames
arrived. This is PC convenience tooling, not raw image publication on the phone.

## RViz compressed camera finding (2026-10-25)

The installed RViz Image display has no Transport Hint selector and subscribes
to raw Image. Topic OK can appear with zero publishers. Use the PC Jazzy
image_transport republisher with in_transport/out_transport parameters as shown
in V0.1.md, and Best Effort in RViz. Directing raw Image to a CompressedImage
topic cannot work. Phone compressed reception was measured at about 30 FPS;
raw republisher reception is checked separately. No phone changes required.

## Product and decisions

Installer portability fix (2026-10-25): `setup/run-device.sh` uses standard
`grep -F` for APK package verification; ripgrep is not required to follow the
install guide. Failed identity checks now report a clear error before ADB.
Verified Bash syntax and mocked valid/invalid APK paths with a PATH containing
no `rg`: valid identity reaches install/start; mismatched identity never calls
ADB. No physical reinstall was needed for this shell-only change.

Replace a Raspberry Pi-class robot computer with a smartphone's processor and
sensors. Keep native Android; no Ubuntu installation, root or required Termux.
Edit/build/deploy from the PC over USB debugging or Wi-Fi, then execute ROS nodes
on the phone. A development PC must eventually be removable from the robot.

- App name: ROS2 Android. Credit: Built by Kishore Saravanan. About links to
  https://github.com/sponsors/kishore-saravanan with "Support development".
- Kotlin provides the Android UI, permissions and foreground-service lifecycle.
- Android ARM64 native ROS 2 Jazzy uses Cyclone DDS. Native rclcpp and embedded
  CPython/rclpy are real phone runtimes, not a PC ROS bridge.
- v0.1 means PC ↔ phone; v0.2 means phone ↔ MCU. Embedded micro-ROS Agent is
  still pending. Do not label v0.2 complete from phone/PC tests.
- Deploy project APKs before attempting dynamic binary loading. Project runners
  are separate app processes from the main hardware/sensor app.
- General Raspberry Pi binaries, arbitrary Linux packages/drivers and arbitrary
  pip native extensions do not automatically run on Android.
- Work on `develop`. Git identity is configured; preserve the existing identity.
  Do not push unless requested. Verify status before committing unrelated work.
- Never inspect personal phone apps/data, shared storage, notifications, photos,
  contacts or accounts. Only the main app, generated runners, own artifacts,
  hardware diagnostics and required development settings are authorized.
- Before UI input/capture, verify an allowed project app is foreground and the
  phone is unlocked. Use Activity FLAG_KEEP_SCREEN_ON; do not bypass Realme's
  restriction on the global stay-awake setting or change security settings.

## Implemented and physically verified

| Area | Evidence and scope |
| --- | --- |
| Phone/PC ROS topics | Native Jazzy/Cyclone DDS, real IMU and PC-to-phone text over Wi-Fi and USB tethering IP; [v0.1 guide](V0.1.md) |
| Sensor UI | Capability detection; front/back camera, motion and other available sensors; editable topics/rates, publisher selection, debug-text subscriber and type-matched discovery |
| Camera pipeline | Continuous Camera2 YUV capture, bounded JPEG publishing, timestamps/metrics; 29.677 received FPS in a 10-minute USB background run and 29.733 FPS in a 30-second Wi-Fi run; [evidence](verification/2026-10-25-camera-service.md) |
| Runtime lifecycle | Main app service owns hardware/runtime across Activity navigation; notification Stop and recreation verified; 1-hour/second-device qualification pending |
| About | Author credit and GitHub Sponsors button installed and visually checked |
| C++ ABI 1 | One PC-built rclcpp node executes on Android; status, exact command reply, IMU computation, custom messages, parameters and remaps; [guide](CPP-PROJECTS.md), [evidence](verification/2026-10-25-cpp-runner.md) |
| C++ ABI 2 | 1–16 compiled components share an executor/process; per-node names/namespaces/scalar params/remaps, partial-start rollback, group failure and explicit restart; [guide](WORKSPACE-LAUNCH.md), [evidence](verification/2026-10-25-workspace-launch.md) |
| Caught C++ exceptions | Failure monitoring, teardown, foreground-service removal, explicit recovery; [evidence](verification/2026-10-25-runner-supervision.md) |
| Python feasibility | Embedded CPython 3.12.15, rclpy 7.1.12; typed topics, real IMU norm calculation, custom messages, parameter changes, Python→phone C++→Python round trip, caught exception cleanup/restart; [guide](PYTHON-PROJECTS.md), [evidence](verification/2026-10-25-python-runner.md) |
| Ordinary colcon C++ workspace | Three standard packages and two standalone main() executables built on Linux/Android; custom-message/computation/parameter parity and child-exit recovery passed; [guide](COLCON-WORKSPACES.md), [evidence](verification/2026-10-25-colcon-workspace.md) |

## Completed milestone: ROS launch-file adapter

The initial adapter passed 19 tests and physical two-node/conditional-start
checks on the phone. Standard supported `.launch.py` descriptions
are evaluated on the PC and compiled into the existing Android component plan.
The resolved default is packaged in the APK; the phone does not need the PC to
re-evaluate it when Start is tapped. This is a build-time adapter, not a complete
on-phone ROS LaunchService. Registered C++ components are the first target.
Supported: registered C++ Node actions, launch arguments, basic substitutions,
conditions, Python includes and scoped namespaces. Build-time argument defaults
were verified from the packaged APK; a runtime condition launched only the
producer. Unsupported process actions, timers, parameter files and unregistered
nodes fail explicitly. See [ROS launch guide](ROS-LAUNCH.md) and
[physical evidence](verification/2026-10-25-ros-launch.md).

## Completed milestone: ordinary colcon workspaces

The user clarified the intended workflow: take a normal Linux workspace, build
all its compatible packages for Android, deploy it and preserve its ROS behavior.
Earlier component factories/Python entry-module proofs did not establish this.

The first end-to-end example now passes on Linux and Android:
`examples/android_test_ws` contains ws_interfaces, ws_nodes and ws_bringup. All
three ament_cmake packages cross-build with colcon. The same C++ main() functions
run on the phone as separate native processes. The same probe verified custom
messages, numerical results, graph names and parameter services on both targets.
Child abort detection, controller survival, group cleanup and restart passed.
See [workspace guide](COLCON-WORKSPACES.md) and
[evidence](verification/2026-10-25-colcon-workspace.md).

This first builder supports native ament_cmake workspaces, not arbitrary Linux
or mixed Python workspaces. It automatically discovers installed executables and
generates the Android registry; user packages need no Android factory/manifest.
The new workspace supervisor heartbeat is elapsed supervisor time, not a user
callback progress measure. Its child stdout/stderr is pumped into runner logs.

Current next action: prioritize clean UI/UX, guided setup and community-alpha
usability as described in [handoff](HANDOFF.md). Then add ament_python workspace
entry points/mixed launch and broader dependency/resource compatibility; follow
with full launch runtime semantics, restart policies and physical PC removal. General Linux equivalence
remains a package/API qualification goal, not an implemented blanket guarantee.
ROS launch/knowledge-base checkpoint is bf4bc86. The ordinary-workspace milestone
and community handoff are checkpointed in the following commit; use `git log`
and `git status` to identify the current revision. See [handoff](HANDOFF.md).

Community direction: make the project approachable for robotics contributors,
with guided setup, a clean operator UI, reproducible examples and an explicit
compatibility matrix. Prioritize a documented community alpha with clean UI/UX as a release gate.
The owner explicitly deferred Google Play: no current store preparation or
submission work. Use APKs and PC deployment for the community-alpha path. License selection and public repository/release publication
remain owner decisions; neither has been performed by this checkpoint.

## Current milestone: community operator UI

The initial 0.1.1-dev2 pass adds Overview/Sensors/Connect/Help navigation,
expandable sensor settings, visible topic/rate summaries, a persistent Stop action,
setup/troubleshooting guidance and a local camera thumbnail. The preview reuses
JPEG data, decodes at most two thumbnails per second off the UI thread, and turns
off when its screen is not resumed. The service still owns ROS and hardware.
Drafts and screen/filter/expanded settings survive navigation/recreation.
Battery percentage is now first in the sensor catalog and the default first-message
example: /phone/battery_percent, std_msgs/msg/Float64, 0–100 percent, 1 Hz default
(0.2–1 Hz supported). BatteryManager supplies the charge level without a runtime
permission; a main-thread timer publishes until stopped, including across Activity
navigation. Unknown readings fail explicitly. IMU topics are unchanged. Battery
uses native publisher kind 9, sharing Float64 serialization with proximity kind 5.
An additional physical BatteryUiTest passed publication/navigation/recreation/stop;
the PC received data: 100.0, matching the hardware capacity reading of 100 percent.

Seven physical UI/lifecycle checks passed; the preview-visible local publish sample
was 28.2–28.8 fps over five seconds, and a separate concurrent PC probe received valid
JPEG traffic. See [UI design](UI-UX.md) and [evidence](verification/2026-10-25-operator-ui.md).
The first pass retains Android Views; Compose migration did not happen. Installed
project discovery/navigation and shared runner UI are now implemented (see below);
preview rotation, cross-app live-state integration, broad accessibility/layout checks
and external first-user onboarding remain next UI gates. This is not a finished
community alpha. UI and documentation changes remain uncommitted until requested.

## Installed projects milestone

Main-app Projects queries only the explicit version-1 runner discovery action,
validates project-prefixed package/Activity/metadata and opens selected controls.
No broad installed-app listing, cross-app command extras or automatic startup.
New C++, Python and workspace builds advertise the interface; legacy APKs need
rebuilding/redeployment. The browser shows installed state, not remote live state.

All runners share readable status, persistent Start/Stop, runtime-reported node
names, expandable connection/JSON arguments, packaged component list, raw
diagnostics and Return to ROS2 Android. Profiles are independent from the main
sensor profile; UI defaults to domain 0, while the CLI example uses 42. Main Stop
session and runner Stop project have independent scopes. See [guide](PROJECTS.md).
Main build/unit/lint and all three backend assembly/lint checks passed. Physical
browser discovery/refresh/recreation, battery continuation, sensor drafts and
Display controls passed (four checks, one camera opt-in check skipped in this run).
The actual main Projects button opened the stopped workspace runner. UI Start
launched two native nodes; the PC Wi-Fi probe passed custom messages, computation,
node names and parameter read on domain 0. UI Stop cleared the node list; UI
restart and Return to the main app preserved the restarted workspace. This is a
functional Wi-Fi probe, not a transport soak or performance qualification.
No full Python/C++-factory device rerun is implied by compiling their shared UI.
See [evidence](verification/2026-10-25-project-browser.md). UIAutomator dump could
not reach idle on the periodically refreshed UI; use Activity instrumentation
and guarded visual checks, and qualify redundant text updates/TalkBack next.
Next: accessibility/layout qualification and external onboarding, then authenticated
cross-app status/control if needed. Launch/parameter editing and a log viewer remain
pending. Changes are uncommitted until requested.

## Accessibility and onboarding refinement

Main/runner status text is assigned only when values change. Hidden runner raw
diagnostics no longer update; meaningful live counters continue updating. This
reduces redundant text/accessibility events without changing runtime polling.
Main navigation scrolls at effective widths below 320 dp (screen width/font scale),
selecting the current tab into view. Narrow/large-text headers stack status/actions.
Headings and expanded settings expose API-appropriate accessibility semantics;
debug display labels no longer read machine-only test identifiers.

Overview/Help Go to battery publisher selects/scrolls to the battery card without
automatic publication. Help validates saved domain/topic and offers explicit-type
PC commands and an optional Copy action; it never reads the user's clipboard.
[Getting started](GETTING-STARTED.md) records prerequisites, transport, Publish,
expected output, recovery and the independent runner Stop scope. Corrected an
old development-guide suggestion about changing global stay-awake settings.

Seven physical checks passed, including a 200% portrait app-local layout/target
check, unchanged-text watcher, battery, sensor drafts, camera preview, Display and
project discovery. Two accessibility checks passed again after header refinement. A stopped Overview
UIAutomator dump now reaches idle; live counters can still prevent idle snapshots.
The debug-only non-exported LargeTextPreviewActivity uses a private configuration
context and does not alter system fonts/security. Its screenshot is an ignored
app-cache artifact captured only with foreground focus and no lock screen.

This is a prepared-machine maintainer walkthrough, not a completed clean-machine
or new-contributor trial. Full TalkBack/focus/keyboard/display-size/contrast and
cross-device qualification remain pending. The guarded battery shortcut/Publish
walkthrough produced data: 100.0 using plain PC ROS CLI; the workspace probe also
passed while battery publishing ran. See
[verification](verification/2026-10-25-accessibility-onboarding.md).
Keep Play deferred and changes
uncommitted until requested. See UI-UX.md and the getting-started trial checklist.

## Roadmap and remaining gates

[PLATFORM-PLAN.md](PLATFORM-PLAN.md) remains the product roadmap. Completion of a
feasibility spike does not complete every acceptance criterion in that milestone.

1. **Launch:** basic C++ ROS description adapter is verified; add worker
   mapping for Python/mixed projects, parameter files, timers/events and other
   launch features only with explicit semantics/tests.
2. **Workers/supervision:** independent node processes, observable exits and
   bounded restart policy. Current composed C++ nodes share one thread/process;
   one blocking callback stalls that group. Fatal native crashes are not covered
   by caught-exception tests. Shell `kill` force-stops only the selected runner.
3. **Autonomy qualification:** physically remove the PC link, then reconnect and
   inspect actual continued computation. A heartbeat while a PC is connected is
   not this test. Also qualify one-hour background operation and another phone.
4. **MCU v0.2:** phone-hosted micro-ROS Agent, UDP then USB/OTG, one reference MCU,
   watchdog/command expiry and unplug/reconnect tests without a PC Agent.
5. **Workspaces:** ordinary C++ colcon example is verified. Add Python/mixed
   packages, dependency ports, resource/ABI qualification and general workspace
   compatibility. Component factories remain an alternative, not a requirement
   for the new standalone C++ executable backend.
6. **Product UI:** initial View-based operator navigation and thumbnail preview
   implemented. Continue with projects/launch selection, runner parity, preview
   orientation/configuration, accessibility/layout qualification, onboarding trials
   and a second-device support matrix. Compose remains an option, not a completed
   or prerequisite migration.

## Architecture and source map

| Path | Role |
| --- | --- |
| `android/app` | Main Kotlin sensor/operator app, package dev.ros2android.android |
| `android/runner/src/main` | Shared runner Activity, foreground service, config/launch schema |
| `android/runner/src/cpp` | C++ runner backend, JNI API wrapper |
| `native/runner` | rclcpp host, ABI-1 factory / ABI-2 component registry contract |
| `android/workspaceRunner` | Foreground controller for ordinary packaged native executables; process exit/stop supervision |
| `tools/ros2android-workspace` | Workspace inspect, host/cross-colcon build, staging and delegated deployment/control |
| `examples/android_test_ws` | Standard three-package Linux/Android compatibility target |
| `android/pythonRunner` | Embedded CPython backend using shared runner UI/service |
| `android/pythonRunner/src/main/python/phone_runtime.py` | rclpy context/executor lifecycle and exception diagnostics |
| `native/python` | JNI environment setup for the Python ROS process |
| `examples/cpp_talker` | Single-node demo, custom ROSIDL message, optional callback fault |
| `examples/cpp_workspace` | Two-node producer/processor pipeline and launch examples |
| `examples/python_talker` | Editable phone_node.py, custom message and optional C++ bridge |
| `tools/ros2android` | PC CLI: SDK, build, deploy, launch-plan, start/status/stop/kill/logs/ROS tools |
| `tools/compile_ros_launch.py` | Supported ROS launch-description → Android plan adapter |
| `tools/robot_launch.py` | Strict bounded JSON launch schema validator |
| `native/*lock.json`, `native/patches` | Pinned sources/runtime artifacts and reviewed Android patches |
| `docs/verification` | Evidence, measured results and explicit untested boundaries |

`native-deps` is an ignored symlink to the native SDK cache outside the repository
(default ~/.cache/ros2-android-native). Upstream generators need a path without
spaces, even though the project folder has spaces. C++ outputs use install/ and
apk-libs/; Python uses python-install/, python-packages/, python-apk-libs/ and
python-assets/. Do not replace the C++ SDK with the Python SDK.

The pinned PC Cyclone middleware lives in ~/.cache/ros2-android-host-runtime.
Generated PC message overlays are in ~/.cache/ros2-android-projects/<id>/host.
Device identifiers/logs/screenshots and generated outputs belong in ignored
`device-logs/` or build/cache directories, never in Git.

## Quick resume and verification

From the repository root:

```bash
git status --short
git branch --show-current
./setup/build.sh
python3 -m unittest discover -s tools/tests -v
```

Use setup/build.sh for Gradle assembly/unit/lint. Install with setup/run-device.sh
or the CLI deploy command which calls it. Instrumented main-app tests use
`:app:connectedDebugAndroidTest`; separate ROS probes verify actual traffic.

Build prerequisites: Ubuntu 24.04 PC with Jazzy, Android SDK 35, NDK
27.2.12479018, CMake 3.22.1, Java 21 for Gradle (Java/Kotlin 17 bytecode), Python 3.12; Python SDK builds also need
NumPy and pybind11 headers. See [DEVELOPMENT.md](DEVELOPMENT.md).

```bash
python3 setup/fetch-native-ros.py
./tools/ros2android --project examples/cpp_workspace build
./tools/ros2android --project examples/cpp_workspace deploy
./tools/ros2android --project examples/cpp_workspace start --peer PC_IP --local PHONE_IP
./tools/ros2android --project examples/cpp_workspace status
./tools/pc-ros.sh --interface PC_IP -- python3 tools/pc-workspace-probe.py
```

Read current USB/Wi-Fi addresses from the network interface diagnostics; DHCP
addresses and interface names change after reconnect. Never reuse an old address
from a transcript as authoritative. ADB availability, lock state, installed
builds and running services must be checked afresh.

Python setup, bridge and six-criterion probe commands are in
[PYTHON-PROJECTS.md](PYTHON-PROJECTS.md). Enable `/phone/imu` in the main app for
IMU-dependent probes. A missing IMU publisher is not a Python calculation failure.
No new runtime-wide soak or Wi-Fi Python result is implied by USB checks.

## Findings that save debugging time

- USB tethering is an IP network; ADB deploy/control is a separate channel. Raw
  MCU USB serial needs an Agent/protocol and is not ordinary DDS over a cable.
- PC Fast DDS topics interoperated with phone Cyclone. Parameter calls across
  that tested combination stalled; project-local PC Cyclone resolved them.
  Use the scoped tools/pc-ros.sh wrapper instead of changing system defaults.
- rcl uses the first matching remap rule. Per-node launch identity/remaps must
  precede generic CLI namespace rules. Keep this regression covered.
- Realme can discard the first Activity launch just after APK installation.
  The CLI waits for its runner to resume and retries that own Activity once.
- Stopped apps require explicit include-stopped-packages for debug broadcasts.
  The receiver requires privileged DUMP permission and is absent from release.
- Retained previous errors can appear during Starting. Use lifecycle failure
  states for startup errors, not any nonempty old error field.
- Android Python extensions must link explicitly to libpython. The CPython target
  library has no SONAME; use portable link names, not absolute PC library paths.
- Include native vendor libraries outside SDK lib/, notably libyaml. Staging
  checks ARM64, portable DT_NEEDED names, collisions and the packaged closure.
- NumPy/pybind11 build headers must match the supported Python/Android ABI; do
  not add the entire host /usr/include directory to Android compilation.
- Workspace native executables run from the APK native-library directory, not
  writable app data. Package the NDK shared libc++ explicitly when the APK has
  no externalNativeBuild task. Java owns only its launched Process objects and
  reports child exits; source fingerprints guard deploy/start after workspace edits.
- Project source fingerprints exclude build/.git/.cxx/.gradle/__pycache__. They
  identify project inputs, not the entire SDK; APK SHA-256 records the full build.
- Do not claim ROS interoperability from JNI/sensor diagnostics, or fatal crash
  handling from a caught exception/force-stop test. Keep pending items explicit.

## Recorded checkpoints

- 1d80a96: continuous cameras and foreground session ownership.
- 71b67d1: isolated C++ project runner, custom messages and PC tooling.
- e093edc: C++ callback failure monitoring and recovery.
- 76e72ab: About/author/Sponsors UI.
- d972184: composed C++ launch groups.
- 7e46364: embedded rclpy, Python/C++ interoperability and recovery.
- bf4bc86: supported ROS launch adapter and this development knowledge base.

Before ending a milestone, update this file and its evidence links with what
changed, checks actually run, limitations, current next action and commit state.
Do not store transient phone identifiers or mark untested roadmap items complete.
