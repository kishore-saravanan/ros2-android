# Minimal dashboard and standard subscribers — 2026-10-25

## Scope

Owner requested a Dashboard bottom tab and subscription widgets while avoiding
feature creep. Navigation is Home / Sensors / Dashboard / Projects / Settings.
There is one saved dashboard, one Start/Stop action, existing publisher controls
and a single read-only subscribed-value presentation. No plots, gauges, layout
engine or additional setup wizard. The editor shows only relevant fields after
choosing Publish or Subscribe. Old control layouts load without changing them.

Native rcl subscriptions support std_msgs String, Bool, Int32, Int64, Float32 and
Float64. Latest-value depth 1/best-effort/volatile QoS, bounded polling/text, UTF-8
bytes, exact Int64 formatting and receipt timestamps feed simple Waiting/Live/
Stale cards. Five seconds without a new sample is Stale; it does not prove a lost
network connection. Stop clears values. Service generation/epoch guards reject
queued readings from earlier dashboard sessions. Existing debug display owns a
separate native subscription. Dashboard navigation/pause releases its endpoints;
sensor/display and MCU Agent lifetimes remain separate.

## Actual checks

- `setup/build.sh`: assembly, JVM tests and lint passed, including supported
  subscriber schema checks and preservation of the distinct Int64 path.
- Existing host tooling suite: 24 tests passed; new PC publisher syntax checked.
- A complete physical run passed **4 tests, 0 failures, 0 skips**: existing
  joystick/lifecycle tests, editor validation/persistence plus the new Subscribe
  flow, and real PC→phone typed subscriptions/staleness/recovery.
- The editor test selects Subscribe through the actual Android selector,
  confirms publisher fields disappear, saves/recreates the value widget and
  checks Stopped→Waiting. It also exercises the new bottom Dashboard tab.
- A separate PC publisher supplied all six types, including UTF-8 emoji and
  Int64 9223372036854775806. Phone assertions checked exact values, then Stale
  after a deliberate seven-second pause, then Live with new Bool=false data.
  Existing Sensors display also received its own independent String messages.
- Stopping Dashboard cleared readings while battery publishing and the separate
  debug-display subscription remained active. Reserved /phone/dashboard_test/*
  and /phone/control_test/* topics only; no actuator commands or MCU firmware
  changes were part of these trials. Test layout preferences were restored.

A follow-up physical test passed with actual card-text assertions for
Waiting/Live/Stale/recovery, exact values and clearing on Stop. A guarded
app-content render on the reference phone was visually inspected: String/Bool/
numeric cards display live data and the dedicated Dashboard tab is selected. A first render attempt
used an accessibility root on the UI thread, which returned null; the capture
now verifies the own Activity's foreground window focus and unlocked state and
renders only its own content view. No personal app UI is captured. Portrait inspection found the Dashboard nav
label wrapping; explicit single-line labels, normal letter spacing and smaller
horizontal padding fix it while preserving touch targets. The final editor/
recreation check passed after this change, and a guarded portrait screenshot
confirmed all five labels fit on one line. Instrumentation APK removed; normal
app left on Dashboard with the original saved layout restored.

Reproduce in two terminals with matching ROS domain/network settings:

```bash
ROS_DOMAIN_ID=0 ./tools/pc-ros.sh --interface PC_IP -- python3 tools/pc-dashboard-probe.py
./setup/build.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.ros2android.android.DashboardSubscriberTest,dev.ros2android.android.ControlsTest \
  -Pandroid.testInstrumentationRunnerArguments.dashboardPeer=true
```

## Remaining boundaries

No arbitrary ROSIDL schemas/arrays/nested message editor, graph/gauge widgets,
multiple named dashboards or import/export. Stale threshold is fixed at five
seconds; very slow topics can naturally be stale between valid updates. Full
TalkBack/focus/large-font/other-device qualification remains pending. Subscription
widgets are read-only; actuator command timeouts and previous motion-control
qualification limits still apply. This feature is not a completed community
release or new MCU transport/endurance qualification.
