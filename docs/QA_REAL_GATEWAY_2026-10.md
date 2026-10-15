# QA — Real OpenClaw gateway verification (roadmap M5), 2026-10-14

Server-side verification of the real upstream gateway against every HTTP path and WS method the
Android and iOS clients use. No app ran on a device; every client-side claim below comes from
reading the client source, and is marked as such.

## 1. Gateway under test

| Field | Value |
|---|---|
| Image | `ghcr.io/openclaw/openclaw:latest` |
| Digest | `sha256:70b8dbd23f8d27a206f43f9828fdebc87289e50d56dc55177354c9270d764026` |
| Version (`org.opencontainers.image.version`) | `2026.10.3` |
| Upstream revision | `59cb44796b0f1f0daa0e37af8b1495abb45d9bc1` |
| Image created | 2026-10-13T07:37:58Z, linux/amd64, Node 24.21.0 |
| WS protocol | `4` (`hello-ok.protocol`); `hello-ok.features`: 496 methods, 65 events |
| Host | Docker Desktop (WSL2) on Windows 11, `-p 18789:18789`, clients connect from the bridge (172.17.0.1), so the gateway treats them as **remote**, not loopback |

Startup facts:

- With no config the container exits 78: `Gateway start blocked: existing config is missing gateway.mode`.
- With `bind: "lan"` and `auth.mode: "none"` it exits 78: `Refusing to bind gateway to lan without auth.`
  A LAN-reachable gateway therefore always has auth; `auth: none` exists only on loopback,
  tunnel, or trusted-proxy setups.
- A bind-mounted Windows directory fails Doctor with `EPERM: operation not permitted, fchmod`; a named volume works.
- The config used (copied into a named volume at `/home/node/.openclaw/openclaw.json`):

```json
{ "gateway": { "mode": "local", "port": 18789, "bind": "lan",
               "auth": { "mode": "token", "token": "<qa token>" } } }
```

- Log: `http server listening (14 plugins: anthropic, browser, canvas, cua-computer, device-pair, file-transfer, geolocation, github, linux-node, memory-core, ollama, openai, talk-voice, xai)`. There is no `bonjour` in that list.

The image ships its own docs under `/app/docs`. The protocol facts below come from live captures and
are cross-checked against `/app/docs/gateway/protocol/*.md`, `/app/docs/gateway/health.md`,
`/app/docs/gateway/bonjour.md`, `/app/docs/plugins/admin-http-rpc.md`, and `/app/docs/cli/qr.md`.

## 2. HTTP: client assumption → real behaviour

Each path was probed with curl three ways: no `Authorization`, the correct bearer, and a wrong
bearer. **The wrong bearer produced byte-identical status codes and content types to no bearer on
every path.**

