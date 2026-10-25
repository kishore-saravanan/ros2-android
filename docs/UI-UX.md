# Operator UI

Current design: 0.1.1-dev4, 2026-10-25. This supersedes the earlier text-heavy
operator prototype. Keep product screens focused on actions; long explanations
belong in Help or repository documentation.

## Design rules

- Use familiar Android navigation, list rows, forms, back navigation and buttons.
- Show the current task's mandatory controls. Put explanations, counters, ROS
  types, QoS, build identifiers and diagnostics behind disclosures.
- Main navigation is Home, Sensors, Projects and Settings. Keep labels and icons
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

**Home:** local runtime state, current battery percentage, Publish/Stop and
Configure, and a small active-endpoint summary. Session details are collapsed.
The battery value can be read while stopped; only Publish starts ROS output.

**Sensors:** compact hardware rows (icon, name, state and chevron) with category
filters. Tap a row to open a detail screen. Topic and publish rate are visible
there; message type, QoS, discovery and explanations are under Advanced. The
primary Publish/Stop control stays below the scrolling form. Cameras show a
preview. Display is the debug-text subscriber, selected through its filter.

**Projects:** project name, live runtime state, active node names and one
Start/Stop action. Connection settings opens a familiar dialog; fields lock while
running. Logs and package/build details are collapsed. Details retains Open runner
as a fallback. Signature-authenticated controls never expose a shell endpoint.
Refresh stays in the toolbar. See PROJECTS.md for lifecycle and log limitations.

**Settings:** domain and Save, collapsed advanced connection fields, collapsed
network/runtime diagnostics, a device-appearance indication, Help and the visible
About footer. Namespace and IPv4 fields lock during a session. About shows app
version, Built by Kishore Saravanan and the support link.

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

Preview reuses the existing JPEG stream and decodes only a small thumbnail at up
to 2 fps while its detail screen is resumed. It opens no second camera and saves
no images. Preview FPS, capture FPS, local ROS writes and PC receipt are separate.
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
