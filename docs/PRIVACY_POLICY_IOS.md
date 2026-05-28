# Privacy Policy — GhostCrab for iOS / iPadOS / macOS

**Effective date:** 2026-05-28
**Developer:** Steve Ackley
**Contact:** support@getghostcrab.com
**App:** GhostCrab — Remote Client for OpenClaw Gateway
**Bundle Identifier:** com.qavren.ghostcrab
**Platforms:** iOS 18.0+, iPadOS 18.0+, macOS 14.0+ (Mac Catalyst)

---

## 1. Overview

GhostCrab is a thin native client for OpenClaw Gateway, an open-source AI agent and LLM orchestration platform you self-host. The app does **not** collect, transmit, or sell personal data to third parties. There are no analytics SDKs, no advertising networks, no crash reporters, and no cloud backend operated by the developer.

The app exists to let you reach a gateway **you own and operate**. All network activity originates from explicit actions you take in the UI.

---

## 2. Data Stored on Your Device

| Data | Where | Why |
|------|-------|-----|
| Gateway URL(s) | `UserDefaults` (standard) | Remember connection profiles across launches |
| Profile display names | `UserDefaults` (standard) | User-set label for each saved gateway |
| Last-connected timestamp | `UserDefaults` (standard) | Display "last seen" in the profile list |
| Bearer token(s) | iOS Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) | Authenticate to your gateway without re-entering the token every launch |
| Onboarding progress | `UserDefaults` (standard) | Resume a partially completed first-run setup |
| Security toggle (allow cleartext HTTP to public IPs) | `UserDefaults` (standard) | Opt-in override of the default cleartext-public-IP guard |

All data is stored locally on the device. Nothing is transmitted to any server operated by the developer. Keychain entries are flagged "this device only" — they are not synchronized via iCloud Keychain.

---

## 3. Data Transmitted to Your Gateway

When you connect to a gateway, the app sends:

- HTTP(S) requests (`GET`, `POST`, `PATCH`) to the gateway URL you entered.
- A Bearer token in the `Authorization` header if one is configured for that profile. **This header is stripped from any device logs before they reach the unified logging system.**
- Configuration read/write payloads to the gateway's `/config` endpoints.
- AI recommendation query text — only if you use the AI Recommendation feature.
- Skill install/uninstall payloads — only if you use the Installed Skills feature.

**You operate the gateway.** The developer has no visibility into, and no access to, any data transmitted between GhostCrab and your gateway.

---

## 4. Permissions and Capabilities

| Capability | Purpose | When requested |
|-----------|---------|----------------|
| `NSLocalNetworkUsageDescription` | LAN discovery via Bonjour and direct connections to gateways on the local network | First time the Scan or Manual Entry screen is opened |
| `NSBonjourServices` (`_openclaw-gw._tcp`) | mDNS browsing for OpenClaw gateways advertising on the LAN | Implicit, on Scan screen open |
| `NSCameraUsageDescription` | QR code scanner for pairing with a gateway via Cloudflare Tunnel URL or `ghostcrab://pair` deep link | First time the QR Scan screen is opened |
| App Sandbox (Mac Catalyst only) | Required by Apple for Mac App Store distribution | Always enabled |
| `com.apple.security.network.client` (Mac Catalyst) | Outbound network requests to your gateway | Always enabled |
| `com.apple.security.network.server` (Mac Catalyst) | Inbound mDNS responses for LAN discovery | Always enabled |

No location, microphone, contacts, photos, calendar, reminders, motion, health, or media library permissions are requested.

---

## 5. Third-Party Services

GhostCrab uses **no third-party SDKs**. No analytics, no crash reporters, no advertising frameworks, no remote configuration, no feature-flag services. The only network traffic the app generates is the traffic you explicitly initiate to a gateway you control.

System frameworks used (Apple-provided, no data shared with third parties):

- `Foundation`, `SwiftUI`, `Combine` — standard app framework
- `Network` (NWBrowser, NWConnection) — Bonjour discovery and connection lifecycle
- `Security` (Keychain Services) — bearer token storage
- `VisionKit` (`DataScannerViewController`) — QR scanner (iOS only)
- `os.Logger` — local-only debug logging via Apple's unified logging system

---

## 6. Children's Privacy

GhostCrab is a developer and infrastructure-operator utility. It is not directed at children under 13 and does not knowingly collect data from children. The App Store age rating is 4+ on the basis of contained content; the audience is technical.

---

## 7. Security

- Bearer tokens are stored in the iOS Keychain (`kSecClassGenericPassword`, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`). They are bound to the device, accessible only after first unlock, and not synced to iCloud.
- If a Keychain entry becomes unreadable (e.g. after a major iOS restore), the app deletes the corrupted entry and prompts you to re-authenticate. Tokens are never written to plaintext logs.
- The `Authorization: Bearer …` header is automatically redacted from any text passed through the in-app logger (`PrivacySafeLogger`). URLs containing embedded credentials (`http://user:pass@host`) are similarly redacted.
- HTTP (non-TLS) connections are permitted for LAN use; the app displays a prominent amber security banner when a connection is unencrypted. HTTP connections to **public** IP addresses are blocked by default; you must explicitly opt in via Settings → Security.

---

## 8. Data Deletion

To remove all locally-stored data:

- Delete the app from your device. iOS / iPadOS / macOS automatically clears the app's `UserDefaults` and Keychain items on uninstall.
- Or, within the app: Settings → Reset → Clear all profiles (clears `UserDefaults` and removes all associated Keychain tokens).

There is no server-side data to delete.

---

## 9. Changes to This Policy

Material changes will be accompanied by an updated effective date. For a version distributed through the App Store or TestFlight, the policy at the URL listed in the App Store Connect record is authoritative.

---

## 10. Contact

Questions or concerns:

- **Email:** support@getghostcrab.com
- **GitHub Issues:** https://github.com/stevenfackley/GhostCrab/issues