| Path | Used by | Client assumption | Real behaviour (status · content-type · body) | Impact |
|---|---|---|---|---|
| `GET /health` | Android `OpenClawApiClient.kt:85`, iOS `OpenClawAPIClient.swift:151` | JSON with a `status` string, no auth | `200` · `application/json` · `{"ok":true,"status":"live"}`. No auth needed. `/healthz` is the same | **OK** (decodes; the value `"live"` is never compared) |
| `GET /ready` | not used by either client | — | `200` · JSON · `{"ready":true}`. With a **valid** bearer it adds `failing`, `uptimeMs`, `eventLoop`, … `/readyz` is the same | Not used. The detailed body appears only with a valid token (§5) |
| `GET /startupz` | not used | — | `200` · `{"ok":true,"status":"started"}`. A valid bearer adds `"version":"2026.10.3","uptimeMs":…` | The only HTTP source of the version |
| `GET /status` | Android `OpenClawApiClient.kt:105`, iOS `OpenClawAPIClient.swift:178` | JSON `{displayName, version, capabilities, hardware}`; **401 when a token is required** | `200` · `text/html` · Control UI SPA (`<!doctype html><html data-openclaw-control-ui-…`) for no, wrong, **and** correct token | **Mismatch (P0).** The auth probe always returns `None`, a wrong token is never detected, `capabilities` is always `[]`, and the version is always `"unknown"` |
| `GET /config` | Android `:121`, iOS `:203` | JSON `openclaw.json` + `ETag` | `200` · `text/html` · the same SPA | **OK-degraded.** Both clients map non-JSON to an empty config (NoConfigApi), but config editing is impossible |
| `PATCH /config/{section}` | Android `:144`, iOS `:228` | merge-patch, `If-Match`, 412/422 | `404` · `text/plain` · `Not Found` | **Mismatch** (real API: WS `config.patch`) |
| `GET /api/config` | not used | — | `404` · `text/plain` | — |
| `GET /api/models` | not used | — | `404` | — |
| `GET /api/models/status` | Android `:184`, iOS `:271` | model list | `404` · `text/plain` | **Mismatch** (real API: WS `models.list`) |
| `POST /api/models/active` | Android `:200`, iOS `:288` | `{id}` → 200/204 | `404` | **Mismatch** (real API: `config.patch` on `agents.defaults.model.primary`) |
| `POST /api/ai/recommend` | Android `:223`, iOS `:317` | recommendation JSON; 404 = skill missing | `404` | **Mismatch, no upstream equivalent** |
| `GET /api/skills`, `POST /api/skills/install`, `POST /api/skills/uninstall` | iOS `:359`, `:398`, `:442` | REST skills | `404` (all three) | **Mismatch** (iOS has no WS client) |
| `POST /api/auth/whoami` | iOS `:482` | `{scopes}` | `404` | **Mismatch.** iOS maps 404 to `unknownOldGateway` (`ScopeProbeImpl.swift:33`) |
| `GET /pair` | neither (pairing is the `ghostcrab://pair` deep link) | — | `200` · `text/html` · SPA fallback | No impact |
| `GET /ws` (no upgrade) | — | — | `200` · `text/html` · SPA fallback | — |
| `POST /tools/invoke` | not used | — | No/wrong token: `401` · JSON `{"error":{"message":"Unauthorized","type":"unauthorized"}}`. Valid token: `404` `Tool not available: x` | The one probed route that enforces HTTP bearer auth |
| `GET /v1/models`, `GET /api/diagnostics/prometheus` | not used | — | `404` (their plugins are disabled by default) | — |

There is no OpenAPI or route list. HTTP surface on this build: probes (`/health*`, `/ready*`,
`/startup*`), a Control UI SPA catch-all for other GETs, `404 text/plain` for other `/api/*` paths,
and plugin routes that exist only when enabled. One of those plugins is the bundled, opt-in
**`admin-http-rpc`** (`POST /api/v1/admin/rpc`, see §7).

## 3. WebSocket: client assumption → real behaviour

Only Android has a WS client (`GatewayWsClient.kt`). iOS uses REST for skills and whoami (all 404,
§2). Captures were made with Node 26's built-in `WebSocket`; the raw `.jsonl` files are listed in §8.

