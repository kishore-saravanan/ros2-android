# ADR 0002: opt-in certificate-pinned runner management

Status: implemented preview, 2026-11-01; security review and broader qualification
pending. This decision replaces the same-private-key requirement for opted-in
runners, without removing legacy same-signature controls.

## Context

A prebuilt main app and community-built workspace runners cannot share the
maintainer's private signing key. Keeping that requirement would prevent the
external workspace journey. Removing the permission and accepting any caller
would expose start/stop/configuration and logs to unrelated apps.

## Decision

Keep the existing signature-protected service. Enable a separate paired service
only when the builder supplies a verified main APK using `--controller-apk`.
Embed its current public signing-certificate SHA-256. Both services share the
same narrow status/start/stop/configure implementation, with different caller
policies. The paired handler authenticates each request by Messenger UID, exact
package ownership and the compiled certificate before reading data or replying.

The main app approves each independent runner's package/current certificate in a
local operator confirmation. It checks approval/identity for sends and replies.
Approval is not auto-start. Revocation closes the connection, not the runtime.
Package visibility is declared narrowly for the known controller/project action;
there is no QUERY_ALL_PACKAGES request. Current single-signers only are supported.
Signer changes require explicit re-approval/rebuild; there is no rotation shortcut.

Use the platform-provided [Messenger sender UID](https://developer.android.com/reference/android/os/Message#sendingUid)
and [current APK signers](https://developer.android.com/reference/android/content/pm/SigningInfo#getApkContentsSigners()).
Declare [package visibility](https://developer.android.com/training/package-visibility/declaring)
for packages participating in this relationship. These API choices do not replace
a security review or prove that all Android versions behave identically.

## Alternatives and consequences

A shared published debug/private key defeats signer separation. A dynamic
permission/known-cert manifest cannot cover arbitrary future user signers across
our API range. A network management server or general shell expands the attack
surface and is unnecessary for this step.

The paired Binder entry can be bound by an unrelated app, so request-level
identity checks are mandatory and default-deny. A debug-only isolated-process
probe verifies that possessing the Binder does not grant status access. Flooding,
revocation/update races and certificate-rotation/reinstall scenarios still need
review. Existing signed developer builds retain their original implicit trust.
See [usage and evidence](../RUNNER-AUTHORIZATION.md) for exact verified scope.
