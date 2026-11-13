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
  Only tracked Python sources enter the host package. SDK ZIP entries use fixed
  timestamps and canonical executable modes so checkout mtimes do not change
  their identity. This is not a claim of reproducible APKs or whole wheels across
  different compilers, Python or compression-library versions.
- Verify package contents, checksums and supported-device checks. Record source,
  toolchain and SDK provenance. Review dependency licenses, NOTICE and citation.
- Check that first-topic, external workspace and robot-operation guides match
  those exact artifacts. Identify prerequisites without assuming a maintainer PC.
- Inspect artifacts and tracked source for local journals, credentials, private
  device data and debug outputs. Review historical Git content before changing
  repository visibility; removing a file from HEAD does not erase history.
- Publish known limits and test conditions with release notes. Do not infer
  real-time guarantees, endurance or all-device support from a demonstration.

## Assemble a release folder

These commands prepare, sign and verify artifacts locally; they publish nothing.
Run them from a clean checkout of the tagged revision with the prepared SDK:

```bash
python3 setup/stage-licenses.py            # also part of setup/build-native-ros.sh
python3 setup/generate-notices.py --check  # THIRD-PARTY-NOTICES.md matches the locks
./setup/build.sh :app:assembleRelease :app:lintVitalRelease
python3 setup/build-python-package.py      # bundled host wheel, from a venv with pip
# Prebuilt native SDK: run the "Native Android SDK build" workflow on the release commit and
# download its ros2-android-native-sdk artifact (built on a clean runner, install-tested there).
export ROS2_ANDROID_KEYSTORE_PASSWORD=...  # never on the command line
python3 setup/assemble-release.py --apk android/app/build/outputs/apk/release/app-release-unsigned.apk \
  --wheel dist/ros2_android-0.1.0a1-py3-none-any.whl \
  --sdk ~/Downloads/ros2-android-native-sdk/ros2-android-native-sdk-0.1.0a1-arm64-v8a.tar.xz \
  --keystore /offline/ros2-android-release.jks --key-alias ros2-android --out dist/release-0.1.1
python3 setup/verify-release.py dist/release-0.1.1 --expect-certificate <published SHA-256>
```

The assembler refuses a dirty tracked tree, a debuggable APK, a version that
differs from the compatibility matrix and an APK without license texts for every
staged component. It aligns and signs the APK with `apksigner` (v2/v3 schemes for
API 26+), checks the single signer, copies LICENSE, NOTICE,
[THIRD-PARTY-NOTICES.md](../THIRD-PARTY-NOTICES.md) and CHANGELOG, and writes
`release-manifest.json` (source revision, versions, toolchain pins, SDK snapshot,
signer digest and schemes) plus `SHA256SUMS`, which `sha256sum -c` also accepts.
With `--sdk`, the assembler accepts the prebuilt SDK archive only when it was packaged
from a clean tree, for the same client version and from this revision's pinned build
inputs, and records its source revision; the verifier re-checks the archive manifest.
Use the CI archive: binaries keep their build path in debug strings, and CI's path is
neutral where a maintainer's home folder is not.
To include a Debian package, build it from the signed APK in the release folder
and pass `--deb` in a second assembly; the assembler rejects a package bundling any
other APK. `--unsigned-dry-run` exercises the flow without a key and is marked as
not a release; the verifier rejects it unless `--allow-unsigned` is given.

Signing key custody is a maintainer decision made before the first public APK:

- Generate a long-lived key (for example RSA 3072/4096 with `keytool`) on a trusted
  machine, keep the keystore and passwords outside the repository and CI logs, and
  keep an encrypted offline backup. Losing it prevents updates to installed apps.
- Publish the certificate SHA-256 alongside releases so users and
  `verify-release.py --expect-certificate` can check the signer.
- Android only updates an app signed by the same key, or by a rotated key that
  carries a v3 rotation lineage (`apksigner rotate`). Plan rotation before a key is
  suspected compromised; a different key otherwise needs uninstall/reinstall.
- Runner APKs are signed by whoever builds them. They must share the controller's
  signature or use [independent runner authorization](RUNNER-AUTHORIZATION.md).

APT repository hosting/signing, Play Store requirements, public visibility and
artifact publication are separate explicitly authorized steps. Adding this
checklist or a CI workflow does not perform them.

Use [APK inspection and upgrade checks](UPGRADING.md) when comparing release
artifacts. File compatibility is not proof of a successful on-device upgrade.