| Aspect | Android assumption (file:line) | Real behaviour | Impact |
|---|---|---|---|
| URL | `ws(s)://<base>/ws`, bearer in the upgrade header (`OpenClawApiClient.kt:251-254`) | The upgrade succeeds on **any** path (`/`, `/ws`, `/random-path`, `/api/ws` all return `connect.challenge`). The upgrade header is not used for auth; auth goes in the `connect` frame | URL is OK; the header is useless |
| First server frame | none expected | `{"type":"event","event":"connect.challenge","payload":{"nonce":"…","ts":1792027387953,"capabilities":["model-catalog-snapshot"]}}` within ~2 ms | The client drops it: no `id`, no `method` (`GatewayWsClient.kt:80-95`) |
| First client frame | JSON-RPC request (`JsonRpc.kt:13-18`) | **Must be `connect`**. Any other frame gets close `1008 "invalid request frame"`. Idle without `connect` for 10 s: not closed | **Mismatch (fatal).** Every Android WS call fails with `GatewayUnreachableException` |
| Envelope | `{"jsonrpc":"2.0","id":<Long>,"method","params"}` → `{jsonrpc,id,result|error{code:Int}}` (`JsonRpc.kt:13-48`) | `{type:"req", id:<string>, method, params}` → `{type:"res", id, ok, payload|error{code:<string>, message, details?, retryable?, retryAfterMs?}}`; events `{type:"event", event, payload, seq?, stateVersion?}` | **Mismatch.** String ids, string error codes, and `ok`/`payload` instead of `result` |
| Auth | Bearer header | `connect.params.auth.token`. With no device identity, a correct token returns `hello-ok` with **`scopes: []`**, so everything except `health` fails `FORBIDDEN missing scope: operator.read`. With a signed Ed25519 `device` from a remote IP: `NOT_PAIRED`/`PAIRING_REQUIRED` with a `requestId` until approved (`openclaw devices approve <id>` or the Control UI); after approval `hello-ok` grants the requested scopes plus a `deviceToken` | **Mismatch (P0).** Device identity and pairing are mandatory for any useful scope |
| `auth.whoami` | scope probe (`ScopeProbeImpl.kt:27`) | `INVALID_REQUEST unknown method: auth.whoami`. Scopes are in `hello-ok.auth.scopes` | **Mismatch** |
| `skills.list` | `InstalledSkillRepositoryImpl.kt:63`, expects `{skills:[…]}` | `unknown method: skills.list`. Real: **`skills.status`** (`operator.read`) → `{workspaceDir, managedSkillsDir, agentId, skills:[{name, description, source, bundled, filePath, baseDir, skillKey, always, disabled, blockedByAllowlist, eligible, emoji, homepage, missing, configChecks, …, install}]}` (51 bundled skills here) | **Mismatch** (rename + remap DTO) |
| `skills.install` | `{source:"clawhub", slug, version?, force:false}` (`:126-134`) | **Exists with this exact param shape** (`operator.admin`). One `res` frame (~3 s). Unknown slug: `ok:false`, `error.code:"UNAVAILABLE"`, `message:"Skill \"…\" not found on ClawHub. …"` | **OK on params**; result and error mapping need changes |
| `skills.install.progress` notifications | progress UI (`:107`) | Not in the 65 advertised events. Nearest: `skills.changed`, `plugins.install.progress` | **Mismatch.** No install progress stream |
| `skills.uninstall` | `:156` | `unknown method: skills.uninstall`. No removal method appears in `features.methods`. `skills.update` (admin) config mode can set `skills.entries.<key>.enabled=false` (from the docs; not exercised) | **Mismatch, no equivalent.** Removal could not be determined |
| Error codes | numeric JSON-RPC: `-32601` (unknown method → `UnknownOldGateway`, `ScopeProbeImpl.kt:39`), `-32003`, `-32004` | `INVALID_REQUEST` ("unknown method: …"), `FORBIDDEN`+`details.code:"MISSING_SCOPE"`, `NOT_PAIRED`+`PAIRING_REQUIRED`, `UNAVAILABLE`, connect failures `AUTH_TOKEN_MISMATCH` / `AUTH_TOKEN_MISSING` / `DEVICE_IDENTITY_REQUIRED` in `details.code` | **Mismatch** |
| Other methods GhostCrab needs | (REST today) | Present and exercised with admin scopes: `health`, `status`, `models.list` (→ `{"models":[],"accountSelection":{…}}` with no provider configured), `config.get` (→ `{path, raw (JSON5 string, secrets redacted as "__OPENCLAW_REDACTED__"), parsed, config, resolved, valid, issues, hash:"hmac-sha256:v1:…", configRevisionHash, appliedConfigHash, …}`), `config.patch` (documented: `{raw, baseHash}`, admin, 30 req/min). `models.set`: `unknown method` | Remap targets exist for config and models |

### Raw handshake excerpt

Android's exact first frame, sent right after the upgrade (`captures/ws-android-immediate.jsonl`):

