# Play Store Listing — GhostCrab

Reference doc for filling out the Play Console store listing.

---

## App Identity

| Field | Value |
|-------|-------|
| App name | GhostCrab |
| Package name | com.openclaw.ghostcrab |
| Default language | English (United States) |
| Developer name | Steve Ackley |
| Developer email | stevenfackley@gmail.com |
| Privacy policy URL | https://steveackley.org/android/privacy |

---

## Short Description (≤80 chars)

```
Remote control for your OpenClaw Gateway — config, models, AI.
```

---

## Full Description (≤4000 chars)

```
GhostCrab is the Android companion to OpenClaw Gateway — the open-source AI model serving platform. Point it at any reachable gateway on your LAN or over the internet and you get full remote control from your phone.

KEY FEATURES

• Connect via LAN auto-discovery (mDNS) or manual URL entry
• Manage gateway configuration (openclaw.json) with a form-based editor
• View and swap active language models
• Get AI-powered configuration recommendations proxied through your gateway
• Secure token authentication with AES-256 encrypted local storage
• Explicit security banners for HTTP and no-auth connections — no silent risks

WHO IT'S FOR

GhostCrab is built for developers and self-hosters who run their own OpenClaw Gateway. It is not a consumer AI chat app — it is a configuration and administration client.

PRIVACY

No analytics. No ads. No cloud backend. Connection profiles and tokens are stored locally on your device. Tokens are encrypted using the Android Keystore (AES-256-GCM). Nothing is sent to the developer.

REQUIREMENTS

• Android 8.0 (API 26) or higher
• A running OpenClaw Gateway reachable from your device

Open source. Bug reports and contributions welcome.
```

---

## Category

**Tools**  
(Secondary: Productivity)

---

## Tags / Keywords

openclsw, gateway, self-hosted, ai, llm, model management, developer tools, remote admin, config editor, mDNS

---

## Content Rating Questionnaire

Answer **No** to all violence/sexual content questions.  
Target audience: **18+** (developer utility).  
App type for rating: **Utility / Productivity**.  
Expected IARC rating: **Everyone (E)** or **Rating Pending**.

---

## Pricing & Distribution

| Field | Value |
|-------|-------|
| Price | Free |
| Contains ads | No |
| In-app purchases | No |
| Countries | All countries |

---

## Graphics Required

| Asset | Size | Notes |
|-------|------|-------|
| App icon | 512 × 512 px PNG | High-res version of `logo_cropped.png`; no alpha |
| Feature graphic | 1024 × 500 px JPG/PNG | Required for store listing header |
| Phone screenshots | Min 2, max 8 · 16:9 or 9:16 | Take on Pixel 7 emulator at 1080p |
| 7-inch tablet screenshots | Optional but recommended | |
| 10-inch tablet screenshots | Optional | |

**Screenshot suggestions (at minimum):**
1. Connection Picker (saved profiles)
2. LAN Scan in progress (pulse animation)
3. Dashboard (connected state, version + health)
4. Config Editor (form view)
5. Manual Entry screen (URL + token fields)

---

## Data Safety Form (Play Console → Data Safety)

### Data collected

| Data type | Collected? | Shared? | Required? | Purpose |
|-----------|-----------|---------|-----------|---------|
| Personal info (email, name) | No | — | — | — |
| Financial info | No | — | — | — |
| Health & fitness | No | — | — | — |
| Location (precise or coarse) | No | — | — | — |
| Web history | No | — | — | — |
| App activity | No | — | — | — |
| App info & performance | No | — | — | — |
| Device or other IDs | No | — | — | — |

**Answer "No" to all collection questions.** No data is collected or shared with third parties.

### Security practices

- [x] Data is encrypted in transit (HTTPS supported; HTTP flagged to user)
- [x] You provide a way for users to request data deletion (email: stevenfackley@gmail.com)
- [ ] Follows Families Policy — No (not for children)

---

## App Access (Play Console → App Content)

Select: **All or most functionality is available without special access**

If a reviewer needs to test authenticated features, they will need a running OpenClaw Gateway.  
Provide testing instructions:

```
This app requires an OpenClaw Gateway to function. For review:
1. The connection screen is accessible immediately on launch.
2. The LAN scan and manual entry forms are fully testable without a live gateway.
3. Error states (unreachable host, auth failure) are visible by entering any invalid URL.
A live gateway is not required to review the core UI.
```

---

## Target API Level

`targetSdk = 35` (Android 15) — satisfies Play Store's current minimum target API requirement.
