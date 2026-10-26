# Operator UI

Current design: 0.1.1-dev4, 2026-10-25. This supersedes the earlier text-heavy
operator prototype. Keep product screens focused on actions; long explanations
belong in Help or repository documentation.

## Design rules

- Use familiar Android navigation, list rows, forms, back navigation and buttons.
- Show the current task's mandatory controls. Put explanations, counters, ROS
  types, QoS, build identifiers and diagnostics behind disclosures.
- Main navigation is Home, Sensors, Dashboard, Projects and Settings. Keep labels and icons
  stable; avoid inventing interaction patterns for ordinary settings.
- Follow the device's light/dark appearance automatically. Do not add a forced
  dark theme or change system appearance/security settings.
- About is a visible footer section at the bottom of Settings. It is neither a
  separate destination nor a dropdown. Help is a separate settings row.

Launcher/Home/About branding is a custom eight-dot navy grid with an original teal
robot at its center. Native vector resources are shared through
android/branding/res; preserve adaptive safe areas and design notes in
BRANDING.md. About remains a visible footer with author credit and support link.

## Screens

**Home:** local runtime state, a Connection card with saved ROS domain/namespace,
automatic or explicit peer discovery, optional selected phone address and current
phone IPv4 interfaces. Connection settings opens Settings. A ROS activity card
shows Sensor topics publishing counts and Dashboard topics publishing/subscribed counts,
project running/installed and active node counts, and micro-ROS Agent state. Sensors, Dashboard and Projects remain directly available in bottom navigation;
the activity card has no shortcut button.
These counts do not include services or actions. Session details
remain collapsed. Author credit and Sponsors are in Settings → About only. Battery
controls live under Sensors, alongside other hardware; Home has no sensor form.
An active local runtime is not proof of a reachable PC/robot. Addresses refresh
every five seconds while Home is visible; project runners retain separate profiles.

**Sensors:** compact hardware rows (icon, name, state and chevron) with category
filters. Tap a row to open a detail screen. Topic and publish rate are visible
there; message type, QoS, discovery and explanations are under Advanced. The
primary Publish/Stop control stays below the scrolling form. Cameras show a
preview. Sensors contains hardware publishers only. Text subscribers belong in
Dashboard (Subscribe, std_msgs/msg/String); there is no separate Display filter.

**Projects:** project name, live runtime state, active node names and one
Start/Stop action. Connection settings opens a familiar dialog; fields lock while
running. Logs and package/build details are collapsed. Details retains Open runner
as a fallback. Signature-authenticated controls never expose a shell endpoint.
Refresh stays in the toolbar. Runner activities have no launcher entry; Projects
and its explicit Open runner action remain their entry points. See PROJECTS.md
for lifecycle and log limitations.

**Settings:** domain and Save, collapsed advanced connection fields, collapsed
network/runtime diagnostics, a collapsed Microcontrollers section with Agent
Wi-Fi/USB Start, shared Stop and nested bounded logs, a device-appearance indication, Help and the visible
About footer. Namespace and IPv4 fields lock during a session. About shows app
version, Built by Kishore Saravanan (plain text) and the existing support link.
There is no author hyperlink or repository button.

**Help:** collapsed task sections for connection, first message, camera/display
and troubleshooting. First-message commands use the validated saved topic/domain
and explicit Float64 type. Copying is user-triggered; clipboard contents are never
read. See GETTING-STARTED.md for complete PC prerequisites and steps.

**Runner:** project name, current state, one relevant Start/Stop action and live
node names when running. Connection, launch configuration and diagnostics are
collapsed by default. The back control returns to the main app; native execution
continues. Its profile and Stop action remain independent from the sensor app.

## Colour and system appearance

