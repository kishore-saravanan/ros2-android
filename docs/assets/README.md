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

The maintainer plans to provide the ball-tracker robot recordings later. No video
or invented preview is included yet. When supplied, add a selected preview/link
to the README's demo section, describe the actual phone/MCU computation path and
link the example guide. Review visible personal material, screen contents and
private addresses before publishing; avoid committing large raw recordings.

Original project media follows the repository's [Apache-2.0 license](../../LICENSE).
