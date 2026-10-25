# Accessibility and onboarding refinement — 2026-10-25

## Implemented

- Main/runner text updates only when values change; hidden runner raw diagnostics
  are not continuously rewritten. Runtime polling and actual counters remain.
- Main navigation adapts to effective width below 320 dp with horizontal scrolling;
  larger-text/narrow headers stack status and Stop. Text keeps the selected scale.
- API-appropriate heading and settings expanded/collapsed semantics; readable
  debug display field labels and a direct battery publisher navigation action.
- Help generates validated saved-domain/topic PC commands with explicit Float64
  type, best-effort QoS and optional user-triggered copying. Existing clipboard
  content is never read. A full getting-started walkthrough is in GETTING-STARTED.md.
- Removed an outdated global stay-awake instruction from DEVELOPMENT.md. No global
  font/display/security/stay-awake settings were changed for verification.

## Passed

- Main APK assembly, configuration unit tests and lint via tools/build.sh.
- C++, Python and workspace runner APK assembly/lint for the shared UI changes.
  Workspace backend updated through the guarded deploy tool; factory C++/Python
  runtime behavior was not newly qualified by their compilation.
- Seven physical checks passed: stable status TextWatcher, 200% portrait layout,
  battery publishing across navigation/recreation/stop, sensor drafts, camera
  thumbnail/publication/navigation/recreation, Display controls, project discovery.
- Two accessibility checks passed again after stacking the larger-text header.
  The measured navigation buttons met minimum 48 dp height with no first-line
  ellipsis; Help and the battery shortcut remained addressable. These are scoped
  checks, not proof that every screen or input method is accessible.
- LargeTextPreviewActivity is debug-only/non-exported. It uses a private 200% font
  configuration and portrait Activity orientation. An app-view snapshot was
  captured only after checking foreground window focus and no lock screen. The
  ignored app-cache artifact was pulled for visual inspection; no personal screen
  or app data was inspected.
- UIAutomator dump now reached idle on the stopped Overview screen and read the
  project UI successfully. This does not promise idle snapshots while live counters
  update. Earlier project-browser evidence records the pre-refinement idle failure.
- Followed the battery shortcut using guarded real UI input: navigated to the
  battery card while stopped, then tapped Publish explicitly. Plain PC ROS CLI
  listed /phone/battery_percent as std_msgs/msg/Float64 and received data: 100.0.
  The phone was at 100 percent. This uses the prepared reference PC and USB IP path.
- The restarted workspace also passed the full PC custom-message/computation,
  graph-name and parameter probe while the main app's battery publisher ran.

## Remaining qualification

A real new-contributor trial and a fresh-machine prerequisite installation were
not performed. No TalkBack listening session, complete focus traversal, keyboard/
IME, display-size/contrast audit, runner 200% layout or second-device qualification
is implied. Camera orientation/configuration and cross-app live-state/control
remain separate work. No one-hour/disconnected-operation qualification or Play
submission occurred. Release/signing/licensing/public distribution gates remain.
Device logs, screenshots, addresses and identifiers are excluded from Git.
