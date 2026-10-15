# Privacy Policy — GhostCrab

**Effective date:** 2026-10-15  
**Developer:** Steve Ackley  
**Contact:** stevenfackley@gmail.com  
**App:** GhostCrab — Remote Client for OpenClaw Gateway  
**Package:** com.openclaw.ghostcrab

---

## 1. Overview

GhostCrab is a thin Android client that connects to an OpenClaw Gateway you control. It does **not** sell personal data, and the developer receives none of it: there are no analytics SDKs of its own, no advertising networks, and no cloud backend operated by this app. Data leaves the phone in two ways only: to the gateway you configure (section 3), and as the diagnostics that Google's ML Kit library, used by the QR scanner, sends to Google (section 5).

---

## 2. Data Stored on Your Device

| Data | Where | Why |
|------|-------|-----|
| Gateway URL(s) | Android DataStore (Preferences) | Remember connection profiles across launches |
| Profile display names | Android DataStore (Preferences) | User-set label for each saved gateway |
| Last-connected timestamp | Android DataStore (Preferences) | Display "last seen" in the profile list |
| Gateway token(s) you enter | Android EncryptedSharedPreferences (AES-256-GCM, key in the Android Keystore) | Sign in to your gateway without re-entering the token on every launch |
| Device tokens issued by your gateways (one per gateway, with the scopes it granted) | Android EncryptedSharedPreferences (same file) | Reconnect to a gateway that has already approved this phone |
| Device key: an Ed25519 key pair generated on the first connect | Android EncryptedSharedPreferences (a separate file) | Prove to your gateways that a connection comes from this approved phone. The private key never leaves the device |
| Onboarding progress | Android DataStore (Preferences) | Resume a partially completed setup walkthrough |

All data is stored locally on the device. Nothing is transmitted to any server operated by the developer. "Clear All Profiles" in Settings removes the profiles, gateway tokens and device tokens (deleting a profile also removes the device token of a gateway no other profile uses); the device key stays, so gateways that approved this phone keep recognising it. Uninstalling the app removes everything.

---

## 3. Data Transmitted to Your Gateway

When you connect to a gateway, the app talks only to the gateway URL you entered (typed, picked from LAN discovery, or read from a QR code):

- `GET /health` over HTTP(S), without any credential, to check that the gateway is reachable.
- One WebSocket session. Its first message, the signed `connect`, carries:
  - the app's client id (`openclaw-android`), version, platform (`android`), mode (`ui`) and a fixed display name ("GhostCrab (Android)"), plus the requested role and permission scopes;
  - your gateway token, or the device token that gateway issued to this phone earlier, if either exists;
  - your phone's language setting (e.g. `en-US`) and a user agent (`ghostcrab-android/<version>`);
  - the device identity: a device id (a SHA-256 fingerprint of the public key), the public key, and a signature over the gateway's one-time challenge with its timestamp. The private key is never sent.
- The requests you trigger in that session: reading and saving the gateway configuration, listing models and choosing the active one, listing, installing and enabling or disabling skills, and periodic health checks while the dashboard is open.

Tokens and the `connect` message are never written to device logs. LAN discovery sends standard mDNS queries on your local network only.

**You operate the gateway.** The developer has no visibility into, and no access to, any data transmitted between GhostCrab and your gateway.

---

## 4. Permissions

| Permission | Why |
|-----------|-----|
| `INTERNET` | Connect to your OpenClaw Gateway |
| `ACCESS_NETWORK_STATE` | Detect network availability before attempting connections |
| `ACCESS_WIFI_STATE` | Required for mDNS multicast on some devices |
| `CHANGE_WIFI_MULTICAST_STATE` | Enable mDNS (LAN gateway discovery); acquired only while the Scan screen is open |
| `CAMERA` | Scan a gateway QR code; used only while the QR scanner is open. Camera frames are decoded on the device and never stored |

No location, microphone, contacts, or storage permissions are requested.

---

## 5. Third-Party Services

GhostCrab uses **Android Downloadable Fonts** (via Google Mobile Services) to load Inter and JetBrains Mono typefaces. This is a system-level mechanism; the app does not send any user data to Google in this process. Refer to [Google's privacy policy](https://policies.google.com/privacy) for GMS behaviour.

The QR scanner uses Google's **ML Kit barcode scanning** library, which decodes camera frames on the device. Google documents that ML Kit sends Google diagnostics and usage metrics (device model and OS version, app package name and version, a per-installation identifier, performance metrics and error codes), encrypted in transit and not shared with third parties: see [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure). Google's list includes no camera images or QR contents.

No other third-party SDKs, analytics libraries, or advertising frameworks are included.

---

## 6. Children's Privacy

GhostCrab is a developer/administrator utility. It is not directed at children under 13 and does not knowingly collect data from children.

---

## 7. Security

- Gateway tokens, device tokens and the device private key are stored in Android EncryptedSharedPreferences backed by the Android Keystore (AES-256-GCM). They are bound to the device and cannot be read on another device.
- Credentials travel only inside the signed `connect` message on the WebSocket, never in an HTTP header or URL, and never appear in device logs.
- If the Keystore entry becomes unreadable (e.g. after a factory reset), the app deletes the corrupted entry and prompts re-authentication. If the device key is lost that way, the app creates a new one and each gateway asks you to approve this phone again.
- HTTP connections (non-TLS) are permitted for LAN use; the app displays a prominent warning banner when a connection is unencrypted.

---

## 8. Changes to This Policy

Material changes will be accompanied by an updated effective date. For a version that has been distributed through Google Play, the policy at the URL listed in the Play Store listing is authoritative.

---

## 9. Contact

Questions or concerns: **stevenfackley@gmail.com**
