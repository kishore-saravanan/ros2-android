# Unified project controls and custom icon — 2026-10-25

Icon update: the later original teal robot replaces the Android-head artwork
and its credit disclosure. See BRANDING.md and PROJECT-KNOWLEDGE.md for the
current assets; the control verification below remains applicable.

## Implemented

Main app 0.1.1-dev4 controls installed runners through a narrow Messenger service.
The Projects tab shows actual supervisor state/node names, Start/Stop, connection
settings and collapsed bounded logs. Explicit service binding is signature-gated;
the main app checks runner signatures and the runner validates each sender UID
against the main app package and signing certificate. Main-app navigation and
Activity recreation do not own the separate worker service lifetime.

Workspace child stdout/stderr is collected into a 60-entry in-memory tail, each
entry capped at 2048 characters, along with service lifecycle events. No global
logcat reader or filesystem access endpoint is exposed. C++/Python prototype
variants expose lifecycle events, not native output capture. The ordinary example
nodes now emit one normal RCLCPP_INFO startup message each, allowing meaningful
end-to-end log-view verification without ongoing log spam.

Native shared vectors implement eight navy dots around a green Android head.
Adaptive icon resources use the explicit ic_launcher_ros2 name. Home and About
show the same custom logo. Attribution is in the About footer's collapsed Artwork
credits and BRANDING.md. No launcher or personal app screen was inspected.

## Final verification

- setup/build.sh: main assembly, unit tests and lint passed.
- Full ordinary-workspace cross-build/package/runner lint passed after adding the
  example startup messages. New workspace source fingerprint: 8ff6f9e9426c.
- Shared C++ and Python runner assembly/lint passed with the control service.
- Main and current workspace runner installed successfully.
- Four device tests passed (0 skipped, 0 failures): installed project discovery
  and recreation; main-app start/stop/restart, workspace stdout in Logs and
  reconnect to running workers after recreation; invalid-domain rejection with
  preserved profile/error through polling; both installed PackageManager icons
  contain the green Android mark.
- An unprivileged shell attempt to access ProjectControlService was rejected for
  missing MANAGE_PROJECTS permission. The separate DUMP-protected debug receiver
  remains available to authorized ADB development tools.
- Starting from the main UI produced real custom-message ROS traffic on the PC;
  the probe passed input, computation, both nodes and parameter-service checks.
- Guarded screenshots of the project's own Home/Projects screens were inspected;
  Home visibly shows the custom Android/dot logo. Device artifacts remain ignored.
- Final rebuilt workspace also passed the PC probe; battery publishing was
  restored and its percentage received on the PC.
- Instrumentation APK removed after verification, preserving the owner's earlier
  cleanup request. Main app and current workspace runner remain installed.

## Limits

This is unified management of separate runner APKs, not a shared-runtime redesign.
A single reference phone and one deployed ordinary C++ workspace were physically
qualified. Concurrent multi-workspace operation, other Android versions, runner
update/disconnect/crash recovery through this new UI and different signing-key
negative tests need broader qualification. Failure/timeout paths exist but their
presence is not a substitute for those device trials. The actual launcher cache
was not inspected; both installed icon resources and the Home logo were checked.

Status/node names and Stop refer only to the runner's app-supervised group.
Independently started native ROS-shell processes are outside this control path.
Logs are bounded and process-local, not persistent history. Launch selection,
parameter editing, per-node control, shared runtime packaging and Play release
remain separate work.
