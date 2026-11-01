# ROS2 Android: maintenance and delivery plan

Status: proposal, 2026-11-01. No repositories have been created or code moved.
APT/Play publication stays deferred. Preserve the currently working robot demo
while establishing the platform contracts.

## Current assessment

There is real native ROS interoperability, a host CLI/wheel and Debian alpha,
C++/Python runners, a phone-hosted Agent and an operator-confirmed ground demo.
The architecture already has useful service-owned hardware lifetime and pinned
native dependencies. These are foundations worth retaining.

The next work is operational reliability and developer independence. Current
couplings include RobotSessionService handling hardware plus Dashboard state,
MainActivity handling several product areas, host CLI dispatching cached scripts,
duplicated runner packaging/lifecycle concerns and robot-specific firmware/demo
code living beside platform code. The initial review found no GitHub Actions
workflow; baseline host/documentation and Android JVM/lint jobs have since been
added, with their first hosted execution pending. Existing unit, tooling and
physical tests are retained rather than replaced by a new framework.

## Ordered work and exit gates

| Stage | Work | Exit gate |
| --- | --- | --- |
| 0: preserve evidence | Save working source/artifacts, capture current supported journeys and known faults | Baseline recoverable; user videos linked with consent; no private logs/assets published |
| 1: dependable control/state | Reproduce OFF failure, separate requested/observed state, specify stop scopes and startup/reconnect behavior | End-to-end ON/OFF, lost link and late/restarted subscriber checks pass; missing acknowledgement visible |
| 2: supported runner trust | Document same-key developer path; design/review signer pairing for official APK + user-built runner | Independent author can install/control/upgrade/revoke a runner without maintainer secrets; unauthorized caller rejected |
| 3: modular internals | Extract session/feature controllers and shared runner contracts; move host implementation into Python package | Existing journeys pass; module dependency rules/conformance tests prevent reverse coupling |
| 4: external examples | Create proposed examples repo, move ball follower/motor application with history and metadata | Fresh checkout builds using documented platform package/SDK without platform source edits |
| 5: qualified community alpha | Reproducible artifacts, operator docs, second phone and soak/failure tests | Clean-machine install/deploy/rollback and published compatibility/evidence matrix pass |
| Later | APT publication, additional adapters/services/actions UX and broader device support | Core release gates stay green; each extension has a user journey and evidence |

Stages can overlap in documentation/tests; avoid combining behavioral fixes,
large file moves, signing changes and UI redesign in one PR. The unfinished raw
sensor_msgs/Image Dashboard subscription remains a planned feature, after the
control/reliability gates rather than ahead of them.

## Minimal contributor structure

Keep the platform repository together. Proposed public docs: current Architecture,
HLD, LLD, supported configurations, getting started, external workspace guide,
troubleshooting, CONTRIBUTING, SECURITY reporting, release checklist and changelog.
Use short architecture decision records for consequential choices, starting with
[platform/examples separation](../decisions/0001-platform-and-examples.md).

Three documentation journeys:

1. Operator: install APK/client, select connection, start components, observe,
   stop and recover. Optional robot examples are linked separately.
2. ROS developer: inspect/build/deploy an ordinary external workspace; understand
   ABI/dependency limitations, runner trust, logs, ROS CLI and updates.
3. Contributor: build/test the platform, understand module ownership and submit
   focused changes. Main README should route to these journeys rather than grow
   into a chronological project notebook.

Local handoff notes, device addresses/identifiers, recordings, firmware backups,
signing keys and raw logs stay outside Git. Public design and decisions belong
in the repository. Do not publish the user's new videos without explicit consent.
Document what was measured separately from what an operator reported.

## CI and release discipline

Start with PR jobs for host unit/contract tests, native guard/codec tests, Android
unit/lint/assembly and documentation links. Run expensive pinned native SDK builds
in a separate cached workflow; never treat a missing SDK as a passing integration
test. Add wheel/Debian content/install smoke tests in clean environments.
Physical-device qualification remains an explicit operator-controlled test job;
public PR code must not run unattended against a powered robot.

One compatibility manifest links CLI, main APK, runner management protocol,
runtime ABI, native SDK and supported example revisions. Publish checksums,
provenance and required third-party notices with each artifact. No floating
upstream dependency branches in released builds. Signing keys stay private;
user-owned development keys require documented backup/upgrade behavior.

Start with one maintained release line and a changelog. Document protocol-major
breaks and migration instructions; do not promise indefinite ABI compatibility.
Keep the previous installable artifact and profile for rollback. APT release can
follow the same verified artifacts once the runtime/trust journey is sound.

## Simplicity rules for the maintainer

- Two repositories initially: platform and examples. No split per sensor/backend.
- One public host command family, ros2-android; compatibility wrappers can forward
  during migration. Setup scripts stay contributor bootstrap, not business logic.
- One canonical accepted state per component; UI preferences are not remote truth.
- One owner per camera/context/process group/USB connection.
- One bounded queue per streaming stage; prefer dropping stale work to latency.
- Shared schemas and lifecycle helpers only where multiple implementations need them.
- Small PRs with behavior and verification explained; no large rewrite for patterns.
- New features must name their owner, data contract, failure behavior and test gate.

First implementation slice (2026-11-01): a bounded latest-request queue and local
Switch request status are implemented. Pure regressions and an independent
phone/PC ROS test cover OFF, navigation and no restart replay; see
[physical test instructions](../../tests/integration/README.md). Original missed
OFF root cause, network-loss/late-subscriber checks and integrated observed-state
binding remain open. Stage 1 is partially implemented, not qualified complete.
Resolve these reliability gaps and the runner signing path before an official
installable community release.
