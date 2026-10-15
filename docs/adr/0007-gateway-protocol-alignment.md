# ADR-007: Align Both Clients With the Real OpenClaw Gateway Protocol

**Status:** Accepted
**Date:** 2026-10-14
**Supersedes:** ADR-002 (auth-mode probing). **Amends:** ADR-004 (AI recommendations are unsupported until a new ADR).

## Context

Roadmap M5 ran both clients' assumptions against the real gateway, `ghcr.io/openclaw/openclaw:latest` = `2026.10.3` (revision `59cb4479`, WS protocol `4`). Full evidence is in `docs/QA_REAL_GATEWAY_2026-10.md` (§2 HTTP matrix, §3 WS captures, §4 QR, §5 auth probe, §8 change list). Contract sources used for this decision:

- the live captures in that QA run (`ws-real-*.jsonl`, `ws-device-*.jsonl`, `http-*.txt`);
- `@openclaw/gateway-protocol@2026.10.3` `protocol.schema.json` (unpkg; definitions `ConnectParams`, `HelloOk`, `RequestFrame`, `ResponseFrame`, `EventFrame`, `ErrorShape`, `SkillsInstallParams`, `SkillsUpdateParams`, `ConfigPatchParams`, `ModelsListResult`) and `connect-error-details` / `gateway-error-details` from the same package;
- `@openclaw/gateway-client@2026.10.3` (`buildDeviceAuthPayloadV3`, `selectGatewayConnectAuth`, `GatewayBrowserDeviceAuthLifecycle`);
- the image's own docs under `/app/docs`: `gateway/protocol/{handshake,auth,transport}.md`, `gateway/clients.md`, `gateway/configuration/config-rpc.md`, `gateway/pairing.md`, `cli/qr.md`, `channels/pairing.md`, `cli/models.md`.

### What the clients assumed

| Area | Android | iOS |
|---|---|---|
| Identity, version, capabilities | `GET /status` JSON | same |
| Auth detection (ADR-002) | `/status` without a token: 401 means Token, otherwise None | same |
| Auth transport | `Authorization: Bearer` on HTTP and on the WS upgrade | Bearer on HTTP |
| Config | `GET /config` + ETag, `PATCH /config/{section}` + `If-Match` | same |
| Models | `GET /api/models/status`, `POST /api/models/active` | same |
| AI | `POST /api/ai/recommend` | same |
| Skills | JSON-RPC 2.0 over `/ws`: numeric ids, `skills.list`, `skills.install` + `skills.install.progress`, `skills.uninstall` | REST `/api/skills*` |
| Scopes | JSON-RPC `auth.whoami` | REST `POST /api/auth/whoami` |
| QR | `ghostcrab://pair?p=<b64url {v:1,url,token,name}>` or a plain `http(s)` URL | same |

### What the server does

- **HTTP** is probes plus the Control UI. `/health` and `/ready` are JSON. `/status` and `/config` return the HTML SPA to every caller, with or without a token, valid or not. Every `/api/*` path GhostCrab calls is `404 text/plain`. A wrong bearer behaves exactly like no bearer. ADR-002's probe therefore reports a token-protected LAN gateway as `None` and lets a wrong token "connect" (QA §5).
- **WebSocket is the only control plane** (upstream `protocol.md` calls it that). The server sends `connect.challenge {nonce, ts}` first. The first client frame must be `connect`; anything else closes with `1008 invalid request frame`. Frames are `{type:"req", id:<string>, method, params}` → `{type:"res", id, ok, payload | error{code:<string>, message, details?, retryable?, retryAfterMs?}}`, plus `{type:"event", event, payload, seq?}`.
- **Auth lives in the `connect` frame.** A token without a device identity gets `hello-ok` with `scopes: []`, so everything except `health` fails `FORBIDDEN missing scope`. A remote device signs the challenge with Ed25519 (payload `v3|deviceId|clientId|mode|role|scopes|signedAt|token|nonce|platform|deviceFamily`, `deviceId = hex(sha256(raw public key))`, base64url) and gets `NOT_PAIRED` / `PAIRING_REQUIRED` with a `requestId` until someone runs `openclaw devices approve <requestId>` or approves it in the Control UI. Remote devices are never auto-approved. After approval, `hello-ok.auth` carries the granted `scopes` and a per-device `deviceToken`. Credential failures are typed in `error.details.code`: `AUTH_TOKEN_MISSING`, `AUTH_TOKEN_MISMATCH`, `DEVICE_IDENTITY_REQUIRED`, `AUTH_DEVICE_TOKEN_MISMATCH`, `AUTH_BOOTSTRAP_TOKEN_INVALID`, `DEVICE_AUTH_*`.
- **Methods that exist** (all exercised with admin scopes except `config.patch`, which is documented): `health` (no scope), `status`, `models.list`, `skills.status` (`operator.read`), `skills.install` with GhostCrab's exact params, `skills.update`, `config.get` (`raw` JSON5 with secrets redacted as `__OPENCLAW_REDACTED__`, `parsed`, `hash`, …) and `config.patch {raw, baseHash, replacePaths?}` (admin, 30 writes/min). The default model is the config key `agents.defaults.model.primary` (`provider/model`).
- **Methods that do not exist:** `auth.whoami`, `skills.list`, `skills.uninstall`, `models.set`, any AI-recommendation call, and the `skills.install.progress` event.
- **QR:** `openclaw qr` emits a setup code: base64url JSON `{url: ws(s)://…, bootstrapToken, expiresAtMs, urls?}` (bare, or as `oc-pair://<code>`). The token is single-use, expires after 10 minutes, and is not the gateway token. GhostCrab rejects it today.
- **Discovery:** the service type matches (`_openclaw-gw._tcp`), but the `bonjour` plugin is on by default only on macOS, and the TXT record has no `version` key.

