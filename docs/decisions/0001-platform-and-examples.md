# ADR 0001: separate platform and robot examples

Status: direction accepted, 2026-11-01. A history-preserving local companion and
installed-client build path are prepared; public publication and final migration
remain pending.

## Context

ROS2 Android is intended to be a maintained robot-computer platform. The ball demo
proves local Python vision, camera subscription, Dashboard images/control and MCU
communication. Its detector, calibration, turn strategy and L298N PWM mapping
are robot/application concerns with a different change cadence from the platform.
A community user must be able to build that application without platform edits.

## Decision

Keep app/runtime/SDK/host tooling and minimal compatibility fixtures together in
ros2-android. Extract robot applications into one companion repository,
ros2-android-examples, starting with the ball follower and its reference motor
firmware/configuration/presets. Keep generic MCU transport checks in the platform.

The examples repository consumes versioned platform artifacts and declares its
compatibility matrix. Platform integration optionally checks out a pinned example
revision. There is no platform import/build dependency on the examples repository.
No mandatory submodule or per-demo repository is required.

## Alternatives

Keeping everything together reduces initial release coordination but encourages
robot-specific changes in platform services/SDKs. A separate ball-only repository
is viable if it becomes an independently maintained product, but creates more
repository overhead than one examples collection today. Splitting the platform
into many repositories would make coordinated ABI/lifecycle changes harder.

## Consequences and migration gate

Maintain two changelogs/compatibility references and a small integration test.
Preserve history, license/notices/citation and working artifacts. Prove the demo
from an external checkout using the installed host client/SDK, then change links
and remove old copies. Do not silently move the working project during planning.
