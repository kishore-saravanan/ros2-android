# Community release checklist

No stable public package/APT release is claimed. Treat this as a maintainer gate,
not a command that publishes automatically. A release needs explicit authorization.

## Source and contracts

- Identify the tested source revision and supported ROS/Android/ABI/dependency
  combinations. Reconcile host package, main APK, runtime and runner versions.
- Pass baseline CI plus native SDK/packaging checks. Record physical results and
  skipped/pending tests separately; include a clean-machine external workspace.
- Resolve or clearly scope independent runner signing/trust before claiming that
  an official APK supports arbitrary user-built projects. Never distribute keys.
- Keep the earlier installable artifact/configuration for rollback. Explain any
  manifest, management protocol or ABI migration.

## Artifacts and documentation

- Build APK/SDK/host packages from the recorded source and pinned dependencies.
  Use `setup/build-python-package.py` for the bundled wheel and the documented
  Debian packaging path; ordinary pip wheel alone omits required backend content.
- Verify package contents, checksums and supported-device checks. Record source,
  toolchain and SDK provenance. Review dependency licenses, NOTICE and citation.
- Check that first-topic, external workspace and robot-operation guides match
  those exact artifacts. Identify prerequisites without assuming a maintainer PC.
- Inspect artifacts and tracked source for local journals, credentials, private
  device data and debug outputs. Review historical Git content before changing
  repository visibility; removing a file from HEAD does not erase history.
- Publish known limits and test conditions with release notes. Do not infer
  real-time guarantees, endurance or all-device support from a demonstration.

APT repository hosting/signing, Play Store requirements, public visibility and
artifact publication are separate explicitly authorized steps. Adding this
checklist or a CI workflow does not perform them.
