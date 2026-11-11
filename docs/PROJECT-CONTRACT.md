# Project compatibility contract

A project source manifest, a native runtime ABI and the Android management
protocol are different contracts. None implies that an arbitrary Linux package
can run on Android. The host client now validates source manifests before any
build or phone command. Direct Gradle invocations are contributor internals and
do not use this host validator.

## Inspect before building

For a project with `robot-project.json`:

```bash
ros2-android project --project /absolute/path/to/project inspect
```

From a source checkout, use `./tools/ros2android --project PATH inspect`.
This prints the validated manifest, selected compatibility versions and source
fingerprint. It needs no Android SDK, ROS installation or device, and does not
execute the project's code. It does not verify native dependencies, build output,
launch semantics, signing identity or installed phone compatibility.

For an ordinary colcon workspace use `ros2-android workspace --workspace PATH
inspect` instead. That command inventories supported package types; the workspace
builder generates the runner manifest in its cache. Do not add a manifest to each
ROS package. See [workspace guide](COLCON-WORKSPACES.md).

## Source manifest version 1

Example for a native C++ factory project:

```json
{
  "schemaVersion": 1,
  "id": "my_project",
  "applicationId": "dev.ros2android.android.runner.my_project",
  "name": "My project",
  "runtime": "cpp",
  "runtimeAbi": 1
}
```

| Field | Contract |
| --- | --- |
| `schemaVersion` | Integer `1`; booleans and string versions are rejected |
| `id` | Lowercase initial letter, then lowercase letters/digits/underscores; at most 64 characters |
| `applicationId` | `dev.ros2android.android.runner.` followed by a lowercase initial letter and lowercase letters/digits/underscores |
| `name` | Optional nonempty display string |
| `runtime` | `cpp` (also the legacy default), `python` or `workspace` |
| `runtimeAbi` | Integer supported by that runtime, as listed below |
| `defaultLaunch` | Optional project-relative path; requires ABI 2 |
| `pythonSourceDir` | Optional project-relative source path |
| `launchComponents` | Optional object mapping `package/executable` to component identifiers |

Relative source paths cannot be empty, absolute or contain parent traversal.
This is input validation, not a filesystem sandbox for untrusted projects.
Additional fields are preserved to allow additive build metadata. Unknown schema,
runtime and ABI versions fail explicitly; install a matching client/SDK rather
than changing a version number to bypass the check.

| Runtime | Supported ABIs | Meaning |
| --- | --- | --- |
| `cpp` | 1, 2 | Prototype native factory; ABI 2 supports component launch plans |
| `python` | 1 | Embedded Python project backend |
| `workspace` | 2 | Cross-built ordinary C++ executables supervised as child processes |

These numbers are independent of the host package version, Android app version
and pinned native SDK revisions. Changing one version does not upgrade the others.
A syntactically valid manifest is only the first validation step: launch
resolution, native build, dependency closure, source matching and device checks
still run at their respective boundaries.

## Management protocol

Installed runners currently advertise `dev.ros2android.PROJECT_PROTOCOL = 1`.
The main app discovers only version-1 compatible metadata. Its authenticated
Messenger interface offers status, start, stop and configure. See
[authorization](RUNNER-AUTHORIZATION.md) and [project controls](PROJECTS.md).

Updated services and controllers use an additive version-1 Messenger envelope:

- Each new request has integer `protocolVersion = 1` in its Bundle.
- Each new reply has integer `protocolVersion = 1` and an integer-array
  `supportedCommands`. Commands are status (1), start (2), stop (3), configure (4).
- Authorization by UID/package/certificate happens before reading request or
  response contents. Protocol fields are compatibility data, not credentials.
- The service rejects an explicit unsupported or malformed version and unknown
  command before starting, stopping or saving settings. Unauthorized callers
  still receive no reply.
- The controller obtains status/capabilities before sending a state-changing
  request. Unadvertised commands stay disabled. Unknown additive capability IDs
  are ignored; malformed arrays or versions fail closed.

For existing authenticated version-1 runners, replies without either envelope
field retain the original four-command behavior. New services also accept legacy
requests without a version. This migration path applies to version-1 installed
metadata; it is not a fallback from an explicit incompatible version.

Otherwise valid runners advertising incompatible management metadata remain
visible in Projects with a rebuild message; the controller does not bind to them.
A bind that never completes now reports a timeout. Refresh closes old connections
before retrying. Invalid identities or malformed discovery metadata are still
excluded. Compatibility failures do not stop a running project automatically.

Pure contract tests and compilation cover this envelope. Physical updated/legacy
Binder interoperability and incompatible-runner presentation still need dedicated
qualification; existing DDS tests do not establish these new checks.

## Dependency and platform limits

Workspace inspection currently accepts `ament_cmake`; it is not a dependency
solver. A dependency installed in Linux through apt/rosdep is not evidence that
an Android build exists. It must be in the target SDK or built from compatible
workspace sources. Linux-only libraries, drivers and services need replacements
or explicit Android ports. Mixed `ament_python` workspaces remain unsupported.

When build/deploy fails:

1. Fix the first manifest/package/launch error before retrying a device command.
2. For a missing CMake package, check the Android underlay and workspace sources;
   installing another host binary package alone does not fix a target dependency.
3. For missing Android shared libraries, inspect the dependency named by the
   packager and rebuild it for the target ABI.
4. For missing/stale runner output, complete build and deploy again. Source edits
   and SDK/runtime changes require a new APK.

Dependency reports with target availability, physical compatibility-envelope tests
and fresh-machine qualification remain roadmap work. See [Status](STATUS.md) and
[testing](TESTING.md) for actual verification boundaries.
