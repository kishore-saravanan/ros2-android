# ROS2 Android: low-level design

Status: proposed refactoring contracts, 2026-11-01. Names below are proposed unless
explicitly described as existing. Implement incrementally with current behavior
covered by tests; do not replace working services in a single rewrite.

## Start from existing ownership

| Current code | Proposed extraction, retaining an adapter |
| --- | --- |
| MainActivity + ControlDashboard | Thin screen rendering and UI event binding; per-feature presentation state |
| RobotSessionService | Android service host around SessionCoordinator; extract sensor and Dashboard controllers |
| NativeRuntime + native/ros_runtime.cpp | Typed endpoint/runtime adapter with explicit resource handles |
| CameraPublisher / SensorPublishers | Hardware sources with bounded queues, timestamps and lifecycle |
| MicroRosAgentService / UsbSerialBridge / Esp32ProgrammingService | Agent/programming controllers sharing one exclusive USB lease |
| ProjectController / ProjectControlService | Versioned typed management contract with caller authentication |
| ProjectService + native/Python/workspace backends | Shared supervisor contract plus honest backend capabilities |
| python/ros2android/cli.py + tools scripts | Thin public CLI over importable device/build/package/run backends |

Use package separation within existing Gradle modules first. Prefer a few small
interfaces at native, hardware, IPC, clock and process boundaries. Do not create
an interface, factory, repository and use-case class for every button or value.

## State and operation contracts

Separate lifecycle from readiness and telemetry:

```text
Lifecycle = Stopped | Starting | Running | Stopping | Failed
Readiness = Ready | NeedsPermission | DeviceUnavailable | Incompatible | Unknown
ComponentSnapshot = id + generation + lifecycle + readiness + reason
                  + acceptedConfigRevision + lastTraffic + counters
OperationResult = requestId + accepted/rejected + reason + observedGeneration
```

Running means the component is initialized/operating, not that another ROS peer
is publishing data. Traffic can be stale while the service remains Running.
Starting/Stopping are bounded transitions with explicit timeouts and failure
reasons. Stopped is idempotent. Process death reconciles observed state; it must
not silently replay a previous movement/ON command.

Use immutable accepted connection profiles: domain, namespace, interface/peers and
configuration revision. One normal phone session uses one connection profile;
reject conflicting active configuration before mutating resources. Advanced
cross-domain runners, if exposed, must show their distinct profile explicitly.
Both PC and UI starts read the same accepted runner configuration in status.

### Suggested boundary operations

```text
RuntimePort: start(profile) -> handle; snapshot(handle); stop(handle)
EndpointPort: open(descriptor, runtimeHandle) -> handle; close(handle)
ProjectPort: inspect(manifest); start(config); stop(jobId); observe(jobId)
UsbLeasePort: acquire(device, Agent|Programming) -> lease; close(lease)
```

Use typed results rather than success-shaped strings. Narrow handles own their
resources and close once; native RAII and try/finally implement cleanup. Android
permissions remain in Android adapters, not native code or screen constructors.
Keep existing executors/Handler scheduling initially; immutable snapshots can
later be adapted to Flow without a simultaneous UI-framework rewrite.

## Resource ownership and scheduling

| Resource | Owner / execution policy |
| --- | --- |
| UI state | Main-thread presentation adapter; rendering never blocks on DDS/USB/build work |
| ROS context/executor | Runtime adapter; serialize start/stop/configuration transitions |
| Camera capture/encode | Camera adapter; bounded latest-frame slots and worker threads |
| Dashboard image decoding | Subscriber decoder workers; latest-frame replacement, bounded allocation |
| Workspace process(es) | Runner supervisor; track only owned jobs/process groups and exit codes |
| Python executor | Python backend; one embedded interpreter/process with declared node grouping |
| USB connection | Exclusive lease held by Agent or programmer; explicit permission/detach states |
| Logs | Bounded per-component ring/files with export-time redaction |

A stopped generation cannot publish callbacks into a new generation. Cancellation
must drain/ignore old work and release camera, wake/multicast locks, native handles,
file descriptors and child processes. Maintain separate capture, processing and
output counters so discarded stale frames are visible.

Use monotonic local timestamps for intervals and watchdogs. ROS/capture timestamps
remain available for correlation; cross-device age requires known clock alignment.
Report unknown/skewed age explicitly rather than treating all negative age as proof
of a sensor failure. Resource and payload bounds protect memory; they are separate
from user-selected capture rates/resolution.

