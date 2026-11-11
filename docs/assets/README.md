# README media

The maintainer explicitly requested these curated documentation images for the
community repository. Raw device logs, identifiers, screenshots and private test
artifacts continue to stay outside Git.

## App screenshots

Captured 2026-11-11 from the reference phone, app version 0.1.1-dev6 plus current
development-source changes, in its existing dark/portrait appearance. No global
theme or security setting was changed. These are real rendered Android app views,
not mockups or evidence of broad device qualification.

- `screenshots/home.png`: connection and ROS activity summary; IPv4 values redacted.
- `screenshots/sensors.png`: detected hardware; availability depends on the phone.
- `screenshots/dashboard.png`: temporary joystick/switch configuration using
  `/demo/*` topics, with Dashboard stopped and no commands published. Original
  saved widgets restored after capture.
- `screenshots/projects.png`: installed example controls, including a stopped
  workspace and an independently signed fixture awaiting authorization.

Capture uses only the app's content view after checking foreground focus and an
absent lock screen. It excludes system bars, notifications and other apps. IPv4
redaction happens on text views before drawing; the original UI text is restored
immediately. No camera or personal photos are accessed.

The opt-in capture is `ReadmeScreenshotsTest`. With the app open and unlocked,
Dashboard stopped and installation authorized for a development phone:

```bash
./setup/build.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.ReadmeScreenshotsTest \
  -Pandroid.testInstrumentationRunnerArguments.captureReadme=true
```

It writes `readme-{home,sensors,dashboard,projects}.png` into the main app's own
cache. Review every image in an ignored local directory before intentionally
copying curated images here. Never bulk-copy device logs or shared phone storage.

## Logo

`ros2-android.svg` mirrors the project's existing custom Android vector artwork
in `android/branding/res/drawable/ic_ros_logo.xml`. It is the original project
robot mark, not an official ROS or Android logo. See [branding](../BRANDING.md).

## Demo videos

The README embeds a GitHub-hosted [video attachment](https://github.com/user-attachments/assets/bcb325b3-a472-4b71-82ee-de90b0352fcb).
The tracked MP4 remains a downloadable fallback; GitHub’s repository file viewer
may decline to preview it. Attachment access follows the repository’s permissions.
Only the stable attachment URL is used, never its temporary signed media URL.

The maintainer supplied two recordings of the November 1, 2026 ball-following
experiment and requested their good opening sections for the community demo.
Only the selected, compressed showcase and a reviewed cover enter the repository:

- `demo/ros2-android-demo.mp4`: 67 seconds, H.264, 1280×720, 30 fps, muted,
  fast-start MP4. It concatenates source 1 at 00:01–00:40 and source 2 at
  00:00–00:28, with a brief identifying caption at each section's beginning.
- `demo/cover.jpg`: real frames identifying the remote Android phone and onboard Android phone,
  arranged on a ROS-inspired title card. No generated robot imagery.

Source recordings: `2026-11-01 20-25-41.mp4` (1920×1080/60 fps) and
`20261101_203553.mp4` (3840×2160, variable frame rate). Raw files remain outside
Git, unchanged. Separate uncaptioned 39-second/28-second 1080p clips and a 1080p
showcase are local sharing exports, not duplicate repository assets.

Action plays at its original speed. Exports convert to 30 fps without motion
interpolation, time compression, synthetic frames or artificial stabilization.
Room audio and source metadata are removed. Full 16:9 composition is retained.
The selected footage and cover were visually reviewed for personal screen
contents/addresses; later settings footage from source 2 is excluded. This is
supplied recorded media, not a new phone screen capture or camera access.

The onboard phone runs camera capture and Python ROS 2 vision/control nodes. A
phone-hosted micro-ROS Agent connects by USB to the ESP32 motor controller. A
remote Android phone triggers tracking and monitors subscribed annotated images.
The footage shows the then-current app UI, which can differ from later development.
It demonstrates one application; it is not a latency, hard-real-time, endurance,
calibrated-motion or general robot-controller benchmark.

All four local MP4 exports were fully decoded without reported errors. Dimensions,
frame counts/durations, 30 fps, absence of audio and fast-start atom order were
checked; title visibility and the section cut were visually inspected. Raw review
frames, encoding logs and export hashes remain ignored, not in public media.

Original project media follows the repository's [Apache-2.0 license](../../LICENSE).
