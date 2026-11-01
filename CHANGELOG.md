# Changelog

Changes below are unreleased source changes, not a published APK/package promise.
Host-client, app, runner protocol and native SDK versions are tracked separately;
see the [release checklist](docs/RELEASING.md).

## Unreleased

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