```jsonl
{"t":51,"dir":"open","url":"ws://127.0.0.1:18789/ws","bearer":"good"}
{"t":52,"dir":"out","frame":{"jsonrpc":"2.0","id":1,"method":"auth.whoami","params":null}}
{"t":54,"dir":"in","frame":{"type":"event","event":"connect.challenge","payload":{"nonce":"2e569bf0-e1ad-4819-9479-d83877005eab","ts":1792027398441,"capabilities":["model-catalog-snapshot"]}}}
{"t":62,"dir":"close","code":1008,"reason":"invalid request frame"}
{"t":1554,"dir":"out","frame":{"jsonrpc":"2.0","id":2,"method":"skills.list","params":null}}
{"t":1555,"dir":"note","msg":"not sent, readyState=3"}
```

The same thing after 10 s of passive logging (`ws-android-after-10s.jsonl`) gives the challenge at
63 ms, nothing else for 10 s, then `close 1008 "invalid request frame"` 9 ms after the first JSON-RPC frame.

Real handshake: token only, no device identity (`ws-real-good.jsonl`). The device-less socket gets no scopes:

```jsonl
{"dir":"out","frame":{"type":"req","id":"qa-1","method":"connect","params":{"minProtocol":4,"maxProtocol":4,"client":{"id":"openclaw-android","version":"0.2.0","platform":"android","mode":"ui","displayName":"GhostCrab QA"},"role":"operator","scopes":["operator.read","operator.write","operator.admin"],"caps":[],"commands":[],"permissions":{},"auth":{"token":"<qa token>"},"locale":"en-US","userAgent":"ghostcrab-qa/0.2.0"}}}
{"dir":"in","frame":{"type":"res","id":"qa-1","ok":true,"payload":{"type":"hello-ok","protocol":4,"server":{"version":"2026.10.3",…},"features":{"methods":[…496…],"events":[…65…]},"auth":{"method":"token","role":"operator","scopes":[],…},"policy":{"maxPayload":26214400,"maxBufferedBytes":52428800,"tickIntervalMs":30000,…}}}}
{"dir":"in","frame":{"type":"res","id":"qa-4","ok":false,"error":{"code":"FORBIDDEN","message":"missing scope: operator.read","details":{"code":"MISSING_SCOPE","missingScope":"operator.read","requiredScopes":["operator.read"]}}}}
```

Real handshake with a signed device (v3 payload `v3|deviceId|clientId|mode|role|scopes|signedAt|token|nonce|platform|deviceFamily`,
`deviceId = sha256(raw Ed25519 public key)` hex, key and signature base64url), first attempt
(`ws-device-1-unpaired.jsonl`):

```jsonl
{"dir":"in","frame":{"type":"res","id":"qa-1","ok":false,"error":{"code":"NOT_PAIRED","message":"pairing required: device is not approved yet","details":{"code":"PAIRING_REQUIRED","reason":"not-paired","requestId":"af0b5d81-7bd2-4b86-80b0-4a489b541974","remediationHint":"Approve this device from the pending pairing requests.","deviceId":"f044a1f5…","requestedRole":"operator","requestedScopes":["operator.read","operator.write","operator.admin"]}}}}
{"dir":"close","code":1008,"reason":"pairing required: device is not approved yet (requestId: af0b5d81-7bd2-4b86-80b0-4a489b541974)"}
```

After `openclaw devices approve af0b5d81-…` inside the container (`ws-device-2-paired.jsonl`):

```jsonl
{"dir":"in","frame":{"type":"res","id":"qa-1","ok":true,"payload":{"type":"hello-ok",…,"auth":{"method":"token","role":"operator","scopes":["operator.read","operator.write","operator.admin"],"deviceToken":"<redacted>","issuedAtMs":1792027507058,…}}}}
{"dir":"in","frame":{"type":"res","id":"qa-6","ok":false,"error":{"code":"INVALID_REQUEST","message":"unknown method: skills.list"}}}
{"dir":"in","frame":{"type":"res","id":"qa-7","ok":false,"error":{"code":"INVALID_REQUEST","message":"unknown method: auth.whoami"}}}
{"dir":"in","frame":{"type":"res","id":"qa-8","ok":false,"error":{"code":"INVALID_REQUEST","message":"unknown method: skills.uninstall"}}}
{"dir":"in","frame":{"type":"res","id":"qa-9","ok":false,"error":{"code":"INVALID_REQUEST","message":"unknown method: models.set"}}}
{"dir":"in","frame":{"type":"res","id":"qa-10","ok":false,"payload":{"ok":false,"error":"Skill \"qa-nonexistent-skill-zz9\" not found on ClawHub. …"},"error":{"code":"UNAVAILABLE","message":"Skill \"qa-nonexistent-skill-zz9\" not found on ClawHub. …"}}}
```

