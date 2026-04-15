# Data Safety — GhostCrab

Reference for the Play Console **Data Safety** section and the `PRIVACY.md` legal document.

---

## Does your app collect or share any of the required data types?

**No.** GhostCrab does not collect, share, or sell any user data with third parties or with the developer.

All persistent state (gateway URLs, display names, bearer tokens, onboarding progress) is stored **locally on the device** and is never transmitted outside the user's own gateway.

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
- App info and performance (crash logs, diagnostics, other) → Not collected
- Device or other IDs (device ID, IMEI, etc.) → Not collected

---

## Security Practices Checklist

- [x] **Data is encrypted in transit** — Ktor client uses OkHttp; HTTPS is supported. HTTP connections are explicitly flagged with an amber warning banner in-app.
- [x] **You provide a way for users to request data deletion** — Contact: stevenfackley@gmail.com. Since no data leaves the device, "deletion" means uninstalling the app.
- [ ] **Your app follows the Families Policy** — Not applicable (not targeted at children).
- [ ] **Your app has been independently security reviewed** — Not at v1.0.

---

## Notes for Reviewer

The only data that "persists" in GhostCrab is:

1. **Gateway connection profiles** (URL, display name, last-connected date) — stored in Android DataStore Preferences. This is equivalent to a bookmark. No PII.
2. **Bearer tokens** — stored in Android EncryptedSharedPreferences, AES-256-GCM, Android Keystore. Unreadable without the device's hardware-backed key. Automatically cleared on factory reset.
3. **Onboarding step** — a single integer. No PII.

None of this constitutes "collection" under GDPR, CCPA, or Google's data safety definitions because it never leaves the device and is never accessible to the developer.
