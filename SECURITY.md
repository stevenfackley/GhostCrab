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

- Auth tokens are stored exclusively in `EncryptedSharedPreferences` (AES256-GCM); the master key lives in AndroidKeyStore and never leaves the device
- The bearer token is attached only to requests whose host, port and scheme match the configured gateway origin — never to a redirect target or any other host
- `Authorization` headers are stripped from Ktor HTTP logs, and that logging exists in debug builds only; release builds strip `Log.d` call sites entirely
- `PrivacySafeUncaughtExceptionHandler` scrubs bearer tokens, URL credentials, `token=` / `api_key` fields and IPv4 addresses before any crash report is written
- App data is excluded from Android cloud backup and device-to-device transfer (`allowBackup="false"` + `dataExtractionRules`)
- HTTP-mode connections display a persistent amber warning banner; the app does not silently downgrade security. Cleartext HTTP to a public IP literal is blocked unless the user opts in under Settings → Security
- Secret scanning and push protection are enabled on this repository via GitHub Advanced Security