Auth failures, which are the real auth signal (`ws-real-bad.jsonl`, `ws-real-none.jsonl`, `ws-device-3-paired-notoken.jsonl`):

```jsonl
{"error":{"code":"INVALID_REQUEST","message":"unauthorized: gateway token mismatch (provide gateway auth token)","details":{"code":"AUTH_TOKEN_MISMATCH","authReason":"token_mismatch","canRetryWithDeviceToken":false,"recommendedNextStep":"update_auth_credentials"}}}
{"error":{"code":"NOT_PAIRED","message":"device identity required","details":{"code":"DEVICE_IDENTITY_REQUIRED"}}}
{"error":{"code":"INVALID_REQUEST","message":"unauthorized: gateway token missing (provide gateway auth token)","details":{"code":"AUTH_TOKEN_MISSING","authReason":"token_missing","canRetryWithDeviceToken":false,"recommendedNextStep":"update_auth_configuration"}}}
```

Each is followed by close `1008` with the message as the reason.

## 4. Discovery and QR

- **mDNS service type matches.** Both clients browse `_openclaw-gw._tcp` (`NsdDiscoveryServiceImpl.kt:22`,
  `NWBrowserDiscoveryService.swift:35`), and that is the type the docs say the gateway advertises. (The M5 brief said
  `_openclaw._tcp`; that type does not exist.)
- **The gateway does not advertise by default off macOS.** The bundled `bonjour` plugin manifest has
  `"enabledByDefaultOnPlatforms": ["darwin"]`. Linux, Windows, and container gateways must opt in
  (`openclaw plugins enable bonjour`). In containers it also auto-disables unless `OPENCLAW_DISABLE_BONJOUR=0`.
  Observed: `bonjour` was not among the 14 loaded plugins, and the gateway log had 0 bonjour lines. Docker
  Desktop on Windows would not bridge multicast anyway. Whether a phone discovers a gateway that has Bonjour enabled **needs a device**.
- TXT keys (docs): `role`, `displayName`, `lanHost`, `gatewayPort`, `transport`, optional `gatewayTls*`.
  There is **no `version` key**, so the `version` both clients read (`NsdDiscoveryServiceImpl.kt:82`,
  `NWBrowserDiscoveryService.swift:221`) is always null. Minor.
- **The upstream QR cannot be read by GhostCrab.** `openclaw qr --json` returns a `setupCode` that is base64url JSON
  `{"url":"ws://172.17.0.2:18789","bootstrapToken":"<43 chars>","expiresAtMs":…}`. That is a WS URL,
  a short-lived bootstrap token rather than the gateway token, and no `v`. GhostCrab accepts only
  `ghostcrab://pair?p=<base64url {v:1,url:http(s),token,name}>` or a plain http(s) URL
  (`PairCode.kt:15`, `:73-86`; iOS `PairingLink.swift`), so it rejects the setup code as Invalid. The QR output
  also reported `"access":"limited","accessDowngraded":true` because the URL is plain `ws://` on LAN. Per
  `/app/docs/cli/qr.md`, a setup-code operator token never includes `operator.admin`.
- The `docker/tunnel-qr` helper (plain tunnel URL) still produces a URL GhostCrab accepts. That is client-side
  parsing only; connecting through it hits the same protocol gaps.

## 5. Auth-probe consequences (ADR-002 is invalid against the real server)