## Decision

Adapt **both** clients to the real WS control plane. Do not pursue an upstream REST PR. Keep the frozen `domain/repository` (Android) and `Domain/Repository` (iOS) signatures; change implementations; add members only under ADR-0005.

1. **Transport.** One control-plane WebSocket per session at `ws(s)://<base>/ws`, opened by the connection manager and shared by every repository. REST is used only for `GET /health` (reachability and the app's cleartext policy before the socket opens). No credential is ever sent over HTTP again.
2. **Envelope.** `req` / `res` / `event`, string request ids (`gc-1`, `gc-2`, …), string error codes. Clients branch on `error.code` and `error.details.code`; `message` is shown verbatim. Typed errors: pairing required, connect rejected (credential/device codes), missing scope, unknown method (unsupported), generic RPC error carrying code, detail code, message and `retryable`.
3. **Handshake.** Wait for `connect.challenge` (10 s), send `connect` with `minProtocol = maxProtocol = 4`, `role: "operator"`, `client {id, version, platform, mode: "ui", displayName}`, scopes, `auth`, `locale`, `userAgent`, and a signed `device {id, publicKey, signature, signedAt = challenge.ts, nonce}` (v3 payload, `platform`/`deviceFamily` trimmed and ASCII-lowercased as upstream does). Read `hello-ok`: `server.version`, `features.methods`, `auth.{method, role, scopes, deviceToken}`, `snapshot.authMode`, the `presence` entry with `reason: "self"` (gateway host name). Any other negotiated protocol is a typed protocol error.
4. **Client identity.** `client.id` is a closed upstream enum, so GhostCrab sends `openclaw-android` / `openclaw-ios` with `mode: "ui"`, but stays honest everywhere else: its own app version, `displayName: "GhostCrab (Android)"` / `"GhostCrab (iOS)"`, `userAgent: ghostcrab-<platform>/<version>`. No `deviceFamily` (the QA-verified path; it is bound into the signature and pinned at approval, so it must never change).
5. **Device identity.** One Ed25519 key per install.
   - Android: Tink `tink-android` 1.8.0 `subtle.Ed25519Sign` (already on the runtime classpath through `androidx.security:security-crypto`; now declared explicitly). The 32-byte seed lives in its own EncryptedSharedPreferences file (`ghostcrab_device_identity`, AES256-GCM, Keystore-wrapped key). Android Keystore cannot hold Ed25519 below API 33 and `minSdk` is 26. Bouncy Castle is **not** used: the 1.85 pin in `app/build.gradle.kts` only covers AGP's host-side build tooling and is not on the app classpath.
   - iOS: CryptoKit `Curve25519.Signing.PrivateKey`; the raw seed is a Keychain generic password (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, own service).
   - The identity survives "Clear all saved gateways" (the gateway's pairing record stays valid); an unreadable seed is replaced and the gateway shows a new pairing request.
6. **Credentials.** Selection follows upstream `selectGatewayConnectAuth`: an explicit shared token (`auth.token`) first, then this gateway's stored device token (`auth.deviceToken`, reusing its stored scopes), then a setup-code bootstrap token (`auth.bootstrapToken`), else none. Requested scopes: `operator.read`, `operator.write`, `operator.admin` (`config.*` and `skills.install` are admin-only). Every `hello-ok.auth.deviceToken` is persisted per gateway origin with its scopes and device id, in the same encrypted store as the profile token, so "Clear all" wipes it. `AUTH_DEVICE_TOKEN_MISMATCH` forgets the stored token. There is no automatic shared→device-token retry on `AUTH_TOKEN_MISMATCH` (upstream gates that to pinned or loopback endpoints).
7. **Pairing UX.** `PAIRING_REQUIRED` is not an error screen. The screen that started the connect (Manual Entry or the profile list) shows an approval panel: the reason, the `requestId`, the exact command `openclaw devices approve <requestId>` (copyable, JetBrains Mono), and the requested scopes. GhostCrab re-attempts the connect every 5 s, up to the 5-minute lifetime of a pending request, with "Check now" and "Cancel". Scope, role and metadata upgrades use the same panel. Nothing is ever approved by the app.
8. **Auth requirement (replaces ADR-002).** `GatewayConnection.Connected.authRequirement` comes from `hello-ok` (`snapshot.authMode`, else `auth.method`). `probeAuth()` keeps its frozen signature but now runs `/health` plus a credential-less signed `connect`: `AUTH_TOKEN_MISSING` / `AUTH_PASSWORD_MISSING` / `AUTH_REQUIRED` → Token; `hello-ok` → its auth mode; `PAIRING_REQUIRED` → None (only the approval is missing).
9. **Feature mapping.**

   | Feature | Before | After |
   |---|---|---|
   | Dashboard name / version / capabilities | `/status` | `hello-ok`: presence host, `server.version`, `features.methods` → `config` / `models` / `skills` |
   | Health polling | `GET /health` | `health` RPC on the session socket (fails as soon as the socket drops) |
   | Config read | `GET /config` + ETag | `config.get`: `parsed` sections, `hash` as the ETag |
   | Config write | `PATCH /config/{section}` + `If-Match` | `config.patch {raw: "{<section>: <value>}", baseHash}`; stale hash → 412-style error, other `INVALID_REQUEST` → 422-style, both with the server text |
   | Typed `gateway` editor | `http.host/port`, `auth.mode none|bearer`, `mdns.*` (not the real schema) | `port`, `bind`, `mode`, `auth.mode none|token|password|trusted-proxy`, redacted token preserved |
   | Models list / active | `/api/models/*` | `models.list` + `config.get` → `agents.defaults.model.primary`; activation = `config.patch` of that key; ids become `provider/model` refs |
   | Skills list | `skills.list` | `skills.status` (`skillKey`, `source`, `bundled`, `disabled`) |
   | Skill install | `skills.install` + progress events | same params, one `res` (no progress stream), list refreshed after |
   | Skill uninstall | `skills.uninstall` | unsupported (typed error); the UI disables instead via `skills.update {skillKey, enabled:false}` (additive `setSkillEnabled`) |
   | Scope probe | `auth.whoami` | `hello-ok.auth.scopes` |
   | AI recommendations | `/api/ai/recommend` | unsupported: `isAvailable()` is `false`, `getRecommendation` throws the unavailable error, Android `AI_PRO_ENABLED = false` |
   | QR / deep link | `ghostcrab://pair` v1, `http(s)` URLs | plus the `openclaw qr` setup code (bare or `oc-pair://`) and `ws(s)://` URLs; the bootstrap token never enters a route or the profile store |
   | mDNS | unchanged | unchanged; copy tells Linux/Windows/Docker users to run `openclaw plugins enable bonjour` |

10. **Test honesty.** Frames captured from 2026.10.3 (trimmed, secrets replaced) live once in `fixtures/gateway-protocol/2026.10.3/` and drive the Android unit tests, the iOS unit tests and the mock gateway's conformance tests. `scripts/mock-gateway` is rewritten to the real protocol and gets a CI job.

Execution: `docs/superpowers/plans/2026-10-15-protocol-alignment-android.md` and `docs/superpowers/plans/2026-10-15-protocol-alignment-ios.md` (roadmap M7).

## Reasons

- Upstream already chose WS as the single control plane. Its one HTTP RPC surface, the bundled `admin-http-rpc` plugin, is opt-in, documented as being for host tooling that cannot hold a socket, hands full operator rights to any bearer holder, and excludes `skills.*`. A PR adding GhostCrab's `/api/*` shape would contradict that direction and would have no timeline.
- The WS surface covers nearly everything GhostCrab needs: health, config read/write with a conflict guard, models, skills status/install/update, pairing. The remaining gaps (skill removal, install progress, AI recommendations) would not be closed by a REST PR either.
- Device identity and pairing are mandatory for any useful scope. A shared token alone yields `scopes: []`. Per-device tokens are revocable per phone; a leaked shared token is not.
- Upstream publishes a machine-readable contract (`protocol.schema.json`) and a reference client, so the shapes and the signature format are verifiable rather than guessed a second time.
- It works against `2026.10.3` today, with no gateway upgrade for users.

## Consequences

- **ADR-002 is superseded.** Its `/status` asymmetry never worked against a real gateway. `GatewayConnectionManager.probeAuth()` keeps its frozen signature with new semantics (item 8). The no-auth banner is now truthful.
- **Frozen interfaces stay frozen.** Implementations change. Additive members under ADR-0005, on both platforms: a new `GatewayBootstrapConnector` interface/protocol (implemented by `GatewayConnectionManagerImpl`) and `InstalledSkillRepository.setSkillEnabled`. `ScopeProbeResult.UnknownOldGateway` is no longer produced. KDoc/doc comments on frozen members are updated to describe the real transport; signatures are not.
- **Domain models change additively or with an ADR (this one):** `InstalledSkill.disabled`; `SkillInstallError.Protocol` carries a string code; `PairBundle` / `PairingRequest` gain an optional bootstrap token; Android's typed `gateway` config section models are rewritten to the real schema (iOS never used its typed section models and deletes them); iOS `GatewayError` gains `rpc`, `methodUnsupported`, `missingScope`, `pairingRequired`, `connectRejected` and `protocolViolation`.
- **Users must approve each phone once** on the gateway host (or in the Control UI). This is new friction, and it is the security model upstream enforces; GhostCrab makes it explicit instead of failing with empty scopes.
- **Secrets on device:** the device seed (identity store) and per-gateway device tokens (token store) join the shared gateway token. None is logged; every type holding one redacts `toString` / `description`. The connect frame is never logged.
- **Features shrink honestly.** Skill uninstall becomes "disable"; install has no progress phases; AI recommendations are hidden (Android) / unavailable (iOS) until a separate ADR decides whether to rebuild them on an agent turn (`chat.send`). ADR-004's routing principle (no provider keys on device) still holds.
- **Riskier assumptions, each with a ⚠ verification step in the plans:** the stale-`baseHash` error code (not captured; mapped by message/detail text), the success payloads of `skills.install` / `skills.update` / `config.patch` (not captured; the clients only rely on `ok:true`), whether reconnecting during a pending approval keeps the same `requestId`, `skills.status` `source` values for ClawHub-installed skills, the scopes a setup-code pairing ends up with (upstream hands plain-LAN `ws://` setup codes the limited operator profile without `operator.admin`, `cli/qr.md`; config writes and skill installs would then need a shared-token connect or a `wss://` gateway), and GhostCrab claiming the `openclaw-android` / `openclaw-ios` client ids (a future server policy keyed on those ids, for example a minimum app version, would reject GhostCrab's own version string).
- **Not covered:** TLS pinning (`gateway.remote.tlsFingerprint`), `urls` fallbacks in setup codes (GhostCrab uses `url` only), gateway context paths other than `/ws`, `hello-ok.auth.deviceTokens` bootstrap hand-off lists (GhostCrab connects as `operator` and keeps only the primary `deviceToken`), and live event subscriptions beyond what the screens need.

## Alternatives rejected

- **Upstream REST PR** (`/api/*` routes matching GhostCrab): contradicts upstream's documented WS-only direction, needs every user to upgrade their gateway, has no timeline, and still could not provide skill removal, install progress or AI recommendations.
- **Interim `admin-http-rpc`** (`POST /api/v1/admin/rpc` with the bearer): requires users to enable a plugin that gives any bearer holder full operator rights, has no `skills.*` methods, and keeps the shared token as the only credential.
- **Keep the mock contract** (`scripts/mock-gateway` + the speculative REST/JSON-RPC surface): every unit test and on-device QA would keep validating a protocol that does not exist; M5 showed connect, auth detection, config, models and skills all fail against the real gateway.
