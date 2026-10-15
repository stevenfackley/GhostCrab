# Data Safety — GhostCrab

Reference for the Play Console **Data Safety** section and the `PRIVACY.md` legal document.

---

## Does your app collect or share any of the required data types?

**Nothing reaches the developer**, and GhostCrab sells or shares no user data. One SDK does collect, though: see [Open decision: ML Kit diagnostics](#open-decision-ml-kit-diagnostics) before submitting the form.

All persistent state (gateway URLs, display names, gateway tokens, per-gateway device tokens, the device's Ed25519 key, onboarding progress) is stored **locally on the device**. The app transmits only to the gateway the user configured: a credential-less `GET /health`, then a WebSocket whose signed `connect` carries the client metadata (id, version, platform, mode, fixed display name, locale, user agent), the gateway or device token, and the device id, public key and a signature over the gateway's challenge. The private key never leaves the device.

## Open decision: ML Kit diagnostics

The QR scanner uses Google's ML Kit barcode scanning (`com.google.mlkit:barcode-scanning`). Google's [ML Kit data disclosure](https://developers.google.com/ml-kit/android-data-disclosure) (checked 2026-10-15) says it sends Google diagnostics and usage analytics: device information (manufacturer, model, OS version), app package name and version, a per-installation identifier, performance metrics, API configuration, input/output sizes and error codes, encrypted in transit and not transferred to third parties. It leaves the Data safety answers to the developer, and Play counts what an SDK in the app collects as the app's collection.

Before submitting, either:

1. declare it: **App info and performance** (diagnostics) and **Device or other IDs**, collected, not shared, purpose Analytics, encrypted in transit; or
2. replace ML Kit in the QR scanner, so the answers below hold.

The answers below are option 2 and do **not** match the current build.

---

## Data Safety Form — Field-by-Field

### Does your app collect or share user data?
→ **No**

Play Console will ask you to confirm:

| Question | Answer |
|----------|--------|
| Does your app collect or share any of the required user data types? | **No** |
| Is all user data encrypted in transit? | **Yes** (when using HTTPS; HTTP is flagged to the user) |
| Do you provide a way for users to request that their data is deleted? | **Yes** — email stevenfackley@gmail.com |

### Data types — mark all as "Not collected"

- Personal info (name, email, user IDs, address, phone number, other personal info) → Not collected
- Financial info → Not collected
- Health & fitness → Not collected
- Messages → Not collected
- Photos and videos → Not collected
- Audio files → Not collected
- Files and docs → Not collected
- Calendar → Not collected
- Contacts → Not collected
- App activity (interactions, search history, installed apps, other) → Not collected
- Web browsing → Not collected
- App info and performance (crash logs, diagnostics, other) → Not collected (ML Kit: see the open decision)
- Device or other IDs (device ID, IMEI, etc.) → Not collected (ML Kit: see the open decision; the gateway device id goes only to the user's own gateway)

---

## Security Practices Checklist

- [x] **Data is encrypted in transit** — Ktor client uses OkHttp; HTTPS is supported. HTTP connections are explicitly flagged with an amber warning banner in-app.
- [x] **You provide a way for users to request data deletion** — Contact: stevenfackley@gmail.com. The developer holds no user data: deletion means "Clear All Profiles" in Settings or uninstalling the app on the phone, and revoking the device on the user's own gateway.
- [ ] **Your app follows the Families Policy** — Not applicable (not targeted at children).
- [ ] **Your app has been independently security reviewed** — Not at v1.0.

---

## Notes for Reviewer

The only data that "persists" in GhostCrab is:

1. **Gateway connection profiles** (URL, display name, last-connected date) — stored in Android DataStore Preferences. This is equivalent to a bookmark. No PII.
2. **Gateway tokens and per-gateway device tokens** — stored in Android EncryptedSharedPreferences, AES-256-GCM, Android Keystore. Unreadable without the device's hardware-backed key. Automatically cleared on factory reset.
3. **Device key** — an Ed25519 key pair generated on the first connect; the private key is stored in its own EncryptedSharedPreferences file and never leaves the device. Its public key, the derived device id and challenge signatures are sent only to the user's own gateway, which uses them to approve this phone.
4. **Onboarding step** — a single integer. No PII.

None of this reaches the developer: it stays on the device, or goes only to the gateway the user operates. The ML Kit diagnostics above are the exception that needs a decision.