ADR-002's probe (`/health` OK, then `/status` without a token: 401 means Token, otherwise None) cannot work:
`/status` is `200 text/html` for every caller. Per the code (`GatewayConnectionManagerImpl.kt:71-85`,
`GatewayConnectionManagerImpl.swift:136-160`), a real token-protected LAN gateway is reported as
`AuthRequirement.None`. The Dashboard then shows the no-auth security banner (`DashboardScreen.kt:152`,
`DashboardScreen.swift:71`) on a gateway that **does** require auth. Connecting with a **wrong** token succeeds,
because `status()` returns 200 HTML. Device confirmation is still needed; the server behaviour behind both is verified.

Reliable auth signals that exist on the real server:
1. The WS `connect` error codes in §3: `AUTH_TOKEN_MISSING`, `AUTH_TOKEN_MISMATCH`, `DEVICE_IDENTITY_REQUIRED`. These are authoritative.
2. The interim HTTP heuristic `GET /readyz` or `/startupz` with the bearer. The detailed body (`version`, `uptimeMs`,
   `failing`) comes back only when the token is valid. Caveat from `health.md`: "local-direct" callers also get
   details without a token, so this is unreliable over loopback or adb reverse.

## 6. M5 checklist

- [x] Run `ghcr.io/openclaw/openclaw:latest`; record image digest and version. Done in §1. It ran on Docker Desktop, port-published to localhost, not bridged onto the LAN.
- [ ] Android + iOS: mDNS scan, manual entry by IP, QR plain URL. **Needs device.** Server facts: Bonjour is off by default on Linux/Docker; the service type matches; the upstream setup-code QR is not parseable (§4).
- [x] `/health` JSON, `/status` HTML. Server verified (§2). "Connect succeeds, Dashboard renders" **needs device**; per the code, connect succeeds with `version="unknown"` and empty capabilities.
- [ ] Token gateway: probe says Token; wrong token shows URL + HTTP 401 + exception class. **Fails against the real server by analysis:** no client HTTP path returns 401 for a missing or wrong token, so the probe says None and a wrong token connects (§5). Client fix required; device confirmation still needed.
- [x] Config editor shows NoConfigApi (HTML at `/config`). Server verified: `200 text/html`. The UI state **needs device**.
- [x] Models → NotSupported on 404; AI → SkillUnavailable. Server verified: every `/api/*` is `404 text/plain`. "Nothing crashes" **needs device**.
- [x] Capture the real `/ws` handshake and the `skills.*` names; compare with `GatewayWsClient` / `InstalledSkillRepositoryImpl`. Done in §3.
- [ ] `http://<public IP>` blocked with the CleartextBlocked text; toggling takes effect live. Client-only behaviour. **Needs device.**
- [ ] Kill the gateway: Dashboard goes Degraded in < 60 s. **Needs device.**
- [ ] `adb logcat | grep -i bearer` empty; Xcode console likewise. **Needs device.** The current WS path sends the bearer only in the upgrade header, which the server ignores.
- [ ] Phase 6/7 acceptance (port round-trip, port 99999 error, raw JSON round-trip, concurrent-edit reload, model swap on Dashboard). **Cannot pass against the real gateway:** `PATCH /config/*` and `/api/models/*` are 404. The real equivalents are `config.patch` with `baseHash` and `config.patch` on `agents.defaults.model.primary`, which need the client rewrite.
- [ ] **Outcome:** ADR-0007 "Gateway protocol alignment". Recommendation in §7; the ADR itself is not written yet. README and CLAUDE.md feature claims are not updated in this PR.

## 7. ADR-0007 recommendation: adapt the clients to the real WS protocol

**Recommend: adapt the clients to the real WS protocol. Do not pursue an upstream REST PR.**

Reasons:
1. **Upstream already chose WS.** `protocol.md` calls the WS protocol "the single control plane". The one
   HTTP RPC surface upstream ships, `admin-http-rpc` (`POST /api/v1/admin/rpc`), is opt-in, disabled by default,
   and documented as "for host tooling that cannot keep a WebSocket open. … For OpenClaw clients … use
   WebSocket RPC instead". A PR adding GhostCrab's `/api/*` shape would contradict that documented direction.
