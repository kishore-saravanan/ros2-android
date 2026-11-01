# Security

ROS2 Android is a development preview. There is no stable release line with a
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

Current boundaries and limitations:

- Android permissions and app isolation remain in effect. Only explicitly
  authorized device deployment/programming is supported.
- Main-app/runner management currently requires matching signing identity.
  Independent runner trust is an open release gate. Do not share the maintainer's
  signing key or remove authentication to bypass this limitation.
- Ordinary DDS topics are not an authenticated remote administration channel.
  Current network operation assumes a trusted ROS network; do not expose the
  development controls or DDS traffic directly to the Internet. A domain ID is
  discovery separation, not an access-control credential.
- A Dashboard request/local write is not confirmed robot state. Actuator-local
  expiry and physical stop mechanisms remain application/hardware responsibilities.

See [architecture](ARCHITECTURE.md), [operating limits](docs/STATUS.md) and
[release gates](docs/RELEASING.md). This policy does not claim a security audit.
