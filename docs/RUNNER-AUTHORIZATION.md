# Workspace runner authorization

The main app and a workspace runner do not need to share a private signing key.
There are now two control paths. This is a beta implementation;
review and broader device/signing qualification remain release gates.

## Existing same-signature builds

Without a controller pin, runners keep the original signature-protected control
service. Main app and runner must share a signing identity. The optional paired
service is disabled. Existing installations and local debug workflows still work.

## Independently signed runner

Keep the exact main APK you installed on the phone. Use it as a public identity
input when building your workspace:

```bash
ros2-android workspace --workspace /absolute/path/to/my_ws build \
  --controller-apk /absolute/path/to/ros2-android.apk
ros2-android workspace --workspace /absolute/path/to/my_ws deploy
```

The source-checkout equivalent is `./tools/ros2android-workspace`. For standalone
project manifests, `ros2-android project --project /path/to/project build` also
accepts `--controller-apk`. The flag takes an APK, never a keystore or password.
The host verifies its package name/signature and embeds the single current
certificate's SHA-256 fingerprint into the runner.

Normal workspace builds retain the ROS CLI and require the prepared Python SDK.
For a smaller C++-only smoke test, add `--without-shell`; this omits the on-phone
ROS CLI. Native SDK/NDK and ordinary workspace compatibility prerequisites still
apply. See [workspace deployment](COLCON-WORKSPACES.md).

On the phone:

1. Open **Projects**, then **Refresh** after installation/update.
2. Expand the project's **Details** and review the runner package/certificate.
3. Tap **Authorize runner**, then confirm only if you trust that workspace code.
4. Configure the connection and tap **Start project** explicitly. Authorization
   itself only permits management; it does not start nodes.

Approval is local to that phone and runner package/current certificate. A new
runner signed by another key requires a fresh review. Same-key runner updates
retain approval, so only install updates from a trusted author. A current-signer
change on the main APK requires rebuilding the runner with the new controller
APK. Multiple signers/automatic certificate-rotation migration are not supported.
A mismatched pin shows **Rebuild runner for this controller APK**.

## Revocation and boundaries

Stop a running project first, then use **Details → Revoke authorization**.
Revocation removes the main app's local permission and closes its connection;
it does not kill a running workspace. The runner's own screen remains its
independent control surface. Re-authorize to restore main-app control.

The opt-in paired Binder entry is exported because Android signature permissions
cannot grant access across arbitrary independent signers. It is not an anonymous
command API: before reading request data or replying, every request checks the
actual Messenger sender UID, its exact owning package and the compiled controller
certificate. The main app verifies the runner's current certificate approval and
UID before accepting replies, and rechecks authorization before sending commands.
A shared UID, missing identity or invalid pin is rejected. No LAN shell endpoint
or new ROS data format is introduced. The old signature-protected entry remains.

Approval trusts the runner author/code; it is not a sandbox or a guarantee of
benign behavior. Device installation, signing-key storage and ROS network trust
remain separate concerns. Never distribute the maintainer's private signing key.
See [the design decision](decisions/0002-runner-certificate-authorization.md).

For file-level identity checks and same-key update/rollback procedures, see
[Upgrading](UPGRADING.md). Comparing APKs does not authorize a runner or install it.

## Signer changes, reinstalls and key rotation

Both checks use the **current** APK signer only (`SigningIdentity.current`): the runner
compares the main app's current certificate with its compiled pin, and the main app
compares a runner's current certificate with the locally approved one. Packages with
several signers are rejected.

| Change | Result | What to do |
| --- | --- | --- |
| Runner update signed by the same key | Approval kept; controls reconnect after Refresh | Install updates only from that author |
| Runner reinstalled after uninstall, same key | Approval kept (stored per package and certificate in the main app) | Revoke first if you no longer trust it |
| Runner signed by a different key | Approval no longer matches; controls refused | Review the new certificate and authorize again, or reject |
| Main app reinstalled after uninstall | All approvals are lost with its data | Authorize each runner again |
| Main app signed by a new key, with or without a v3 rotation lineage | Every paired runner shows **Rebuild runner for this controller APK** and refuses requests | Rebuild runners with the new main APK as `--controller-apk` |
| Approval revoked | Main app stops controlling the runner; a running project keeps running | Stop first; the runner screen still controls it |

Android 9+ can check a package's signing *history* (`PackageManager.hasSigningCertificate`)
so that a v3 rotation keeps trusting older keys. The runner deliberately does not: a key is
usually rotated because it should no longer be trusted, and accepting the history would let
any APK signed with the old key keep controlling runners. Rotating the main app's key is
therefore a planned migration:

1. Create the new key and a rotation lineage with `apksigner rotate`, and sign the next
   main APK with `--lineage` so installed copies can update.
2. Rebuild and redeploy every independently signed runner with that APK as
   `--controller-apk`, then authorize them again where their certificates changed.
3. Keep the old key offline; destroy it once no runner pinned to it remains.

A compromised **runner** key lets its holder ship updates that keep their approval, and a
compromised **main app** key lets a look-alike app control every runner pinned to it. Both
need the attacker to install the APK on the phone (ADB or sideloading). Revoke approvals,
rebuild runners with a new pin and reinstall from trusted sources.

## Actual verification and remaining work

Reference-device tests used a separate disposable signing key, a three-package
external C++ workspace, and no camera or actuator topics. They covered rejection
before approval, changed-fingerprint rejection, configure/start/two owned nodes/
stop, Activity recreation, local revocation and an isolated unauthorized UID with
no response. Existing same-signature profile validation also passed. An independent
Linux ROS observer received five processed messages from the Android workspace.

An installed local wheel inspected/cross-built/deployed that external workspace
from outside the checkout, reusing the prepared native SDK cache. Its runner was
also exercised on-device. This is not a fresh-machine or public-release result.
The test runner used `--without-shell`; full-shell independent-signing and Python
runners still need physical qualification. Their shared Kotlin control code
compiles, but compilation is not runtime evidence.

Pending: API 26/other Android versions, a rotation drill exercising the table above,
multiple independently signed authors, adversarial Binder/load review, recovery
and broader accessibility/theme testing. Keep this a preview until those gates
are qualified. Reproduction notes are in [Testing](TESTING.md).
