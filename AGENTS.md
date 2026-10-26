# Project instructions

## Physical Android device

- The user prohibits accessing, modifying, or deleting sensitive personal files
  or personal apps on the phone. Limit device work to ROS2 Android
  (`dev.ros2android.android`) and its generated project runners
  (`dev.ros2android.android.runner.*`), legacy project apps
  (`dev.robotbrain.android` and `dev.robotbrain.android.runner.*`) during migration,
  their own debug/test artifacts, hardware diagnostics,
  and development settings needed for this project.
- Before UI input or screen capture, verify that ROS2 Android or its project runner is foreground and
  the lock screen is absent. If another app is visible, do not inspect it.
- Keep ROS2 Android awake during development using its activity's
  `FLAG_KEEP_SCREEN_ON`. Realme blocks ADB changes to the global stay-awake
  setting. Do not bypass that restriction or change security settings.
- Do not inspect shared storage, personal app data, notifications, contacts,
  messages, photos, or accounts. Do not uninstall other apps or clear their data.
- Keep device screenshots, identifiers, and logs out of Git. `device-logs/` and
  generated build/test outputs are ignored.

## Development

- Read `docs/PROJECT-KNOWLEDGE.md` before resuming development. Update it after
  each milestone with implemented work, actual verification, findings and
  remaining roadmap items; keep device identifiers and transient addresses out.

- Work on `develop`. Git identity is configured; preserve it and never invent
  an author. Commit when requested; push only when requested.
- Use `setup/build.sh` for the build, unit tests, and lint. Install through
  `setup/run-device.sh`. Physical tests use `:app:connectedDebugAndroidTest`.
- Report native ROS 2 and micro-ROS Agent integration as pending until real
  interoperability tests pass. JNI/sensor diagnostics do not prove ROS traffic.

## UI / UX

- Use familiar Android navigation and controls. Main tabs are Home, Sensors,
  Dashboard, Projects and Settings. Keep default screens concise and action-focused;
  technical detail, instructions and diagnostics belong in collapsed sections.
- Sensor lists open dedicated controls; keep topic/rate and Publish/Stop visible
  on detail screens. Preserve service-owned ROS/hardware lifetime.
- Follow the device light/dark mode using the ROS-inspired resource palettes.
  Do not force a theme or alter global device appearance settings for tests.
- About is a visible section at the bottom of Settings, not a dropdown or a
  separate page. Preserve author credit and GitHub Sponsors link.
- Read docs/UI-UX.md before changing product screens; update the knowledge base
  with actual visual/device evidence and remaining usability gaps.
