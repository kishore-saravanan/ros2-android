# Changelog

Changes below are unreleased source changes, not a published APK/package promise.
Host-client, app, runner protocol and native SDK versions are tracked separately;
see the [release checklist](docs/RELEASING.md).

## Unreleased

- Add read-only workspace inspection of declared dependency providers across
  workspace sources, selected Android SDK indexes and Linux ROS indexes. Report
  unsupported package types, unresolved groups, conditions and version constraints
  without treating a manifest scan as proof of Android compatibility.

- Fix Android CI bootstrap by resolving SDK Manager from the Android SDK root
  rather than assuming it is on PATH. Clean-source JVM/lint checks pass locally;
  hosted verification of the corrected workflow remains pending.

- Refresh the community README with the custom logo, real app screenshots,
  a system overview and a staged roadmap with explicit acceptance gates.
  Document media provenance and leave room for maintainer-provided demo videos.

- Add an additive runner-control version/capability envelope with strict rejection
  before state changes, legacy v1 compatibility and capability-gated controls.
  Show incompatible protocol metadata and binding timeouts instead of silently
  hiding a runner or waiting indefinitely. Physical envelope qualification pending.

- Validate source manifest/runtime compatibility before host build or device
  commands; add offline `project inspect` and actionable missing-build errors.
  Document the source/ABI/management contracts and remaining IPC negotiation gate.

- Add opt-in independently signed runner controls: pin the verified controller
  APK public certificate at build time and approve/revoke each runner locally.
  Keep legacy signature controls; authenticate requests/replies by actual UID
  and current certificate. This remains a preview pending security/device gates.
- Verify an installed client building/deploying an external C++ workspace using
  the prepared SDK cache; fix controller binding lifetime across Activity recreation.

- Document platform architecture, ownership boundaries and staged maintenance.
- Make the README, contributor guides and roadmap platform-focused; keep robot
  applications as optional proof-of-concept examples pending external extraction.
- Add baseline host/documentation and Android JVM/lint CI. Hosted execution pending.
- Show Dashboard switch requests as queued, written locally or inactive, with
  remote state explicitly unknown. Guard command completion and restart replay;
  add queue and independent phone/PC regression coverage.

The existing development preview is described in [Status](docs/STATUS.md).
Earlier development history remains in Git; it has not been reconstructed into
unverified release notes. There is no stable release yet.
