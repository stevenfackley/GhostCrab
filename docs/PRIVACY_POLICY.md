# Privacy Policy — GhostCrab

**Effective date:** 2026-04-14  
**Developer:** Steve Ackley  
**Contact:** stevenfackley@gmail.com  
**App:** GhostCrab — Remote Client for OpenClaw Gateway  
**Package:** com.openclaw.ghostcrab

---

## 1. Overview

GhostCrab is a thin Android client that connects to an OpenClaw Gateway you control. It does **not** collect, transmit, or sell personal data to third parties. There are no analytics SDKs, no advertising networks, and no cloud backend operated by this app.

---

## 2. Data Stored on Your Device

| Data | Where | Why |
|------|-------|-----|
| Gateway URL(s) | Android DataStore (Preferences) | Remember connection profiles across launches |
| Profile display names | Android DataStore (Preferences) | User-set label for each saved gateway |
| Last-connected timestamp | Android DataStore (Preferences) | Display "last seen" in the profile list |
| Bearer token(s) | Android EncryptedSharedPreferences (AES-256-GCM, Android Keystore) | Authenticate to your gateway without re-entering the token on every launch |
| Onboarding progress | Android DataStore (Preferences) | Resume a partially completed setup walkthrough |

All data is stored locally on the device. Nothing is transmitted to any server operated by the developer.

---

## 3. Data Transmitted to Your Gateway

When you connect to a gateway, the app sends:

- HTTP requests (GET/PATCH) to the gateway URL you entered.
- A Bearer token in the `Authorization` header if one is configured. **This header is stripped from device logs.**
- Configuration read/write payloads to the gateway's API endpoints.
- AI recommendation query text if you use the AI Recommendations feature.

**You operate the gateway.** The developer has no visibility into, and no access to, any data transmitted between GhostCrab and your gateway.

---

## 4. Permissions

| Permission | Why |
|-----------|-----|
| `INTERNET` | Connect to your OpenClaw Gateway |
| `ACCESS_NETWORK_STATE` | Detect network availability before attempting connections |
| `ACCESS_WIFI_STATE` | Required for mDNS multicast on some devices |
| `CHANGE_WIFI_MULTICAST_STATE` | Enable mDNS (LAN gateway discovery); acquired only while the Scan screen is open |

No location, camera, microphone, contacts, or storage permissions are requested.

---

## 5. Third-Party Services

GhostCrab uses **Android Downloadable Fonts** (via Google Mobile Services) to load Inter and JetBrains Mono typefaces. This is a system-level mechanism; the app does not send any user data to Google in this process. Refer to [Google's privacy policy](https://policies.google.com/privacy) for GMS behaviour.

No other third-party SDKs, analytics libraries, or advertising frameworks are included.

---

## 6. Children's Privacy

GhostCrab is a developer/administrator utility. It is not directed at children under 13 and does not knowingly collect data from children.

---

## 7. Security

- Bearer tokens are stored in Android EncryptedSharedPreferences backed by the Android Keystore (AES-256-GCM). They are bound to the device and cannot be read on another device.
- If the Keystore entry becomes unreadable (e.g. after a factory reset), the app deletes the corrupted entry and prompts re-authentication. Tokens are never transmitted in plaintext logs.
- HTTP connections (non-TLS) are permitted for LAN use; the app displays a prominent warning banner when a connection is unencrypted.

---

## 8. Changes to This Policy

Material changes will be accompanied by an updated effective date. For a version that has been distributed through Google Play, the policy at the URL listed in the Play Store listing is authoritative.

---

## 9. Contact

Questions or concerns: **stevenfackley@gmail.com**