2. **The real WS surface covers almost everything GhostCrab needs.** `status`, `health`, `config.get` /
   `config.patch` (with a `baseHash` conflict guard in place of ETag/If-Match), `models.list` plus
   `config.patch agents.defaults.model.primary`, `skills.status` / `skills.install` (same params) /
   `skills.update` / `skills.search` / `skills.detail`, and pairing. The gaps are skill removal, install progress
   events, and AI recommend, and a REST PR would not close those either.
3. **Device identity is mandatory regardless.** A shared token without a device identity gets `scopes: []` on
   WS. HTTP bearer auth is all-or-nothing operator access, which upstream limits to the admin plugin. A secure
   mobile client needs Ed25519 device identity, pairing approval, and a per-device token. That only exists on WS.
4. **Upstream publishes a machine-readable contract.** `@openclaw/gateway-protocol` ships `protocol.schema.json`
   (TypeBox), so Kotlin and Swift DTOs can be generated instead of guessed a second time.
5. **An upstream PR has no timeline,** and users would have to upgrade their gateways. Adapting the clients works against `2026.10.3` today.

A possible interim option, not recommended as the target: the `admin-http-rpc` plugin gives
`config.get/patch`, `models.list`, and `status` over plain HTTP with the existing bearer. It requires the
user to enable a plugin that hands full operator rights to any bearer holder, and it has no `skills.*` in its allowlist.

## 8. Required client changes (prioritised)

Line numbers are on `main` @ `f102992`.

**P0: connection truthfulness and security**
1. Replace the `/status`-401 auth probe with the WS `connect` outcome (`AUTH_TOKEN_MISSING` → Token; `AUTH_TOKEN_MISMATCH` → typed auth error showing URL and code). Android `data/impl/GatewayConnectionManagerImpl.kt:71-85`, `:99-113`; iOS `Data/Impl/GatewayConnectionManagerImpl.swift:136-160`. Update ADR-002.
2. Stop sourcing identity, version, and capabilities from `GET /status`. Use `hello-ok.server.version` and the `status` RPC. Android `data/api/OpenClawApiClient.kt:105-109` plus `dto/StatusResponse.kt`; iOS `Data/Api/OpenClawAPIClient.swift:178`.

**P1: WS transport (Android first; iOS needs a new WS client)**
3. Replace the JSON-RPC 2.0 envelope with `req`/`res`/`event` frames, string ids, and string error codes with `details`. Android `data/ws/JsonRpc.kt:13-48`; `GatewayWsClient.kt:80-124` (dispatch, request, the `WsRpcException(code:Int)` at `:149`).
4. Handshake: wait for `connect.challenge`, send `connect` (`minProtocol/maxProtocol: 4`, `client.id: "openclaw-android"` / `"openclaw-ios"`, `mode: "ui"`, `role: "operator"`, scopes, `auth.token`, signed `device`), then read `hello-ok` (`auth.scopes`, `auth.deviceToken`, `policy.maxPayload`, `tickIntervalMs`). Android `OpenClawApiClient.kt:251-254`, `GatewayWsClient.kt:43-77`.
5. Device identity: one Ed25519 key per install. Signature payload is v3 (`v3|deviceId|clientId|mode|role|scopes|signedAt|token|nonce|platform|deviceFamily`), `deviceId = hex(sha256(raw pubkey))`, base64url encoding. Persist the per-gateway `deviceToken` and its scopes next to the profile token. Android `data/storage/ConnectionProfileStore.kt` (note: Android Keystore has no Ed25519 below API 33 and minSdk is 26, so this needs a software key under EncryptedSharedPreferences or Tink); iOS `Data/Storage/Keychain.swift` (CryptoKit `Curve25519.Signing`).
6. Pairing UX: on `PAIRING_REQUIRED`, show the `requestId` and the exact approval command (`openclaw devices approve <requestId>`), then retry. Remote devices are never auto-approved.

