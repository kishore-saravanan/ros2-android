# Security

ROS2 Android is in beta (1.0.0-beta.1). There is no stable release line with a
security-support lifetime yet. Fixes target `develop`; the maintainers cannot
currently promise a response or remediation deadline.

Report suspected vulnerabilities through this repository's GitHub **Security →
Report a vulnerability** option when available. If private reporting is not
available, open an issue requesting a private contact channel **without exploit
details, credentials, network addresses or device information**. Do not publish
sensitive reproduction material in a normal issue.

Useful private reports describe the affected revision/artifact, the trust boundary,
impact and a minimal reproduction using synthetic data. Never send signing keys,
phone backups, personal application data or unredacted device logs.

## Verify a downloaded APK

Official ROS2 Android APKs are signed by a single release key. Its certificate
SHA-256 is:

```
abcaf532edc8a3337a8c17d58d83c87ac76306c39e8a41a4fbddd9e9fe12c69e
```

Check a download with the Android SDK's `apksigner` before installing:

```bash
apksigner verify --print-certs ros2-android-VERSION.apk | grep 'SHA-256'
```

The digest must match exactly. An APK signed by any other certificate did not come
from this project's release process; Android also refuses to update an installed
official app with it.

## Boundaries and limitations


- Android permissions and app isolation remain in effect. Only explicitly
  authorized device deployment/programming is supported.
- Same-signature runner management remains supported. The opt-in independent
  signer path pins the controller certificate and requires local runner approval;
  see [authorization](docs/RUNNER-AUTHORIZATION.md). Review/update/rotation and
  broader device qualification remain release gates. Never share signing keys
  or bypass the per-request UID/certificate checks.
- Ordinary DDS topics are not an authenticated remote administration channel.
  Current network operation assumes a trusted ROS network; do not expose the
  development controls or DDS traffic directly to the Internet. A domain ID is
  discovery separation, not an access-control credential.
- DDS Security (SROS 2) is disabled in the native build, so ROS traffic is neither
  authenticated nor encrypted. While the Wi-Fi micro-ROS Agent runs, any device on
  that network can reach it on UDP 8888 and create ROS entities; prefer USB for robots.
- Debug-only receivers and probes require the ADB `DUMP` permission and are absent
  from release builds. On Android 13+, allow notifications so each session's Stop
  action stays visible.
- A Dashboard request/local write is not confirmed robot state. Actuator-local
  expiry and physical stop mechanisms remain application/hardware responsibilities.

See [architecture](ARCHITECTURE.md), [operating limits](docs/STATUS.md),
[release gates](docs/RELEASING.md) and the [pre-release review](docs/SECURITY-REVIEW.md).
This policy does not claim an independent security audit.
