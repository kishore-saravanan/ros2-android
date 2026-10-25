# Package namespace migration and Android ros2 node — 2026-10-25

## Implemented

Main application ID: dev.ros2android.android. Generated runner IDs:
dev.ros2android.android.runner.<project>. Example workspace:
dev.ros2android.android.runner.ws_android_test_ws. Kotlin source packages,
source folders, Gradle namespaces, manifests, JNI symbols, discovery actions and
metadata, example manifests, installer guards and PC command targets all match.

Rebuilt/installed main and example workspace. Migrated only the old main app's
endpoints_v1 preferences and example runner's project_profile preferences.
Backups of those project files and old project APKs are ignored in device-logs/.
Removed only the two superseded project installs after new-package checks; other
legacy runners remain installed and require their own rebuild/redeployment.
No personal apps/data, shared storage or phone security settings were inspected
or changed. No screenshots were taken during this pass. UI input used only the
unlocked, foreground project app with a package guard.

Upstream ros2node 0.32.13 added to the optional shell, with Android NumPy 1.26.2
and BLAS dependencies from pinned Python-runner wheel staging. Upstream node
command implementations are unchanged. Checked Android ros2cli patches defer
psutil imports in daemon/network XMLRPC modules and make the strategy honor
ROS2CLI_NO_DAEMON=1. This gives actual direct graph discovery without a daemon or
fake graph results. Native launch/other CLI plugins remain separate pending work.

AAPT omitted underscore-prefixed directories such as numpy/_utils in an early
candidate. Corrected workspace asset ignore rules and added APK assembly checks
for Python package/native launcher entries. Final deployed package includes the
required directory. Earlier missing-plugin/dependency candidates are not counted
as successful verification.

## Verification

- setup/build.sh passed main APK assembly, unit tests and lint after namespace
  and JNI changes. C++ and Python runner assembly/lint passed as well.
- Workspace cross-build, assembly/lint and final APK asset checks passed.
- Seven new-package physical instrumentation tests passed: ThemeUiTest,
  AccessibilityUiTest, EndpointUiTest, BatteryUiTest and ProjectBrowserUiTest.
  These include real native IMU endpoint/battery publication and runner discovery.
  Camera permission/publishing was not requalified after installing the new UID.
- 24 host tooling tests passed after namespace changes. Python helpers compiled;
  git diff --check passed.
- Android shell `ros2 node list` returned /android_test/talker and
  /android_test/processor. After restoring battery publishing it also discovered
  /phone/robot_brain (ROS node name is independent of Android application ID).
- Native `ros2 node info` for both workspace nodes returned real publishers,
  subscribers and parameter service interfaces.
- Native `ros2 pkg executables ws_nodes` returned talker and processor.
- Connected shell under the renamed app ran `ros2 run ws_nodes talker --ros-args
  -r __ns:=/namespace_check`. PC received Sample sequence/value; Ctrl+C returned
  to the shell and the test shell exited. Normal group nodes remained running.
- PC functional probe passed custom input, correct computation, both group nodes
  and parameter service with three matching samples.
- Restored the main app's migrated settings after instrumentation; published
  battery from Home. Plain PC echo returned data:100.0 on domain 0.

Current debug shell APK is 32.23 MiB, including NumPy/BLAS for graph inspection.
The initial run/pkg-only shell APK was 21.65 MiB. These are APK sizes, not
installed storage or release download-size claims.

Final state: renamed main app battery publisher and normal example workspace
running on domain 0. No test shell nodes remain. Existing old phone shell
sessions must exit/reconnect. Main app may request camera permission on next use
because Android treats the new application ID as a different app.

Source changes remain uncommitted on develop. Device logs, APK backups and
preferences stay ignored. Historical verification retains original package IDs.