**P1: feature remaps**
7. Scopes: replace `auth.whoami` with `hello-ok.auth.scopes`. Android `data/impl/ScopeProbeImpl.kt:27`, `:39`; iOS `Data/Impl/ScopeProbeImpl.swift:30`, `OpenClawAPIClient.swift:482`.
8. Skills: `skills.list` → `skills.status` with a new DTO mapping (`skillKey`/`name`/`source`/`disabled`/`eligible`). `skills.install` keeps its params; read the single `res`, drop `skills.install.progress`, optionally listen for `skills.changed`. `skills.uninstall` has no equivalent, so hide it or downgrade it to disable via `skills.update` (verify first). Android `data/impl/InstalledSkillRepositoryImpl.kt:63`, `:107`, `:126-134`, `:156`; iOS `Data/Impl/InstalledSkillRepositoryImpl.swift:40`, `:54`, `:86` and `OpenClawAPIClient.swift:359`, `:398`, `:442`.
9. Config: `GET /config` and `PATCH /config/{section}` with ETag/If-Match → `config.get` (`hash`, `parsed`/`config`, `raw` JSON5 with redacted secrets) and `config.patch {raw, baseHash}` (admin scope, 30/min). The stale-`baseHash` error code was not exercised, so the 412 and 422 mapping is unknown until tested. Android `OpenClawApiClient.kt:121-173`, `data/impl/ConfigRepositoryImpl.kt`; iOS `OpenClawAPIClient.swift:203-260`.
10. Models: `GET /api/models/status` → `models.list` (returns `{models, accountSelection}`); `POST /api/models/active` → `config.patch` on `agents.defaults.model.primary`. Android `OpenClawApiClient.kt:184-210`; iOS `OpenClawAPIClient.swift:271-303`.

**P2**
11. AI recommend: there is no upstream endpoint, and the `"ai.recommend"` capability (`AIRecommendationServiceImpl.kt:40`, `:95`; iOS `AIRecommendationServiceImpl.swift:20`) can never appear. Either feature-flag it off or redesign it on an agent turn (`chat.send` / `sessions.*`). Undetermined; needs its own decision (it affects ADR-004).
12. QR: accept the upstream setup code (base64url `{url: ws(s)://…, bootstrapToken, expiresAtMs}`) and `ws(s)://` URLs; send `auth.bootstrapToken` on `connect`. Android `domain/util/PairCode.kt:15`, `:73-86`; iOS `Domain/Util/PairingLink.swift`.
13. Discovery copy and onboarding: tell Linux and Docker users to run `openclaw plugins enable bonjour`, and that Docker bridge networking does not carry mDNS. Take the version from `hello-ok`, not TXT (`NsdDiscoveryServiceImpl.kt:82`, `NWBrowserDiscoveryService.swift:221`).
14. `scripts/mock-gateway/`: rewrite it to the real protocol or retire it. It and the unit tests built on it validate a contract that does not exist.

## 9. Reproduction

Scratch artefacts (not committed): `ws-capture.mjs` (scenarios `passive`, `android-immediate`,
`android-after-10s`, `real-good`, `real-bad`, `real-none`, `device`), `probe.sh` (curl matrix), and captures
`http-{noauth,goodtoken,badtoken}.txt`, `ws-*.jsonl`, `hello-features*.json`.

```bash
docker volume create openclaw-qa-state
docker create --name openclaw-qa -p 18789:18789 -v openclaw-qa-state:/home/node/.openclaw ghcr.io/openclaw/openclaw:latest
docker cp openclaw.json openclaw-qa:/home/node/.openclaw/openclaw.json   # config from §1
docker start openclaw-qa
docker exec openclaw-qa node openclaw.mjs devices list                  # pending pairing requests
docker exec openclaw-qa node openclaw.mjs devices approve <requestId>
```

Undetermined here: phone-side behaviour for every "needs device" item, mDNS reception on a real LAN,
stale-`baseHash` error codes, whether any skill-removal RPC exists outside `features.methods`, and behaviour under TLS (`wss://`).
