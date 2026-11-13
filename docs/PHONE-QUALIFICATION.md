# Qualify a phone

Use this to check another phone or Android version and to contribute a row to the
[compatibility matrix](COMPATIBILITY.md). It needs about an hour, a Linux PC prepared
as in [Development](DEVELOPMENT.md), and a phone you may use for development. Nothing
here changes phone settings automatically; you change the few settings below yourself.

## Safety and privacy

- Disconnect motors and microcontrollers. These checks use disposable test apps and
  topics only; no camera, sensor publishing to a robot, or actuator topics.
- The scripts only query this project's own apps. Device lists that could reveal other
  apps are filtered on the phone. Result folders keep the Android version and model,
  never the serial number; share only `summary.json`, not raw logs or screenshots.

## Prepare the phone

1. Enable Developer options and USB debugging; connect by USB and authorize the PC.
2. Turn Wi-Fi **off** (the runtime checks are phone-local and must not depend on a
   router). Keep the screen unlocked during the first batch; UI tests open the app.
3. Run the driver once; it builds and installs the main app and two harmless runner
   fixtures, then lists missing preconditions:

   ```bash
   adb devices                      # note the phone's serial
   python3 tests/integration/phone_qualification.py --serial SERIAL --label phone-b-android14
   ```

4. In each app's Android settings (ROS2 Android → Settings → Background operation, and
   Projects → Details for **runner fixture cpp** and **runner fixture python**), allow
   background activity / choose Unrestricted battery use. On Android 13+ also allow
   notifications. Some vendors separate background activity from the Special access →
   Optimize battery usage list: if the app still reports optimized, choose All apps
   there and disable optimization for these three project apps. Labels vary by vendor; see [standalone operation](STANDALONE-OPERATION.md).

## Run the batch

```bash
python3 tests/integration/phone_qualification.py --serial SERIAL --label phone-b-android14 \
  --skip-install --endurance-minutes 60
```

It runs, pinned to that phone:

| Batch | What it shows |
| --- | --- |
| `suite` | Every instrumentation test; hardware tests without their opt-in arguments are skipped |
| `dashboard-peers` | Phone-local DDS between the app and separate C++ and Python runners, peer loss/Stale, restart without replay, zero Android pauses |
| `runner-update` | Reinstalling a running C++ runner invalidates app controls; after Refresh it is Stopped with its profile, nothing is replayed, and an explicit Start delivers fresh data |
| `endurance` (optional) | Continuous 5 Hz runner telemetry for the chosen minutes with memory, CPU, battery and thermal sampling |

Results are under `device-logs/qualification/<label>/`. Use a fresh label for each
run: earlier evidence is never overwritten. Each batch retains its own XML and log;
the summary is saved after each completed batch. Missing results, failed Gradle
commands and skipped opt-in checks cannot qualify a batch. Default-suite hardware
skips remain expected. The UI suite requires the project app foreground and unlocked;
subsequent peer/update checks run without a visible app screen.

A failing check is a finding,
not something to retry until it passes: keep its log and report it. Typical causes are
vendor background limits (the app then reports "Android paused …"), a locked screen
during UI tests, or Wi-Fi left on.

## Manual checks on the same phone

- On Android 13+, the first Publish asks for notification permission once; allow it and
  confirm the running-session notification shows a **Stop** action.
- Look through Home, Sensors, Dashboard, Projects and Settings in light and dark mode and
  with large text; report clipped or unreadable content.
- With TalkBack on, move through each tab and one project card; fields should be read by
  their labels and expandable sections should announce their state once.

## Report

Open an issue with the phone model, Android version and `summary.json`, plus any manual
findings. Note which vendor background settings you changed. Hardware-specific checks
(ESP32 over USB, cameras, network handover) have their own guides and opt-in tests.
