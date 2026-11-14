# Pre-release security and privacy review

Scope: tracked source and full Git history, the main app and runner manifests, network
listeners, signing/control boundaries and bundled dependencies, reviewed on 2026-11-12
for the development preview and re-scanned on 2026-11-14 for 1.0.0-beta.1. This is a maintainer review, not an independent audit or
penetration test. Report issues as described in [SECURITY.md](../SECURITY.md).

## Repository and history

| Check | Result |
| --- | --- |
| Private keys, keystores, certificates or token patterns (GitHub, cloud, chat, API keys, inline signing passwords) in any of the 82 commits | None found |
| Private network addresses in added lines of any commit | Only synthetic test values (`192.168.1.2/3`, `1.2.3.4`) |
| Personal emails, MAC addresses, home paths or device serial numbers in tracked files | None; examples use `example.invalid` |
| Device model strings | A reference phone model appears in early README/verification history; it names a product, not a unit |
| Local journals, device logs, screenshots, build outputs | Ignored by `.gitignore`; older journal versions remain in history (owner decision whether to rewrite) |

Re-scan for 1.0.0-beta.1 (2026-11-14, all 108 commits, every added line): no private
keys, keystore passwords, token patterns, private IPv4 or MAC addresses, and neither
reference phone's ADB serial number. Email-like matches were Python decorators. One
early commit's `.vscode/settings.json` contained a local checkout path with the
maintainer's Linux account name; the current file is clean. Older versions of the
maintainer's development journals remain in history by owner decision.

Removing a file from the current tree does not remove it from history. Review history
again before changing repository visibility or creating release tags.

## Android components

| Component | Exposure | Control |
| --- | --- | --- |
| Main `MainActivity` | Exported launcher | Starts nothing automatically |
| Session, Agent and programming services | Not exported | Started only by the app's own UI |
| Runner `ProjectControlService` | Exported | `signature` permission `MANAGE_PROJECTS`; per-request UID/package checks |
| Runner `PairedProjectControlService` | Exported only in opted-in builds | Pinned controller certificate and local approval per request |
| Runner `MainActivity` | Exported without launcher entry | Opening it never starts a project |
| Debug receivers, probes and preview Activities | Debug builds only | `DUMP` permission (ADB shell); verified absent from the release manifest |

Backups are disabled. There is no WebView, file provider or HTTP client. PendingIntents
are immutable except Android's USB permission request, which is mutable and explicitly
targeted at the app's own package. Runtime receivers are registered not-exported on
Android 13+.

Android 13+ hides foreground-service notifications until the user allows notifications.
Those notifications carry each session's **Stop** action, so the main app and runners now
declare `POST_NOTIFICATIONS` and ask once when the user first starts a session or project.
Services run either way; Settings keeps the choice.

## Network listeners and ROS traffic

- DDS traffic is unauthenticated and unencrypted. The native build disables DDS Security
  (`ENABLE_SECURITY=OFF`), so SROS 2 is not available. A domain ID only separates
  discovery. Use a trusted network and never expose DDS to the Internet.
- **Wi-Fi micro-ROS Agent:** while it runs, it accepts XRCE-DDS clients on UDP 8888 on every
  phone interface. Any device on that network can then create publishers and subscribers
  in the phone's ROS graph, including topics a robot obeys. Start it only on a network you
  control, stop it when not needed, and prefer the USB transport for robots.
- Project control is Binder-only (no network port). The optional workspace shell and
  programming commands require an authorized ADB connection.

## Dependencies

- Upstream license texts for every bundled native component now ship in the APKs; see
  [third-party notices](../THIRD-PARTY-NOTICES.md). Two Agent components previously shipped
  without their terms; this is fixed.
- An [OSV](https://osv.dev) query of all 29 pinned ROS 2/Cyclone DDS source commits and the
  reference micro-ROS Agent, Fast DDS, Fast CDR, foonathan memory and spdlog revisions
  reported no known vulnerabilities. OSV commit coverage for C/C++ projects is incomplete,
  and vendored code such as libyaml 0.2.5 is not commit-addressable, so repeat the check
  for each release and watch upstream advisories. There is no automated dependency
  alerting yet.
- The Agent superbuild selects Fast DDS/Fast CDR by branch, so a rebuild can pick up newer
  code than the reviewed revisions; record actual revisions for each release.

## Open items before wider publication

- Owner decisions: release-key custody and rotation plan ([releasing](RELEASING.md)),
  whether to rewrite history, and repository visibility.
- Independent-runner signing, update, revocation and key-rotation review remains a
  separate roadmap gate ([authorization](RUNNER-AUTHORIZATION.md)).
- Automated dependency vulnerability alerts and an option to bind the Wi-Fi Agent to one
  interface are not implemented.
