# Security policy

## Project status

OffGrid Messenger v2.5.6 is a **prototype, not independently security-audited**. It uses shared conversation keys, provisioning/diagnostics capabilities, BLE and ESP-NOW. Do not rely on it for safety-critical communications. No supported security maintenance window or guaranteed response SLA has been established.

## Report a vulnerability

**Do not open a public issue containing exploit details, real keys, NVS dumps, identifying device addresses or private user data.** Before a private contact channel is configured, contact the repository owner privately using an existing trusted channel. Once enabled, prefer GitHub private vulnerability reporting (Security > Advisories > Report a vulnerability). Maintainers should configure that mechanism before accepting public disclosures.

A useful report includes affected release/tag, affected component, reproduction steps with sanitized data, impact and a proposed mitigation (if known). Please allow coordinated disclosure and avoid testing on devices you do not own or operate with authorization.

## Release precautions

Review **all 47 reconstructed release tags**, branches, and Git objects for secrets; scan with a dedicated history-aware tool and manual inspection. Protect Android signing keys and do not erase ESP32 NVS as a troubleshooting shortcut. See [docs/SECURITY_AND_RELEASE.md](docs/SECURITY_AND_RELEASE.md).