## Dashboard command versus observed state

The recent OFF incident remains unresolved: the robot reported ON, no OFF sample
was observed during diagnosis, and a PC-issued OFF was processed correctly.
Reproduce the full UI-to-ROS path before attributing the fault to a particular
layer. Trace widget event, enabled/generation guard, queue, native publish, DDS
matching, subscriber callback, application state and UI readback.

Generic Switch publication shows requested value only. Optional state-topic
binding can display Observed ON/OFF/Stale/Unknown separately. Never label an
unacknowledged request as a confirmed remote stop. Applications needing a confirmed
operation can use a ROS service (for example SetBool) plus observed state. Do not
invent mandatory custom envelopes around all standard ROS topic messages.

Management IPC should use a schema version, request ID, expected generation and
accepted profile revision. Specify repeated start/stop and timeout behavior.
Publisher discovery alone does not establish command delivery or application
acknowledgement. Expiring motion commands and MCU watchdogs remain independent.
Do not persist/replay active motor commands or latch ON across reconnections.

## Stop scopes and runtime backends

Document separate stop scopes: sensor endpoint, sensor session, Dashboard motion,
Dashboard session, owned project jobs and Agent. A coordinated robot-session stop
operates on explicitly associated components and reports partial failure. Existing
Stop all behavior currently excludes projects/Agent; do not silently expand it
without changing the UI contract and tests.

Backends report capabilities such as process isolation, launch support and owned
node control. A Python multi-node process cannot promise the same crash isolation
as independent C++ workspace processes. Keep a shared supervisor/result contract
without hiding these differences. Terminal-started jobs need explicit registration
before claiming the supervisor can stop them; never kill by broad process-name
matching. One failed runner should not destroy the main app's camera/Agent session.

Runner management requests use a small pure request tracker and per-request
Messenger reply endpoints. The controller authenticates sender identity before
accepting a matching ticket/command; closed connections and superseded polls
cannot restore stale status. One operator command is outstanding at a time,
with a four-second elapsed-time deadline and no automatic retry. A lost reply
leaves its outcome unknown. Timeout clears controls and unbinds for explicit
Refresh; the runner service continues independently. See the
[management contract](../PROJECT-CONTRACT.md) for legacy compatibility and limits.

The main runtime, C++ factory, embedded Python runner, workspace child processes
and workspace shell share `CycloneNetwork`: a JVM-only startup snapshot and pure
XML policy. Native bridges receive the prepared XML; Fast DDS Agent configuration
remains separate. Loopback is always selected, automatic external selection is
conditional on an up IPv4 interface, and an unavailable explicit pin fails with
recovery guidance. No profile is silently rewritten and no live interface-rebind
or process-restart policy is implied. See [standalone startup](../STANDALONE-OPERATION.md).

## SOLID used pragmatically

| Principle | Concrete use |
| --- | --- |
| Single responsibility | Screens render; controllers coordinate; adapters acquire hardware/native resources |
| Open/closed | Add a real sensor codec or runtime backend through a focused contract and conformance tests |
| Liskov substitution | Backends preserve lifecycle/error semantics and declare unsupported capabilities |
| Interface segregation | Build inspection, deployment, ROS endpoints and USB ownership use separate small ports |
| Dependency inversion | Session logic depends on ports; Android/JNI/process implementations stay at the edges |

Prefer composition, explicit state transitions and ordinary functions for pure
logic. Avoid a generic plugin bus, service locator, deep inheritance or a universal
robot framework. Extract duplication when it represents the same responsibility;
keep similar-looking but differently timed camera/USB operations distinct.

## Tests that support refactoring

- Pure tests: state transitions, profile validation, codec boundaries, queue
  replacement, command expiry and timestamp/skew handling with controlled clocks.
- Contract tests: host/manifest/runtime version mismatches, IPC requests, signature
  rejection, idempotent stop, backend capability differences and error propagation.
- Native/ROS integration: actual typed traffic, QoS compatibility, custom interfaces,
  process exit/restart and bounded memory under slow consumers.
- Device tests: permission denial, USB detach, lifecycle/navigation, process death,
  multiple phones/domains, camera plus workspace plus Agent under load.
- Release tests: clean host install, an external workspace, separately built runner,
  repeatable deployment/rollback and a recorded PC-disconnection interval.

CI simulation/unit success and operator video are useful evidence with different
limits. Record test conditions and measurements; do not convert a video into a
latency, stop-distance or endurance guarantee.
