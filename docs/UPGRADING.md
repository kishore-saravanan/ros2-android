# Upgrade the app and project runners

The host client, main Android APK, each project runner and the native SDK have
separate versions. Updating the client does not update the phone. Updating the
main APK does not rebuild or replace installed runners. Keep the previous working
artifacts and their source/configuration until a replacement is verified.

This guide describes the preview's conservative same-current-certificate path.
Signing-key rotation, multiple current signers, Android rollback mechanisms and
app-data migrations are not qualified. See [Status](STATUS.md).

## Inspect artifacts before connecting a phone

The installed client can inspect files without ADB, a running ROS installation,
SDK extraction or a project build. It needs Android SDK build-tools 35.0.0 and
Java for `apksigner`, as listed in [Compatibility](COMPATIBILITY.md).

```bash
ros2-android apk inspect /absolute/path/previous.apk
ros2-android apk inspect /absolute/path/replacement.apk
ros2-android apk compare /absolute/path/previous.apk /absolute/path/replacement.apk
```

For another SDK location, set ANDROID_HOME or use
`ros2-android apk --sdk /absolute/path/Android/Sdk inspect /absolute/path/app.apk`.
From a source checkout, the equivalent entry point is
`PYTHONPATH=python python3 -m ros2android.cli apk ...`.

Inspection verifies the signature with `apksigner` and reports package name,
version code/name, API levels, native ABIs, debug flag, current public certificate
fingerprint and file SHA-256. It accepts only the main/runner package namespace.
Split APKs are rejected; this workflow handles standalone APKs only.
It does not execute APK code or read a phone. A checksum identifies bytes; it does
not establish that an author is trustworthy. Verify the artifact's provenance.

Comparison reports:

| Decision | Meaning | Exit code |
| --- | --- | --- |
| `identical` | Both verified files have the same hash | 0 |
| `candidate` | Same package/current certificate, nondecreasing numeric version code | 0 |
| `blocked` | Different package/certificate or a lower version code | 1 |
| JSON `error` | Missing/malformed artifact, failed verification or tool problem | 2 |

`candidate` is not an installation guarantee. The tool does not inspect the
installed app, Android API/ABI support, signing lineage, data compatibility or
runtime behavior. Compare against the exact APK previously installed, not merely
one with the same display version. `versionName` is a label; numeric version code
controls ordering. Inspection reads the typed manifest as well as badging, since
build-tools 35 badging omits `versionCodeMajor`. Local debug rebuilds currently reuse version codes, so a changed
hash at the same code can still be a candidate. Releases must increment codes.

## Preserve signing identity

Android signs app updates; changing the machine's debug key can prevent replacing
an existing install. Preserve the original signing key securely if you intend to
continue that local installation. An APK or public certificate cannot recreate its
private key. Keep keystores/passwords outside Git and SDK/client artifacts, and
back them up separately through your own secure storage process. Do not send
passwords or private keys to other contributors.

For community use, prefer [independently signed runners](RUNNER-AUTHORIZATION.md):
the runner pins the main APK's public certificate through `--controller-apk`.
Contributors keep their own runner key. They do not need the maintainer's private
key. Same-key updates normally retain the preview's local approval; a main-app
current-certificate change requires rebuilding the paired runner's controller pin.
A runner current-certificate change invalidates that runner's prior approval and
needs review; Android package replacement must also permit the signer change.
The preview does not provide that signer migration automatically.

## Replace deliberately

1. Stop project nodes, sensor/Dashboard sessions and the Agent that are affected.
   Save their domain, namespace, optional addresses, ROS arguments and intended
   launch. Preserve source plus exact working APKs before building over outputs.
2. Inspect/compare the old and new artifacts. Resolve blocked results; do not
   uninstall a working app, clear its data or force a downgrade as a workaround.
3. Install the main APK with the existing documented
   [installation route](GETTING-STARTED.md), and deploy rebuilt runners through
   `ros2-android workspace --workspace /absolute/path/my_ws deploy` or the
   corresponding project command. Select the intended phone explicitly if needed.
   Deployment is a separate action; `apk compare` never deploys.
4. Reopen ROS2 Android → Projects → Refresh. Review authorization if prompted.
   Check saved settings before starting; a matching package or successful install
   does not prove profile preservation or compatible ROS code.
5. Start explicitly. Observe advancing application results on Dashboard or an
   independent ROS peer. Check Stop and start again. Reapprove USB access if Android
   requests it after an update. Follow [standalone checks](STANDALONE-OPERATION.md)
   when the system must work without the PC.

Installing a replacement may terminate the app's processes. The current protocol
can report a runner update/disconnect; Refresh creates a new control connection.
Pending start/stop/configure commands are not automatically replayed. A lost reply
leaves the outcome unknown, so read actual state before issuing another command.

## Recover and roll back

If an installation fails, retain the current install and read the failure. A
signing mismatch needs the original signing identity or a separately planned
migration. A downgrade error needs a deliberate version/release plan. The host
helper currently calls `adb install -r`; it does not automatically uninstall,
clear app data or retry with downgrade flags.

An older APK is not a guaranteed rollback: Android version policy and newer app
data can block it or make it incompatible. Prefer a forward fix signed by the same
key with a newer version code. Keep older source/artifacts for reproduction; use
a disposable test phone/fixture to qualify actual rollback and data compatibility.
There is no generic app-profile export/import or automatic data rollback yet.

## Remaining device qualification

Use disposable talker fixtures, not actuator projects. For same-key replacements,
record before/after source IDs, accepted profile, local trust, fresh ROS result
sequences and stop/restart behavior. Then separately test main update, runner update
while bound, signing mismatch, revoked approval, uninstall/reinstall and rollback.
The file-level comparisons and host tests do not mark these physical cases passed.
Never change another app or personal data to perform them.

Official references: [Android app signing](https://developer.android.com/studio/publish/app-signing),
[Android versioning](https://developer.android.com/studio/publish/versioning) and
[apksigner verification](https://developer.android.com/tools/apksigner).
