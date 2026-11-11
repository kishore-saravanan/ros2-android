# Workspaces in the main app

Open **ROS2 Android → Projects**. Each compatible installed runner has a card
with live state, runtime-reported node names and **Start project / Stop project**.
You can operate it without opening a second app. Runner APKs have no launcher
entry: ROS2 Android is the only project app shown in the app drawer. Runners
remain listed in Android Settings as installed apps. Rebuild/redeploy existing
runners to apply this change. **Connection settings** shows
its saved domain, namespace, peer/local IPv4 and ROS arguments. Stop before
editing; Save validates the profile in the runner. A PC start updates the same
profile, so main-app controls and runner controls follow accepted PC settings.
The sensor app's own Settings remain independent.

**Logs** is collapsed by default. It shows a bounded in-memory tail of runner
state changes and, for ordinary C++ workspaces, child stdout/stderr. It survives
screen navigation, not runner process death. C++/Python prototype backends only
provide lifecycle events here. It doesn't read device-wide logs. Only processes
started by the app's supervisor appear in this status/log stream; nodes started
independently in an ADB ROS shell are not supervised by the card.

**Details → Open runner** retains the independent runner screen as a fallback.
Opening it does not automatically start code. Changing tabs, leaving the app or
recreating its screen does not stop a running workspace. Android/vendor process
and power constraints still apply. Stop project controls this runner's supervised
group; Stop all in the sensor app controls its sensor endpoints. Neither is a
robot-wide emergency stop or a per-node kill command.

Projects start locally without a PC. For automatic/phone-local address settings
and pending offline qualification, see [Standalone operation](STANDALONE-OPERATION.md).

## First workspace

From the repository root on your Linux PC, with DEVELOPMENT.md prerequisites:

```bash
./tools/ros2android-workspace --workspace examples/android_test_ws build
./tools/ros2android-workspace --workspace examples/android_test_ws deploy
```

1. Open ROS2 Android → Projects; tap Refresh if needed.
2. Open Connection settings, match the PC domain and save current IPv4 addresses
   if explicit discovery/interface selection is needed. Default domain is 0.
3. Tap Start project. Confirm Running and the two node names.
4. Monitor real ROS traffic on the PC using COLCON-WORKSPACES.md.
5. Expand Logs to inspect runner output. Stop project ends the supervised group.

## Compatibility and authentication

Discovery queries only the explicit OPEN_PROJECT action, validates the expected
runner namespace, Activity class and project metadata, and does not
request QUERY_ALL_PACKAGES or enumerate personal apps. Control uses an explicit
bound Messenger protocol. Existing runners use the signature permission
`dev.ros2android.android.MANAGE_PROJECTS`. Opted-in independent runners use a
separate certificate-pinned entry and explicit per-runner approval in Details.
Both verify each sender UID/package; the main app checks approved identity on
replies. See [runner authorization](RUNNER-AUTHORIZATION.md) for building with
`--controller-apk`, approval, revocation and preview qualification limits.
Approval trusts workspace code; it does not sandbox untrusted code.

Commands are status, start packaged launch, stop supervised group and save a
validated connection profile. No arbitrary shell command or filesystem endpoint
is exported. The narrow control service is separate from the existing shell-only
DUMP-protected debug receiver. The main UI polls while on Projects and resumed,
unbinds on leaving, and reports disconnects/timeouts instead of assuming Stopped.
Version-1 control envelopes advertise commands before controls are enabled;
legacy v1 runners remain compatible. Unsupported protocol versions appear with
a rebuild message and receive no control binding. Refresh retries connections.
Rebuild/redeploy older runners to enable controls; a matching namespace alone
does not grant control. Main app and updated runner are both required.

## How many APKs?

Current packaging uses one main sensor app plus one runner APK per workspace.
All supported packages, nodes, libraries and launch resources in a workspace
share that runner. Deploying a new build replaces the existing install. Put
related packages in one workspace to share one runner. Unified controls do not
remove runner APKs or deduplicate their ROS/Python runtime libraries.

A shared runtime for independently deployable workspaces needs another packaging
and executable-loading design. Other pending features: launch selection/editor,
parameter editor, per-node supervision, project removal, automatic restart and
persistent logs. Physical verification of this control protocol currently covers
one ordinary C++ workspace on the reference phone; all three runner variants
build, but simultaneous multiple-workspace/device-version trials remain pending.

For host manifest inspection, runtime ABI combinations and management-version
limits, see the [project compatibility contract](PROJECT-CONTRACT.md).
