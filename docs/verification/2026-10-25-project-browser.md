# Project browser and shared runner UI — 2026-10-25

## Passed

- Main-app build, unit tests and lint passed via tools/build.sh.
- C++, Python and ordinary-workspace runner APK assembly/lint passed with the
  new shared UI and version-1 discovery metadata. Only the workspace backend was
  redeployed and exercised physically for this milestone.
- Installed through the guarded project tools. The ordinary workspace retained
  project fingerprint 2fcb02bfd3f4; runner UI/manifest changes update its APK.
- Physical ProjectBrowserUiTest discovered the deployed workspace via the narrow
  action query, checked its explicit target, refreshed Projects and preserved the
  tab/card across recreation.
- Physical regression run: four checks passed (browser, battery publication
  across navigation/recreation, sensor drafts, Display subscription controls).
  One live-camera check was skipped because this run did not enable its opt-in;
  prior preview evidence remains separately recorded.
- Manually tapped Projects → Open project controls. The runner was stopped after
  opening, confirming navigation did not implicitly start code.
- Tapped Start project; reported Running with talker and processor. The PC probe
  passed custom input, matched squared results, both graph node names and the
  period_ms parameter read, with three matched message pairs.
- This functional ROS probe used the PC Wi-Fi interface with the phone selecting
  its interface automatically, domain 0. It is not a long-duration Wi-Fi,
  failover or latency qualification.
- Tapped Stop project: stopped state, no reported native nodes. Tapped Start again
  and used Return to ROS2 Android; both workspace nodes continued running.
- Projects and runner screens were visually inspected in landscape while an
  allowed project app was foreground and the lock screen absent.
- Restored battery publishing after app replacement; PC received data: 100.0.
  A further receive check while the workspace runner was foreground verified the
  sensor service continues across the app switch. Neither project was auto-started
  by the browser; the explicit Start/Publish controls were used.

## Boundaries and findings

No broad app enumeration or personal data inspection was used. Metadata is an
opt-in discovery/navigation protocol, not authenticated cross-app control or an
assertion that arbitrary user code is trusted. Old runner APKs need rebuilding
to advertise discovery. The main list shows installed state; live state belongs
to the runner. Main sensor and runner profiles and Stop actions are independent.

No unified live-state dashboard, launch editor, parameter editor, in-app logs,
automatic restart, phone-disconnection test or complete accessibility audit was
added. Physical C++ factory/Python runner behavior was not rerun here; their shared
UI compilation/lint passed. Google Play remains deferred.

UIAutomator dump could not reach an idle state on the periodically refreshed UI;
Activity instrumentation plus guarded visual checks were used instead. Reduce
redundant accessibility/text updates and qualify TalkBack as follow-up work.
Device addresses, logs and screenshots stay in ignored outputs, not Git.