The light theme uses ROS navy #22314E, white surfaces and a pale neutral background.
Dark mode uses navy surfaces with a readable pale-blue accent. These are app
palettes inspired by the ROS colour; they do not claim an official ROS app theme.
Source: [ROS brand guide](https://www.ros.org/imgs/ROSBrandGuide.pdf).

Palette resources live in values/colors.xml and values-night/colors.xml in app
and runner. Theme resources follow those qualifiers, including system-bar colours.
Main and all three runner backends share the appearance policy. Android 8.0 uses
a dark navigation bar because light navigation-icon styling needs a newer API.
App navigation and branding use native vectors; see BRANDING.md for credits.

## Lifecycle, preview and accessibility

UI observes RobotSessionService; it never takes ownership of the ROS/sensor/camera
lifetime. Selected sensor, screen and filter restore after Activity recreation;
drafts persist across navigation. Back from sensor detail returns to the list.

Preview reuses the existing JPEG stream and decodes on a dedicated worker while
its detail screen is resumed. One pending latest frame replaces older work;
decoding never blocks ROS encoding/publishing. The view presents the newest
bitmap on animation frames and shows its measured displayed FPS. It opens no
second camera and saves no images. The former 2 FPS production/500 ms UI polling
limits are removed. Preview follows the selected camera publishing rate (up to
30 FPS where supported); this is not an unconditional 30 FPS or hard real-time
guarantee. Preview FPS, capture FPS, local ROS writes and PC receipt are separate.
Orientation correction and selectable capture resolution/quality remain pending.

Text updates only on changes. Headings and disclosures expose API-appropriate
accessibility state. Controls use at least 48 dp touch height. Large text can
scroll navigation instead of truncating labels. Debug-only preview Activities
exercise light/dark/200% configurations without modifying global device settings.
Those scoped checks are not a complete TalkBack, keyboard or device audit.

## Verification and remaining work

See verification/2026-10-25-minimal-ui.md for this redesign; older verification
files describe earlier layouts. Full TalkBack/focus/keyboard and display-size
qualification, a second phone and a real external contributor trial remain gates.
Cross-app state/control and bounded workspace logs now exist. Full launch/parameter
editing and simultaneous multi-workspace trials remain pending. Google Play stays deferred; licensing, signing and public artifact
preparation are separate release requirements.

Microcontroller evidence: two physical Agent/ESP32 tests passed, including a
phone battery acknowledgement in the real phone subscriber and reconnect after
Agent restart. Normal-use startup also passed with PC-observed heartbeat/reply.
See verification/2026-10-25-esp32-wifi-agent.md. The Agent's control is collapsed
under Settings → Microcontrollers and remains independent of sensor Stop all.

USB follow-up: Start USB agent lives inside the existing Microcontrollers section,
with Android's permission prompt and concise Running/Disconnected/error status.
The actual project Settings UI showed Running · USB · 115200 baud after manual
permission. Two raw-PTY device tests and a real USB round-trip/restart test passed;
see verification/2026-10-25-esp32-usb.md. General layout/accessibility scope remains
unchanged. Reconnect uses an explicit Start action.

**Controls (Home sub-screen):** explicit Enable/Disable, Add widget, then concise
control cards with label/topic/action and an overflow menu for Edit/Move/Delete.
The editor uses a standard widget selector and only relevant fields. Buttons,
switches and sliders use Android controls; the round joystick returns to center
on release. Layout saves privately. Navigation/backgrounding disables controls
without stopping sensors. This introduces no extra main tab. See CONTROLS.md and
verification/2026-10-25-controls.md for actual test scope and remaining gaps.

Actual visual follow-up: the empty dashboard was inspected on the reference
phone in its current dark/landscape mode. Enable/Add share one row, disabled
actions use muted text, and joystick height adapts to landscape. Home retains
battery first with a compact dashboard entry afterward. No device appearance
settings were altered. Populated-editor persistence and joystick release/focus
checks passed; broader visual/accessibility qualification remains pending.

## Dashboard tab and subscribed values (current follow-up)

Dashboard is now a main bottom tab, between Sensors and Projects; the Home
shortcut is removed. At narrow widths/large fonts the existing navigation scroll
keeps labels and touch targets usable. Add widget has Direction (Publish or
Subscribe), then only the relevant widget/type and fields. One Start/Stop action
connects that dashboard; publish widgets remain idle until operated. Subscribers
are simple text/numeric/Bool value cards, with Waiting/Live/Stale states (five
seconds since the last sample). No gauges, plots or additional setup screens.
Existing layouts load without migration; About and device-driven themes remain.
See CONTROLS.md and verification/2026-10-25-dashboard-subscribers.md for scope.
Earlier Home-subscreen paragraphs above describe the preceding milestone only.

Dashboard visual evidence: a guarded app-content image on the reference phone
showed real String/Bool/numeric cards and selected Dashboard navigation in its
current dark/portrait appearance. Card Waiting/Live/Stale/recovery/Stop text was
asserted on-device. The portrait label-wrap finding led to single-line nav text
with normal letter spacing and reduced horizontal padding; touch targets remain.

Home follow-up (2026-10-25): battery shortcut replaced by connection overview and
sensor summary, with Developed by Kishore Saravanan below collapsed session
details. Both physical light/dark theme tests passed; guarded portrait captures
were inspected with readable content and all five navigation labels visible.
No global appearance setting changed. Large-text/second-device qualification for
this revised Home remains pending.

Dashboard lifetime follow-up: endpoints/readings remain service-owned across
navigation and Activity pause. Navigation/pause requests zero for all joysticks;
release/cancel/focus loss/detachment also requests zero. Explicit Stop dashboard
or Stop all removes endpoints. Returning never restores a held joystick command.
Home polls project status while visible without starting project execution.

ESP32 programming follow-up: Settings → Microcontrollers conditionally displays
USB programming state and Stop USB programming, with the same stop action in
its foreground notification. No separate product destination or public LAN shell
is added. PC starts programming through a protected debug-only ADB control.
Reference CP2102 flash/hash verification and Agent/Dashboard recovery passed;
other adapters, interrupted-write and endurance qualification remain pending. See
PROJECT-KNOWLEDGE.md for actual tests and the saved ESP32 LED Switch example (physical LED wiring unverified).

Robot-operation refinement (2026-10-26): Help → Run without the PC is a collapsed
task section explaining workspace/sensor/MCU startup, fresh-feedback checks and
independent Stop scopes. Camera/subscriber help now explicitly says Stop all
stops sensors and Dashboard, while projects and Agent remain independent. No
new navigation destination or automatically started hardware is introduced.
GETTING-STARTED.md now uses Sensors → Battery percentage instead of the removed
Home battery card. Visual/device regression results are recorded in the robot
readiness ledger; existing broad accessibility gaps remain explicit.

Status scope correction: the main toolbar now says Sensors & dashboard active /
stopped instead of Phone runtime stopped / On-device runtime active. This status
belongs to RobotSessionService, not the independent workspace runners or Agent.
Their live states remain in Home ROS activity. It is not a robot-wide readiness
or verified-communication indicator.

Preview performance evidence (2026-10-26): physical navigation/recreation/Stop
regression passed with requested 30 Hz, 26.94 displayed FPS and 26.94 local ROS
publish FPS concurrently over ten seconds. Estimated frame age p95 110 ms,
max116 ms; later five-second local publication29.6 FPS. This is not remote FPS or
an optical panel-latency measurement. See robot-readiness ledger.
