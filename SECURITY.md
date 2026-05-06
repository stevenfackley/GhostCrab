# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| Latest release on `main` | ✓ |
| Older tags | ✗ |

Only the current HEAD of `main` receives security fixes.

## Reporting a Vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

Use GitHub's private vulnerability reporting:
**Security → Report a vulnerability** on this repository page.

Include:
- Description of the issue and its impact
- Steps to reproduce (device/OS version, app version or git SHA)
- Any relevant logs (redact tokens, IPs, and personal data before sharing)

**Expected response:** acknowledgment within 5 business days, triage within 14 days. Critical issues (token exfiltration, auth bypass) are prioritized.

## Scope

In scope:
- Token leakage via logs, crash reports, or IPC
- Authentication bypass or session fixation against the OpenClaw Gateway
- Insecure local storage (EncryptedSharedPreferences, DataStore)
- Man-in-the-middle attacks on the gateway connection (Ktor/HTTP)
- mDNS/NSD discovery spoofing leading to credential exposure
- Vulnerabilities in shipped dependencies (Ktor, Koin, DataStore)

Out of scope:
- Vulnerabilities requiring physical device access with ADB enabled (assumed-compromised device model)
- Issues in the OpenClaw Gateway server itself (separate repo)
- Bugs without a security impact

## Security Design Notes

- Auth tokens are stored exclusively in `EncryptedSharedPreferences` (AES256-GCM)
- `Authorization` headers are stripped from Ktor HTTP logs at the client layer
- `PrivacySafeUncaughtExceptionHandler` scrubs tokens and IP addresses before any crash report is written
- HTTP-mode connections display a persistent amber warning banner; the app does not silently downgrade security
- Secret scanning is enabled on this repository via GitHub Advanced Security
