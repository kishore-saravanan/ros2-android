# Project instructions

## Physical Android device

- The user prohibits accessing, modifying, or deleting sensitive personal files
  or personal apps on the phone. Limit device work to ROS2 Android
  (`dev.robotbrain.android`) and its generated project runners
  (`dev.robotbrain.android.runner.*`), their own debug/test artifacts, hardware diagnostics,
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

- Work on `develop`. The user will configure their Git author later; do not
  invent an author or commit/push before that is resolved.
- Use `tools/build.sh` for the build, unit tests, and lint. Install through
  `tools/run-device.sh`. Physical tests use `:app:connectedDebugAndroidTest`.
- Report native ROS 2 and micro-ROS Agent integration as pending until real
  interoperability tests pass. JNI/sensor diagnostics do not prove ROS traffic.
