# Runner executor supervision — 2026-10-25

Follow-up to the C++ runner milestone, on the same physical Android 12 ARM64
phone and ROS 2 Jazzy PC. Development source fingerprint: 62878beb0974.

Implemented executor monitoring and explicit failure cleanup. An exception in a
user callback is caught by the native executor host, surfaced as a failed state,
and followed by native context teardown, foreground-service removal, and lock
release. The failure is retained in process memory until the next lifecycle
transition. No automatic restart policy is enabled.

Physical recovery test (`tools/test-runner-recovery.py`) passed:

- Opt-in demo callback threw after three ticks.
- Status reported `Failed: Requested demo callback failure`, busy false,
  running false, empty native node name and the matching native error.
- Scoped Android service inspection confirmed ProjectService was removed.
- Explicit restart reached Running, cleared the native error and advanced the
  executor heartbeat.
- A separate ROS probe after restart passed status, command echo, raw IMU,
  computed magnitude, and custom-message checks.

Runner assembly/lint passed. Python compilation, shell syntax and Git whitespace
checks passed. Wake-lock release follows service cleanup; it was not measured
with a separate power instrument. This test covers a caught callback exception,
not a fatal native signal, ANR, blocked callback or physical PC link removal.
The debug CLI also explicitly includes the selected stopped runner package in
its protected broadcasts so status/stop work immediately after installation.
