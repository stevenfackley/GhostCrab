# Privacy Policy — GhostCrab for iOS / iPadOS / macOS

**Effective date:** 2026-10-15
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
| Gateway token(s) you enter | iOS Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) | Sign in to your gateway without re-entering the token every launch |
| Device tokens issued by your gateways (one per gateway, with the scopes it granted) | iOS Keychain (same protection, token service) | Reconnect to a gateway that has already approved this device |
| Device key: an Ed25519 key pair generated on the first connect | iOS Keychain (separate service, same protection; only the private seed is stored) | Prove to your gateways that a connection comes from this approved device. The private key never leaves the device |
| Onboarding progress | `UserDefaults` (standard) | Resume a partially completed first-run setup |
| Security toggle (allow cleartext HTTP to public IPs) | `UserDefaults` (standard) | Opt-in override of the default cleartext-public-IP guard |

All data is stored locally on the device. Nothing is transmitted to any server operated by the developer. Keychain entries are flagged "this device only" — they are not synchronized via iCloud Keychain. "Clear all saved gateways" in Settings removes the profiles, gateway tokens and device tokens; the device key stays, so gateways that approved this device keep recognising it. Deleting the app removes everything.

---

## 3. Data Transmitted to Your Gateway

When you connect to a gateway, the app talks only to the gateway URL you entered (typed, picked from LAN discovery, or read from a QR code):

- `GET /health` over HTTP(S), without any credential, to check that the gateway is reachable.
- One WebSocket session. Its first message, the signed `connect`, carries:
  - the app's client id (`openclaw-ios`), version, platform (`ios`, also on Mac), mode (`ui`) and a fixed display name ("GhostCrab (iOS)"), plus the requested role and permission scopes;
  - your gateway token, or the device token that gateway issued to this device earlier, if either exists;
  - your device's language setting (e.g. `en-US`) and a user agent (`ghostcrab-ios/<version>`);
  - the device identity: a device id (a SHA-256 fingerprint of the public key), the public key, and a signature over the gateway's one-time challenge with its timestamp. The private key is never sent.
- The requests you trigger in that session: reading and saving the gateway configuration, listing models and choosing the active one, and listing, installing and disabling skills. The app runs no periodic health checks; `GET /health` is sent only before a connect.
- If the session drops (for example while the app was in the background), one reconnect that repeats the `/health` check and the signed `connect` above, with the device token that gateway issued when there is one.

Credentials travel only inside that signed `connect` message, never in an HTTP header or URL. Tokens and the `connect` message are never written to device logs. LAN discovery sends standard Bonjour (mDNS) queries on your local network only.

**You operate the gateway.** The developer has no visibility into, and no access to, any data transmitted between GhostCrab and your gateway.

---

## 4. Permissions and Capabilities

| Capability | Purpose | When requested |
|-----------|---------|----------------|
| `NSLocalNetworkUsageDescription` | LAN discovery via Bonjour and direct connections to gateways on the local network | First time the Scan or Manual Entry screen is opened |
| `NSBonjourServices` (`_openclaw-gw._tcp`) | mDNS browsing for OpenClaw gateways advertising on the LAN | Implicit, on Scan screen open |
| `NSCameraUsageDescription` | QR code scanner for reading a gateway URL, `ghostcrab://pair` link or `openclaw qr` setup code | First time the QR Scan screen is opened |
| App Sandbox (Mac Catalyst only) | Required by Apple for Mac App Store distribution | Always enabled |
| `com.apple.security.network.client` (Mac Catalyst) | Outbound network requests to your gateway and Bonjour browsing for LAN discovery | Always enabled |

No location, microphone, contacts, photos, calendar, reminders, motion, health, or media library permissions are requested.

---

## 5. Third-Party Services

GhostCrab uses **no third-party SDKs**. No analytics, no crash reporters, no advertising frameworks, no remote configuration, no feature-flag services. The only network traffic the app generates is the traffic you explicitly initiate to a gateway you control.

System frameworks used (Apple-provided, no data shared with third parties):

- `Foundation`, `SwiftUI`, `Combine` — standard app framework
- `Network` (NWBrowser, NWConnection) — Bonjour discovery and connection lifecycle
- `Security` (Keychain Services) — token and device key storage
- `CryptoKit` — Ed25519 signing of the gateway's challenge with the device key, on the device
- `URLSession` — the connection to your gateway (HTTP(S) health check and the WebSocket)
- `VisionKit` (`DataScannerViewController`) — QR scanner (iOS only)
- `os.Logger` — local-only debug logging via Apple's unified logging system

---

## 6. Children's Privacy

GhostCrab is a developer and infrastructure-operator utility. It is not directed at children under 13 and does not knowingly collect data from children. The App Store age rating is 4+ on the basis of contained content; the audience is technical.

---

## 7. Security

- Gateway tokens, device tokens and the device key are stored in the iOS Keychain (`kSecClassGenericPassword`, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`). They are bound to the device, accessible only after first unlock, and not synced to iCloud.
- If a token entry becomes unreadable (e.g. after a major iOS restore), the app deletes the corrupted entry and prompts you to re-authenticate. If the device key is unreadable, the app creates a new one and each gateway asks you to approve this device again; a temporary Keychain error (for example while the device is still locked) does not replace it. Tokens are never written to plaintext logs.
- Credentials are sent only inside the signed `connect` message on the WebSocket, never in an HTTP header or URL. URLs containing embedded credentials (`http://user:pass@host`) are redacted from the in-app logger (`PrivacySafeLogger`).
- HTTP (non-TLS) connections are permitted for LAN use; the app displays a prominent amber security banner when a connection is unencrypted. HTTP connections to **public** IP addresses are blocked by default; you must explicitly opt in via Settings → Security.
- iOS App Transport Security blocks plain HTTP to every hostname except `.local` names, whatever the setting above, so a gateway reached by any other name (including LAN names such as `.lan`, `.home.arpa` or `*.ts.net`) needs HTTPS.

---

## 8. Data Deletion

To remove all locally-stored data:

- Delete the app from your device. iOS / iPadOS / macOS automatically clears the app's `UserDefaults` and Keychain items on uninstall.
- Or, within the app: Settings → Clear all saved gateways (clears the profiles in `UserDefaults` and removes the gateway tokens and device tokens from the Keychain). The device key stays so gateways keep recognising this device; deleting the app removes it.

There is no server-side data to delete.

---

## 9. Changes to This Policy

Material changes will be accompanied by an updated effective date. For a version distributed through the App Store or TestFlight, the policy at the URL listed in the App Store Connect record is authoritative.

---

## 10. Contact

Questions or concerns:

- **Email:** support@getghostcrab.com
- **GitHub Issues:** https://github.com/stevenfackley/GhostCrab/issues
