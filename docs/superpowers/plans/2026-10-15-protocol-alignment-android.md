# Gateway Protocol Alignment (Android) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Android client speak the real OpenClaw gateway control plane (WS protocol 4: `connect.challenge` → Ed25519-signed `connect` → `hello-ok`, device pairing, `req`/`res`/`event` frames) so connect, auth detection, config, models and skills work against `ghcr.io/openclaw/openclaw` 2026.10.3, as decided in ADR-0007.

**Architecture:** A new `data/gateway` package holds the frame codec, `GatewayRpcClient` (string-id correlation, events, challenge capture, timeouts), the Tink-backed Ed25519 `DeviceIdentity`, and `GatewayHandshake`. `GatewayConnectionManagerImpl` opens one control-plane socket per session after a `/health` check and every repository calls RPCs on that socket. Frozen `domain/repository` signatures do not change; additive members follow ADR-0005. A pairing-approval panel replaces the error path for `PAIRING_REQUIRED`. Tests replay frames captured from the real gateway (`fixtures/gateway-protocol/2026.10.3/`), and `scripts/mock-gateway` is rewritten to the same protocol.

**Tech Stack:** Kotlin 2.4, kotlinx.serialization 1.11, Ktor 3.6 (OkHttp engine + WebSockets), Koin 4.2, Jetpack Compose (Material 3), Tink `tink-android` 1.8.0 (`subtle.Ed25519Sign`), JUnit 5, Turbine, MockK, kotlinx-coroutines-test; Node 24 + `ws` 8 for the mock gateway.

---

## Read first

- `docs/adr/0007-gateway-protocol-alignment.md` (the decision), `docs/QA_REAL_GATEWAY_2026-10.md` §3, §5, §8 (the evidence), `docs/adr/0005-additive-repository-methods.md` (the rule for new interface members).
- `CLAUDE.md`: strict layering (UI → ViewModel → domain → data), frozen `domain/repository`, KDoc with `@param` / `@return` / `@throws` on every public API, no `Any`, no unchecked casts, no swallowed exceptions, brand tokens only (no hex outside `ui/theme`), JetBrains Mono (`MonoFontFamily`) for URLs, ids and commands.
- Protocol facts this plan relies on (all from the 2026.10.3 captures or `protocol.schema.json` of `@openclaw/gateway-protocol@2026.10.3`):
  - First server frame: `{"type":"event","event":"connect.challenge","payload":{"nonce":"…","ts":<ms>}}`.
  - First client frame must be `{"type":"req","id":"<string>","method":"connect","params":{…}}`; anything else closes `1008`.
  - Responses: `{"type":"res","id":"<string>","ok":true,"payload":…}` or `{"type":"res","id":"…","ok":false,"error":{"code":"…","message":"…","details":{…}}}`.
  - Device signature payload v3: `v3|deviceId|clientId|clientMode|role|scopes(comma)|signedAt|token|nonce|platform|deviceFamily`, `platform`/`deviceFamily` trimmed and ASCII-lowercased, `deviceId = hex(sha256(raw 32-byte public key))`, key and signature base64url without padding, `signedAt` = challenge `ts`.
- Pinned by the verification run of 2026-10-15 against the same 2026.10.3 image (frames in Task 1; ADR-0007 "Verification"):
  - `client.id` and `client.mode` are closed allow-lists checked before auth: `ghostcrab-android` → `INVALID_REQUEST invalid connect params: at /client/id: must be equal to one of the allowed values`, close 1008; `mode: "operator"` the same for `/client/mode`; `ui` and `cli` both get full scopes. On a paired device a different `platform` (case-insensitive) re-triggers pairing (`reason: "metadata-upgrade"`); a different `client.id` does not. GhostCrab therefore sends one constant identity: `openclaw-android`, `ui`, `android`, no `deviceFamily`.
  - Pairing: the `requestId` is stable across reconnects; the server closes an unpaired socket with 1008 right after `PAIRING_REQUIRED`; a socket that never sends `connect` is closed after ~15 s; `device.pair.resolved {requestId, deviceId, decision}` reaches only an already-paired admin socket. Re-attempting the connect is the only way to notice an approval.
  - Setup-code bootstrap tokens authenticate only `role: "node"` connects; over `ws://` they yield a node token with `scopes: []` and no operator handoff, and a node token is refused for operator (`AUTH_DEVICE_TOKEN_MISMATCH`). Failed attempts do not consume a code; the first valid use binds it to that device. GhostCrab (an operator client) never connects with one.
  - `config.patch`: stale or unknown `baseHash` → `INVALID_REQUEST "config changed since last load; re-run config.get and retry"` (no `details`); missing → `"config base hash required for <path>; …"`; validation → `"invalid config: <path>: …"` with `details.issues[{path, message, allowedValues?}]`; success → `{ok, path, hash, config, changedPaths, sentinel}`.
  - `skills.update {skillKey, enabled}` → `{ok:true, skillKey, config:{enabled}}`, also for keys the gateway does not know. `skills.status` stays stale until the `config.changed` event (~80 ms later, sometimes before the response); no `skills.changed` event is sent. A ClawHub install answers `{ok, message, slug, version, targetDir, …}` and is listed with `source: "openclaw-workspace"` plus `clawhub {status, slug, installedVersion, installedAt, …}` and `skillCard`.
- Conventions:
  - Run every command from the repo root in Git Bash (`./gradlew`). Unit tests: `./gradlew testDebugUnitTest --tests "<FQCN>"`. Full gate before each commit that touches `main` sources: `./gradlew testDebugUnitTest detekt lintDebug`.
  - Work on `git switch -c feat/protocol-alignment-android` (never on `main`). One commit per task; Conventional Commits; subject + body only; no co-author trailer and no AI attribution of any kind. Open one PR at the end and squash-merge it.
  - Baseline: before Task 1, run `./gradlew testDebugUnitTest detekt lintDebug`. If it is not green on a clean checkout, stop and report that first.
  - Secrets: never log a token, bootstrap token, device token or seed. Every new type that holds one overrides `toString` to redact it. The test token strings (`shared-token`, `dt-test-0001`, `bt-test-0001`) are dummies.
  - **⚠ manual** marks a step that needs a real gateway or a device. Everything else runs on the Windows dev box.

## Task order

Run the tasks in numeric order; every task assumes the ones before it. The dependencies that are not obvious from the numbering:

1. Tasks 1–9 build the protocol layer bottom-up. Nothing in the app uses it until Task 10, so the app compiles and behaves as today after each of them. Task 8's tests use `OPERATOR_SCOPES` and the RFC 8032 keys from Task 5's `TestKeys.kt`; every test from Task 2 on reads Task 1's fixtures.
2. Task 10 switches the connection manager to the WS handshake (repositories still compile through `requireClient()`).
3. Task 11 pins the allow-listed client identity; Tasks 12–18 move one feature at a time onto the session socket. Task 13 introduces `liveSession()` in `DataModule.kt`, which Tasks 15 and 17 use; Task 17 also uses Task 15's `ConfigWrites.kt`.
4. Tasks 19–23 are the UI (pairing panel, setup-code explanation, discovery copy). Task 21 reuses Task 20's `PairingApprovalCard`.
5. Task 24 deletes the dead REST/JSON-RPC code and needs Tasks 10–18. Task 25 (mock gateway) needs Task 1's fixtures and nothing else from the app, so it can be done any time after Task 1. Task 26 (⚠ manual real-gateway pass) needs everything before it. Task 27 is docs.

## File structure

| File | Status | Responsibility |
|---|---|---|
| `fixtures/gateway-protocol/2026.10.3/*.json` | create | 23 frames captured from the real gateway (trimmed, secrets replaced); shared with iOS tests and the mock gateway |
| `app/build.gradle.kts` | modify | fixtures as test resources; Tink dependency; `AI_PRO_ENABLED = false` |
| `gradle/libs.versions.toml` | modify | `tink-android` 1.8.0 |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayFrames.kt` | create | frame types, codec, JSON helpers |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayErrors.kt` | create | `ErrorShape` → typed `GatewayException` |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayRpcClient.kt` | create | request/response correlation, events, challenge, timeouts |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceIdentity.kt` | create | Ed25519 identity, device id, `DeviceIdentityProvider` |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceAuthPayload.kt` | create | v3 signature payload |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceTokenStore.kt` | create | `StoredDeviceToken`, `DeviceTokenStore`, `gatewayKeyOf` |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayHello.kt` | create | `HelloOk`, `ConnectChallenge`, auth-mode and capability mapping |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayHandshake.kt` | create | `ClientInfo` (+ the pinned `ClientInfo.android`), `ConnectAuth`, `selectConnectAuth`, connect params, `perform` |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/storage/DeviceIdentityStore.kt` | create | encrypted seed storage |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/storage/ConnectionProfileStore.kt` | modify | implements `DeviceTokenStore` in the token file |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt` | modify | `openWsSession()`, `webSocketUrl()`; later loses the dead REST calls |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/GatewayConnectionManagerImpl.kt` | rewrite | WS handshake session lifecycle |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ScopeProbeImpl.kt` | rewrite | scopes from `hello-ok` |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImpl.kt` | rewrite | `skills.status` / `skills.install` / `skills.update` |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ConfigWrites.kt` | create | `config.patch` params and error mapping |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ConfigRepositoryImpl.kt` | rewrite | `config.get` / `config.patch` |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ModelRepositoryImpl.kt` | rewrite | `models.list` + `agents.defaults.model.primary` |
| `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/AIRecommendationServiceImpl.kt` | rewrite | honest "unsupported" |
| `app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/config/*.kt` | rewrite | real `gateway` section schema |
| `app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/sections/GatewaySectionEditor.kt` | rewrite | editor for the real schema |
| `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/PairingApproval.kt` | create | `PairingPrompt`, `awaitPairingApproval`, `connectAwaitingApproval` |
| `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/PairingApprovalCard.kt` | create | approval panel composable |
| `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ManualEntry*.kt`, `ConnectionPicker*.kt` | modify | pairing state |
| `app/src/main/kotlin/com/openclaw/ghostcrab/domain/util/PairCode.kt`, `GatewayUrl.kt` | modify | setup codes recognised and explained (never used), `ws(s)://` URLs |
| `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanViewModel.kt`, `MainActivity.kt` | modify | setup-code explanation |
| `app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt`, `UiModule.kt` | modify | wiring |
| `app/src/test/kotlin/com/openclaw/ghostcrab/testutil/*.kt` | create | fixtures loader, scripted socket, in-memory stores, test keys |
| `scripts/mock-gateway/*` | rewrite | protocol-4 mock + conformance tests + real-gateway probe |
| `.github/workflows/ci.yml` | modify | `mock-gateway` job |

---

### Task 1: Real-gateway protocol fixtures

**Files:**
- Create: `fixtures/gateway-protocol/2026.10.3/` (23 files below)
- Modify: `app/build.gradle.kts` (inside `android { }`, after the `testOptions { … }` block)
- Create: `app/src/test/kotlin/com/openclaw/ghostcrab/testutil/GatewayFixtures.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/testutil/GatewayFixturesTest.kt`

These are frames from the M5 captures against 2026.10.3 (`docs/QA_REAL_GATEWAY_2026-10.md` §3, §9) plus nine from the verification run of 2026-10-15 against the same image (client-id allow-list, metadata-upgrade pairing, `config.patch` errors and success, `skills.update`, `config.changed`, `skills.install`; `res-skills-status` gained the ClawHub-installed `weather` entry). Trimmed: `hello-ok.features` keeps the methods/events GhostCrab uses, `snapshot` keeps only the self presence entry, `skills.status` keeps two of 51 skills, `config.get` keeps the fields GhostCrab reads. Replaced: the issued device token (`dt-test-0001`); `recoveryScope` removed. Everything else is byte-for-byte what the gateway sent. If the iOS plan already created this directory, compare with `git diff --no-index` against the contents below and skip Step 1.

- [ ] **Step 1: Create the fixture files**

`fixtures/gateway-protocol/2026.10.3/challenge.json`:
```json
{"type":"event","event":"connect.challenge","payload":{"nonce":"71d63897-7ebd-44e8-a27a-20ac1598b846","ts":1792027507354,"capabilities":["model-catalog-snapshot"]}}
```

`fixtures/gateway-protocol/2026.10.3/hello-ok-paired.json`:
```json
{"type":"res","id":"qa-1","ok":true,"payload":{"type":"hello-ok","protocol":4,"server":{"version":"2026.10.3","buildId":"2026.10.3-59cb44796b0f-2026-10-13T07-32-01.005Z","bootId":"1c37f3f1-5892-4226-94e9-0002e32dccf6","controlUiBuildSource":"bundled","connId":"9e1f7302-0538-492f-bd6f-fb7682c2ad47"},"features":{"methods":["health","status","config.get","config.patch","config.schema.lookup","models.list","skills.status","skills.search","skills.install","skills.update","device.pair.list","chat.send"],"events":["connect.challenge","presence","tick","shutdown","health","device.pair.requested","device.pair.resolved","skills.changed"],"capabilities":["model-catalog-snapshot","profile-binding-v1"]},"snapshot":{"presence":[{"host":"ecb0a0611ff5","ip":"172.17.0.2","version":"2026.10.3","platform":"linux 6.18.54.1-microsoft-standard-WSL2","deviceFamily":"Linux","mode":"gateway","reason":"self","instanceId":"22f84fc6-7434-4ecb-a0c9-5225751dbcff","text":"Gateway: ecb0a0611ff5 (172.17.0.2) · app 2026.10.3 · mode gateway · reason self","ts":1792027507392}],"stateVersion":{"presence":3,"health":10},"uptimeMs":422099,"authMode":"token","appliedConfigHash":"hmac-sha256:v1:8TC1wWOnH2Dg0m3BtlCjnweykbH02riydC06wWjOVMg"},"auth":{"method":"token","role":"operator","scopes":["operator.read","operator.write","operator.admin"],"deviceToken":"dt-test-0001","issuedAtMs":1792027507058},"policy":{"maxPayload":26214400,"maxBufferedBytes":52428800,"tickIntervalMs":30000,"attachments":{"maxBytes":19464192,"maxImageBytes":6291456},"allowedSessionVisibilities":["shared","read-only","suggest","draft"],"hasMultipleSessionSharingIdentities":false}}}
```

`fixtures/gateway-protocol/2026.10.3/hello-ok-noscope.json` (token, no device identity: the server clears the scopes):
```json
{"type":"res","id":"qa-1","ok":true,"payload":{"type":"hello-ok","protocol":4,"server":{"version":"2026.10.3","buildId":"2026.10.3-59cb44796b0f-2026-10-13T07-32-01.005Z","bootId":"1c37f3f1-5892-4226-94e9-0002e32dccf6","controlUiBuildSource":"bundled","connId":"3305a6e0-f47a-4c80-82f7-08d16c898658"},"features":{"methods":["health","status","config.get","config.patch","config.schema.lookup","models.list","skills.status","skills.search","skills.install","skills.update","device.pair.list","chat.send"],"events":["connect.challenge","presence","tick","shutdown","health","device.pair.requested","device.pair.resolved","skills.changed"],"capabilities":["model-catalog-snapshot","profile-binding-v1"]},"snapshot":{"presence":[],"stateVersion":{"presence":1,"health":7},"uptimeMs":350281,"appliedConfigHash":"hmac-sha256:v1:8TC1wWOnH2Dg0m3BtlCjnweykbH02riydC06wWjOVMg"},"auth":{"method":"token","role":"operator","scopes":[]},"policy":{"maxPayload":26214400,"maxBufferedBytes":52428800,"tickIntervalMs":30000,"attachments":{"maxBytes":19464192,"maxImageBytes":6291456},"allowedSessionVisibilities":["shared","read-only","suggest","draft"],"hasMultipleSessionSharingIdentities":false}}}
```

`fixtures/gateway-protocol/2026.10.3/err-pairing-required.json`:
```json
{"type":"res","id":"qa-1","ok":false,"error":{"code":"NOT_PAIRED","message":"pairing required: device is not approved yet","details":{"code":"PAIRING_REQUIRED","reason":"not-paired","requestId":"af0b5d81-7bd2-4b86-80b0-4a489b541974","remediationHint":"Approve this device from the pending pairing requests.","deviceId":"f044a1f50388e949137206dbf72f4c79c9b499af0e2a4048b005bbf217a9f2e2","requestedRole":"operator","requestedScopes":["operator.read","operator.write","operator.admin"]}}}
```

`fixtures/gateway-protocol/2026.10.3/err-token-mismatch.json`:
```json
{"type":"res","id":"qa-1","ok":false,"error":{"code":"INVALID_REQUEST","message":"unauthorized: gateway token mismatch (provide gateway auth token)","details":{"code":"AUTH_TOKEN_MISMATCH","authReason":"token_mismatch","canRetryWithDeviceToken":false,"recommendedNextStep":"update_auth_credentials"}}}
```

`fixtures/gateway-protocol/2026.10.3/err-token-missing.json`:
```json
{"type":"res","id":"qa-1","ok":false,"error":{"code":"INVALID_REQUEST","message":"unauthorized: gateway token missing (provide gateway auth token)","details":{"code":"AUTH_TOKEN_MISSING","authReason":"token_missing","canRetryWithDeviceToken":false,"recommendedNextStep":"update_auth_configuration"}}}
```

`fixtures/gateway-protocol/2026.10.3/err-device-identity-required.json`:
```json
{"type":"res","id":"qa-1","ok":false,"error":{"code":"NOT_PAIRED","message":"device identity required","details":{"code":"DEVICE_IDENTITY_REQUIRED"}}}
```

`fixtures/gateway-protocol/2026.10.3/err-missing-scope.json`:
```json
{"type":"res","id":"qa-3","ok":false,"error":{"code":"FORBIDDEN","message":"missing scope: operator.read","details":{"code":"MISSING_SCOPE","missingScope":"operator.read","requiredScopes":["operator.read"]}}}
```

`fixtures/gateway-protocol/2026.10.3/err-unknown-method.json`:
```json
{"type":"res","id":"qa-6","ok":false,"error":{"code":"INVALID_REQUEST","message":"unknown method: skills.list"}}
```

`fixtures/gateway-protocol/2026.10.3/err-skill-not-found.json`:
```json
{"type":"res","id":"qa-10","ok":false,"payload":{"ok":false,"error":"Skill \"qa-nonexistent-skill-zz9\" not found on ClawHub. Run `openclaw skills search qa-nonexistent-skill-zz9` to find the right skill reference."},"error":{"code":"UNAVAILABLE","message":"Skill \"qa-nonexistent-skill-zz9\" not found on ClawHub. Run `openclaw skills search qa-nonexistent-skill-zz9` to find the right skill reference."}}
```

`fixtures/gateway-protocol/2026.10.3/res-health.json`:
```json
{"type":"res","id":"qa-2","ok":true,"payload":{"ok":true,"ts":1792027403916,"durationMs":9,"defaultAgentId":"main"}}
```

`fixtures/gateway-protocol/2026.10.3/res-skills-status.json`:
```json
{"type":"res","id":"qa-3","ok":true,"payload":{"workspaceDir":"/home/node/.openclaw/workspace","managedSkillsDir":"/home/node/.openclaw/skills","agentId":"main","skills":[{"name":"1password","description":"Set up and use 1Password CLI for sign-in, desktop integration, and reading or injecting secrets.","source":"openclaw-bundled","bundled":true,"filePath":"/app/skills/1password/SKILL.md","baseDir":"/app/skills/1password","skillKey":"1password","always":false,"disabled":true,"blockedByAllowlist":false,"eligible":false,"emoji":"🔐","homepage":"https://developer.1password.com/docs/cli/get-started/","missing":{"bins":["op"],"anyBins":[],"env":[],"config":[],"os":[]},"configChecks":[],"blockedByAgentFilter":false,"platformIncompatible":false,"modelVisible":false,"userInvocable":true,"commandVisible":false,"requirements":{"bins":["op"],"anyBins":[],"env":[],"config":[],"os":[]},"install":[{"id":"brew","kind":"brew","label":"Install 1Password CLI (brew)","bins":["op"]}]},{"name":"browser-automation","description":"Use when controlling web pages with the OpenClaw browser tool, especially multi-step flows, login checks, tab management, or recovery from stale refs/timeouts.","source":"openclaw-extra","bundled":false,"filePath":"/home/node/.openclaw/plugin-skills/browser-automation/SKILL.md","baseDir":"/home/node/.openclaw/plugin-skills/browser-automation","skillKey":"browser-automation","always":false,"disabled":false,"blockedByAllowlist":false,"eligible":true,"missing":{"bins":[],"anyBins":[],"env":[],"config":[],"os":[]},"configChecks":[],"blockedByAgentFilter":false,"platformIncompatible":false,"modelVisible":true,"userInvocable":false,"commandVisible":false,"requirements":{"bins":[],"anyBins":[],"env":[],"config":[],"os":[]},"install":[]},{"name":"weather","description":"Get current weather and forecasts (no API key required).","source":"openclaw-workspace","bundled":false,"filePath":"/home/node/.openclaw/workspace/skills/weather/SKILL.md","baseDir":"/home/node/.openclaw/workspace/skills/weather","skillKey":"weather","always":false,"disabled":false,"blockedByAllowlist":false,"eligible":true,"homepage":"https://wttr.in/:help","install":[],"clawhub":{"status":"linked","valid":true,"registry":"https://clawhub.ai","slug":"weather","ownerHandle":"steipete","installedVersion":"1.0.0","installedAt":1792035309473},"skillCard":{"present":true,"path":"/home/node/.openclaw/workspace/skills/weather/skill-card.md","sizeBytes":1748}}]}}
```

`fixtures/gateway-protocol/2026.10.3/res-models-list.json` (no provider configured on the QA gateway, hence empty):
```json
{"type":"res","id":"qa-4","ok":true,"payload":{"models":[],"accountSelection":{"kind":"automatic","label":"Automatic account selection"}}}
```

`fixtures/gateway-protocol/2026.10.3/res-config-get.json`:
```json
{"type":"res","id":"qa-5","ok":true,"payload":{"path":"/home/node/.openclaw/openclaw.json","exists":true,"raw":"{\n  \"gateway\": {\n    \"mode\": \"local\",\n    \"port\": 18789,\n    \"bind\": \"lan\",\n    \"auth\": {\n      \"mode\": \"token\",\n      \"token\": \"__OPENCLAW_REDACTED__\"\n    }\n  },\n  \"agents\": {\n    \"entries\": {\n      \"main\": {}\n    }\n  }\n}","parsed":{"gateway":{"mode":"local","port":18789,"bind":"lan","auth":{"mode":"token","token":"__OPENCLAW_REDACTED__"}},"agents":{"entries":{"main":{}}}},"config":{"gateway":{"mode":"local","port":18789,"bind":"lan","auth":{"mode":"token","token":"__OPENCLAW_REDACTED__"}},"agents":{"entries":{"main":{}}}},"valid":true,"issues":[],"warnings":[],"hash":"hmac-sha256:v1:u_qU7JLKF7G9u0jc_GfUp-dKovJYwiLpy0gNxuKjka0","configRevisionHash":"hmac-sha256:v1:8TC1wWOnH2Dg0m3BtlCjnweykbH02riydC06wWjOVMg","appliedConfigHash":"hmac-sha256:v1:8TC1wWOnH2Dg0m3BtlCjnweykbH02riydC06wWjOVMg"}}
```

`fixtures/gateway-protocol/2026.10.3/err-client-id-invalid.json` (a `client.id` outside upstream's allow-list is rejected before auth, then close 1008):
```json
{"type":"res","id":"qa-1","ok":false,"error":{"code":"INVALID_REQUEST","message":"invalid connect params: at /client/id: must be equal to one of the allowed values"}}
```

`fixtures/gateway-protocol/2026.10.3/err-pairing-metadata-upgrade.json` (a paired device whose `platform` changed must be re-approved):
```json
{"type":"res","id":"qa-1","ok":false,"error":{"code":"NOT_PAIRED","message":"pairing required: device identity changed and must be re-approved","details":{"code":"PAIRING_REQUIRED","reason":"metadata-upgrade","requestId":"6837c6b0-f559-445b-bc0c-a50fbe5b5998","remediationHint":"Review the refreshed device details, then approve the pending request.","deviceId":"3f219088c9d60e28387657a427ae904a1061b0f74df3722362be8f14f4b2e3d4","requestedRole":"operator","requestedScopes":["operator.read","operator.write","operator.admin"],"approvedRoles":["operator"],"approvedScopes":["operator.admin","operator.read","operator.write"]}}}
```

`fixtures/gateway-protocol/2026.10.3/err-config-stale-hash.json` (stale or unknown `baseHash`; no `details`):
```json
{"type":"res","id":"qa-6","ok":false,"error":{"code":"INVALID_REQUEST","message":"config changed since last load; re-run config.get and retry"}}
```

`fixtures/gateway-protocol/2026.10.3/err-config-base-hash-required.json` (`config.patch` without `baseHash`):
```json
{"type":"res","id":"qa-6","ok":false,"error":{"code":"INVALID_REQUEST","message":"config base hash required for skills.entries.gifgrep.env.QA_MARK; re-run config.get and retry with baseHash"}}
```

`fixtures/gateway-protocol/2026.10.3/err-config-invalid.json` (schema validation; `details.issues[]` carries `path`, `message` and sometimes `allowedValues`):
```json
{"type":"res","id":"qa-6","ok":false,"error":{"code":"INVALID_REQUEST","message":"invalid config: gateway.port: Too big: expected number to be <=65535 (maximum: 65535)","details":{"issues":[{"path":"gateway.port","message":"Too big: expected number to be <=65535 (maximum: 65535)"}]}}}
```

`fixtures/gateway-protocol/2026.10.3/res-config-patch.json` (success; `config` trimmed, `sentinel.payload` trimmed to `kind`/`status`):
```json
{"type":"res","id":"qa-6","ok":true,"payload":{"ok":true,"path":"/home/node/.openclaw/openclaw.json","hash":"hmac-sha256:v1:9zRz_TNpxroYBBCZhMFq3HGpv1dFAcFxPnmwn7VTXYE","config":{"gateway":{"mode":"local","port":18789,"bind":"lan","auth":{"mode":"token","token":"__OPENCLAW_REDACTED__"}},"agents":{"entries":{"main":{}}},"skills":{"entries":{"gifgrep":{"enabled":false,"env":{"QA_MARK":"1"}}}}},"changedPaths":["skills.entries.gifgrep.env"],"sentinel":{"persisted":true,"payload":{"kind":"config-patch","status":"ok"}}}}
```

`fixtures/gateway-protocol/2026.10.3/res-skills-update.json` (also `ok:true` for a key `skills.status` does not list):
```json
{"type":"res","id":"qa-7","ok":true,"payload":{"ok":true,"skillKey":"browser-automation","config":{"enabled":false}}}
```

`fixtures/gateway-protocol/2026.10.3/evt-config-changed.json` (~80 ms after a `skills.update` / `config.patch`, sometimes before its `res`; `skills.status` is stale until then):
```json
{"type":"event","event":"config.changed","payload":{"path":"/home/node/.openclaw/openclaw.json","hash":"hmac-sha256:v1:2sFWUUtsC-UI1KuZe97ow05xC0utmh6-UkQUs2Itm1Q","ts":1792035286950},"seq":2}
```

`fixtures/gateway-protocol/2026.10.3/res-skills-install.json` (`skills.install {source:"clawhub", slug:"@steipete/weather"}`; the answer names the bare key):
```json
{"type":"res","id":"qa-8","ok":true,"payload":{"ok":true,"message":"Installed weather@1.0.0","stdout":"","stderr":"","code":0,"slug":"weather","version":"1.0.0","targetDir":"/home/node/.openclaw/workspace/skills/weather"}}
```

- [ ] **Step 2: Expose the fixtures as unit-test resources**

In `app/build.gradle.kts`, inside `android { … }` directly after the closing brace of `testOptions { … }`:
```kotlin
    // Frames captured from the real gateway (ADR-0007). One copy, shared with the iOS tests
    // (ios/project.yml) and the mock gateway's conformance tests. On the classpath as /2026.10.3/<name>.json.
    sourceSets {
        getByName("test") {
            resources.srcDir(rootProject.file("fixtures/gateway-protocol"))
        }
    }
```

- [ ] **Step 3: Write the failing test**

`app/src/test/kotlin/com/openclaw/ghostcrab/testutil/GatewayFixturesTest.kt`:
```kotlin
package com.openclaw.ghostcrab.testutil

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewayFixturesTest {

    @Test fun `every fixture is a res or event frame`() {
        GatewayFixtures.ALL.forEach { name ->
            val type = GatewayFixtures.frame(name)["type"]?.jsonPrimitive?.content
            assertTrue(type == "res" || type == "event", "$name has type $type")
        }
    }

    @Test fun `paired hello grants the three operator scopes`() {
        val auth = GatewayFixtures.frame("hello-ok-paired").getValue("payload").jsonObject.getValue("auth").jsonObject
        assertEquals(
            listOf("operator.read", "operator.write", "operator.admin"),
            auth.getValue("scopes").jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
```

- [ ] **Step 4: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.testutil.GatewayFixturesTest"`
Expected: FAIL, compilation error `Unresolved reference 'GatewayFixtures'`.

- [ ] **Step 5: Write the loader**

`app/src/test/kotlin/com/openclaw/ghostcrab/testutil/GatewayFixtures.kt`:
```kotlin
package com.openclaw.ghostcrab.testutil

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Frames captured from `ghcr.io/openclaw/openclaw` 2026.10.3 during roadmap M5
 * (`docs/QA_REAL_GATEWAY_2026-10.md`), trimmed, with the issued device token replaced by `dt-test-0001`.
 * Source directory: `fixtures/gateway-protocol/2026.10.3/` (a test resource root, see `app/build.gradle.kts`).
 */
object GatewayFixtures {

    /** Gateway release the frames were captured from. */
    const val VERSION = "2026.10.3"

    /** Every fixture name, without the `.json` suffix. */
    val ALL = listOf(
        "challenge", "hello-ok-paired", "hello-ok-noscope",
        "err-pairing-required", "err-token-mismatch", "err-token-missing", "err-device-identity-required",
        "err-missing-scope", "err-unknown-method", "err-skill-not-found",
        "res-health", "res-skills-status", "res-models-list", "res-config-get",
        "err-client-id-invalid", "err-pairing-metadata-upgrade",
        "err-config-stale-hash", "err-config-base-hash-required", "err-config-invalid", "res-config-patch",
        "res-skills-update", "evt-config-changed", "res-skills-install",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param name Fixture name without `.json`.
     * @return The raw frame text.
     * @throws IllegalStateException if the fixture is not on the test classpath.
     */
    fun text(name: String): String {
        val path = "/$VERSION/$name.json"
        val stream = GatewayFixtures::class.java.getResourceAsStream(path)
            ?: error("missing test fixture $path (fixtures/gateway-protocol must be a test resource dir)")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * @param name Fixture name without `.json`.
     * @return The parsed frame.
     */
    fun frame(name: String): JsonObject = json.parseToJsonElement(text(name)).jsonObject
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.testutil.GatewayFixturesTest"`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add fixtures/gateway-protocol app/build.gradle.kts app/src/test/kotlin/com/openclaw/ghostcrab/testutil
git commit -m "test: add real-gateway 2026.10.3 protocol fixtures" -m "Frames captured in roadmap M5 (docs/QA_REAL_GATEWAY_2026-10.md), trimmed, device token replaced. Shared test resource for Android, iOS and the mock gateway (ADR-0007)."
```

---

### Task 2: Frame codec

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayFrames.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayFramesTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayFramesTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.testutil.GatewayFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewayFramesTest {

    @Test fun `challenge decodes as an event carrying nonce and ts`() {
        val frame = GatewayFrameCodec.decode(GatewayFixtures.text("challenge")) as GatewayFrame.Event
        assertEquals("connect.challenge", frame.event)
        val payload = frame.payload as JsonObject
        assertEquals("71d63897-7ebd-44e8-a27a-20ac1598b846", payload.stringOrNull("nonce"))
        assertEquals("1792027507354", payload["ts"]?.jsonPrimitive?.content)
    }

    @Test fun `hello-ok decodes as an ok response with a string id`() {
        val frame = GatewayFrameCodec.decode(GatewayFixtures.text("hello-ok-paired")) as GatewayFrame.Response
        assertEquals("qa-1", frame.id)
        assertTrue(frame.ok)
        assertEquals("hello-ok", (frame.payload as JsonObject).stringOrNull("type"))
        assertNull(frame.error)
    }

    @Test fun `error response exposes the string code and details code`() {
        val frame = GatewayFrameCodec.decode(GatewayFixtures.text("err-pairing-required")) as GatewayFrame.Response
        assertFalse(frame.ok)
        assertEquals("NOT_PAIRED", frame.error?.code)
        assertEquals("PAIRING_REQUIRED", frame.error?.detailCode)
        assertEquals("af0b5d81-7bd2-4b86-80b0-4a489b541974", frame.error?.detailsObject?.stringOrNull("requestId"))
    }

    @Test fun `a failed response keeps both payload and error`() {
        val frame = GatewayFrameCodec.decode(GatewayFixtures.text("err-skill-not-found")) as GatewayFrame.Response
        assertEquals("UNAVAILABLE", frame.error?.code)
        assertTrue(frame.payload is JsonObject)
    }

    @Test fun `input that is not a protocol-4 frame decodes to null`() {
        listOf(
            "not json",
            "[1,2,3]",
            """{"jsonrpc":"2.0","id":1,"result":{}}""",
            """{"type":"res","id":7,"ok":true}""",
            """{"type":"res","id":"x"}""",
            """{"type":"event"}""",
        ).forEach { assertNull(GatewayFrameCodec.decode(it), it) }
    }

    @Test fun `request encodes type req with a string id and params`() {
        val text = GatewayFrameCodec.encode(
            RequestFrame(id = "gc-1", method = "health", params = buildJsonObject { put("probe", true) }),
        )
        val obj = Json.parseToJsonElement(text).jsonObject
        assertEquals("req", obj.stringOrNull("type"))
        assertEquals("gc-1", obj.stringOrNull("id"))
        assertEquals("health", obj.stringOrNull("method"))
        assertEquals("true", obj.getValue("params").jsonObject["probe"]?.jsonPrimitive?.content)
    }

    @Test fun `objectAt walks nested objects and stops at non-objects`() {
        val obj = Json.parseToJsonElement("""{"a":{"b":{"c":1}},"x":2}""").jsonObject
        assertEquals("1", obj.objectAt("a", "b")?.get("c")?.jsonPrimitive?.content)
        assertNull(obj.objectAt("x", "y"))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.GatewayFramesTest"`
Expected: FAIL, `Unresolved reference 'GatewayFrameCodec'`.

- [ ] **Step 3: Implement the codec**

`app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayFrames.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Gateway WS protocol version this client implements (`connect.minProtocol` = `maxProtocol`, ADR-0007). */
const val GATEWAY_PROTOCOL_VERSION: Int = 4

/** Codec for gateway frames. Unknown keys are normal here: every gateway release adds fields. */
internal val gatewayJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * Client → gateway request: `{"type":"req","id":"<string>","method":"…","params":{…}}`.
 *
 * @param id Unique per socket; the response echoes it.
 * @param method RPC method name, e.g. `skills.status`.
 * @param params Method parameters; omitted from the frame when `null`.
 * @param type Always `req`.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RequestFrame(
    val id: String,
    val method: String,
    val params: JsonElement? = null,
    @EncodeDefault val type: String = "req",
)

/**
 * `error` of a failed response (upstream `ErrorShape`).
 *
 * @param code Top-level code: `INVALID_REQUEST`, `FORBIDDEN`, `NOT_PAIRED`, `UNAVAILABLE`, ….
 * @param message Human-readable server text; shown verbatim, never parsed when [details] says more.
 * @param details Structured details; `details.code` is the machine-readable sub-code.
 * @param retryable Server hint; absent means "not retryable".
 * @param retryAfterMs Server back-off hint, if any.
 */
@Serializable
data class ErrorShape(
    val code: String,
    val message: String,
    val details: JsonElement? = null,
    val retryable: Boolean? = null,
    val retryAfterMs: Long? = null,
) {
    /** [details] when it is a JSON object, else `null`. */
    val detailsObject: JsonObject? get() = details as? JsonObject

    /** `details.code` (e.g. `PAIRING_REQUIRED`, `MISSING_SCOPE`, `AUTH_TOKEN_MISMATCH`), or `null`. */
    val detailCode: String? get() = detailsObject?.stringOrNull("code")
}

/** A decoded gateway → client frame. */
sealed interface GatewayFrame {

    /**
     * Answer to a request.
     *
     * @param id The request id it answers.
     * @param ok `true` on success.
     * @param payload Result (may also be present on failure, e.g. `skills.install`).
     * @param error Present when [ok] is `false`.
     */
    data class Response(
        val id: String,
        val ok: Boolean,
        val payload: JsonElement?,
        val error: ErrorShape?,
    ) : GatewayFrame

    /**
     * Server push, e.g. `connect.challenge`, `tick`, `health`, `skills.changed`.
     *
     * @param event Event name.
     * @param payload Event body.
     * @param seq Connection sequence number, when the server sends one.
     */
    data class Event(
        val event: String,
        val payload: JsonElement?,
        val seq: Long?,
    ) : GatewayFrame
}

/** Encodes requests and decodes everything the gateway sends. Never throws on decode. */
object GatewayFrameCodec {

    /**
     * @param frame The request.
     * @return The JSON text to send.
     */
    fun encode(frame: RequestFrame): String = gatewayJson.encodeToString(RequestFrame.serializer(), frame)

    /**
     * @param raw One text frame from the socket.
     * @return The frame, or `null` for anything that is not a well-formed `res` / `event` frame
     *   (malformed JSON, JSON-RPC, numeric ids). The official client ignores such frames too.
     */
    fun decode(raw: String): GatewayFrame? {
        val obj = try {
            gatewayJson.parseToJsonElement(raw) as? JsonObject
        } catch (ignored: IllegalArgumentException) { // SerializationException extends it
            null
        } ?: return null
        return when (obj.stringOrNull("type")) {
            "res" -> decodeResponse(obj)
            "event" -> obj.stringOrNull("event")?.let { name ->
                GatewayFrame.Event(name, obj["payload"], (obj["seq"] as? JsonPrimitive)?.longOrNull)
            }
            else -> null
        }
    }

    @Suppress("ReturnCount")
    private fun decodeResponse(obj: JsonObject): GatewayFrame.Response? {
        val id = obj.stringOrNull("id") ?: return null
        val ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull ?: return null
        val error = (obj["error"] as? JsonObject)?.let { element ->
            try {
                gatewayJson.decodeFromJsonElement(ErrorShape.serializer(), element)
            } catch (ignored: IllegalArgumentException) {
                ErrorShape(code = "MALFORMED_ERROR", message = element.toString())
            }
        }
        return GatewayFrame.Response(id, ok, obj["payload"], error)
    }
}

/** The string at [key], or `null` when absent or not a JSON string. Never throws. */
internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The string elements of the array at [key]; other elements are skipped. Never throws. */
internal fun JsonObject.stringListOrEmpty(key: String): List<String> =
    (this[key] as? JsonArray)
        ?.mapNotNull { element -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content }
        .orEmpty()

/** The object at the nested [path], or `null` as soon as a step is missing or not an object. */
internal fun JsonObject.objectAt(vararg path: String): JsonObject? =
    path.fold(this as JsonObject?) { current, key -> current?.get(key) as? JsonObject }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.GatewayFramesTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayFrames.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayFramesTest.kt
git commit -m "feat(gateway): add protocol-4 frame codec" -m "req/res/event frames with string ids and string error codes (ADR-0007). Decoding never throws; JSON-RPC and numeric-id frames are rejected."
```

---

### Task 3: Typed gateway errors

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/exception/GatewayException.kt` (append five classes at the end)
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayErrors.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayErrorsTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayErrorsTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayMethodUnsupportedException
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException
import com.openclaw.ghostcrab.testutil.GatewayFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewayErrorsTest {

    private val url = "ws://192.168.1.50:18789/ws"

    private fun errorOf(fixture: String): ErrorShape =
        requireNotNull((GatewayFrameCodec.decode(GatewayFixtures.text(fixture)) as GatewayFrame.Response).error)

    @Test fun `PAIRING_REQUIRED carries the request id, reason and scopes`() {
        val e = errorOf("err-pairing-required").toGatewayException(url, "connect")
        assertInstanceOf(GatewayPairingRequiredException::class.java, e)
        e as GatewayPairingRequiredException
        assertEquals("af0b5d81-7bd2-4b86-80b0-4a489b541974", e.requestId)
        assertEquals("not-paired", e.reason)
        assertEquals(listOf("operator.read", "operator.write", "operator.admin"), e.requestedScopes)
        assertTrue(e.isRetryable)
        assertTrue(e.message.orEmpty().contains("openclaw devices approve af0b5d81-7bd2-4b86-80b0-4a489b541974"))
    }

    @Test fun `token mismatch is a connect-auth error naming the url and code`() {
        val e = errorOf("err-token-mismatch").toGatewayException(url, "connect") as GatewayConnectAuthException
        assertEquals("AUTH_TOKEN_MISMATCH", e.detailCode)
        assertEquals("update_auth_credentials", e.recommendedNextStep)
        assertFalse(e.isRetryable)
        assertTrue(e.message.orEmpty().contains(url))
        assertTrue(e.message.orEmpty().contains("AUTH_TOKEN_MISMATCH"))
    }

    @Test fun `token missing and device identity required are connect-auth errors`() {
        assertEquals(
            "AUTH_TOKEN_MISSING",
            (errorOf("err-token-missing").toGatewayException(url, "connect") as GatewayConnectAuthException).detailCode,
        )
        assertEquals(
            "DEVICE_IDENTITY_REQUIRED",
            (errorOf("err-device-identity-required").toGatewayException(url, "connect") as GatewayConnectAuthException)
                .detailCode,
        )
    }

    @Test fun `MISSING_SCOPE names the scope`() {
        val e = errorOf("err-missing-scope").toGatewayException(url, "status") as GatewayMissingScopeException
        assertEquals("operator.read", e.missingScope)
        assertEquals("status", e.method)
    }

    @Test fun `legacy missing-scope message without details still maps`() {
        val e = ErrorShape(code = "FORBIDDEN", message = "missing scope: operator.admin")
            .toGatewayException(url, "config.get") as GatewayMissingScopeException
        assertEquals("operator.admin", e.missingScope)
    }

    @Test fun `unknown method is unsupported, not a generic error`() {
        val e = errorOf("err-unknown-method").toGatewayException(url, "skills.list")
        assertInstanceOf(GatewayMethodUnsupportedException::class.java, e)
        assertEquals("skills.list", (e as GatewayMethodUnsupportedException).method)
    }

    @Test fun `anything else is a generic rpc error with the server text`() {
        val e = errorOf("err-skill-not-found").toGatewayException(url, "skills.install") as GatewayRpcException
        assertEquals("UNAVAILABLE", e.code)
        assertNull(e.detailCode)
        assertTrue(e.rpcMessage.startsWith("Skill \"qa-nonexistent-skill-zz9\" not found on ClawHub"))
        assertFalse(e.isRetryable)
    }

    @Test fun `the retryable hint is honoured`() {
        val e = ErrorShape(code = "UNAVAILABLE", message = "startup", retryable = true)
            .toGatewayException(url, "connect")
        assertTrue(e.isRetryable)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.GatewayErrorsTest"`
Expected: FAIL, `Unresolved reference 'GatewayConnectAuthException'`.

- [ ] **Step 3: Add the domain exceptions**

Append to `app/src/main/kotlin/com/openclaw/ghostcrab/domain/exception/GatewayException.kt`:
```kotlin

/**
 * A gateway RPC answered `ok:false` with a code this client has no more specific type for (ADR-0007).
 *
 * @param url The gateway WebSocket endpoint.
 * @param method The RPC method.
 * @param code Top-level error code (e.g. `UNAVAILABLE`, `INVALID_REQUEST`).
 * @param detailCode `error.details.code`, when the server sent one.
 * @param rpcMessage The server's message, verbatim.
 * @param retryable The server's `retryable` hint.
 */
class GatewayRpcException(
    val url: String,
    val method: String,
    val code: String,
    val detailCode: String?,
    val rpcMessage: String,
    retryable: Boolean,
) : GatewayException("Gateway RPC $method failed at $url: $code${detailCode?.let { "/$it" } ?: ""} — $rpcMessage") {
    override val isRetryable: Boolean = retryable
}

/**
 * The gateway does not implement [method] (`INVALID_REQUEST unknown method: …`). The feature is unsupported
 * on this gateway; retrying cannot help.
 *
 * @param url The gateway WebSocket endpoint.
 * @param method The RPC method the gateway does not know.
 */
class GatewayMethodUnsupportedException(
    val url: String,
    val method: String,
) : GatewayException("Gateway at $url does not support '$method' (INVALID_REQUEST unknown method)") {
    override val isRetryable: Boolean = false
}

/**
 * The connection lacks the operator scope [method] needs (`FORBIDDEN` / `MISSING_SCOPE`). Approve this device
 * with that scope on the gateway host.
 *
 * @param url The gateway WebSocket endpoint.
 * @param method The refused RPC method.
 * @param missingScope The scope the gateway reported, e.g. `operator.admin`.
 */
class GatewayMissingScopeException(
    val url: String,
    val method: String,
    val missingScope: String,
) : GatewayException("Gateway at $url refused '$method': missing scope $missingScope (FORBIDDEN/MISSING_SCOPE)") {
    override val isRetryable: Boolean = false
}

/**
 * The gateway knows this device but its owner has not approved it yet (`NOT_PAIRED` / `PAIRING_REQUIRED`).
 * Retryable: the same connect succeeds once `openclaw devices approve <requestId>` ran on the gateway host.
 *
 * @param url The gateway WebSocket endpoint.
 * @param requestId The pending pairing request, when the gateway sent one.
 * @param reason `not-paired`, `scope-upgrade`, `role-upgrade` or `metadata-upgrade`.
 * @param requestedScopes The scopes this device asked for.
 */
class GatewayPairingRequiredException(
    val url: String,
    val requestId: String?,
    val reason: String,
    val requestedScopes: List<String>,
) : GatewayException(
    "Gateway at $url needs this device approved ($reason). On the gateway host run: " +
        (requestId?.let { "openclaw devices approve $it" } ?: "openclaw devices list"),
) {
    override val isRetryable: Boolean = true
}

/**
 * The gateway rejected the `connect` handshake's credential or device proof (`error.details.code` starting
 * with `AUTH_` or `DEVICE_`, e.g. `AUTH_TOKEN_MISMATCH`, `AUTH_TOKEN_MISSING`, `DEVICE_IDENTITY_REQUIRED`).
 *
 * @param url The gateway WebSocket endpoint.
 * @param detailCode The structured code.
 * @param rpcMessage The server's message, verbatim.
 * @param recommendedNextStep Upstream recovery hint (`update_auth_credentials`, `wait_then_retry`, …), if any.
 */
class GatewayConnectAuthException(
    val url: String,
    val detailCode: String,
    val rpcMessage: String,
    val recommendedNextStep: String?,
) : GatewayException("Gateway at $url rejected the connect handshake: $detailCode — $rpcMessage") {
    override val isRetryable: Boolean = false
}
```

- [ ] **Step 4: Add the mapping**

`app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayErrors.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayMethodUnsupportedException
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException

private const val MISSING_SCOPE_PREFIX = "missing scope: "
private const val UNKNOWN_METHOD_PREFIX = "unknown method:"

/**
 * Maps a failed response onto the typed [GatewayException] leaves. Branches on `code` and `details.code`
 * (upstream's contract); the legacy `missing scope: …` and `unknown method: …` messages are a fallback only.
 *
 * @param url The gateway WebSocket endpoint, for the message.
 * @param method The RPC method that failed.
 * @return The most specific exception for this error.
 */
internal fun ErrorShape.toGatewayException(url: String, method: String): GatewayException {
    val details = detailsObject
    val detail = detailCode
    return when {
        detail == "PAIRING_REQUIRED" -> GatewayPairingRequiredException(
            url = url,
            requestId = details?.stringOrNull("requestId"),
            reason = details?.stringOrNull("reason") ?: "not-paired",
            requestedScopes = details?.stringListOrEmpty("requestedScopes").orEmpty(),
        )
        detail != null && (detail.startsWith("AUTH_") || detail.startsWith("DEVICE_")) ->
            GatewayConnectAuthException(url, detail, message, details?.stringOrNull("recommendedNextStep"))
        code == "FORBIDDEN" && (detail == "MISSING_SCOPE" || message.startsWith(MISSING_SCOPE_PREFIX)) ->
            GatewayMissingScopeException(
                url = url,
                method = method,
                missingScope = details?.stringOrNull("missingScope") ?: message.removePrefix(MISSING_SCOPE_PREFIX),
            )
        code == "INVALID_REQUEST" && message.startsWith(UNKNOWN_METHOD_PREFIX) ->
            GatewayMethodUnsupportedException(url, method)
        else -> GatewayRpcException(url, method, code, detail, message, retryable == true)
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.GatewayErrorsTest"`
Expected: PASS (8 tests).

- [ ] **Step 6: Run the whole suite** (new `GatewayException` leaves must not break an exhaustive `when`)

Run: `./gradlew testDebugUnitTest detekt`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/domain/exception/GatewayException.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayErrors.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayErrorsTest.kt
git commit -m "feat(gateway): typed errors for string error codes" -m "PAIRING_REQUIRED, AUTH_*/DEVICE_* connect rejections, MISSING_SCOPE and unknown methods get their own GatewayException leaves; everything else keeps code, detail code and server text (ADR-0007)."
```

---

### Task 4: `GatewayRpcClient` (correlation, events, challenge)

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayRpcClient.kt`
- Create: `app/src/test/kotlin/com/openclaw/ghostcrab/testutil/ScriptedWsSession.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayRpcClientTest.kt`

The client reuses the existing `com.openclaw.ghostcrab.data.ws.WsSession` interface and `KtorWsSession` adapter (in `GatewayWsClient.kt`; Task 24 moves them to their own file).

- [ ] **Step 1: Write the scripted socket used by every protocol test**

`app/src/test/kotlin/com/openclaw/ghostcrab/testutil/ScriptedWsSession.kt`:
```kotlin
package com.openclaw.ghostcrab.testutil

import com.openclaw.ghostcrab.data.ws.WsSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fake gateway socket. Emits the captured `connect.challenge` on open (unless [sendChallenge] is false),
 * then answers each request with the frame [respond] returns for its method; the reply's `id` is rewritten
 * to the request's id. A `null` reply means "never answer". Methods in [closeAfterReplyTo] end the socket
 * after the reply, as the real gateway does after a failed `connect` (close 1008).
 */
class ScriptedWsSession(
    sendChallenge: Boolean = true,
    private val closeAfterReplyTo: Set<String> = emptySet(),
    private val respond: (method: String, params: JsonObject) -> JsonObject?,
) : WsSession {

    private val inbound = Channel<String>(Channel.UNLIMITED)
    private val sentFrames = CopyOnWriteArrayList<JsonObject>()

    /** Every frame the client sent, parsed, in order. */
    val sent: List<JsonObject> get() = sentFrames.toList()

    /** `true` once the client closed the session. */
    @Volatile var closedByClient: Boolean = false
        private set

    init {
        if (sendChallenge) inbound.trySend(GatewayFixtures.text("challenge"))
    }

    /** The `params` of the first request for [method] (empty object when it had none). */
    fun paramsOf(method: String): JsonObject =
        sentFrames.first { it["method"]?.jsonPrimitive?.content == method }["params"] as? JsonObject
            ?: JsonObject(emptyMap())

    override suspend fun send(text: String) {
        val request = Json.parseToJsonElement(text).jsonObject
        sentFrames.add(request)
        val method = request.getValue("method").jsonPrimitive.content
        val reply = respond(method, request["params"] as? JsonObject ?: JsonObject(emptyMap())) ?: return
        inbound.trySend(JsonObject(reply + ("id" to request.getValue("id"))).toString())
        if (method in closeAfterReplyTo) inbound.close()
    }

    /** Delivers a server-pushed frame. */
    fun push(frame: JsonObject) {
        inbound.trySend(frame.toString())
    }

    /** Simulates the gateway dropping the socket. */
    fun drop() {
        inbound.close()
    }

    override fun incoming(): Flow<String> = inbound.receiveAsFlow()

    override suspend fun close() {
        closedByClient = true
        inbound.close()
    }

    companion object {
        /** A responder answering each listed method with the named fixture frame. */
        fun fixtures(vararg byMethod: Pair<String, String>): (String, JsonObject) -> JsonObject? {
            val names = byMethod.toMap()
            return { method, _ -> names[method]?.let { GatewayFixtures.frame(it) } }
        }
    }
}
```

- [ ] **Step 2: Write the failing test**

`app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayRpcClientTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.exception.GatewayMethodUnsupportedException
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import com.openclaw.ghostcrab.testutil.ScriptedWsSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GatewayRpcClientTest {

    private val endpoint = "ws://test/ws"

    private fun client(session: ScriptedWsSession, timeoutMs: Long = GatewayRpcClient.DEFAULT_REQUEST_TIMEOUT_MS) =
        GatewayRpcClient.create(session, endpoint, Dispatchers.Unconfined, timeoutMs)

    @Test fun `request correlates the response by string id`() = runTest {
        val session = ScriptedWsSession(respond = ScriptedWsSession.fixtures("health" to "res-health"))
        val payload = client(session).request("health") as JsonObject
        assertEquals("true", payload["ok"].toString())
        val sent = session.sent.single()
        assertEquals("req", sent.stringOrNull("type"))
        assertTrue(sent.stringOrNull("id").orEmpty().startsWith("gc-"))
        assertEquals(JsonObject(emptyMap()), sent["params"])
    }

    @Test fun `ok false becomes the typed exception`() = runTest {
        val session = ScriptedWsSession(
            respond = ScriptedWsSession.fixtures(
                "skills.list" to "err-unknown-method",
                "status" to "err-missing-scope",
            ),
        )
        val rpc = client(session)
        assertThrows<GatewayMethodUnsupportedException> { rpc.request("skills.list") }
        assertEquals("operator.read", assertThrows<GatewayMissingScopeException> { rpc.request("status") }.missingScope)
    }

    @Test fun `a challenge that arrived before awaitChallenge is still returned`() = runTest {
        val rpc = client(ScriptedWsSession(respond = { _, _ -> null }))
        assertEquals(GatewayRpcClient.CONNECT_CHALLENGE_EVENT, rpc.awaitChallenge().event)
    }

    @Test fun `no challenge times out with a typed error`() = runTest {
        val rpc = client(ScriptedWsSession(sendChallenge = false, respond = { _, _ -> null }))
        val e = assertThrows<GatewayTimeoutException> { rpc.awaitChallenge(timeoutMs = 500) }
        assertEquals("$endpoint#connect.challenge", e.url)
    }

    @Test fun `an unanswered request times out`() = runTest {
        val rpc = client(ScriptedWsSession(respond = { _, _ -> null }), timeoutMs = 1_000)
        assertEquals("$endpoint#health", assertThrows<GatewayTimeoutException> { rpc.request("health") }.url)
    }

    @Test fun `a dropped socket fails the pending request and every later one`() = runTest {
        val session = ScriptedWsSession(respond = { _, _ -> null })
        val rpc = client(session)
        val pending = async { runCatching { rpc.request("health") } }
        runCurrent()
        session.drop()
        assertInstanceOf(GatewayUnreachableException::class.java, pending.await().exceptionOrNull())
        assertThrows<GatewayUnreachableException> { rpc.request("status") }
        assertTrue(rpc.isClosed)
    }

    @Test fun `server events reach collectors`() = runTest {
        val session = ScriptedWsSession(respond = { _, _ -> null })
        val rpc = client(session)
        val event = async { rpc.events.first { it.event == "skills.changed" } }
        runCurrent()
        session.push(
            buildJsonObject {
                put("type", "event")
                put("event", "skills.changed")
                putJsonObject("payload") {}
            },
        )
        assertEquals("skills.changed", event.await().event)
    }

    @Test fun `close closes the underlying socket`() = runTest {
        val session = ScriptedWsSession(respond = { _, _ -> null })
        client(session).close()
        assertTrue(session.closedByClient)
    }
}
```

- [ ] **Step 3: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.GatewayRpcClientTest"`
Expected: FAIL, `Unresolved reference 'GatewayRpcClient'`.

- [ ] **Step 4: Implement the client**

`app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayRpcClient.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.data.ws.WsSession
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/**
 * One gateway control-plane socket (ADR-0007). Request ids are `gc-1`, `gc-2`, …; each pending request is
 * parked by id until its `res` frame arrives. `connect.challenge` is captured as soon as it arrives, so a
 * caller that subscribes late still gets it. Every request is bounded by a timeout; when the socket ends,
 * pending and later requests fail with [GatewayUnreachableException] instead of hanging.
 *
 * Construct with [create]; call [close] when the session ends.
 */
class GatewayRpcClient private constructor(
    private val session: WsSession,
    /** The `ws(s)://…/ws` endpoint, used in error messages. */
    val endpoint: String,
    private val defaultTimeoutMs: Long,
    dispatcher: CoroutineContext,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<GatewayFrame.Response>>()
    private val challenge = CompletableDeferred<GatewayFrame.Event>()
    private val _events = MutableSharedFlow<GatewayFrame.Event>(extraBufferCapacity = EVENT_BUFFER)

    @Volatile private var closedCause: GatewayUnreachableException? = null

    /** Server events (`tick`, `health`, `skills.changed`, …). Best effort: a slow collector drops events. */
    val events: SharedFlow<GatewayFrame.Event> = _events.asSharedFlow()

    /** `true` once the socket has ended. */
    val isClosed: Boolean get() = closedCause != null

    private val readerJob: Job = scope.launch {
        var transportError: Exception? = null
        try {
            session.incoming().collect { raw -> dispatch(raw) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // A transport error ends the socket like a close; it must not reach the uncaught handler.
            transportError = e
        } finally {
            val cause = GatewayUnreachableException(endpoint, transportError)
            closedCause = cause
            challenge.completeExceptionally(cause)
            pending.values.forEach { it.completeExceptionally(cause) }
            pending.clear()
        }
    }

    private fun dispatch(raw: String) {
        when (val frame = GatewayFrameCodec.decode(raw)) {
            is GatewayFrame.Response -> pending.remove(frame.id)?.complete(frame)
            is GatewayFrame.Event -> {
                if (frame.event == CONNECT_CHALLENGE_EVENT) challenge.complete(frame)
                _events.tryEmit(frame)
            }
            null -> Unit // Not a protocol-4 frame: ignored, like the official client does.
        }
    }

    /**
     * @param timeoutMs How long to wait for the gateway's first frame.
     * @return The `connect.challenge` event (also when it arrived before this call).
     * @throws GatewayTimeoutException if none arrives in time.
     * @throws GatewayUnreachableException if the socket ended first.
     */
    suspend fun awaitChallenge(timeoutMs: Long = CHALLENGE_TIMEOUT_MS): GatewayFrame.Event = try {
        withTimeout(timeoutMs) { challenge.await() }
    } catch (e: TimeoutCancellationException) {
        throw GatewayTimeoutException("$endpoint#$CONNECT_CHALLENGE_EVENT", e)
    }

    /**
     * Sends a request and suspends until its response.
     *
     * @param method RPC method, e.g. `skills.status`.
     * @param params Method parameters; `{}` by default (the gateway expects an object).
     * @param timeoutMs Per-request timeout.
     * @return The response `payload` (an empty object when the gateway sent none).
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException subtype for `ok:false`
     *   (see `toGatewayException`).
     * @throws GatewayTimeoutException if no response arrives in time.
     * @throws GatewayUnreachableException if the frame could not be sent or the socket ended.
     */
    suspend fun request(
        method: String,
        params: JsonElement = JsonObject(emptyMap()),
        timeoutMs: Long = defaultTimeoutMs,
    ): JsonElement {
        val id = "gc-${nextId.getAndIncrement()}"
        val deferred = CompletableDeferred<GatewayFrame.Response>()
        pending[id] = deferred
        // Registered after the reader failed the parked requests: fail now instead of timing out.
        closedCause?.let { deferred.completeExceptionally(it) }
        val response = try {
            if (!deferred.isCompleted) send(RequestFrame(id = id, method = method, params = params))
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw GatewayTimeoutException("$endpoint#$method", e)
        } finally {
            pending.remove(id)
        }
        if (!response.ok) {
            val error = response.error ?: ErrorShape(code = "UNKNOWN", message = "ok:false without an error object")
            throw error.toGatewayException(endpoint, method)
        }
        return response.payload ?: JsonObject(emptyMap())
    }

    private suspend fun send(frame: RequestFrame) {
        try {
            session.send(GatewayFrameCodec.encode(frame))
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw GatewayUnreachableException(endpoint, e)
        }
    }

    /** Stops the reader and closes the socket. Idempotent. */
    suspend fun close() {
        readerJob.cancel()
        scope.cancel()
        session.close()
    }

    companion object {
        /** First frame of every gateway socket. */
        const val CONNECT_CHALLENGE_EVENT = "connect.challenge"

        /** Default per-request timeout. */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L

        /** How long to wait for `connect.challenge` (the real gateway sends it within ~2 ms). */
        const val CHALLENGE_TIMEOUT_MS = 10_000L

        private const val EVENT_BUFFER = 64

        /**
         * @param session A connected socket; the client owns it from now on.
         * @param endpoint The `ws(s)://…/ws` URL, for error messages.
         * @param dispatcher Where the reader loop runs (the connection manager passes its IO dispatcher).
         * @param requestTimeoutMs Default per-request timeout.
         * @return A client that is already reading.
         */
        fun create(
            session: WsSession,
            endpoint: String,
            dispatcher: CoroutineContext = Dispatchers.Default,
            requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        ): GatewayRpcClient = GatewayRpcClient(session, endpoint, requestTimeoutMs, dispatcher)
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.GatewayRpcClientTest"`
Expected: PASS (8 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayRpcClient.kt app/src/test/kotlin/com/openclaw/ghostcrab/testutil/ScriptedWsSession.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayRpcClientTest.kt
git commit -m "feat(gateway): add GatewayRpcClient for the protocol-4 socket" -m "String-id request/response correlation, typed ok:false errors, early connect.challenge capture, events flow, per-request timeouts and fail-fast after close."
```

---

### Task 5: Ed25519 device identity (Tink)

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceIdentity.kt`
- Create: `app/src/test/kotlin/com/openclaw/ghostcrab/testutil/TestKeys.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceIdentityTest.kt`

`tink-android` 1.8.0 is already on the release runtime classpath through `androidx.security:security-crypto` (`./gradlew :app:dependencyInsight --configuration releaseRuntimeClasspath --dependency tink-android`). This task declares it explicitly so the app does not depend on a transitive. Bouncy Castle is not an option: the `org.bouncycastle` pin in `app/build.gradle.kts` only covers AGP's host-side tooling. Android Keystore has no Ed25519 below API 33 (`minSdk` is 26), hence a software key stored encrypted (Task 7).

- [ ] **Step 1: Declare the dependency**

`gradle/libs.versions.toml`, under `[versions]` after `guava = …`:
```toml
tink = "1.8.0"
```
Under `[libraries]` after `androidx-security-crypto = …`:
```toml
tink-android = { group = "com.google.crypto.tink", name = "tink-android", version.ref = "tink" }
```
`app/build.gradle.kts`, in `dependencies { }` directly after `implementation(libs.androidx.security.crypto)`:
```kotlin
    // Ed25519 device identity (ADR-0007). Same version security-crypto already pulls in.
    implementation(libs.tink.android)
```

- [ ] **Step 2: Add the shared test keys**

`app/src/test/kotlin/com/openclaw/ghostcrab/testutil/TestKeys.kt`:
```kotlin
package com.openclaw.ghostcrab.testutil

/** RFC 8032 §7.1 TEST 1 secret key (Ed25519 seed). Public key `d75a9801…511a`. */
val RFC8032_SEED: ByteArray = hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")

/** hex(sha256(RFC 8032 TEST 1 public key)): the gateway device id of [RFC8032_SEED]. */
const val RFC8032_DEVICE_ID = "21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9"

/** The three operator scopes GhostCrab requests. */
val OPERATOR_SCOPES = listOf("operator.read", "operator.write", "operator.admin")

/**
 * @param hex An even-length hex string.
 * @return Its bytes.
 */
fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
```

- [ ] **Step 3: Write the failing test**

`app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceIdentityTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

import com.google.crypto.tink.subtle.Ed25519Verify
import com.openclaw.ghostcrab.testutil.RFC8032_DEVICE_ID
import com.openclaw.ghostcrab.testutil.RFC8032_SEED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class DeviceIdentityTest {

    @Test fun `RFC 8032 test 1 seed yields the RFC public key and its device id`() {
        val identity = DeviceIdentity.fromSeed(RFC8032_SEED)
        assertEquals("11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo", identity.publicKeyBase64Url)
        assertEquals(RFC8032_DEVICE_ID, identity.deviceId)
    }

    @Test fun `RFC 8032 test 1 signature of the empty message`() {
        assertEquals(
            "5VZDAMNgrHKQhuLMgG6CioSHfx645dl02HPgZSJJAVVfuIIVkKM7rMYeOXAc-bRr0lv18FlbviRlUUFDjnoQCw",
            DeviceIdentity.fromSeed(RFC8032_SEED).sign(""),
        )
    }

    @Test fun `device id matches the fingerprint the real gateway accepted`() {
        // device.publicKey and device.id of the connect frame the 2026.10.3 gateway paired (M5 capture).
        assertEquals(
            "f044a1f50388e949137206dbf72f4c79c9b499af0e2a4048b005bbf217a9f2e2",
            DeviceIdentity.deviceIdForPublicKey("e9iojoSOwE3ajNKyeYkso69DO4ByRKR_kJv2IrzMS3Q"),
        )
    }

    @Test fun `generated identities differ and their signatures verify`() {
        val a = DeviceIdentity.generate()
        val b = DeviceIdentity.generate()
        assertNotEquals(a.deviceId, b.deviceId)
        val decoder = Base64.getUrlDecoder()
        Ed25519Verify(decoder.decode(a.publicKeyBase64Url))
            .verify(decoder.decode(a.sign("payload")), "payload".toByteArray())
    }

    @Test fun `toString shows only the device id`() {
        val text = DeviceIdentity.fromSeed(RFC8032_SEED).toString()
        assertEquals("DeviceIdentity(deviceId=$RFC8032_DEVICE_ID)", text)
        assertFalse(text.contains("9d61"))
    }

    @Test fun `a seed that is not 32 bytes is rejected`() {
        assertThrows<IllegalArgumentException> { DeviceIdentity.fromSeed(ByteArray(31)) }
    }
}
```

- [ ] **Step 4: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.DeviceIdentityTest"`
Expected: FAIL, `Unresolved reference 'DeviceIdentity'`.

- [ ] **Step 5: Implement the identity**

`app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceIdentity.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

import com.google.crypto.tink.subtle.Ed25519Sign
import java.security.MessageDigest
import java.util.Base64

/**
 * This install's Ed25519 device identity (ADR-0007). [deviceId] is `hex(sha256(raw public key))`, the
 * fingerprint the gateway checks against `device.publicKey`. The private seed leaves this object only through
 * [seedForStorage]; [toString] prints the device id and nothing else.
 */
class DeviceIdentity private constructor(seed: ByteArray, publicKey: ByteArray) {

    private val seed: ByteArray = seed.copyOf()

    /** `hex(sha256(raw public key))`. */
    val deviceId: String = sha256Hex(publicKey)

    /** Raw 32-byte public key, base64url without padding (`connect.params.device.publicKey`). */
    val publicKeyBase64Url: String = base64Url(publicKey)

    /**
     * @param payload The text to sign (the v3 device-auth payload).
     * @return The Ed25519 signature, base64url without padding.
     * @throws java.security.GeneralSecurityException if Tink refuses the key (never for a valid seed).
     */
    fun sign(payload: String): String = base64Url(Ed25519Sign(seed).sign(payload.toByteArray(Charsets.UTF_8)))

    /** @return A copy of the 32-byte seed, for the encrypted store only. Sensitive — never log. */
    internal fun seedForStorage(): ByteArray = seed.copyOf()

    override fun toString(): String = "DeviceIdentity(deviceId=$deviceId)"

    companion object {
        /** Ed25519 seed length. */
        const val SEED_BYTES: Int = 32

        /** @return A fresh random identity. */
        fun generate(): DeviceIdentity {
            val pair = Ed25519Sign.KeyPair.newKeyPair()
            return DeviceIdentity(pair.privateKey, pair.publicKey)
        }

        /**
         * @param seed The 32-byte seed from [seedForStorage].
         * @return The identity for that seed.
         * @throws IllegalArgumentException if [seed] is not [SEED_BYTES] long.
         */
        fun fromSeed(seed: ByteArray): DeviceIdentity {
            require(seed.size == SEED_BYTES) { "Ed25519 seed must be $SEED_BYTES bytes, was ${seed.size}" }
            val pair = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed)
            return DeviceIdentity(pair.privateKey, pair.publicKey)
        }

        /**
         * @param publicKeyBase64Url A raw public key, base64url.
         * @return The gateway device id for it.
         */
        fun deviceIdForPublicKey(publicKeyBase64Url: String): String =
            sha256Hex(Base64.getUrlDecoder().decode(publicKeyBase64Url))
    }
}

/** Supplies this install's device identity (persisted by `DeviceIdentityStore`). */
interface DeviceIdentityProvider {
    /**
     * @return The identity, created and persisted on first use.
     * @throws Exception if the encrypted store cannot be written (the connect then fails with that cause).
     */
    suspend fun identity(): DeviceIdentity
}

/** base64url without padding, as the gateway expects for keys and signatures. */
internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.DeviceIdentityTest"`
Expected: PASS (6 tests).

- [ ] **Step 7: Check the release build still shrinks cleanly**

Run: `./gradlew assembleRelease`
Expected: BUILD SUCCESSFUL (no new R8 warnings: Tink classes are referenced directly, no reflection).

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceIdentity.kt app/src/test/kotlin/com/openclaw/ghostcrab/testutil/TestKeys.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceIdentityTest.kt
git commit -m "feat(gateway): Ed25519 device identity via Tink" -m "deviceId = hex(sha256(raw public key)), base64url key and signatures, RFC 8032 vectors and the fingerprint the 2026.10.3 gateway accepted. Declares tink-android 1.8.0 explicitly (already transitive via security-crypto)."
```

---

### Task 6: v3 device-auth signature payload

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceAuthPayload.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceAuthPayloadTest.kt`

The format is upstream `buildDeviceAuthPayloadV3` (`@openclaw/gateway-client@2026.10.3`). In M5, signatures over exactly this layout were accepted by the real gateway (`ws-device-1-unpaired`, `ws-device-2-paired`).

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceAuthPayloadTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.testutil.OPERATOR_SCOPES
import com.openclaw.ghostcrab.testutil.RFC8032_SEED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class DeviceAuthPayloadTest {

    private fun payload(
        token: String? = "shared-token",
        platform: String = "android",
        deviceFamily: String? = null,
    ) = DeviceAuthPayload(
        deviceId = "f044a1f50388e949137206dbf72f4c79c9b499af0e2a4048b005bbf217a9f2e2",
        clientId = "openclaw-android",
        clientMode = "ui",
        role = "operator",
        scopes = OPERATOR_SCOPES,
        signedAtMs = 1792027489701,
        token = token,
        nonce = "80f21d17-5553-4b86-b6bf-5bbddbc4f807",
        platform = platform,
        deviceFamily = deviceFamily,
    )

    @Test fun `v3 layout matches the frames the 2026_10_3 gateway accepted`() {
        // Fields of the M5 connect frame (ws-device-1-unpaired); the QA token is replaced by shared-token.
        assertEquals(
            "v3|f044a1f50388e949137206dbf72f4c79c9b499af0e2a4048b005bbf217a9f2e2|openclaw-android|ui|operator|" +
                "operator.read,operator.write,operator.admin|1792027489701|shared-token|" +
                "80f21d17-5553-4b86-b6bf-5bbddbc4f807|android|",
            payload().v3(),
        )
    }

    @Test fun `platform and device family are trimmed and ASCII-lowercased like upstream`() {
        assertEquals(true, payload(platform = " Android ", deviceFamily = "Pixel").v3().endsWith("|android|pixel"))
    }

    @Test fun `no token signs as an empty field`() {
        assertEquals(true, payload(token = null).v3().contains("|1792027489701||80f21d17-"))
    }

    @Test fun `signature for a fixed seed matches Node's Ed25519`() {
        // Node 26: crypto.sign(null, Buffer.from(payload), rfc8032Test1Key).toString("base64url").
        val identity = DeviceIdentity.fromSeed(RFC8032_SEED)
        val signed = DeviceAuthPayload(
            deviceId = identity.deviceId, clientId = "openclaw-android", clientMode = "ui", role = "operator",
            scopes = OPERATOR_SCOPES, signedAtMs = 1792027507354, token = "shared-token",
            nonce = "71d63897-7ebd-44e8-a27a-20ac1598b846", platform = "android", deviceFamily = null,
        ).v3()
        assertEquals(
            "GzOBsHYT5BEZyxVwIPScWWlbTEymYQ8Pf5XPg9loqDJwoJraqVIhPeZa2Y1c8dWBprteYbjNT-DpqDXjVKEyBg",
            identity.sign(signed),
        )
    }

    @Test fun `toString redacts the token`() {
        assertFalse(payload().toString().contains("shared-token"))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.DeviceAuthPayloadTest"`
Expected: FAIL, `Unresolved reference 'DeviceAuthPayload'`.

- [ ] **Step 3: Implement the payload**

`app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceAuthPayload.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

/**
 * Fields bound into the device signature of `connect` (upstream `buildDeviceAuthPayloadV3`).
 *
 * @param deviceId [DeviceIdentity.deviceId].
 * @param clientId `connect.params.client.id`.
 * @param clientMode `connect.params.client.mode`.
 * @param role `connect.params.role`.
 * @param scopes `connect.params.scopes`, in request order.
 * @param signedAtMs The challenge `ts` (also sent as `device.signedAt`).
 * @param token The credential sent in `auth` (shared or device token), or `null`. Sensitive.
 * @param nonce The challenge nonce (also sent as `device.nonce`).
 * @param platform `connect.params.client.platform`.
 * @param deviceFamily `connect.params.client.deviceFamily`, or `null`.
 */
data class DeviceAuthPayload(
    val deviceId: String,
    val clientId: String,
    val clientMode: String,
    val role: String,
    val scopes: List<String>,
    val signedAtMs: Long,
    val token: String?,
    val nonce: String,
    val platform: String,
    val deviceFamily: String?,
) {
    /** @return `v3|deviceId|clientId|mode|role|scopes|signedAt|token|nonce|platform|deviceFamily`. */
    fun v3(): String = listOf(
        "v3",
        deviceId,
        clientId,
        clientMode,
        role,
        scopes.joinToString(","),
        signedAtMs.toString(),
        token.orEmpty(),
        nonce,
        normalizeDeviceMetadata(platform),
        normalizeDeviceMetadata(deviceFamily),
    ).joinToString("|")

    override fun toString(): String =
        "DeviceAuthPayload(deviceId=$deviceId, clientId=$clientId, role=$role, scopes=$scopes, " +
            "signedAtMs=$signedAtMs, token=${token?.let { "[REDACTED]" }}, nonce=$nonce, platform=$platform)"
}

/** Upstream `normalizeDeviceMetadataForAuth`: trim, then lowercase ASCII `A`–`Z` only. */
private fun normalizeDeviceMetadata(value: String?): String =
    value?.trim()?.map { c -> if (c in 'A'..'Z') c + ('a' - 'A') else c }?.joinToString("").orEmpty()
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.DeviceAuthPayloadTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceAuthPayload.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceAuthPayloadTest.kt
git commit -m "feat(gateway): v3 device-auth signature payload" -m "Matches upstream buildDeviceAuthPayloadV3 and the frames the 2026.10.3 gateway accepted; signature vector cross-checked with Node's Ed25519."
```

---

### Task 7: Encrypted device-identity store

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/data/storage/DeviceIdentityStore.kt`
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/data/storage/KeystoreReads.kt`
- Create: `app/src/test/kotlin/com/openclaw/ghostcrab/testutil/InMemorySharedPreferences.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/storage/DeviceIdentityStoreTest.kt`

- [ ] **Step 1: Write an in-memory `SharedPreferences` for JVM tests**

`app/src/test/kotlin/com/openclaw/ghostcrab/testutil/InMemorySharedPreferences.kt`:
```kotlin
package com.openclaw.ghostcrab.testutil

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/** Map-backed [SharedPreferences] for JVM unit tests (EncryptedSharedPreferences needs the Keystore). */
class InMemorySharedPreferences : SharedPreferences {

    private val values = ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = key != null && values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val puts = HashMap<String, Any>()
        private val removes = HashSet<String>()
        private var clearAll = false

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            if (key != null) {
                if (value == null) removes.add(key) else puts[key] = value
            }
            return this
        }

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            put(key, values?.toSet())
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = put(key, value)
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = put(key, value)
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = put(key, value)
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = put(key, value)
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removes.add(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }
        override fun commit(): Boolean {
            if (clearAll) values.clear()
            removes.forEach { values.remove(it) }
            values.putAll(puts)
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
```

- [ ] **Step 2: Write the failing test**

`app/src/test/kotlin/com/openclaw/ghostcrab/data/storage/DeviceIdentityStoreTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.openclaw.ghostcrab.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class DeviceIdentityStoreTest {

    private val context = mockk<Context>(relaxed = true)

    @Test fun `first use generates and persists a seed that a new store reloads`() = runTest {
        val prefs = InMemorySharedPreferences()
        val io = StandardTestDispatcher(testScheduler)
        val first = DeviceIdentityStore(context, io) { prefs }.identity()
        assertTrue(prefs.contains(DeviceIdentityStore.KEY_SEED))
        val reloaded = DeviceIdentityStore(context, io) { prefs }.identity()
        assertEquals(first.deviceId, reloaded.deviceId)
    }

    @Test fun `the identity is cached per store`() = runTest {
        val store = DeviceIdentityStore(context, StandardTestDispatcher(testScheduler)) { InMemorySharedPreferences() }
        assertSame(store.identity(), store.identity())
    }

    @Test fun `a seed of the wrong length is replaced`() = runTest {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString(DeviceIdentityStore.KEY_SEED, Base64.getEncoder().encodeToString(ByteArray(5))).commit()
        val identity = DeviceIdentityStore(context, StandardTestDispatcher(testScheduler)) { prefs }.identity()
        val stored = Base64.getDecoder().decode(prefs.getString(DeviceIdentityStore.KEY_SEED, null))
        assertEquals(32, stored.size)
        assertEquals(64, identity.deviceId.length)
    }

    @Test fun `an unreadable seed (Keystore reset) is cleared and replaced`() = runTest {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true) {
            every { remove(any()) } returns this
            every { putString(any(), any()) } returns this
            every { commit() } returns true
        }
        val prefs = mockk<SharedPreferences> {
            every { getString(DeviceIdentityStore.KEY_SEED, null) } throws SecurityException("keystore key lost")
            every { edit() } returns editor
        }
        val a = DeviceIdentityStore(context, StandardTestDispatcher(testScheduler)) { prefs }.identity()
        val b = DeviceIdentityStore(context, StandardTestDispatcher(testScheduler)) { prefs }.identity()
        verify { editor.remove(DeviceIdentityStore.KEY_SEED) }
        assertNotEquals(a.deviceId, b.deviceId) // nothing readable was persisted, so each store starts over
    }
}
```

- [ ] **Step 3: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.storage.DeviceIdentityStoreTest"`
Expected: FAIL, `Unresolved reference 'DeviceIdentityStore'`.

- [ ] **Step 4: Implement the store**

`app/src/main/kotlin/com/openclaw/ghostcrab/data/storage/KeystoreReads.kt`:
```kotlin
package com.openclaw.ghostcrab.data.storage

import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Runs [read] against EncryptedSharedPreferences and maps the failures a lost or reset Keystore key and a corrupt
 * value produce to `null`: [GeneralSecurityException] (key or keyset unusable), [SecurityException] (the prefs'
 * wrapper for a failed decrypt), [IOException] (unreadable keyset) and [IllegalArgumentException] (bad Base64 /
 * JSON). Anything else is a bug and propagates.
 *
 * @param read The read; must not have side effects that a retry would repeat.
 * @return The value, or `null` when it is unreadable.
 */
internal inline fun <T> keystoreReadOrNull(read: () -> T?): T? = try {
    read()
} catch (_: GeneralSecurityException) {
    null
} catch (_: SecurityException) {
    null
} catch (_: IOException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
```

`app/src/main/kotlin/com/openclaw/ghostcrab/data/storage/DeviceIdentityStore.kt`:
```kotlin
package com.openclaw.ghostcrab.data.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openclaw.ghostcrab.data.gateway.DeviceIdentity
import com.openclaw.ghostcrab.data.gateway.DeviceIdentityProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Base64

private const val IDENTITY_PREFS_FILE = "ghostcrab_device_identity"

/** Opens the EncryptedSharedPreferences file holding the device seed (AES256-GCM values, Keystore-wrapped key). */
internal fun openIdentityPrefs(context: Context): SharedPreferences {
    val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    return EncryptedSharedPreferences.create(
        context,
        IDENTITY_PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
}

/**
 * Persists this install's Ed25519 seed (ADR-0007) in its own encrypted file, separate from the token file, so
 * "Clear all saved gateways" keeps the identity and the gateways' pairing records stay valid.
 *
 * An unreadable seed (Keystore reset, corrupt value) is removed and a new identity is generated; the gateway
 * then shows a new pairing request for this phone. That is the only recovery a lost key allows.
 *
 * @param context Application context.
 * @param ioDispatcher Dispatcher for the blocking preferences I/O.
 * @param prefsFactory Opens the preferences; injectable for JVM tests.
 */
class DeviceIdentityStore(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val prefsFactory: (Context) -> SharedPreferences = ::openIdentityPrefs,
) : DeviceIdentityProvider {

    private val mutex = Mutex()
    private val prefs: SharedPreferences by lazy { prefsFactory(context) }

    @Volatile private var cached: DeviceIdentity? = null

    /**
     * @return The persisted identity, generating and storing one on first use.
     * @throws IllegalStateException if a newly generated seed cannot be committed.
     */
    override suspend fun identity(): DeviceIdentity = cached ?: mutex.withLock {
        cached ?: withContext(ioDispatcher) { loadOrCreate() }.also { cached = it }
    }

    private fun loadOrCreate(): DeviceIdentity {
        readSeed()?.let { return DeviceIdentity.fromSeed(it) }
        val fresh = DeviceIdentity.generate()
        val encoded = Base64.getEncoder().encodeToString(fresh.seedForStorage())
        check(prefs.edit().putString(KEY_SEED, encoded).commit()) { "device identity could not be persisted" }
        return fresh
    }

    /** The stored seed, or `null` when absent or unusable (an unusable entry is removed first). */
    private fun readSeed(): ByteArray? {
        // Keystore loss or a corrupt value: recovery is a new identity (the gateway then shows a new pairing request).
        val seed = keystoreReadOrNull { prefs.getString(KEY_SEED, null)?.let { Base64.getDecoder().decode(it) } }
        if (seed?.size == DeviceIdentity.SEED_BYTES) return seed
        prefs.edit().remove(KEY_SEED).commit()
        return null
    }

    internal companion object {
        /** Preference key of the base64 seed. */
        const val KEY_SEED = "ed25519_seed_v1"
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.storage.DeviceIdentityStoreTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/storage/DeviceIdentityStore.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/storage/KeystoreReads.kt app/src/test/kotlin/com/openclaw/ghostcrab/testutil/InMemorySharedPreferences.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/storage/DeviceIdentityStoreTest.kt
git commit -m "feat(storage): encrypted Ed25519 device identity store" -m "Own EncryptedSharedPreferences file so Clear all keeps the pairing valid; an unreadable seed is replaced (new pairing request) instead of blocking every connect."
```

---

### Task 8: Per-gateway device-token storage

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceTokenStore.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/storage/ConnectionProfileStore.kt`
- Create: `app/src/test/kotlin/com/openclaw/ghostcrab/testutil/InMemoryDeviceTokenStore.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceTokenStoreTest.kt`

Device tokens live in the same EncryptedSharedPreferences file as the profile tokens (`ghostcrab_tokens`), so `ConnectionProfileStore.clearAll()` wipes them with everything else.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceTokenStoreTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

import android.content.Context
import com.openclaw.ghostcrab.data.storage.ConnectionProfileStore
import com.openclaw.ghostcrab.testutil.InMemorySharedPreferences
import com.openclaw.ghostcrab.testutil.OPERATOR_SCOPES
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DeviceTokenStoreTest {

    private val prefs = InMemorySharedPreferences()
    private val token = StoredDeviceToken("dt-test-0001", OPERATOR_SCOPES, "operator", "device-1")

    private fun TestScope.store() =
        ConnectionProfileStore(mockk<Context>(relaxed = true), StandardTestDispatcher(testScheduler)) { prefs }

    @Test fun `gateway keys are the normalised base URL`() {
        assertEquals("http://192.168.1.50:18789", gatewayKeyOf("http://192.168.1.50:18789/"))
        assertEquals("http://gw.local:18789", gatewayKeyOf("http://gw.local"))
        assertEquals("https://gw.example:443", gatewayKeyOf("https://gw.example/ws"))
        assertEquals("garbage", gatewayKeyOf(" garbage/ "))
    }

    @Test fun `save then load round-trips`() = runTest {
        val store = store()
        store.saveDeviceToken("http://gw.local:18789", token)
        assertEquals(token, store.loadDeviceToken("http://gw.local:18789"))
        assertNull(store.loadDeviceToken("http://other:18789"))
    }

    @Test fun `clear removes only that gateway`() = runTest {
        val store = store()
        store.saveDeviceToken("a", token)
        store.saveDeviceToken("b", token)
        store.clearDeviceToken("a")
        assertNull(store.loadDeviceToken("a"))
        assertEquals(token, store.loadDeviceToken("b"))
    }

    @Test fun `an undecodable entry reads as null and is removed`() = runTest {
        prefs.edit().putString("device_token_a", "{not json").commit()
        assertNull(store().loadDeviceToken("a"))
        assertFalse(prefs.contains("device_token_a"))
    }

    @Test fun `wiping the token file wipes device tokens too`() = runTest {
        val store = store()
        store.saveDeviceToken("a", token)
        store.wipeTokens()
        assertNull(store.loadDeviceToken("a"))
    }

    @Test fun `toString redacts the token`() {
        assertFalse(token.toString().contains("dt-test-0001"))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.DeviceTokenStoreTest"`
Expected: FAIL, `Unresolved reference 'StoredDeviceToken'`.

- [ ] **Step 3: Add the types**

`app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceTokenStore.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.util.GatewayUrl
import kotlinx.serialization.Serializable

/**
 * A device token the gateway issued in `hello-ok.auth.deviceToken`.
 *
 * @param token The bearer for `connect.params.auth.deviceToken`. Sensitive — never log.
 * @param scopes The scopes it was granted (reused when reconnecting with it).
 * @param role The role it was issued for (`operator`).
 * @param deviceId The [DeviceIdentity.deviceId] it belongs to; a token for another identity is ignored.
 */
@Serializable
data class StoredDeviceToken(
    val token: String,
    val scopes: List<String>,
    val role: String,
    val deviceId: String,
) {
    override fun toString(): String =
        "StoredDeviceToken(token=[REDACTED], scopes=$scopes, role=$role, deviceId=$deviceId)"
}

/** Per-gateway device-token persistence, keyed by [gatewayKeyOf]. */
interface DeviceTokenStore {
    /**
     * @param gatewayKey Key from [gatewayKeyOf].
     * @return The stored token, or `null` when none (or when the entry was unreadable; it is then removed).
     */
    suspend fun loadDeviceToken(gatewayKey: String): StoredDeviceToken?

    /**
     * @param gatewayKey Key from [gatewayKeyOf].
     * @param token The token to store, replacing any previous one.
     */
    suspend fun saveDeviceToken(gatewayKey: String, token: StoredDeviceToken)

    /** @param gatewayKey Key from [gatewayKeyOf]. No-op when nothing is stored. */
    suspend fun clearDeviceToken(gatewayKey: String)
}

/**
 * @param url A gateway base URL as the user or a profile gave it.
 * @return The canonical `scheme://host:port` ([GatewayUrl]), or the trimmed input without trailing `/` when it
 *   does not parse.
 */
fun gatewayKeyOf(url: String): String = GatewayUrl.parse(url)?.toString() ?: url.trim().trimEnd('/')
```

- [ ] **Step 4: Implement it in `ConnectionProfileStore`**

In `app/src/main/kotlin/com/openclaw/ghostcrab/data/storage/ConnectionProfileStore.kt`:

Add imports:
```kotlin
import com.openclaw.ghostcrab.data.gateway.DeviceTokenStore
import com.openclaw.ghostcrab.data.gateway.StoredDeviceToken
```

Change the class header from
```kotlin
class ConnectionProfileStore(
```
…`) {` to end with `) : DeviceTokenStore {` (the constructor parameters stay as they are).

Insert this block directly before `private val encryptedPrefs: SharedPreferences by lazy { encryptedPrefsFactory(context) }`:
```kotlin
    // ── Device tokens (ADR-0007) ──────────────────────────────────────────────
    // Same encrypted file as the profile tokens, so clearAll()/wipeTokens() removes them too.

    override suspend fun loadDeviceToken(gatewayKey: String): StoredDeviceToken? = withContext(ioDispatcher) {
        // Keystore loss (typed in keystoreReadOrNull, Task 7): the gateway re-issues a token on the next shared-token
        // connect.
        val raw = keystoreReadOrNull { encryptedPrefs.getString(deviceTokenKey(gatewayKey), null) }
        val decoded = raw?.let(::decodeDeviceToken)
        if (raw != null && decoded == null) removeDeviceToken(gatewayKey)
        decoded
    }

    override suspend fun saveDeviceToken(gatewayKey: String, token: StoredDeviceToken): Unit =
        withContext(ioDispatcher) {
            val json = profileJson.encodeToString(StoredDeviceToken.serializer(), token)
            encryptedPrefs.edit().putString(deviceTokenKey(gatewayKey), json).apply()
        }

    override suspend fun clearDeviceToken(gatewayKey: String): Unit = withContext(ioDispatcher) {
        removeDeviceToken(gatewayKey)
    }

    private fun removeDeviceToken(gatewayKey: String) {
        encryptedPrefs.edit().remove(deviceTokenKey(gatewayKey)).apply()
    }

    private fun deviceTokenKey(gatewayKey: String) = "device_token_$gatewayKey"

```

Add this top-level function after `decodeProfilesForWrite(...)`:
```kotlin
/** Decodes a stored device token; `null` for undecodable data (the caller removes the entry). */
internal fun decodeDeviceToken(json: String): StoredDeviceToken? = try {
    profileJson.decodeFromString(StoredDeviceToken.serializer(), json)
} catch (ignored: IllegalArgumentException) { // SerializationException extends it
    null
}
```

- [ ] **Step 5: Add the in-memory store for later tests**

`app/src/test/kotlin/com/openclaw/ghostcrab/testutil/InMemoryDeviceTokenStore.kt`:
```kotlin
package com.openclaw.ghostcrab.testutil

import com.openclaw.ghostcrab.data.gateway.DeviceTokenStore
import com.openclaw.ghostcrab.data.gateway.StoredDeviceToken
import java.util.concurrent.ConcurrentHashMap

/** [DeviceTokenStore] backed by a map; [tokens] is open for assertions and seeding. */
class InMemoryDeviceTokenStore : DeviceTokenStore {
    val tokens = ConcurrentHashMap<String, StoredDeviceToken>()
    override suspend fun loadDeviceToken(gatewayKey: String): StoredDeviceToken? = tokens[gatewayKey]
    override suspend fun saveDeviceToken(gatewayKey: String, token: StoredDeviceToken) {
        tokens[gatewayKey] = token
    }
    override suspend fun clearDeviceToken(gatewayKey: String) {
        tokens.remove(gatewayKey)
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.DeviceTokenStoreTest" --tests "com.openclaw.ghostcrab.data.ConnectionProfileStoreTest"`
Expected: PASS (new 6 tests; the existing `ConnectionProfileStoreTest` stays green).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceTokenStore.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/storage/ConnectionProfileStore.kt app/src/test/kotlin/com/openclaw/ghostcrab/testutil/InMemoryDeviceTokenStore.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/DeviceTokenStoreTest.kt
git commit -m "feat(storage): per-gateway device tokens in the encrypted token file" -m "Keyed by the normalised gateway URL, carries scopes and device id, wiped by Clear all (ADR-0007)."
```

---

### Task 9: Connect handshake

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayHello.kt`
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayHandshake.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayHandshakeTest.kt`

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayHandshakeTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.testutil.GatewayFixtures
import com.openclaw.ghostcrab.testutil.OPERATOR_SCOPES
import com.openclaw.ghostcrab.testutil.RFC8032_DEVICE_ID
import com.openclaw.ghostcrab.testutil.RFC8032_SEED
import com.openclaw.ghostcrab.testutil.ScriptedWsSession
import com.openclaw.ghostcrab.testutil.ScriptedWsSession.Companion.fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GatewayHandshakeTest {

    private val identity = DeviceIdentity.fromSeed(RFC8032_SEED)
    private val client = ClientInfo(
        id = "openclaw-android",
        version = "0.3.0",
        platform = "android",
        displayName = "GhostCrab (Android)",
    )

    private fun rpc(session: ScriptedWsSession) = GatewayRpcClient.create(session, "ws://gw/ws", Dispatchers.Unconfined)

    @Test fun `perform signs the challenge and parses hello-ok`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        val hello = GatewayHandshake.perform(rpc(session), client, ConnectAuth.SharedToken("shared-token"), identity)

        assertEquals("2026.10.3", hello.serverVersion)
        assertEquals(OPERATOR_SCOPES.toSet(), hello.scopes)
        assertEquals("dt-test-0001", hello.deviceToken)
        assertEquals("token", hello.gatewayAuthMode)
        assertEquals("ecb0a0611ff5", hello.gatewayHost)
        assertTrue("skills.status" in hello.methods)

        val params = session.paramsOf("connect")
        assertEquals(4, params.getValue("minProtocol").jsonPrimitive.content.toInt())
        assertEquals("operator", params.getValue("role").jsonPrimitive.content)
        assertEquals("shared-token", params.getValue("auth").jsonObject.getValue("token").jsonPrimitive.content)
        assertEquals("ghostcrab-android/0.3.0", params.getValue("userAgent").jsonPrimitive.content)
        val device = params.getValue("device").jsonObject
        assertEquals(RFC8032_DEVICE_ID, device.getValue("id").jsonPrimitive.content)
        assertEquals("71d63897-7ebd-44e8-a27a-20ac1598b846", device.getValue("nonce").jsonPrimitive.content)
        assertEquals("1792027507354", device.getValue("signedAt").jsonPrimitive.content)
        // Same payload and key as DeviceAuthPayloadTest: Tink's Ed25519 is deterministic.
        assertEquals(
            "GzOBsHYT5BEZyxVwIPScWWlbTEymYQ8Pf5XPg9loqDJwoJraqVIhPeZa2Y1c8dWBprteYbjNT-DpqDXjVKEyBg",
            device.getValue("signature").jsonPrimitive.content,
        )
    }

    @Test fun `a stored device token is sent as deviceToken with its own scopes`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        val auth = ConnectAuth.DeviceToken("dt-old", listOf("operator.read"))
        GatewayHandshake.perform(rpc(session), client, auth, identity)
        val params = session.paramsOf("connect")
        assertEquals("dt-old", params.getValue("auth").jsonObject.getValue("deviceToken").jsonPrimitive.content)
        assertEquals(listOf("operator.read"), params.getValue("scopes").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `no credential sends no auth block`() = runTest {
        val none = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        GatewayHandshake.perform(rpc(none), client, ConnectAuth.None, identity)
        assertNull(none.paramsOf("connect")["auth"])
    }

    @Test fun `pairing required and token mismatch surface as typed errors`() = runTest {
        val pairing = ScriptedWsSession(
            closeAfterReplyTo = setOf("connect"),
            respond = fixtures("connect" to "err-pairing-required"),
        )
        assertEquals(
            "af0b5d81-7bd2-4b86-80b0-4a489b541974",
            assertThrows<GatewayPairingRequiredException> {
                GatewayHandshake.perform(rpc(pairing), client, ConnectAuth.SharedToken("shared-token"), identity)
            }.requestId,
        )
        val mismatch = ScriptedWsSession(
            closeAfterReplyTo = setOf("connect"),
            respond = fixtures("connect" to "err-token-mismatch"),
        )
        assertEquals(
            "AUTH_TOKEN_MISMATCH",
            assertThrows<GatewayConnectAuthException> {
                GatewayHandshake.perform(rpc(mismatch), client, ConnectAuth.SharedToken("wrong"), identity)
            }.detailCode,
        )
    }

    @Test fun `a token-only hello has no scopes`() {
        val payload = GatewayFixtures.frame("hello-ok-noscope").getValue("payload")
        val hello = GatewayHandshake.parseHello("ws://gw/ws", payload)
        assertTrue(hello.scopes.isEmpty())
        assertNull(hello.gatewayAuthMode)
        assertNull(hello.gatewayHost)
    }

    @Test fun `a challenge without ts is a protocol error`() = runTest {
        val session = ScriptedWsSession(sendChallenge = false, respond = { _, _ -> null })
        session.push(
            buildJsonObject {
                put("type", "event")
                put("event", "connect.challenge")
                putJsonObject("payload") { put("nonce", "n") }
            },
        )
        assertThrows<GatewayProtocolException> {
            GatewayHandshake.perform(rpc(session), client, ConnectAuth.None, identity)
        }
    }

    @Test fun `auth requirement and capabilities come from hello-ok`() {
        val payload = GatewayFixtures.frame("hello-ok-paired").getValue("payload")
        val hello = GatewayHandshake.parseHello("ws://gw/ws", payload)
        assertEquals(AuthRequirement.Token, hello.toAuthRequirement(token = null))
        assertEquals(AuthRequirement.None, hello.copy(gatewayAuthMode = "none").toAuthRequirement(token = "x"))
        val silent = hello.copy(gatewayAuthMode = null, authMethod = null)
        assertEquals(AuthRequirement.None, silent.toAuthRequirement(null))
        assertEquals(
            listOf(CAPABILITY_CONFIG, CAPABILITY_MODELS, CAPABILITY_SKILLS),
            gatewayCapabilities(hello.methods),
        )
    }

    @Test fun `credential selection follows upstream order without setup-code tokens`() {
        val stored = StoredDeviceToken("dt", listOf("operator.read"), "operator", RFC8032_DEVICE_ID)
        assertTrue(selectConnectAuth("t", stored) is ConnectAuth.SharedToken)
        assertTrue(selectConnectAuth(null, stored) is ConnectAuth.DeviceToken)
        assertEquals(ConnectAuth.None, selectConnectAuth(null, null))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.GatewayHandshakeTest"`
Expected: FAIL, `Unresolved reference 'ClientInfo'`.

- [ ] **Step 3: Add the hello model**

`app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayHello.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Dashboard capability key: `config.get` + `config.patch` are available. */
const val CAPABILITY_CONFIG = "config"

/** Dashboard capability key: `models.list` is available. */
const val CAPABILITY_MODELS = "models"

/** Dashboard capability key: `skills.status` is available. */
const val CAPABILITY_SKILLS = "skills"

/**
 * `connect.challenge` payload.
 *
 * @param nonce Signed and echoed in `device.nonce`.
 * @param ts Server time in ms; used as `device.signedAt`.
 */
data class ConnectChallenge(val nonce: String, val ts: Long) {
    companion object {
        /**
         * @param endpoint For the error message.
         * @param payload The event payload.
         * @return The challenge.
         * @throws GatewayProtocolException unless it has a non-blank `nonce` and a non-negative integer `ts`
         *   (upstream treats anything else as invalid).
         */
        fun parse(endpoint: String, payload: JsonElement?): ConnectChallenge {
            val obj = payload as? JsonObject
            val nonce = obj?.stringOrNull("nonce")?.takeIf { it.isNotBlank() }
            val ts = (obj?.get("ts") as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it >= 0 }
            if (nonce == null || ts == null) {
                throw GatewayProtocolException(
                    endpoint,
                    "connect.challenge without a nonce and a non-negative integer ts",
                )
            }
            return ConnectChallenge(nonce, ts)
        }
    }
}

/**
 * The parts of `hello-ok` GhostCrab uses.
 *
 * @param protocol Negotiated protocol (always [GATEWAY_PROTOCOL_VERSION] once parsed).
 * @param serverVersion `server.version`, e.g. `2026.10.3`.
 * @param methods `features.methods`.
 * @param authMethod `auth.method`: `token`, `device-token`, `bootstrap-token`, `none`, ….
 * @param role `auth.role`.
 * @param scopes `auth.scopes`: the live authority of this socket.
 * @param deviceToken `auth.deviceToken`, when the gateway issued one. Sensitive.
 * @param gatewayAuthMode `snapshot.authMode` (`token`, `password`, `none`, …); absent on a scope-less socket.
 * @param gatewayHost `host` of the `snapshot.presence` entry with `reason: "self"`.
 */
data class HelloOk(
    val protocol: Int,
    val serverVersion: String,
    val methods: Set<String>,
    val authMethod: String?,
    val role: String,
    val scopes: Set<String>,
    val deviceToken: String?,
    val gatewayAuthMode: String?,
    val gatewayHost: String?,
) {
    override fun toString(): String =
        "HelloOk(protocol=$protocol, serverVersion=$serverVersion, methods=${methods.size}, authMethod=$authMethod, " +
            "role=$role, scopes=$scopes, deviceToken=${deviceToken?.let { "[REDACTED]" }}, " +
            "gatewayAuthMode=$gatewayAuthMode, gatewayHost=$gatewayHost)"
}

/**
 * ADR-0007 replacement for ADR-002's probe: the gateway's auth mode from `hello-ok`.
 *
 * @param token The shared token used for this connect (decides when the hello says nothing).
 * @return [AuthRequirement.None] for `none`, else [AuthRequirement.Token].
 */
fun HelloOk.toAuthRequirement(token: String?): AuthRequirement = when (gatewayAuthMode ?: authMethod) {
    "none" -> AuthRequirement.None
    null -> if (token == null) AuthRequirement.None else AuthRequirement.Token
    else -> AuthRequirement.Token
}

/**
 * @param methods `hello-ok.features.methods`.
 * @return The GhostCrab capability keys these methods support, in a stable order.
 */
fun gatewayCapabilities(methods: Set<String>): List<String> = buildList {
    if ("config.get" in methods && "config.patch" in methods) add(CAPABILITY_CONFIG)
    if ("models.list" in methods) add(CAPABILITY_MODELS)
    if ("skills.status" in methods) add(CAPABILITY_SKILLS)
}

internal fun parseHelloOk(endpoint: String, payload: JsonElement): HelloOk {
    val obj = payload as? JsonObject
    val auth = obj?.get("auth") as? JsonObject
    if (obj == null || auth == null || obj.stringOrNull("type") != "hello-ok") {
        throw GatewayProtocolException(endpoint, "connect answered without a hello-ok payload")
    }
    val server = obj["server"] as? JsonObject
    val protocol = (obj["protocol"] as? JsonPrimitive)?.longOrNull?.toInt()
    if (server == null || protocol != GATEWAY_PROTOCOL_VERSION) {
        throw GatewayProtocolException(
            endpoint,
            "gateway negotiated protocol $protocol; GhostCrab speaks $GATEWAY_PROTOCOL_VERSION",
        )
    }
    val snapshot = obj["snapshot"] as? JsonObject
    return HelloOk(
        protocol = GATEWAY_PROTOCOL_VERSION,
        serverVersion = server.stringOrNull("version") ?: "unknown",
        methods = (obj["features"] as? JsonObject)?.stringListOrEmpty("methods").orEmpty().toSet(),
        authMethod = auth.stringOrNull("method"),
        role = auth.stringOrNull("role") ?: GatewayHandshake.ROLE_OPERATOR,
        scopes = auth.stringListOrEmpty("scopes").toSet(),
        deviceToken = auth.stringOrNull("deviceToken"),
        gatewayAuthMode = snapshot?.stringOrNull("authMode"),
        gatewayHost = (snapshot?.get("presence") as? JsonArray)
            ?.firstNotNullOfOrNull { entry -> (entry as? JsonObject)?.takeIf { it.stringOrNull("reason") == "self" } }
            ?.stringOrNull("host"),
    )
}
```

- [ ] **Step 4: Add the handshake**

`app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayHandshake.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `connect.params.client` (ADR-0007). [id] and [mode] must be values upstream's allow-lists accept (checked before
 * auth: anything else is `INVALID_REQUEST invalid connect params`, close 1008); [version] and [displayName] stay
 * honest. [platform] is bound into the device signature and pinned when the device is approved: changing it
 * re-triggers pairing (`metadata-upgrade`), so it is a constant per app, never derived from the device.
 *
 * @param id Upstream client id.
 * @param version GhostCrab's version name.
 * @param platform `android`.
 * @param displayName Shown in the gateway's pending-pairing list.
 * @param mode Upstream client mode; `ui` for an interactive operator app.
 * @param deviceFamily Optional; bound into the signature and pinned at approval, so it must never change.
 * @param locale BCP-47 tag for `connect.params.locale`.
 */
data class ClientInfo(
    val id: String,
    val version: String,
    val platform: String,
    val displayName: String,
    val mode: String = "ui",
    val deviceFamily: String? = null,
    val locale: String = "en-US",
) {
    /** `connect.params.userAgent`. */
    val userAgent: String get() = "ghostcrab-$platform/$version"
}

/** Credential for `connect.params.auth`. Every `toString` redacts the secret. */
sealed interface ConnectAuth {
    /** The value bound into the device signature's token field, or `null`. */
    val signatureToken: String?

    /** The gateway's shared token (`auth.token`). */
    class SharedToken(val token: String) : ConnectAuth {
        override val signatureToken: String get() = token
        override fun toString(): String = "SharedToken([REDACTED])"
    }

    /** A device token this gateway issued earlier (`auth.deviceToken`), reused with its granted [scopes]. */
    class DeviceToken(val token: String, val scopes: List<String>) : ConnectAuth {
        override val signatureToken: String get() = token
        override fun toString(): String = "DeviceToken([REDACTED], scopes=$scopes)"
    }

    /** No credential: `auth.mode: none` gateways and the auth probe. */
    data object None : ConnectAuth {
        override val signatureToken: String? get() = null
    }
}

/**
 * Upstream `selectGatewayConnectAuth` order for an operator client: explicit shared token, then the stored device
 * token, else nothing. Setup-code bootstrap tokens are not a credential here: the gateway accepts them only for
 * `role: "node"` (verified 2026-10-15, ADR-0007).
 *
 * @param sharedToken Token the user or profile supplied.
 * @param stored This gateway's stored device token (already filtered to the current identity).
 * @return The credential to send.
 */
fun selectConnectAuth(sharedToken: String?, stored: StoredDeviceToken?): ConnectAuth = when {
    sharedToken != null -> ConnectAuth.SharedToken(sharedToken)
    stored != null -> ConnectAuth.DeviceToken(stored.token, stored.scopes)
    else -> ConnectAuth.None
}

/** The `connect` exchange (ADR-0007 decision 3). */
object GatewayHandshake {

    /** GhostCrab connects as an operator client. */
    const val ROLE_OPERATOR = "operator"

    /** Scopes requested unless a stored device token says otherwise (`config.*` and `skills.install` need admin). */
    val OPERATOR_SCOPES: List<String> = listOf("operator.read", "operator.write", "operator.admin")

    /** Time for the gateway to answer `connect`. */
    const val CONNECT_TIMEOUT_MS: Long = 15_000L

    /**
     * Waits for the challenge, sends the signed `connect`, parses `hello-ok`.
     *
     * @param rpc A freshly opened socket (nothing sent yet).
     * @param client This app's client info.
     * @param auth The credential from [selectConnectAuth].
     * @param identity This install's device identity.
     * @return The parsed hello.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException while the device awaits approval.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException for credential/device rejections.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayProtocolException for a malformed challenge or hello.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException or
     *   [com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException] on transport failure.
     */
    suspend fun perform(
        rpc: GatewayRpcClient,
        client: ClientInfo,
        auth: ConnectAuth,
        identity: DeviceIdentity,
    ): HelloOk {
        val challenge = ConnectChallenge.parse(rpc.endpoint, rpc.awaitChallenge().payload)
        val payload = rpc.request("connect", connectParams(client, auth, identity, challenge), CONNECT_TIMEOUT_MS)
        return parseHello(rpc.endpoint, payload)
    }

    /**
     * @param endpoint For error messages.
     * @param payload The `connect` response payload.
     * @return The parsed hello.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayProtocolException if it is not a protocol-4 `hello-ok`.
     */
    fun parseHello(endpoint: String, payload: JsonElement): HelloOk = parseHelloOk(endpoint, payload)

    /**
     * @param client This app's client info.
     * @param auth The credential; also bound into the signature.
     * @param identity Signs the v3 payload.
     * @param challenge Supplies `nonce` and `signedAt`.
     * @return `connect.params` with a v3 device signature over [challenge].
     */
    fun connectParams(
        client: ClientInfo,
        auth: ConnectAuth,
        identity: DeviceIdentity,
        challenge: ConnectChallenge,
    ): JsonObject {
        val scopes = (auth as? ConnectAuth.DeviceToken)?.scopes?.takeIf { it.isNotEmpty() } ?: OPERATOR_SCOPES
        val signed = DeviceAuthPayload(
            deviceId = identity.deviceId,
            clientId = client.id,
            clientMode = client.mode,
            role = ROLE_OPERATOR,
            scopes = scopes,
            signedAtMs = challenge.ts,
            token = auth.signatureToken,
            nonce = challenge.nonce,
            platform = client.platform,
            deviceFamily = client.deviceFamily,
        ).v3()
        return buildJsonObject {
            put("minProtocol", GATEWAY_PROTOCOL_VERSION)
            put("maxProtocol", GATEWAY_PROTOCOL_VERSION)
            putJsonObject("client") {
                put("id", client.id)
                put("version", client.version)
                put("platform", client.platform)
                put("mode", client.mode)
                put("displayName", client.displayName)
                client.deviceFamily?.let { put("deviceFamily", it) }
            }
            put("role", ROLE_OPERATOR)
            putJsonArray("scopes") { scopes.forEach { add(it) } }
            putJsonArray("caps") {}
            putJsonArray("commands") {}
            putJsonObject("permissions") {}
            authJson(auth)?.let { put("auth", it) }
            put("locale", client.locale)
            put("userAgent", client.userAgent)
            putJsonObject("device") {
                put("id", identity.deviceId)
                put("publicKey", identity.publicKeyBase64Url)
                put("signature", identity.sign(signed))
                put("signedAt", challenge.ts)
                put("nonce", challenge.nonce)
            }
        }
    }

    private fun authJson(auth: ConnectAuth): JsonObject? = when (auth) {
        is ConnectAuth.SharedToken -> buildJsonObject { put("token", auth.token) }
        is ConnectAuth.DeviceToken -> buildJsonObject { put("deviceToken", auth.token) }
        ConnectAuth.None -> null
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.GatewayHandshakeTest"`
Expected: PASS (8 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayHello.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayHandshake.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayHandshakeTest.kt
git commit -m "feat(gateway): connect handshake with signed device identity" -m "challenge -> v3-signed connect -> hello-ok; upstream credential order for operators (shared, device, none); auth mode, scopes, device token, host and capabilities read from hello-ok (ADR-0007)."
```

---
### Task 10: Connection manager on the WS handshake

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt` (add `openWsSession()` and `webSocketUrl()` after `openWebSocket()`)
- Rewrite: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/GatewayConnectionManagerImpl.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt`
- Modify: `app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientErrorMappingTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/di/KoinModulesTest.kt`
- Rewrite: `app/src/test/kotlin/com/openclaw/ghostcrab/data/GatewayConnectionManagerImplTest.kt`

After this task, connect/probe/health use the protocol-4 socket. Config, models, AI and skills still go through `requireClient()` / `liveWsFactory()` until Tasks 12–18; they behave exactly as today against a real gateway.

- [ ] **Step 1: Write the failing client tests**

Add to the class in `app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientErrorMappingTest.kt` (the needed imports are already in the file):
```kotlin
    @Test fun `openWsSession refused upgrade maps to GatewayUnreachableException`() = runTest {
        val client = OpenClawApiClient.forTest(
            ERR_BASE,
            HttpClient(MockEngine { throw ConnectException("refused") }) { install(WebSockets) },
        )
        val ex = assertThrows<GatewayUnreachableException> { client.openWsSession() }
        assertTrue(ex.cause is ConnectException)
    }

    @Test fun `webSocketUrl maps http to ws and https to wss`() {
        val engine = MockEngine { error("not called") }
        val plain = OpenClawApiClient.forTest(ERR_BASE, HttpClient(engine))
        val tls = OpenClawApiClient.forTest("https://gw.example:443/", HttpClient(engine))
        assertEquals("ws://192.168.1.50:18789/ws", plain.webSocketUrl())
        assertEquals("wss://gw.example:443/ws", tls.webSocketUrl())
    }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.OpenClawApiClientErrorMappingTest"`
Expected: FAIL, `Unresolved reference 'openWsSession'`.

- [ ] **Step 3: Add the raw-socket opener**

In `OpenClawApiClient.kt` add the import `import com.openclaw.ghostcrab.data.ws.WsSession` and, directly after `openWebSocket()`:
```kotlin
    /**
     * Opens the gateway control-plane WebSocket (`<baseUrl>/ws`, ADR-0007) on this client's engine, so the
     * upgrade honours the cleartext policy, timeouts and sanitized logging of every HTTP call. The upgrade
     * carries no credential: authentication happens in the `connect` frame.
     *
     * @return The raw socket. Wrap it in a `GatewayRpcClient`, which then owns and closes it.
     * @throws GatewayUnreachableException if the host cannot be reached.
     * @throws GatewayTimeoutException if the upgrade times out.
     * @throws CleartextBlockedException if app policy blocks cleartext to a public IP.
     */
    suspend fun openWsSession(): WsSession = safeRequest(baseUrl) {
        KtorWsSession(httpClient.webSocketSession(webSocketUrl()))
    }

    /** @return `ws(s)://host:port/ws` for [baseUrl] (`https` becomes `wss`). */
    fun webSocketUrl(): String = baseUrl.replaceFirst(Regex("^http"), "ws").trimEnd('/') + "/ws"
```

- [ ] **Step 4: Run the client tests**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.OpenClawApiClientErrorMappingTest"`
Expected: PASS.

- [ ] **Step 5: Rewrite the manager test (failing)**

Replace `app/src/test/kotlin/com/openclaw/ghostcrab/data/GatewayConnectionManagerImplTest.kt` with the file below. It keeps, adapted to the WebSocket flow, the six tests on `main` that still apply (concurrent connects, the unclassified-exception wrap, the redacted `toString`, the injected IO dispatcher, the live cleartext opt-in, the fail-closed settings read); the other `main` tests exercise the `/status` probe and the REST session client that this task removes.
```kotlin
package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.data.api.dto.HealthResponse
import com.openclaw.ghostcrab.data.gateway.ClientInfo
import com.openclaw.ghostcrab.data.gateway.DeviceIdentity
import com.openclaw.ghostcrab.data.gateway.DeviceIdentityProvider
import com.openclaw.ghostcrab.data.gateway.StoredDeviceToken
import com.openclaw.ghostcrab.data.impl.GatewayConnectionManagerImpl
import com.openclaw.ghostcrab.data.impl.OpenClawApiClientFactory
import com.openclaw.ghostcrab.data.ws.WsSession
import com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.SettingsRepository
import com.openclaw.ghostcrab.testutil.InMemoryDeviceTokenStore
import com.openclaw.ghostcrab.testutil.OPERATOR_SCOPES
import com.openclaw.ghostcrab.testutil.RFC8032_SEED
import com.openclaw.ghostcrab.testutil.ScriptedWsSession
import com.openclaw.ghostcrab.testutil.ScriptedWsSession.Companion.fixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.coroutines.CoroutineContext

class GatewayConnectionManagerImplTest {

    private val url = "http://192.168.1.50:18789"
    private val fixedIdentity = DeviceIdentity.fromSeed(RFC8032_SEED)
    private val identityProvider = object : DeviceIdentityProvider {
        override suspend fun identity(): DeviceIdentity = fixedIdentity
    }
    private val clientInfo = ClientInfo(
        id = "openclaw-android",
        version = "0.3.0",
        platform = "android",
        displayName = "GhostCrab (Android)",
    )
    private val tokens = InMemoryDeviceTokenStore()
    private val blockCleartext = object : SettingsRepository {
        override val allowCleartextPublicIPs: Flow<Boolean> = flowOf(false)
        override suspend fun setAllowCleartextPublicIPs(enabled: Boolean) = Unit
    }
    private lateinit var rest: OpenClawApiClient
    private lateinit var factory: OpenClawApiClientFactory

    private fun restClient(session: WsSession?, wsUrl: String): OpenClawApiClient {
        val client = mockk<OpenClawApiClient>(relaxed = true)
        coEvery { client.health() } returns HealthResponse("live")
        if (session != null) coEvery { client.openWsSession() } returns session
        every { client.webSocketUrl() } returns wsUrl
        return client
    }

    private fun TestScope.manager(
        session: WsSession?,
        settings: SettingsRepository = blockCleartext,
        ioDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ): GatewayConnectionManagerImpl {
        rest = restClient(session, "ws://192.168.1.50:18789/ws")
        factory = mockk { every { unauthenticated(any(), any()) } returns rest }
        return GatewayConnectionManagerImpl(
            identityProvider = identityProvider,
            deviceTokens = tokens,
            clientInfo = clientInfo,
            clientFactory = factory,
            settingsRepository = settings,
            ioDispatcher = ioDispatcher,
        )
    }

    // ── connect ───────────────────────────────────────────────────────────────

    @Test fun `shared-token connect signs the handshake and lands in Connected`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        val m = manager(session)
        m.connect(url, "shared-token")

        val state = m.connectionState.value as GatewayConnection.Connected
        assertEquals("ecb0a0611ff5", state.displayName)
        assertEquals("2026.10.3", state.version)
        assertEquals(AuthRequirement.Token, state.authRequirement)
        assertEquals(listOf("config", "models", "skills"), state.capabilities)
        assertEquals("shared-token", state.tokenOrNull)
        val params = session.paramsOf("connect")
        assertEquals("shared-token", params.getValue("auth").jsonObject.getValue("token").jsonPrimitive.content)
        assertEquals(fixedIdentity.deviceId, params.getValue("device").jsonObject.getValue("id").jsonPrimitive.content)
    }

    @Test fun `the issued device token is stored with the granted scopes`() = runTest {
        manager(ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))).connect(url, "shared-token")
        val expected = StoredDeviceToken("dt-test-0001", OPERATOR_SCOPES, "operator", fixedIdentity.deviceId)
        assertEquals(expected, tokens.tokens[url])
    }

    @Test fun `pairing required surfaces the request id and lands in Error`() = runTest {
        val session = ScriptedWsSession(
            closeAfterReplyTo = setOf("connect"),
            respond = fixtures("connect" to "err-pairing-required"),
        )
        val m = manager(session)
        val e = assertThrows<GatewayPairingRequiredException> { m.connect(url, "shared-token") }
        assertEquals("af0b5d81-7bd2-4b86-80b0-4a489b541974", e.requestId)
        assertSame(e, (m.connectionState.value as GatewayConnection.Error).cause)
        assertTrue(session.closedByClient)
    }

    @Test fun `a wrong token is a typed connect-auth error`() = runTest {
        val session = ScriptedWsSession(
            closeAfterReplyTo = setOf("connect"),
            respond = fixtures("connect" to "err-token-mismatch"),
        )
        val e = assertThrows<GatewayConnectAuthException> { manager(session).connect(url, "wrong") }
        assertEquals("AUTH_TOKEN_MISMATCH", e.detailCode)
    }

    @Test fun `an unreachable gateway never opens the socket`() = runTest {
        val m = manager(session = null)
        coEvery { rest.health() } throws GatewayUnreachableException(url)
        assertThrows<GatewayUnreachableException> { m.connect(url, null) }
        coVerify(exactly = 0) { rest.openWsSession() }
        assertInstanceOf(GatewayConnection.Error::class.java, m.connectionState.value)
    }

    @Test fun `without a shared token the stored device token is reused with its scopes`() = runTest {
        tokens.tokens[url] = StoredDeviceToken("dt-old", listOf("operator.read"), "operator", fixedIdentity.deviceId)
        val session = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        manager(session).connect(url, null)
        val params = session.paramsOf("connect")
        assertEquals("dt-old", params.getValue("auth").jsonObject.getValue("deviceToken").jsonPrimitive.content)
        assertEquals(listOf("operator.read"), params.getValue("scopes").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun `a device token stored for another identity is ignored`() = runTest {
        tokens.tokens[url] = StoredDeviceToken("dt-other", listOf("operator.read"), "operator", "another-device")
        val session = ScriptedWsSession(
            closeAfterReplyTo = setOf("connect"),
            respond = fixtures("connect" to "err-token-missing"),
        )
        val e = assertThrows<GatewayConnectAuthException> { manager(session).connect(url, null) }
        assertEquals("AUTH_TOKEN_MISSING", e.detailCode)
        assertNull(session.paramsOf("connect")["auth"])
    }

    @Test fun `a rejected device token is forgotten`() = runTest {
        tokens.tokens[url] = StoredDeviceToken("dt-revoked", OPERATOR_SCOPES, "operator", fixedIdentity.deviceId)
        val rejection = buildJsonObject {
            put("type", "res")
            put("ok", false)
            putJsonObject("error") {
                put("code", "INVALID_REQUEST")
                put("message", "unauthorized: device token mismatch")
                putJsonObject("details") { put("code", "AUTH_DEVICE_TOKEN_MISMATCH") }
            }
        }
        val m = manager(ScriptedWsSession(respond = { _, _ -> rejection }))
        assertThrows<GatewayConnectAuthException> { m.connect(url, null) }
        assertNull(tokens.tokens[url])
    }

    @Test fun `a cancelled handshake ends Disconnected and closes the socket`() = runTest {
        val session = ScriptedWsSession(respond = { _, _ -> null })
        val m = manager(session)
        val job = launch { m.connect(url, "shared-token") }
        runCurrent()
        assertInstanceOf(GatewayConnection.Connecting::class.java, m.connectionState.value)
        job.cancel()
        job.join()
        assertEquals(GatewayConnection.Disconnected, m.connectionState.value)
        assertTrue(session.closedByClient)
    }

    // ── probeAuth ─────────────────────────────────────────────────────────────

    @Test fun `probeAuth maps a missing token to Token and sends no credential`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("connect" to "err-token-missing"))
        assertEquals(AuthRequirement.Token, manager(session).probeAuth(url))
        assertNull(session.paramsOf("connect")["auth"])
    }

    @Test fun `probeAuth maps a pending approval to None`() = runTest {
        val m = manager(ScriptedWsSession(respond = fixtures("connect" to "err-pairing-required")))
        assertEquals(AuthRequirement.None, m.probeAuth(url))
    }

    @Test fun `probeAuth reads the auth mode from hello-ok`() = runTest {
        val m = manager(ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired")))
        assertEquals(AuthRequirement.Token, m.probeAuth(url))
    }

    // ── session ───────────────────────────────────────────────────────────────

    @Test fun `check sends health over the live socket`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired", "health" to "res-health"))
        val m = manager(session)
        m.connect(url, "shared-token")
        m.check()
        assertEquals(listOf("connect", "health"), session.sent.map { it.getValue("method").jsonPrimitive.content })
    }

    @Test fun `granted scopes come from hello-ok and disconnect drops them`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        val m = manager(session)
        m.connect(url, "shared-token")
        assertEquals(OPERATOR_SCOPES.toSet(), m.grantedScopes())
        m.disconnect()
        assertNull(m.grantedScopes())
        assertTrue(session.closedByClient)
        assertEquals(GatewayConnection.Disconnected, m.connectionState.value)
        assertThrows<GatewayNotConnectedException> { m.check() }
    }

    // ── Kept from main, adapted to the WebSocket flow ────────────────────────

    @Test fun `two concurrent connect calls result in exactly one Connected state`() = runTest {
        val url2 = "http://192.168.1.51:18789"
        val first = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        val second = ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired"))
        val m = manager(first)
        every { factory.unauthenticated(url2, any()) } returns restClient(second, "ws://192.168.1.51:18789/ws")

        val job1 = async { runCatching { m.connect(url, "shared-token") } }
        val job2 = async { runCatching { m.connect(url2, "shared-token") } }
        awaitAll(job1, job2)

        // The mutex serialises them: whichever ran first had its socket closed by the second.
        val finalUrl = (m.connectionState.value as GatewayConnection.Connected).url
        assertTrue(if (finalUrl == url) second.closedByClient else first.closedByClient)
    }

    @Test fun `connect wraps an unclassified exception and lands in Error, never stuck Connecting`() = runTest {
        val m = manager(session = null)
        coEvery { rest.openWsSession() } throws IllegalStateException("engine rejected URL")

        val exception = runCatching { m.connect(url, null) }.exceptionOrNull()

        assertInstanceOf(GatewayProtocolException::class.java, exception)
        assertInstanceOf(IllegalStateException::class.java, exception!!.cause)
        assertEquals(url, (m.connectionState.value as GatewayConnection.Error).url)
        verify { rest.close() }
    }

    @Test fun `Connected toString never contains the token`() = runTest {
        val secret = "super-secret-bearer-value"
        val m = manager(ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired")))

        m.connect(url, secret)

        val rendered = m.connectionState.value.toString()
        assertTrue(rendered.startsWith("Connected("), rendered)
        assertFalse(rendered.contains(secret), rendered)
        assertTrue(rendered.contains("tokenOrNull=[REDACTED]"), rendered)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `connect runs on the injected IO dispatcher`() = runTest {
        val io = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val m = manager(ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired")), ioDispatcher = io)
        val before = io.dispatches

        m.connect(url, "shared-token")

        assertTrue(io.dispatches > before)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `turning the cleartext opt-in off applies to the live session`() = runTest {
        val setting = MutableStateFlow(true)
        val settings = object : SettingsRepository {
            override val allowCleartextPublicIPs: Flow<Boolean> = setting
            override suspend fun setAllowCleartextPublicIPs(enabled: Boolean) {
                setting.value = enabled
            }
        }
        val m = manager(ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired")), settings)
        val policy = slot<() -> Boolean>()
        every { factory.unauthenticated(any(), capture(policy)) } returns rest

        m.connect(url, "shared-token")
        assertTrue(policy.captured())

        setting.value = false
        assertFalse(policy.captured())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a settings read error fails closed and does not propagate`() = runTest {
        val source = MutableSharedFlow<Boolean>(replay = 1)
        val settings = object : SettingsRepository {
            override val allowCleartextPublicIPs: Flow<Boolean> =
                source.map { if (it) true else throw java.io.IOException("datastore gone") }
            override suspend fun setAllowCleartextPublicIPs(enabled: Boolean) = Unit
        }
        source.tryEmit(true)
        val m = manager(ScriptedWsSession(respond = fixtures("connect" to "hello-ok-paired")), settings)
        val policy = slot<() -> Boolean>()
        every { factory.unauthenticated(any(), capture(policy)) } returns rest

        m.connect(url, "shared-token")
        assertTrue(policy.captured())

        source.tryEmit(false)
        assertFalse(policy.captured())
    }
}

private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    var dispatches = 0
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatches++
        delegate.dispatch(context, block)
    }
}
```

- [ ] **Step 6: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.GatewayConnectionManagerImplTest"`
Expected: FAIL, compilation errors (`No parameter with name 'identityProvider'`, `Unresolved reference 'grantedScopes'`).

- [ ] **Step 7: Rewrite the manager**

Replace `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/GatewayConnectionManagerImpl.kt` entirely:
```kotlin
package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.data.gateway.ClientInfo
import com.openclaw.ghostcrab.data.gateway.ConnectAuth
import com.openclaw.ghostcrab.data.gateway.DeviceIdentity
import com.openclaw.ghostcrab.data.gateway.DeviceIdentityProvider
import com.openclaw.ghostcrab.data.gateway.DeviceTokenStore
import com.openclaw.ghostcrab.data.gateway.GatewayHandshake
import com.openclaw.ghostcrab.data.gateway.GatewayRpcClient
import com.openclaw.ghostcrab.data.gateway.HelloOk
import com.openclaw.ghostcrab.data.gateway.StoredDeviceToken
import com.openclaw.ghostcrab.data.gateway.gatewayCapabilities
import com.openclaw.ghostcrab.data.gateway.gatewayKeyOf
import com.openclaw.ghostcrab.data.gateway.selectConnectAuth
import com.openclaw.ghostcrab.data.gateway.toAuthRequirement
import com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.GatewayHealthChecker
import com.openclaw.ghostcrab.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val METHOD_HEALTH = "health"
private const val DEVICE_TOKEN_MISMATCH = "AUTH_DEVICE_TOKEN_MISMATCH"
private const val DEFAULT_DISPLAY_NAME = "OpenClaw Gateway"
private val CREDENTIAL_MISSING_CODES = setOf("AUTH_TOKEN_MISSING", "AUTH_PASSWORD_MISSING", "AUTH_REQUIRED")

/**
 * Production [GatewayConnectionManager] (ADR-0007): `GET /health`, then one control-plane WebSocket per session
 * opened with a signed `connect`. Repositories call RPCs on [requireSession]. Thread-safe via [mutex].
 *
 * @param identityProvider This install's Ed25519 identity.
 * @param deviceTokens Per-gateway device tokens from earlier `hello-ok`s.
 * @param clientInfo `connect.params.client`.
 * @param clientFactory Builds the REST client used for `/health` and to open the socket. Injectable for tests.
 * @param settingsRepository Source of the cleartext-to-public-IP opt-in.
 * @param ioDispatcher Blocking work and the socket reader loop; injected via the Koin IoDispatcher qualifier.
 */
@Suppress("TooManyFunctions") // one session lifecycle: connect, probe, health, teardown helpers
class GatewayConnectionManagerImpl(
    private val identityProvider: DeviceIdentityProvider,
    private val deviceTokens: DeviceTokenStore,
    private val clientInfo: ClientInfo,
    private val clientFactory: OpenClawApiClientFactory = DefaultClientFactory,
    private val settingsRepository: SettingsRepository = AlwaysBlockCleartextSettingsRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : GatewayConnectionManager, GatewayHealthChecker {

    /** The live session: REST client (health, teardown), control-plane socket, and the hello that opened it. */
    private class ActiveSession(val client: OpenClawApiClient, val rpc: GatewayRpcClient, val hello: HelloOk)

    private val _connectionState = MutableStateFlow<GatewayConnection>(GatewayConnection.Disconnected)
    override val connectionState: StateFlow<GatewayConnection> = _connectionState.asStateFlow()

    // Written under [mutex] on the IO dispatcher, read lock-free by repositories on any thread.
    @Volatile private var active: ActiveSession? = null
    private val mutex = Mutex()

    /** Live copy of the user's cleartext opt-in; every request's interceptor reads it. */
    private val allowCleartext = AtomicBoolean(false)

    // Fail closed: a settings read error (e.g. DataStore IOException) means "not allowed", never a crash.
    private val cleartextSetting: Flow<Boolean> = settingsRepository.allowCleartextPublicIPs.catch { emit(false) }

    init {
        // App-lifetime singleton: keep the opt-in current so a Settings change applies to the live session.
        CoroutineScope(SupervisorJob() + ioDispatcher).launch { cleartextSetting.collect { allowCleartext.set(it) } }
    }

    /** Reads the current opt-in before building clients, so the first request never races the collector. */
    private suspend fun primeCleartextSetting() {
        allowCleartext.set(cleartextSetting.firstOrNull() ?: false)
    }

    /**
     * ADR-0007 decision 8: `/health`, then a credential-less signed `connect`. `AUTH_TOKEN_MISSING`,
     * `AUTH_PASSWORD_MISSING` or `AUTH_REQUIRED` → [AuthRequirement.Token]; `hello-ok` → its auth mode;
     * `PAIRING_REQUIRED` → [AuthRequirement.None] (only the approval is missing). Stores nothing.
     */
    override suspend fun probeAuth(url: String): AuthRequirement = withContext(ioDispatcher) {
        primeCleartextSetting()
        val client = clientFactory.unauthenticated(url, allowCleartext::get)
        var rpc: GatewayRpcClient? = null
        try {
            client.health()
            val socket = openRpc(client).also { rpc = it }
            GatewayHandshake.perform(socket, clientInfo, ConnectAuth.None, identityProvider.identity())
                .toAuthRequirement(token = null)
        } catch (e: GatewayConnectAuthException) {
            if (e.detailCode in CREDENTIAL_MISSING_CODES) AuthRequirement.Token else throw e
        } catch (ignored: GatewayPairingRequiredException) {
            AuthRequirement.None
        } finally {
            release(client, rpc)
        }
    }

    override suspend fun connect(url: String, token: String?) = connectWith(url, token)

    /** Emits Connecting, then Connected or Error (and rethrows). Cancellation ends in Disconnected. */
    private suspend fun connectWith(url: String, token: String?): Unit =
        withContext(ioDispatcher) {
            mutex.withLock {
                closeActive()
                _connectionState.value = GatewayConnection.Connecting(url)
                val key = gatewayKeyOf(url)
                // Held outside the try so every failure path can release what was opened so far.
                var client: OpenClawApiClient? = null
                var rpc: GatewayRpcClient? = null
                try {
                    primeCleartextSetting()
                    val rest = clientFactory.unauthenticated(url, allowCleartext::get).also { client = it }
                    rest.health() // reachability + cleartext policy, before any credential is sent
                    val identity = identityProvider.identity()
                    val stored = deviceTokens.loadDeviceToken(key)?.takeIf { it.deviceId == identity.deviceId }
                    val socket = openRpc(rest).also { rpc = it }
                    val auth = selectConnectAuth(token, stored)
                    val hello = GatewayHandshake.perform(socket, clientInfo, auth, identity)
                    rememberDeviceToken(key, identity, hello, stored)
                    active = ActiveSession(rest, socket, hello)
                    _connectionState.value = connectedState(url, hello, token)
                } catch (e: GatewayException) {
                    if (e is GatewayConnectAuthException && e.detailCode == DEVICE_TOKEN_MISMATCH) {
                        deviceTokens.clearDeviceToken(key)
                    }
                    fail(url, e, client, rpc)
                    throw e
                } catch (e: CancellationException) {
                    release(client, rpc)
                    _connectionState.value = GatewayConnection.Disconnected
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // Unclassified (identity store, a URL the engine rejects): a client bug, not connectivity.
                    val detail = e::class.java.simpleName + (e.message?.let { ": $it" } ?: "")
                    val wrapped = GatewayProtocolException(url, detail, e)
                    fail(url, wrapped, client, rpc)
                    throw wrapped
                }
            }
        }

    private suspend fun openRpc(client: OpenClawApiClient): GatewayRpcClient =
        GatewayRpcClient.create(client.openWsSession(), client.webSocketUrl(), ioDispatcher)

    private suspend fun rememberDeviceToken(
        key: String,
        identity: DeviceIdentity,
        hello: HelloOk,
        stored: StoredDeviceToken?,
    ) {
        val issued = hello.deviceToken ?: return
        // Upstream rule: the same token keeps its stored scopes; a new token takes this socket's live scopes.
        val scopes = stored?.takeIf { it.token == issued }?.scopes ?: hello.scopes.toList()
        deviceTokens.saveDeviceToken(key, StoredDeviceToken(issued, scopes, hello.role, identity.deviceId))
    }

    private suspend fun release(client: OpenClawApiClient?, rpc: GatewayRpcClient?) {
        withContext(NonCancellable) { rpc?.close() }
        client?.close()
    }

    private suspend fun closeActive() {
        active?.let { release(it.client, it.rpc) }
        active = null
    }

    private suspend fun fail(url: String, cause: GatewayException, client: OpenClawApiClient?, rpc: GatewayRpcClient?) {
        release(client, rpc)
        closeActive()
        _connectionState.value = GatewayConnection.Error(url, cause)
    }

    override suspend fun disconnect() {
        mutex.withLock {
            closeActive()
            _connectionState.value = GatewayConnection.Disconnected
        }
    }

    /** One `health` RPC on the live socket (see [GatewayHealthChecker]); fails as soon as the socket dropped. */
    override suspend fun check() {
        requireSession().request(METHOD_HEALTH)
    }

    /**
     * @return The live session's socket, for repository implementations.
     * @throws GatewayNotConnectedException if no session is active.
     */
    fun requireSession(): GatewayRpcClient = active?.rpc ?: throw GatewayNotConnectedException()

    /** @return `hello-ok.auth.scopes` of the live session, or `null` while disconnected. */
    fun grantedScopes(): Set<String>? = active?.hello?.scopes

    /**
     * @return The live session's REST client (only `/health` is real; removed in Task 24).
     * @throws GatewayNotConnectedException if no session is active.
     */
    fun requireClient(): OpenClawApiClient = active?.client ?: throw GatewayNotConnectedException()
}

private fun connectedState(url: String, hello: HelloOk, token: String?) = GatewayConnection.Connected(
    url = url,
    displayName = hello.gatewayHost ?: DEFAULT_DISPLAY_NAME,
    version = hello.serverVersion,
    authRequirement = hello.toAuthRequirement(token),
    isHttps = url.startsWith("https://", ignoreCase = true),
    capabilities = gatewayCapabilities(hello.methods),
    hardwareInfo = null,
    tokenOrNull = token,
)

// ── Fail-safe default settings (no cleartext to public IPs) ──────────────────

private object AlwaysBlockCleartextSettingsRepository : SettingsRepository {
    override val allowCleartextPublicIPs = kotlinx.coroutines.flow.flowOf(false)
    override suspend fun setAllowCleartextPublicIPs(enabled: Boolean) = Unit
}

// ── Factory interface for testability ────────────────────────────────────────

interface OpenClawApiClientFactory {
    fun unauthenticated(baseUrl: String, allowCleartextPublicIPs: () -> Boolean = { false }): OpenClawApiClient
    fun authenticated(
        baseUrl: String,
        token: String,
        allowCleartextPublicIPs: () -> Boolean = { false },
    ): OpenClawApiClient
}

object DefaultClientFactory : OpenClawApiClientFactory {
    override fun unauthenticated(baseUrl: String, allowCleartextPublicIPs: () -> Boolean) =
        OpenClawApiClient.unauthenticated(baseUrl, allowCleartextPublicIPs)
    override fun authenticated(baseUrl: String, token: String, allowCleartextPublicIPs: () -> Boolean) =
        OpenClawApiClient.authenticated(baseUrl, token, allowCleartextPublicIPs)
}
```

Then update the KDoc of the frozen interfaces to the new transport (signatures unchanged, as ADR-0007 promises). In `domain/repository/GatewayConnectionManager.kt` replace
```kotlin
     * Probes `GET /health` (unauthenticated) then `GET /status` (unauthenticated).
     * - `/health` fails → [GatewayUnreachableException]
     * - `/status` returns 401 or 403 → [AuthRequirement.Token]
     * - `/status` returns 2xx → [AuthRequirement.None]
```
with
```kotlin
     * Probes `GET /health`, then a credential-less signed WebSocket `connect` (ADR-0007); the socket is closed
     * before returning.
     * - `/health` fails → [GatewayUnreachableException]
     * - `AUTH_TOKEN_MISSING` / `AUTH_PASSWORD_MISSING` / `AUTH_REQUIRED` → [AuthRequirement.Token]
     * - `hello-ok` → its auth mode; `PAIRING_REQUIRED` → [AuthRequirement.None] (only the device approval is missing)
```
replace
```kotlin
     * 2. Calls [probeAuth] to determine auth mode.
     * 3. Instantiates an authenticated Ktor client.
     * 4. Fetches `/status` to read version and capabilities.
     * 5. Emits [GatewayConnection.Connected] on success.
     * 6. On any failure: emits [GatewayConnection.Error] **and** rethrows the exception.
```
with
```kotlin
     * 2. Checks `GET /health`.
     * 3. Opens the control-plane WebSocket and sends a signed `connect` (ADR-0007).
     * 4. Reads version, auth mode and capabilities from `hello-ok`.
     * 5. Emits [GatewayConnection.Connected] on success.
     * 6. On any failure: emits [GatewayConnection.Error] **and** rethrows the exception
     *    ([com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException] while the device awaits approval).
```
replace
```kotlin
     * @param token Bearer token for authentication. Pass `null` for unauthenticated gateways.
     * @throws GatewayUnreachableException if the host is not reachable.
     * @throws GatewayAuthException if authentication fails.
```
with
```kotlin
     * @param token Shared gateway token, sent in the `connect` frame (never over HTTP). Pass `null` to use this
     *   gateway's stored device token, or no credential.
     * @throws GatewayUnreachableException if the host is not reachable.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException if the gateway rejects the
     *   credential or the device proof.
```
and `Cancels the held Ktor client, emits [GatewayConnection.Disconnected].` with `Closes the session's WebSocket, emits [GatewayConnection.Disconnected].`; then delete the now-unused `import com.openclaw.ghostcrab.domain.exception.GatewayAuthException`. In `domain/repository/GatewayHealthChecker.kt` replace
```kotlin
 * Liveness probe against the gateway of the **current** session. Uses the live session client, so
 * it honours the same cleartext policy, timeouts and origin-scoped auth as every other call.
```
with
```kotlin
 * Liveness probe against the gateway of the **current** session. Uses the live session's control-plane socket
 * (ADR-0007), so it fails as soon as that socket is gone.
```
and `     * Performs one `GET /health` on the active session.` with `     * Performs one `health` RPC on the active session's socket.`. Add both files to this task's `git add`.

- [ ] **Step 8: Wire it in Koin**

In `app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt`, add imports:
```kotlin
import com.openclaw.ghostcrab.BuildConfig
import com.openclaw.ghostcrab.data.gateway.ClientInfo
import com.openclaw.ghostcrab.data.gateway.DeviceIdentityProvider
import com.openclaw.ghostcrab.data.gateway.DeviceTokenStore
import com.openclaw.ghostcrab.data.storage.DeviceIdentityStore
import java.util.Locale
```
Replace
```kotlin
    single { GatewayConnectionManagerImpl(settingsRepository = get(), ioDispatcher = get(IoDispatcher)) }
```
with
```kotlin
    single<DeviceTokenStore> { get<ConnectionProfileStore>() }
    single<DeviceIdentityProvider> { DeviceIdentityStore(androidContext(), ioDispatcher = get(IoDispatcher)) }
    single { androidClientInfo() }
    single {
        GatewayConnectionManagerImpl(
            identityProvider = get(),
            deviceTokens = get(),
            clientInfo = get(),
            settingsRepository = get(),
            ioDispatcher = get(IoDispatcher),
        )
    }
```
Append at the end of the file:
```kotlin

/** `connect.params.client` for this build (ADR-0007 decision 4: closed upstream id, honest version and name). */
private fun androidClientInfo() = ClientInfo(
    id = "openclaw-android",
    version = BuildConfig.VERSION_NAME,
    platform = "android",
    displayName = "GhostCrab (Android)",
    locale = Locale.getDefault().toLanguageTag(),
)
```

- [ ] **Step 9: Extend the Koin graph test**

In `app/src/test/kotlin/com/openclaw/ghostcrab/di/KoinModulesTest.kt` add the imports
```kotlin
import com.openclaw.ghostcrab.data.gateway.DeviceIdentityProvider
import com.openclaw.ghostcrab.data.gateway.DeviceTokenStore
```
and, after `assertNotNull(koin.get<GatewayHealthChecker>())`:
```kotlin
            assertNotNull(koin.get<DeviceTokenStore>())
            assertNotNull(koin.get<DeviceIdentityProvider>())
```

- [ ] **Step 10: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.GatewayConnectionManagerImplTest" --tests "com.openclaw.ghostcrab.di.KoinModulesTest"`
Expected: PASS (20 + 2 tests).

- [ ] **Step 11: Full gate**

Run: `./gradlew testDebugUnitTest detekt lintDebug`
Expected: BUILD SUCCESSFUL. (`DashboardViewModelTest` and the picker/manual-entry tests mock `GatewayConnectionManager`, so they are unaffected.)

- [ ] **Step 12: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/GatewayConnectionManagerImpl.kt app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt app/src/main/kotlin/com/openclaw/ghostcrab/domain/repository/GatewayConnectionManager.kt app/src/main/kotlin/com/openclaw/ghostcrab/domain/repository/GatewayHealthChecker.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientErrorMappingTest.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/GatewayConnectionManagerImplTest.kt app/src/test/kotlin/com/openclaw/ghostcrab/di/KoinModulesTest.kt
git commit -m "feat(connection): connect over the protocol-4 WS handshake" -m "Replaces the /status auth probe (ADR-002, superseded by ADR-0007) with /health plus a signed connect. Auth mode, version, host and capabilities come from hello-ok; device tokens are stored per gateway and reused; health polling uses the health RPC on the session socket. No credential goes over HTTP any more."
```

---

### Task 11: Pin the client identity the gateway allow-lists

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayHandshake.kt` (`ClientInfo` companion)
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/ClientIdentityTest.kt`

Verified 2026-10-15 against 2026.10.3: `client.id` and `client.mode` are allow-lists checked before any credential (`ghostcrab-android` and `mode: "operator"` are rejected with `INVALID_REQUEST invalid connect params …` and close 1008), so `openclaw-android` / `ui` is forced by the server, not chosen. On a paired device a different `platform` re-triggers pairing (`metadata-upgrade`), while a different `client.id` does not. The identity is therefore one constant; only `version`, `displayName` and `locale` vary. A setup-code connect is not built: bootstrap tokens authenticate only `role: "node"` (ADR-0007).

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/ClientIdentityTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data.gateway

import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException
import com.openclaw.ghostcrab.testutil.RFC8032_SEED
import com.openclaw.ghostcrab.testutil.ScriptedWsSession
import com.openclaw.ghostcrab.testutil.ScriptedWsSession.Companion.fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ClientIdentityTest {

    private val identity = DeviceIdentity.fromSeed(RFC8032_SEED)

    private fun rpc(session: ScriptedWsSession) = GatewayRpcClient.create(session, "ws://gw/ws", Dispatchers.Unconfined)

    @Test fun `the android identity is the allow-listed constant`() {
        val info = ClientInfo.android(version = "9.9.9", locale = "de-DE")
        assertEquals("openclaw-android", info.id)
        assertEquals("ui", info.mode)
        assertEquals("android", info.platform)
        assertNull(info.deviceFamily)
        assertEquals("GhostCrab (Android)", info.displayName)
        assertEquals("9.9.9", info.version)
        assertEquals("de-DE", info.locale)
    }

    @Test fun `an id outside the allow-list is an RPC rejection shown verbatim`() = runTest {
        val session = ScriptedWsSession(
            closeAfterReplyTo = setOf("connect"),
            respond = fixtures("connect" to "err-client-id-invalid"),
        )
        val client = ClientInfo.android(version = "0.3.0").copy(id = "ghostcrab-android")
        val e = assertThrows<GatewayRpcException> {
            GatewayHandshake.perform(rpc(session), client, ConnectAuth.SharedToken("shared-token"), identity)
        }
        assertEquals("INVALID_REQUEST", e.code)
        assertTrue(e.rpcMessage.contains("/client/id"))
    }

    @Test fun `a platform change on a paired device asks for re-approval`() = runTest {
        val session = ScriptedWsSession(
            closeAfterReplyTo = setOf("connect"),
            respond = fixtures("connect" to "err-pairing-metadata-upgrade"),
        )
        val client = ClientInfo.android(version = "0.3.0")
        val e = assertThrows<GatewayPairingRequiredException> {
            GatewayHandshake.perform(rpc(session), client, ConnectAuth.SharedToken("shared-token"), identity)
        }
        assertEquals("metadata-upgrade", e.reason)
        assertEquals("6837c6b0-f559-445b-bc0c-a50fbe5b5998", e.requestId)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.ClientIdentityTest"`
Expected: FAIL, `Unresolved reference 'android'`.

- [ ] **Step 3: Add the pinned identity**

In `GatewayHandshake.kt` add the import `import java.util.Locale` and replace the body of `ClientInfo`
```kotlin
) {
    /** `connect.params.userAgent`. */
    val userAgent: String get() = "ghostcrab-$platform/$version"
}
```
with
```kotlin
) {
    /** `connect.params.userAgent`. */
    val userAgent: String get() = "ghostcrab-$platform/$version"

    companion object {
        /** Upstream allow-listed id for the Android app; any other id is rejected before auth. */
        const val ANDROID_CLIENT_ID = "openclaw-android"

        /** Pinned at approval (case-insensitive): a different value re-triggers pairing. Never derive it. */
        const val ANDROID_PLATFORM = "android"

        /**
         * GhostCrab's Android identity (ADR-0007 decision 4, verified 2026-10-15).
         *
         * @param version GhostCrab's version name (honest; not checked by the gateway).
         * @param locale BCP-47 tag for `connect.params.locale`.
         * @return The constant id / mode / platform with this build's version.
         */
        fun android(version: String, locale: String = Locale.getDefault().toLanguageTag()) = ClientInfo(
            id = ANDROID_CLIENT_ID,
            version = version,
            platform = ANDROID_PLATFORM,
            displayName = "GhostCrab (Android)",
            locale = locale,
        )
    }
}
```

In `DataModule.kt` replace
```kotlin
    single { androidClientInfo() }
```
with
```kotlin
    single { ClientInfo.android(BuildConfig.VERSION_NAME) }
```
and delete the `androidClientInfo()` function Task 10 appended (with its KDoc) and the then-unused `import java.util.Locale`.

- [ ] **Step 4: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.gateway.ClientIdentityTest" --tests "com.openclaw.ghostcrab.di.KoinModulesTest"`
Expected: PASS (3 new tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/gateway/GatewayHandshake.kt app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/gateway/ClientIdentityTest.kt
git commit -m "feat(gateway): pin the allow-listed client identity" -m "client.id and client.mode are server allow-lists checked before auth, and a changed platform re-triggers pairing (metadata-upgrade), so the Android identity is one constant: openclaw-android, ui, android (ADR-0007, verified against 2026.10.3)."
```

---

### Task 12: Scope probe from `hello-ok`

**Files:**
- Rewrite: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ScopeProbeImpl.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/repository/ScopeProbe.kt` (KDoc only)
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt`
- Rewrite: `app/src/test/kotlin/com/openclaw/ghostcrab/data/impl/ScopeProbeImplTest.kt`

- [ ] **Step 1: Write the failing test**

Replace `app/src/test/kotlin/com/openclaw/ghostcrab/data/impl/ScopeProbeImplTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.domain.repository.ScopeProbeResult
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScopeProbeImplTest {

    @Test fun `scopes of the live session are Known`() = runTest {
        val result = ScopeProbeImpl { setOf("operator.read", "operator.admin") }.probe()
        assertEquals(ScopeProbeResult.Known(setOf("operator.read", "operator.admin")), result)
        assertTrue((result as ScopeProbeResult.Known).has("operator.admin"))
    }

    @Test fun `an empty scope set is Known and empty, not unknown`() = runTest {
        assertEquals(ScopeProbeResult.Known(emptySet()), ScopeProbeImpl { emptySet() }.probe())
    }

    @Test fun `no session is Failed with the exception class`() = runTest {
        val result = ScopeProbeImpl { null }.probe()
        assertInstanceOf(ScopeProbeResult.Failed::class.java, result)
        assertTrue((result as ScopeProbeResult.Failed).cause.contains("GatewayNotConnectedException"))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.impl.ScopeProbeImplTest"`
Expected: FAIL (constructor mismatch: `ScopeProbeImpl` still takes `wsFactory`).

- [ ] **Step 3: Implement**

Replace `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ScopeProbeImpl.kt`:
```kotlin
package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.domain.repository.ScopeProbe
import com.openclaw.ghostcrab.domain.repository.ScopeProbeResult

/**
 * [ScopeProbe] over `hello-ok.auth.scopes` of the live session (ADR-0007). The gateway has no `auth.whoami`;
 * the scopes the connect handshake granted are the socket's live authority. No network call.
 *
 * @param scopes Returns the live session's granted scopes, or `null` while disconnected.
 */
class ScopeProbeImpl(
    private val scopes: () -> Set<String>?,
) : ScopeProbe {

    override suspend fun probe(): ScopeProbeResult =
        scopes()?.let { ScopeProbeResult.Known(it) }
            ?: ScopeProbeResult.Failed(cause = "No active gateway session (GatewayNotConnectedException)")
}
```

In `domain/repository/ScopeProbe.kt` replace the interface KDoc (lines 3–9) with:
```kotlin
/**
 * Reports which operator scopes the current gateway session holds.
 *
 * ADR-0007: the scopes come from `hello-ok.auth.scopes` of the connect handshake (upstream has no
 * `auth.whoami`). [ScopeProbeResult.UnknownOldGateway] is no longer produced; it stays for source compatibility.
 */
```

In `DataModule.kt` replace
```kotlin
        single<ScopeProbe> { ScopeProbeImpl(wsFactory = liveWsFactory()) }
```
with
```kotlin
        single<ScopeProbe> { ScopeProbeImpl(scopes = get<GatewayConnectionManagerImpl>()::grantedScopes) }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.impl.ScopeProbeImplTest" --tests "com.openclaw.ghostcrab.ui.AIRecommendationViewModelTest" --tests "com.openclaw.ghostcrab.di.KoinModulesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ScopeProbeImpl.kt app/src/main/kotlin/com/openclaw/ghostcrab/domain/repository/ScopeProbe.kt app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/impl/ScopeProbeImplTest.kt
git commit -m "feat(scopes): read granted scopes from hello-ok" -m "auth.whoami does not exist upstream; hello-ok.auth.scopes is the socket's live authority (ADR-0007)."
```

---

### Task 13: Skills list and install over `skills.status` / `skills.install`

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/InstalledSkill.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/SkillInstallError.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/SkillUnavailableContent.kt`, `app/src/main/res/values/strings.xml`
- Rewrite: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt`
- Rewrite: `app/src/test/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImplTest.kt`

The real gateway answers `skills.install` with one `res` frame and sends no progress events; `skills.uninstall` does not exist (QA §3). Verified 2026-10-15: a successful install answers `{ok, message, slug, version, targetDir, …}` with the bare key in `slug` (`@steipete/weather` → `weather`) and is listed by `skills.status` with `source: "openclaw-workspace"` plus a `clawhub {status, slug, installedVersion, installedAt, …}` object (fixtures `res-skills-install`, `res-skills-status`).

- [ ] **Step 1: Write the failing test**

Replace `app/src/test/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImplTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data.impl

import app.cash.turbine.test
import com.openclaw.ghostcrab.data.gateway.GatewayRpcClient
import com.openclaw.ghostcrab.data.gateway.stringOrNull
import com.openclaw.ghostcrab.domain.exception.GatewayMethodUnsupportedException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.model.SkillInstallError
import com.openclaw.ghostcrab.domain.model.SkillInstallProgress
import com.openclaw.ghostcrab.domain.model.SkillSource
import com.openclaw.ghostcrab.testutil.GatewayFixtures
import com.openclaw.ghostcrab.testutil.ScriptedWsSession
import com.openclaw.ghostcrab.testutil.ScriptedWsSession.Companion.fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstalledSkillRepositoryImplTest {

    private fun skillsRepo(session: ScriptedWsSession): InstalledSkillRepositoryImpl {
        val rpc = GatewayRpcClient.create(session, "ws://gw/ws", Dispatchers.Unconfined)
        return InstalledSkillRepositoryImpl { rpc }
    }

    @Test fun `refresh maps skills status`() = runTest {
        val repo = skillsRepo(ScriptedWsSession(respond = fixtures("skills.status" to "res-skills-status")))
        val skills = repo.refreshFromGateway().getOrThrow()
        assertEquals(listOf("1password", "browser-automation", "weather"), skills.map { it.slug })
        assertTrue(skills[0].disabled)
        assertEquals(SkillSource.Local, skills[0].source)
        assertFalse(skills[1].disabled)
        assertEquals("", skills[1].installedVersion)
        assertEquals(SkillSource.ClawHub, skills[2].source)
        assertEquals("1.0.0", skills[2].installedVersion)
        assertEquals(1_792_035_309L, skills[2].installedAt)
        assertEquals(skills, repo.observeInstalled().first())
    }

    @Test fun `a status without a skills array is a protocol error`() = runTest {
        val bad = buildJsonObject {
            put("type", "res")
            put("ok", true)
            putJsonObject("payload") {}
        }
        val result = skillsRepo(ScriptedWsSession(respond = { _, _ -> bad })).refreshFromGateway()
        assertInstanceOf(GatewayProtocolException::class.java, result.exceptionOrNull())
    }

    @Test fun `install sends the clawhub params and ends Succeeded with the refreshed entry`() = runTest {
        val session = ScriptedWsSession(
            respond = { method, _ ->
                when (method) {
                    "skills.install" -> GatewayFixtures.frame("res-skills-install")
                    "skills.status" -> GatewayFixtures.frame("res-skills-status")
                    else -> null
                }
            },
        )
        skillsRepo(session).install("@steipete/weather", "1.0.0").test {
            assertEquals(SkillInstallProgress.Connecting("@steipete/weather"), awaitItem())
            assertEquals(SkillInstallProgress.Applying("skills.install"), awaitItem())
            val installed = (awaitItem() as SkillInstallProgress.Succeeded).installed
            assertEquals("weather", installed.slug)
            assertEquals("1.0.0", installed.installedVersion)
            assertEquals(SkillSource.ClawHub, installed.source)
            awaitComplete()
        }
        val params = session.paramsOf("skills.install")
        assertEquals("clawhub", params.stringOrNull("source"))
        assertEquals("@steipete/weather", params.stringOrNull("slug"))
        assertEquals("1.0.0", params.stringOrNull("version"))
        assertEquals("false", params["force"].toString())
        assertEquals("120000", params["timeoutMs"].toString())
    }

    @Test fun `an unknown slug ends NotFound`() = runTest {
        val repo = skillsRepo(ScriptedWsSession(respond = fixtures("skills.install" to "err-skill-not-found")))
        repo.install("qa-nonexistent-skill-zz9", null).test {
            awaitItem()
            awaitItem()
            val expected = SkillInstallError.NotFound("qa-nonexistent-skill-zz9")
            assertEquals(SkillInstallProgress.Failed(expected), awaitItem())
            awaitComplete()
        }
    }

    @Test fun `a missing scope ends Unauthorized`() = runTest {
        val repo = skillsRepo(ScriptedWsSession(respond = fixtures("skills.install" to "err-missing-scope")))
        repo.install("a/b", null).test {
            awaitItem()
            awaitItem()
            assertEquals(SkillInstallProgress.Failed(SkillInstallError.Unauthorized("operator.read")), awaitItem())
            awaitComplete()
        }
    }

    @Test fun `install while disconnected ends NotConnected without a request`() = runTest {
        InstalledSkillRepositoryImpl { throw GatewayNotConnectedException() }.install("a/b", null).test {
            assertEquals(SkillInstallProgress.Connecting("a/b"), awaitItem())
            assertEquals(SkillInstallProgress.Failed(SkillInstallError.NotConnected), awaitItem())
            awaitComplete()
        }
    }

    @Test fun `uninstall is unsupported and sends nothing`() = runTest {
        val session = ScriptedWsSession(respond = { _, _ -> null })
        val result = skillsRepo(session).uninstall("a/b")
        assertEquals("skills.uninstall", (result.exceptionOrNull() as GatewayMethodUnsupportedException).method)
        assertTrue(session.sent.isEmpty())
    }

    @Test fun `skills status entries map to SkillSource`() {
        assertEquals(SkillSource.ClawHub, skillSourceOf("openclaw-workspace", bundled = false, clawhub = true))
        assertEquals(SkillSource.Local, skillSourceOf("openclaw-workspace", bundled = false, clawhub = false))
        assertEquals(SkillSource.Local, skillSourceOf("openclaw-bundled", bundled = true, clawhub = false))
        assertEquals(SkillSource.Local, skillSourceOf("openclaw-extra", bundled = false, clawhub = false))
        assertEquals(SkillSource.Unknown, skillSourceOf("workspace", bundled = false, clawhub = false))
        assertEquals(SkillSource.Unknown, skillSourceOf(null, bundled = false, clawhub = false))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.impl.InstalledSkillRepositoryImplTest"`
Expected: FAIL (constructor takes `wsFactory`; `disabled` and `skillSourceOf` unresolved).

- [ ] **Step 3: Extend the domain models**

`app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/InstalledSkill.kt` becomes:
```kotlin
package com.openclaw.ghostcrab.domain.model

import kotlinx.serialization.Serializable

enum class SkillSource { ClawHub, Local, Unknown }

/**
 * A skill the gateway reports in `skills.status` (ADR-0007).
 *
 * @param slug `skillKey` (falls back to `name`).
 * @param installedVersion `clawhub.installedVersion` for ClawHub installs, else `version` when reported; empty
 *   otherwise (bundled skills have none).
 * @param source Where it came from.
 * @param installedAt Epoch seconds from `clawhub.installedAt` for ClawHub installs; 0 when unknown.
 * @param disabled `true` when `skills.entries.<key>.enabled` is false on the gateway.
 */
@Serializable
data class InstalledSkill(
    val slug: String,
    val installedVersion: String,
    val source: SkillSource,
    val installedAt: Long,
    val disabled: Boolean = false,
)
```

In `app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/SkillInstallError.kt` replace
```kotlin
    data class Protocol(val rpcCode: Int, val message: String) : SkillInstallError(false)
```
with
```kotlin
    /** The gateway rejected the install with string [code] (e.g. `UNAVAILABLE`) and [message] (ADR-0007). */
    data class Protocol(val code: String, val message: String) : SkillInstallError(false)
```

In `app/src/main/res/values/strings.xml` replace
```xml
    <string name="ai_skill_install_error_protocol">Install failed (code %1$d): %2$s</string>
```
with
```xml
    <string name="ai_skill_install_error_protocol">Install failed (%1$s): %2$s</string>
```

In `app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/SkillUnavailableContent.kt` replace
```kotlin
            error.rpcCode,
```
with
```kotlin
            error.code,
```

- [ ] **Step 4: Rewrite the repository**

Replace `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImpl.kt`:
```kotlin
package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.gateway.GatewayRpcClient
import com.openclaw.ghostcrab.data.gateway.stringOrNull
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayMethodUnsupportedException
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException
import com.openclaw.ghostcrab.domain.model.InstalledSkill
import com.openclaw.ghostcrab.domain.model.SkillInstallError
import com.openclaw.ghostcrab.domain.model.SkillInstallProgress
import com.openclaw.ghostcrab.domain.model.SkillSource
import com.openclaw.ghostcrab.domain.repository.InstalledSkillRepository
import com.openclaw.ghostcrab.domain.util.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private const val METHOD_STATUS = "skills.status"
private const val METHOD_INSTALL = "skills.install"
private const val METHOD_UNINSTALL = "skills.uninstall"
private const val NOT_CONNECTED_URL = "<no active connection>"
private const val MILLIS_PER_SECOND = 1_000L

/** Sent as `skills.install.timeoutMs`: the gateway downloads and verifies before it answers. */
private const val INSTALL_TIMEOUT_MS = 120_000L
private const val INSTALL_TIMEOUT_MARGIN_MS = 10_000L

/**
 * [InstalledSkillRepository] over the live session socket (ADR-0007): `skills.status` lists, `skills.install`
 * installs (one response, no progress events). OpenClaw has no removal method, so [uninstall] fails with
 * [GatewayMethodUnsupportedException].
 *
 * @param session Returns the live session's socket; throws [GatewayNotConnectedException] while disconnected.
 */
class InstalledSkillRepositoryImpl(
    private val session: () -> GatewayRpcClient,
) : InstalledSkillRepository {

    private val installedFlow = MutableStateFlow<List<InstalledSkill>>(emptyList())

    /** Hot list; starts empty, updated by every `skills.status` read. */
    override fun observeInstalled(): Flow<List<InstalledSkill>> = installedFlow.asStateFlow()

    /** One-shot `skills.status`. */
    override suspend fun refreshFromGateway(): Result<List<InstalledSkill>> =
        suspendRunCatching { fetchStatus(session()) }

    /**
     * Emits [SkillInstallProgress.Connecting], [SkillInstallProgress.Applying] (`skills.install`), then
     * [SkillInstallProgress.Succeeded] or [SkillInstallProgress.Failed], and completes.
     */
    override fun install(slug: String, version: String?): Flow<SkillInstallProgress> = flow {
        emit(SkillInstallProgress.Connecting(slug))
        val rpc = try {
            session()
        } catch (ignored: GatewayNotConnectedException) {
            emit(SkillInstallProgress.Failed(SkillInstallError.NotConnected))
            return@flow
        }
        emit(SkillInstallProgress.Applying(step = METHOD_INSTALL))
        val outcome = try {
            val timeoutMs = INSTALL_TIMEOUT_MS + INSTALL_TIMEOUT_MARGIN_MS
            val result = rpc.request(METHOD_INSTALL, installParams(slug, version), timeoutMs) as? JsonObject
            SkillInstallProgress.Succeeded(installedSkillAfter(rpc, slug, result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GatewayException) {
            SkillInstallProgress.Failed(e.toInstallError(slug))
        }
        emit(outcome)
    }

    /** OpenClaw has no skill-removal RPC (QA §3); see `setSkillEnabled` (Task 14). */
    override suspend fun uninstall(slug: String): Result<Unit> =
        Result.failure(GatewayMethodUnsupportedException(endpointOrPlaceholder(), METHOD_UNINSTALL))

    private suspend fun fetchStatus(rpc: GatewayRpcClient): List<InstalledSkill> {
        val skills = (rpc.request(METHOD_STATUS) as? JsonObject)?.get("skills") as? JsonArray
            ?: throw GatewayProtocolException(rpc.endpoint, "skills.status without a skills array")
        return skills.mapNotNull { (it as? JsonObject)?.toInstalledSkill() }.also { installedFlow.value = it }
    }

    /**
     * The install already succeeded; this only finds its entry. `skills.install` answers with the bare key and the
     * installed version (`{ok, message, slug, version, targetDir, …}`). A failed or incomplete refresh falls back
     * to a synthetic entry rather than reporting a successful install as failed.
     */
    private suspend fun installedSkillAfter(
        rpc: GatewayRpcClient,
        requested: String,
        result: JsonObject?,
    ): InstalledSkill {
        val key = result?.stringOrNull("slug") ?: requested.substringAfterLast('/')
        val refreshed = suspendRunCatching { fetchStatus(rpc) }.getOrNull().orEmpty()
        return refreshed.firstOrNull { it.slug == key }
            ?: InstalledSkill(key, result?.stringOrNull("version").orEmpty(), SkillSource.ClawHub, installedAt = 0L)
    }

    private fun endpointOrPlaceholder(): String = try {
        session().endpoint
    } catch (ignored: GatewayNotConnectedException) {
        NOT_CONNECTED_URL
    }
}

private fun installParams(slug: String, version: String?) = buildJsonObject {
    put("source", "clawhub")
    put("slug", slug)
    version?.let { put("version", it) }
    put("force", false)
    put("timeoutMs", INSTALL_TIMEOUT_MS)
}

private fun JsonObject.toInstalledSkill(): InstalledSkill? {
    val key = stringOrNull("skillKey") ?: stringOrNull("name") ?: return null
    val clawhub = this["clawhub"] as? JsonObject
    val installedAtMs = (clawhub?.get("installedAt") as? JsonPrimitive)?.longOrNull ?: 0L
    return InstalledSkill(
        slug = key,
        installedVersion = clawhub?.stringOrNull("installedVersion") ?: stringOrNull("version").orEmpty(),
        source = skillSourceOf(stringOrNull("source"), flag("bundled"), clawhub = clawhub != null),
        installedAt = installedAtMs / MILLIS_PER_SECOND,
        disabled = flag("disabled"),
    )
}

private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

/**
 * `skills.status` entry → [SkillSource]. Verified against 2026.10.3: a ClawHub install reports
 * `source: "openclaw-workspace"` plus a `clawhub {…}` object; bundled and plugin skills (`openclaw-bundled`,
 * `openclaw-extra`) and hand-made workspace skills have no `clawhub` object and are local.
 *
 * @param source The reported source.
 * @param bundled The reported `bundled` flag.
 * @param clawhub Whether the entry carries a `clawhub` object.
 * @return The mapped source.
 */
internal fun skillSourceOf(source: String?, bundled: Boolean, clawhub: Boolean): SkillSource = when {
    clawhub -> SkillSource.ClawHub
    source == null -> SkillSource.Unknown
    bundled || source.startsWith("openclaw-") -> SkillSource.Local
    else -> SkillSource.Unknown
}

private fun GatewayException.toInstallError(slug: String): SkillInstallError = when (this) {
    is GatewayMissingScopeException -> SkillInstallError.Unauthorized(missingScope)
    is GatewayRpcException ->
        if (code == "UNAVAILABLE" && rpcMessage.contains("not found on ClawHub")) {
            SkillInstallError.NotFound(slug)
        } else {
            SkillInstallError.Protocol(code, rpcMessage)
        }
    is GatewayMethodUnsupportedException -> SkillInstallError.Protocol("INVALID_REQUEST", message.orEmpty())
    is GatewayProtocolException -> SkillInstallError.MalformedResponse(detail)
    is GatewayNotConnectedException -> SkillInstallError.NotConnected
    else -> SkillInstallError.Network(this)
}
```

- [ ] **Step 5: Wire it to the session socket**

In `DataModule.kt` replace
```kotlin
        single<InstalledSkillRepository> { InstalledSkillRepositoryImpl(wsFactory = liveWsFactory()) }
```
with
```kotlin
        single<InstalledSkillRepository> { InstalledSkillRepositoryImpl(session = liveSession()) }
```
and replace the whole `liveWsFactory()` function (and its KDoc) at the end of the file with:
```kotlin
/**
 * The live session's control-plane socket, shared by every WS-backed repository (ADR-0007). Throws
 * `GatewayNotConnectedException` while disconnected.
 */
private fun Scope.liveSession(): () -> GatewayRpcClient {
    val manager = get<GatewayConnectionManagerImpl>()
    return { manager.requireSession() }
}
```
Replace the import `import com.openclaw.ghostcrab.data.ws.GatewayWsClient` with `import com.openclaw.ghostcrab.data.gateway.GatewayRpcClient`.

- [ ] **Step 6: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.impl.InstalledSkillRepositoryImplTest" --tests "com.openclaw.ghostcrab.ui.AIRecommendationViewModelTest" --tests "com.openclaw.ghostcrab.ui.InstalledSkillsViewModelTest" --tests "com.openclaw.ghostcrab.di.KoinModulesTest"`
Expected: PASS (8 new tests).

- [ ] **Step 7: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/InstalledSkill.kt app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/SkillInstallError.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/SkillUnavailableContent.kt app/src/main/res/values/strings.xml app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImpl.kt app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImplTest.kt
git commit -m "feat(skills): list and install over skills.status / skills.install" -m "Real method names and DTO (skillKey, source, bundled, disabled, clawhub); one install response naming the bare key and version, no progress stream; string error codes; skills.uninstall reported as unsupported (ADR-0007)."
```

---

### Task 14: Disable instead of uninstall (`skills.update`)

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/repository/InstalledSkillRepository.kt` (additive member)
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/installedskills/InstalledSkillsViewModel.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/ui/installedskills/InstalledSkillsScreen.kt`, `app/src/main/res/values/strings.xml`
- Modify: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/InstalledSkillsViewModelTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/ui/AIRecommendationViewModelTest.kt` (fakes)
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImplTest.kt`

Verified 2026-10-15: `skills.update {skillKey, enabled}` answers `{ok:true, skillKey, config:{enabled}}`, also for a key the gateway does not know, so the key must be in the last `skills.status` before GhostCrab writes it. `skills.status` stays stale until the gateway emits `config.changed` (~80 ms after the write, sometimes before the response); there is no `skills.changed` event. The repository subscribes to `config.changed` before sending, waits for it (2 s cap), then re-reads the list.

- [ ] **Step 1: Write the failing repository test**

Add to `InstalledSkillRepositoryImplTest`:
```kotlin
    @Test fun `disable waits for config changed, then re-reads skills status`() = runTest {
        lateinit var session: ScriptedWsSession
        session = ScriptedWsSession(
            respond = { method, _ ->
                when (method) {
                    "skills.status" -> GatewayFixtures.frame("res-skills-status")
                    // Like the real gateway: config.changed can arrive before the skills.update response.
                    "skills.update" -> GatewayFixtures.frame("res-skills-update").also {
                        session.push(GatewayFixtures.frame("evt-config-changed"))
                    }
                    else -> null
                }
            },
        )
        val repo = skillsRepo(session)
        assertTrue(repo.setSkillEnabled("browser-automation", enabled = false).isSuccess)
        val params = session.paramsOf("skills.update")
        assertEquals("browser-automation", params.stringOrNull("skillKey"))
        assertEquals("false", params["enabled"].toString())
        val methods = session.sent.map { it.stringOrNull("method") }
        assertEquals(listOf("skills.status", "skills.update", "skills.status"), methods)
        assertEquals(3, repo.observeInstalled().first().size)
    }

    @Test fun `a key skills status does not list is refused without a write`() = runTest {
        val session = ScriptedWsSession(respond = fixtures("skills.status" to "res-skills-status"))
        val result = skillsRepo(session).setSkillEnabled("qa-does-not-exist", enabled = false)
        assertEquals(404, (result.exceptionOrNull() as GatewayApiException).statusCode)
        assertTrue(session.sent.none { it.stringOrNull("method") == "skills.update" })
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.impl.InstalledSkillRepositoryImplTest"`
Expected: FAIL, `Unresolved reference 'setSkillEnabled'`.

- [ ] **Step 3: Add the additive member and implement it**

In `domain/repository/InstalledSkillRepository.kt`, replace the interface KDoc (lines 7–12, starting `Frozen v1.1 contract — additive only. Install + list + uninstall via gateway WebSocket.`) with
```kotlin
/**
 * Frozen v1.1 contract — additive only. Lists, installs and enables/disables gateway skills over the session
 * socket (ADR-0007: `skills.status`, `skills.install`, `skills.update`).
 *
 * All methods assume an active [com.openclaw.ghostcrab.domain.model.GatewayConnection.Connected] state.
 */
```
and add at the end of the interface:
```kotlin

    /**
     * Enables or disables a skill (`skills.update {skillKey, enabled}`). OpenClaw has no removal method, so
     * this is what GhostCrab offers instead of [uninstall].
     *
     * Additive (v1.1): ADR-0005, ADR-0007.
     *
     * @param skillKey [com.openclaw.ghostcrab.domain.model.InstalledSkill.slug] as reported by `skills.status`
     *   (an `owner/slug` is reduced to `slug`).
     * @param enabled `false` to disable.
     * @return Success once the gateway accepted the change and the list was re-read; failure otherwise
     *   (`GatewayApiException` 404 for a key `skills.status` does not list, `GatewayMissingScopeException` without
     *   `operator.admin`).
     */
    suspend fun setSkillEnabled(skillKey: String, enabled: Boolean): Result<Unit>
```

In `InstalledSkillRepositoryImpl.kt` add next to the other constants:
```kotlin
private const val METHOD_UPDATE = "skills.update"
private const val EVENT_CONFIG_CHANGED = "config.changed"
private const val STATUS_NOT_FOUND = 404

/** `config.changed` follows a write within ~80 ms; without it the list is re-read anyway after this long. */
private const val CONFIG_CHANGED_WAIT_MS = 2_000L
```
add the imports
```kotlin
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
```
(`kotlinx.coroutines.flow.first` is needed too if the file lacks it) and, after `uninstall(...)`:
```kotlin
    /**
     * `skills.update {skillKey, enabled}`, then a `skills.status` re-read once the gateway emits `config.changed`
     * (the list is stale until then, and the event can precede the response). The gateway answers `ok:true` even
     * for unknown keys, so the key must be listed by `skills.status` first.
     */
    override suspend fun setSkillEnabled(skillKey: String, enabled: Boolean): Result<Unit> = suspendRunCatching {
        val rpc = session()
        val key = skillKey.substringAfterLast('/')
        val listed = installedFlow.value.takeIf { list -> list.any { it.slug == key } } ?: fetchStatus(rpc)
        if (listed.none { it.slug == key }) {
            throw GatewayApiException(rpc.endpoint, STATUS_NOT_FOUND, "skill $key is not listed by skills.status")
        }
        coroutineScope {
            // Subscribed before the request goes out, so an early config.changed is not missed.
            val changed = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(CONFIG_CHANGED_WAIT_MS) { rpc.events.first { it.event == EVENT_CONFIG_CHANGED } }
            }
            val params = buildJsonObject {
                put("skillKey", key)
                put("enabled", enabled)
            }
            rpc.request(METHOD_UPDATE, params)
            changed.await()
        }
        fetchStatus(rpc)
        Unit
    }
```

- [ ] **Step 4: Update the test fakes**

In `app/src/test/kotlin/com/openclaw/ghostcrab/ui/AIRecommendationViewModelTest.kt`, add to `private class FakeInstallRepo`:
```kotlin
    override suspend fun setSkillEnabled(skillKey: String, enabled: Boolean): Result<Unit> = Result.success(Unit)
```

In `app/src/test/kotlin/com/openclaw/ghostcrab/ui/InstalledSkillsViewModelTest.kt`, add to `private class FakeInstalledRepo` (after `val uninstallCalls = …`):
```kotlin
    val enabledCalls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun setSkillEnabled(skillKey: String, enabled: Boolean): Result<Unit> {
        enabledCalls += skillKey to enabled
        return uninstallResult
    }
```
and in the test `confirmUninstall success sets uninstallSuccessSlug and clears dialog` replace
```kotlin
        assertEquals(listOf("a/b"), repo.uninstallCalls)
```
with
```kotlin
        assertEquals(listOf("a/b" to false), repo.enabledCalls)
        assertTrue(repo.uninstallCalls.isEmpty())
```
(add `import org.junit.jupiter.api.Assertions.assertTrue` if the file lacks it).

- [ ] **Step 5: Run the tests to see the ViewModel test fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.InstalledSkillsViewModelTest" --tests "com.openclaw.ghostcrab.data.impl.InstalledSkillRepositoryImplTest"`
Expected: FAIL in `InstalledSkillsViewModelTest` (`enabledCalls` is empty: the ViewModel still calls `uninstall`); the two repository tests pass (add `import com.openclaw.ghostcrab.domain.exception.GatewayApiException` to the repository test if missing).

- [ ] **Step 6: Switch the ViewModel and the screen to "Disable"**

In `InstalledSkillsViewModel.kt`, inside `confirmUninstall()`, replace
```kotlin
            repo.uninstall(slug)
```
with
```kotlin
            // OpenClaw has no skill removal (ADR-0007): the confirm action disables the skill.
            repo.setSkillEnabled(slug, enabled = false)
```

In `InstalledSkillsScreen.kt`:
- replace the import `import androidx.compose.material.icons.filled.Delete` with `import androidx.compose.material.icons.filled.Block`, and `imageVector = Icons.Default.Delete,` with `imageVector = Icons.Default.Block,`;
- in `SkillRow`, replace `SkillRowTrailing(isUninstalling = isUninstalling, onUninstall = onUninstall)` with
```kotlin
        if (!skill.disabled) SkillRowTrailing(isUninstalling = isUninstalling, onUninstall = onUninstall)
```
- in `SkillDetails`, replace the whole `Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) { … }` (version · source) with
```kotlin
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (skill.installedVersion.isNotBlank()) {
                Text(
                    text = stringResource(R.string.installed_skills_version, skill.installedVersion),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = MonoFontFamily,
                        color = BrandTokens.colorCyanPulse,
                    ),
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorTextSecondary),
                )
            }
            Text(
                text = skill.source.label(),
                style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorTextSecondary),
            )
            if (skill.disabled) {
                Text(
                    text = "·",
                    style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorTextSecondary),
                )
                Text(
                    text = stringResource(R.string.installed_skills_disabled),
                    style = MaterialTheme.typography.bodySmall.copy(color = BrandTokens.colorAmberWarn),
                )
            }
        }
```

In `strings.xml` replace the values (keys unchanged) and add one key:
```xml
    <string name="installed_skills_empty_body">The gateway reports no skills. Install one on the gateway host: openclaw skills install &lt;slug&gt;</string>
    <string name="installed_skills_uninstall_cd">Disable skill</string>
    <string name="installed_skills_uninstall_title">Disable %1$s?</string>
    <string name="installed_skills_uninstall_body">OpenClaw gateways cannot remove skills remotely. GhostCrab disables the skill instead (skills.update, enabled = false). It stays on the gateway host and can be enabled there again.</string>
    <string name="installed_skills_uninstall_confirm">Disable</string>
    <string name="installed_skills_uninstall_success">%1$s disabled</string>
    <string name="installed_skills_disabled">disabled</string>
```

- [ ] **Step 7: Run the tests and the gate**

Run: `./gradlew testDebugUnitTest detekt lintDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/domain/repository/InstalledSkillRepository.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImpl.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/installedskills app/src/main/res/values/strings.xml app/src/test/kotlin/com/openclaw/ghostcrab/ui/InstalledSkillsViewModelTest.kt app/src/test/kotlin/com/openclaw/ghostcrab/ui/AIRecommendationViewModelTest.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImplTest.kt
git commit -m "feat(skills): disable via skills.update instead of uninstall" -m "OpenClaw has no skill removal; additive InstalledSkillRepository.setSkillEnabled (ADR-0005), the Installed Skills screen offers Disable and shows disabled skills (ADR-0007)."
```

---
### Task 15: Config over `config.get` / `config.patch`

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ConfigWrites.kt`
- Rewrite: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ConfigRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/repository/ConfigRepository.kt` (KDoc only)
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt`
- Rewrite: `app/src/test/kotlin/com/openclaw/ghostcrab/data/ConfigRepositoryImplTest.kt`

`config.get` returns the authored config as `parsed` (secrets redacted as `__OPENCLAW_REDACTED__`) and an opaque `hash`; `config.patch {raw, baseHash}` merges a JSON merge patch (`raw` is JSON5 text; JSON is valid JSON5) and is admin-only, 30 writes/min (`/app/docs/gateway/configuration/config-rpc.md`). Verified 2026-10-15: a stale or unknown `baseHash` fails `INVALID_REQUEST "config changed since last load; re-run config.get and retry"` and a missing one `"config base hash required for <path>; …"` (neither has `details`); schema errors fail `"invalid config: <path>: …"` with `details.issues[{path, message, allowedValues?}]`; success answers `{ok, path, hash, config, changedPaths, sentinel}`, and that `hash` is the next `baseHash`. The frozen `ConfigRepository` contract keeps its 412/422 semantics so `ConfigEditorViewModel` (reload on 412, inline error on validation) is unchanged.

- [ ] **Step 1: Write the failing test**

Replace `app/src/test/kotlin/com/openclaw/ghostcrab/data/ConfigRepositoryImplTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.gateway.GatewayRpcClient
import com.openclaw.ghostcrab.data.gateway.stringOrNull
import com.openclaw.ghostcrab.data.impl.ConfigRepositoryImpl
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.testutil.GatewayFixtures
import com.openclaw.ghostcrab.testutil.ScriptedWsSession
import com.openclaw.ghostcrab.testutil.ScriptedWsSession.Companion.fixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConfigRepositoryImplTest {

    private val fixtureHash = "hmac-sha256:v1:u_qU7JLKF7G9u0jc_GfUp-dKovJYwiLpy0gNxuKjka0"

    private val okPatch: JsonObject = GatewayFixtures.frame("res-config-patch")
    private val patchedHash = "hmac-sha256:v1:9zRz_TNpxroYBBCZhMFq3HGpv1dFAcFxPnmwn7VTXYE"

    private fun configSession(patchReply: JsonObject?) = ScriptedWsSession(
        respond = { method, _ ->
            when (method) {
                "config.get" -> GatewayFixtures.frame("res-config-get")
                "config.patch" -> patchReply
                else -> null
            }
        },
    )

    private fun rpc(session: ScriptedWsSession) = GatewayRpcClient.create(session, "ws://gw/ws", Dispatchers.Unconfined)

    @Test fun `getConfig maps parsed sections and the hash`() = runTest {
        val socket = rpc(configSession(okPatch))
        val config = ConfigRepositoryImpl { socket }.getConfig()
        assertEquals(setOf("gateway", "agents"), config.sections.keys)
        assertEquals("18789", config.sections.getValue("gateway").jsonObject["port"].toString())
        assertEquals(fixtureHash, config.etag)
    }

    @Test fun `updateConfig patches one section against the cached hash`() = runTest {
        val session = configSession(okPatch)
        val socket = rpc(session)
        val repo = ConfigRepositoryImpl { socket }
        repo.getConfig()
        repo.updateConfig("gateway", buildJsonObject { put("port", 18790) })
        val params = session.paramsOf("config.patch")
        assertEquals("""{"gateway":{"port":18790}}""", params.stringOrNull("raw"))
        assertEquals(fixtureHash, params.stringOrNull("baseHash"))
    }

    @Test fun `updateConfig without a prior read fetches the hash first`() = runTest {
        val session = configSession(okPatch)
        val socket = rpc(session)
        ConfigRepositoryImpl { socket }.updateConfig("gateway", buildJsonObject { put("port", 18790) })
        assertEquals(listOf("config.get", "config.patch"), session.sent.map { it.stringOrNull("method") })
        assertEquals(fixtureHash, session.paramsOf("config.patch").stringOrNull("baseHash"))
    }

    @Test fun `the hash a patch returns is the next baseHash`() = runTest {
        val session = configSession(okPatch)
        val socket = rpc(session)
        val repo = ConfigRepositoryImpl { socket }
        repo.updateConfig("gateway", buildJsonObject { put("port", 18790) })
        repo.updateConfig("gateway", buildJsonObject { put("port", 18791) })
        val patches = session.sent.filter { it.stringOrNull("method") == "config.patch" }
        assertEquals(listOf("config.get", "config.patch", "config.patch"), session.sent.map { it.stringOrNull("method") })
        assertEquals(patchedHash, (patches[1]["params"] as JsonObject).stringOrNull("baseHash"))
    }

    @Test fun `a stale hash is HTTP-412 style so the editor reloads`() = runTest {
        val socket = rpc(configSession(GatewayFixtures.frame("err-config-stale-hash")))
        val repo = ConfigRepositoryImpl { socket }
        val e = assertThrows<GatewayApiException> { repo.updateConfig("gateway", JsonObject(emptyMap())) }
        assertEquals(412, e.statusCode)
    }

    @Test fun `a missing hash is HTTP-412 style too`() = runTest {
        val socket = rpc(configSession(GatewayFixtures.frame("err-config-base-hash-required")))
        val repo = ConfigRepositoryImpl { socket }
        val e = assertThrows<GatewayApiException> { repo.updateConfig("gateway", JsonObject(emptyMap())) }
        assertEquals(412, e.statusCode)
    }

    @Test fun `a validation rejection is HTTP-422 style with the server text`() = runTest {
        val socket = rpc(configSession(GatewayFixtures.frame("err-config-invalid")))
        val repo = ConfigRepositoryImpl { socket }
        val patch = buildJsonObject { put("port", 99999) }
        val e = assertThrows<GatewayApiException> { repo.updateConfig("gateway", patch) }
        assertEquals(422, e.statusCode)
        assertTrue(e.body.orEmpty().startsWith("invalid config: gateway.port"))
    }

    @Test fun `a missing admin scope passes through typed`() = runTest {
        val socket = rpc(ScriptedWsSession(respond = fixtures("config.get" to "err-missing-scope")))
        assertThrows<GatewayMissingScopeException> { ConfigRepositoryImpl { socket }.getConfig() }
    }

    @Test fun `a hash from an earlier session is never reused`() = runTest {
        val first = configSession(okPatch)
        val second = configSession(okPatch)
        var current = rpc(first)
        val repo = ConfigRepositoryImpl { current }
        repo.getConfig()
        current = rpc(second)
        repo.updateConfig("gateway", JsonObject(emptyMap()))
        assertEquals(listOf("config.get", "config.patch"), second.sent.map { it.stringOrNull("method") })
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.ConfigRepositoryImplTest"`
Expected: FAIL (`ConfigRepositoryImpl` still takes a `GatewayConnectionManagerImpl`).

- [ ] **Step 3: Add the shared write helpers**

`app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ConfigWrites.kt`:
```kotlin
package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `config.patch` answers once the gateway applied the write (hot reload), which can take a while. */
internal const val CONFIG_PATCH_TIMEOUT_MS: Long = 60_000L

private const val STATUS_PRECONDITION_FAILED = 412
private const val STATUS_UNPROCESSABLE = 422

/**
 * @param patch JSON merge patch (objects merge, `null` deletes), e.g. `{"gateway":{"port":18790}}`.
 * @param baseHash `config.get.hash` the patch was made against; required once a config file exists.
 * @return `config.patch` params; `raw` is the patch as JSON text (JSON is valid JSON5).
 */
internal fun configPatchParams(patch: JsonObject, baseHash: String?): JsonObject = buildJsonObject {
    put("raw", patch.toString())
    baseHash?.let { put("baseHash", it) }
}

/** Messages of the two `baseHash` rejections (verified against 2026.10.3; neither carries `details`). */
private val STALE_HASH_PREFIXES = listOf("config changed since last load", "config base hash required")

/**
 * Maps a rejected `config.patch` onto the frozen `ConfigRepository` contract: a stale, unknown or missing
 * `baseHash` becomes a 412-style [GatewayApiException] (callers reload), any other `INVALID_REQUEST` (schema
 * validation with `details.issues[{path, message, allowedValues?}]`, a JSON5 syntax error, array removal without
 * `replacePaths`) a 422-style one carrying the server text, which names the first failing path; anything else
 * passes through.
 *
 * @return The exception to throw.
 */
internal fun GatewayRpcException.toConfigWriteException(): GatewayException = when {
    isStaleBaseHash() -> GatewayApiException(url, STATUS_PRECONDITION_FAILED, rpcMessage)
    code == "INVALID_REQUEST" -> GatewayApiException(url, STATUS_UNPROCESSABLE, rpcMessage)
    else -> this
}

private fun GatewayRpcException.isStaleBaseHash(): Boolean =
    code == "INVALID_REQUEST" && STALE_HASH_PREFIXES.any { rpcMessage.startsWith(it) }
```

- [ ] **Step 4: Rewrite the repository**

Replace `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ConfigRepositoryImpl.kt`:
```kotlin
package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.gateway.GatewayRpcClient
import com.openclaw.ghostcrab.data.gateway.stringOrNull
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException
import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import com.openclaw.ghostcrab.domain.repository.ConfigRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val METHOD_GET = "config.get"
private const val METHOD_PATCH = "config.patch"

/**
 * [ConfigRepository] over the live session socket (ADR-0007). [getConfig] reads `config.get` (`parsed` sections,
 * `hash` as the ETag); [updateConfig] sends `config.patch {raw: {"<section>": value}, baseHash}`.
 *
 * The cached hash is bound to the socket that produced it, so a reconnect never sends a stale one; without a
 * cached hash a write reads `config.get` first. Reads and writes run one at a time under [mutex], so the cache
 * always comes from the request that completed last.
 *
 * @param session Returns the live session's socket; throws `GatewayNotConnectedException` while disconnected.
 */
class ConfigRepositoryImpl(
    private val session: () -> GatewayRpcClient,
) : ConfigRepository {

    private class CachedHash(val rpc: GatewayRpcClient, val hash: String?)

    private val mutex = Mutex()

    /** Guarded by [mutex]. */
    private var lastHash: CachedHash? = null

    /**
     * @return The authored config (`parsed`, secrets redacted by the gateway) and its `hash`.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException without `operator.admin`.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException if no session is active.
     */
    override suspend fun getConfig(): OpenClawConfig = mutex.withLock { fetch(session()) }

    /**
     * @param section Top-level key, e.g. `gateway`.
     * @param value Merge-patch value for that section.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayApiException 412 on a stale hash, 422 on a rejected value.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException without `operator.admin`.
     */
    override suspend fun updateConfig(section: String, value: JsonElement): Unit = mutex.withLock {
        val rpc = session()
        val baseHash = lastHash?.takeIf { it.rpc === rpc }?.hash ?: fetch(rpc).etag
        val params = configPatchParams(JsonObject(mapOf(section to value)), baseHash)
        val result = try {
            rpc.request(METHOD_PATCH, params, CONFIG_PATCH_TIMEOUT_MS)
        } catch (e: GatewayRpcException) {
            throw e.toConfigWriteException()
        }
        // 2026.10.3 answers {ok, path, hash, config, changedPaths, sentinel}; without a hash the next write re-reads.
        lastHash = CachedHash(rpc, (result as? JsonObject)?.stringOrNull("hash"))
    }

    private suspend fun fetch(rpc: GatewayRpcClient): OpenClawConfig {
        val payload = rpc.request(METHOD_GET) as? JsonObject
            ?: throw GatewayProtocolException(rpc.endpoint, "config.get returned a non-object payload")
        val sections = payload["parsed"] as? JsonObject ?: payload["config"] as? JsonObject ?: JsonObject(emptyMap())
        val hash = payload.stringOrNull("hash")
        lastHash = CachedHash(rpc, hash)
        return OpenClawConfig(sections = sections, etag = hash)
    }
}
```
In `domain/repository/ConfigRepository.kt` change only the interface KDoc first paragraph to:
```kotlin
/**
 * Reads and writes the OpenClaw Gateway's `openclaw.json` configuration
 * (ADR-0007: `config.get` / `config.patch` with a `baseHash` conflict guard over the session socket).
 *
 * Optimistic UI is **forbidden** — the gateway is the source of truth.
 * After every [updateConfig], callers must call [getConfig] to reconcile.
 *
 * **Contract frozen at v1.0.**
 */
```

In `DataModule.kt` replace
```kotlin
    single<ConfigRepository> { ConfigRepositoryImpl(get<GatewayConnectionManagerImpl>()) }
```
with
```kotlin
    single<ConfigRepository> { ConfigRepositoryImpl(session = liveSession()) }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.ConfigRepositoryImplTest" --tests "com.openclaw.ghostcrab.ui.ConfigEditorViewModelTest" --tests "com.openclaw.ghostcrab.di.KoinModulesTest"`
Expected: PASS (9 new tests; the editor ViewModel tests mock `ConfigRepository` and stay green).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ConfigWrites.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ConfigRepositoryImpl.kt app/src/main/kotlin/com/openclaw/ghostcrab/domain/repository/ConfigRepository.kt app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/ConfigRepositoryImplTest.kt
git commit -m "feat(config): read and write config over config.get / config.patch" -m "parsed sections + hash as ETag; section merge patches with baseHash bound to the session socket and refreshed from each patch answer; stale or missing hash -> 412, rejected value -> 422 with the server text, matched on the verified 2026.10.3 messages (ADR-0007)."
```

---

### Task 16: Typed `gateway` section editor for the real schema

**Files:**
- Rewrite: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/config/GatewaySection.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/config/GatewayAuthSection.kt`
- Delete: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/config/GatewayHttpSection.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/config/GatewayMdnsSection.kt`
- Rewrite: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/sections/GatewaySectionEditor.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Rewrite: `app/src/test/kotlin/com/openclaw/ghostcrab/domain/GatewaySectionTest.kt`

The old model (`http.host/port`, `auth.mode none|bearer`, `mdns.*`) is not OpenClaw's schema. The real section (`res-config-get` fixture) is `{"mode":"local","port":18789,"bind":"lan","auth":{"mode":"token","token":"__OPENCLAW_REDACTED__"}}`. The editor still rewrites exactly one leaf per edit (`withLeaf`), so unknown keys survive.

- [ ] **Step 1: Write the failing test**

Replace `app/src/test/kotlin/com/openclaw/ghostcrab/domain/GatewaySectionTest.kt`:
```kotlin
package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.model.config.AuthMode
import com.openclaw.ghostcrab.domain.model.config.GatewayAuthSection
import com.openclaw.ghostcrab.domain.model.config.GatewaySection
import com.openclaw.ghostcrab.domain.model.config.OPENCLAW_REDACTED
import com.openclaw.ghostcrab.domain.model.config.toGatewaySection
import com.openclaw.ghostcrab.domain.model.config.toGatewaySectionOrNull
import com.openclaw.ghostcrab.domain.model.config.toJsonElement
import com.openclaw.ghostcrab.testutil.GatewayFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GatewaySectionTest {

    private val realGateway = GatewayFixtures.frame("res-config-get").getValue("payload").jsonObject
        .getValue("parsed").jsonObject.getValue("gateway")

    @Test fun `the real 2026_10_3 gateway section decodes`() {
        val section = realGateway.toGatewaySection()
        assertEquals("local", section.mode)
        assertEquals(18789, section.port)
        assertEquals("lan", section.bind)
        assertEquals(AuthMode.Token, section.auth.mode)
        assertEquals(OPENCLAW_REDACTED, section.auth.token)
    }

    @Test fun `every upstream auth mode decodes by its wire name`() {
        listOf(
            "none" to AuthMode.None,
            "token" to AuthMode.Token,
            "password" to AuthMode.Password,
            "trusted-proxy" to AuthMode.TrustedProxy,
        ).forEach { (wire, mode) ->
            assertEquals(mode, Json.parseToJsonElement("""{"auth":{"mode":"$wire"}}""").toGatewaySection().auth.mode)
            assertEquals(wire, mode.wireName)
        }
    }

    @Test fun `an unknown auth mode does not decode so the editor falls back to raw JSON`() {
        assertNull(Json.parseToJsonElement("""{"auth":{"mode":"oauth"}}""").toGatewaySectionOrNull())
    }

    @Test fun `missing fields take defaults and unknown keys are ignored`() {
        val section = Json.parseToJsonElement("""{"tailscale":{"mode":"serve"}}""").toGatewaySection()
        assertEquals(GatewaySection(), section)
        assertNull(section.auth.mode)
    }

    @Test fun `round trip keeps the values`() {
        val original = GatewaySection(
            mode = "local",
            port = 9090,
            bind = "loopback",
            auth = GatewayAuthSection(AuthMode.Password, null),
        )
        assertEquals(original, original.toJsonElement().toGatewaySection())
    }

    @Test fun `toString redacts the token`() {
        assertFalse(GatewayAuthSection(AuthMode.Token, "secret-token").toString().contains("secret-token"))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.GatewaySectionTest"`
Expected: FAIL (`Unresolved reference 'OPENCLAW_REDACTED'`, `AuthMode.Token`).

- [ ] **Step 3: Rewrite the models**

Delete the old files:
```bash
git rm app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/config/GatewayHttpSection.kt app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/config/GatewayMdnsSection.kt
```

`app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/config/GatewayAuthSection.kt`:
```kotlin
package com.openclaw.ghostcrab.domain.model.config

import com.openclaw.ghostcrab.domain.util.REDACTED_PLACEHOLDER
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the gateway sends in place of every secret in `config.get` (`raw`, `parsed`, `config`). */
const val OPENCLAW_REDACTED: String = "__OPENCLAW_REDACTED__"

/**
 * `gateway.auth.mode` values (upstream 2026.10.3).
 *
 * @property wireName The value written to `openclaw.json`.
 */
@Serializable
enum class AuthMode(val wireName: String) {
    @SerialName("none") None("none"),
    @SerialName("token") Token("token"),
    @SerialName("password") Password("password"),
    @SerialName("trusted-proxy") TrustedProxy("trusted-proxy"),
}

/**
 * `gateway.auth`.
 *
 * @param mode The configured mode, or `null` when unset (the gateway then infers it from the secrets present).
 * @param token The shared token; [OPENCLAW_REDACTED] when read from a gateway. Sensitive — never log.
 */
@Serializable
data class GatewayAuthSection(
    val mode: AuthMode? = null,
    val token: String? = null,
) {
    /** Redacts [token] so the section can be logged or dumped without leaking the secret. */
    override fun toString(): String = "GatewayAuthSection(mode=$mode, token=${token?.let { REDACTED_PLACEHOLDER }})"
}
```

`app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/config/GatewaySection.kt`:
```kotlin
package com.openclaw.ghostcrab.domain.model.config

import com.openclaw.ghostcrab.domain.util.DEFAULT_GATEWAY_PORT
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Lenient [Json] instance used for round-tripping config sections. */
private val configJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Typed view of the top-level `gateway` section of `openclaw.json` (upstream 2026.10.3, `config.get` →
 * `parsed.gateway`). Unknown keys are ignored here; the editor preserves them by rewriting single leaves.
 *
 * @param mode `local` or `remote`.
 * @param port Listening port, 1–65535.
 * @param bind `loopback`, `lan`, `tailnet`, `auto`, … (kept as text: the set grows upstream).
 * @param auth Authentication settings.
 */
@Serializable
data class GatewaySection(
    val mode: String? = null,
    val port: Int = DEFAULT_GATEWAY_PORT,
    val bind: String? = null,
    val auth: GatewayAuthSection = GatewayAuthSection(),
)

/**
 * @return The parsed section.
 * @throws IllegalArgumentException (incl. `SerializationException`) when the JSON does not fit the model, e.g. an
 *   unknown `auth.mode`. UI code should use [toGatewaySectionOrNull].
 */
fun JsonElement.toGatewaySection(): GatewaySection = configJson.decodeFromJsonElement(this)

/**
 * @return The parsed section, or `null` when the JSON cannot be represented by the typed model. Callers then fall
 *   back to raw JSON editing — never to defaults, which would misrepresent the gateway's real settings.
 */
fun JsonElement.toGatewaySectionOrNull(): GatewaySection? =
    try {
        configJson.decodeFromJsonElement<GatewaySection>(this)
    } catch (_: IllegalArgumentException) {
        null
    }

/** @return The section as JSON (default values omitted). */
fun GatewaySection.toJsonElement(): JsonElement = configJson.encodeToJsonElement(this)
```

- [ ] **Step 4: Run the domain test**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.GatewaySectionTest"`
Expected: compilation fails in `GatewaySectionEditor.kt` (it still references `GatewayHttpSection`). Continue with Step 5.

- [ ] **Step 5: Rewrite the editor**

Add to `app/src/main/res/values/strings.xml`:
```xml
    <string name="config_gateway_auth_none_warning">The gateway refuses to start with auth mode none unless bind is loopback or a trusted proxy fronts it.</string>
```

Replace `app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/sections/GatewaySectionEditor.kt`:
```kotlin
package com.openclaw.ghostcrab.ui.config.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.domain.model.config.AuthMode
import com.openclaw.ghostcrab.domain.model.config.GatewaySection
import com.openclaw.ghostcrab.domain.model.config.OPENCLAW_REDACTED
import com.openclaw.ghostcrab.domain.model.config.toGatewaySectionOrNull
import com.openclaw.ghostcrab.domain.util.withLeaf
import com.openclaw.ghostcrab.ui.components.EnumDropdownRow
import com.openclaw.ghostcrab.ui.components.IntFieldRow
import com.openclaw.ghostcrab.ui.components.StringFieldRow
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Writes one leaf (path from the section root) into the section JSON. */
private typealias SetLeaf = (path: List<String>, value: JsonElement) -> Unit

private const val FIELD_PORT = "gateway.port"
private const val FIELD_BIND = "gateway.bind"
private const val FIELD_TOKEN = "gateway.auth.token"
private const val BIND_LOOPBACK = "loopback"

/**
 * Typed form editor for the top-level `gateway` section (upstream 2026.10.3 schema, ADR-0007).
 *
 * - When [sectionValue] decodes, typed fields are shown and each edit rewrites **exactly one leaf** of the original
 *   JSON ([withLeaf]): unknown keys survive, absent objects stay absent.
 * - When it does not decode (e.g. an `auth.mode` this app does not know), the raw JSON editor is shown with a
 *   notice — never a form pre-filled with defaults.
 *
 * The token field never shows the gateway's secret: `config.get` returns it as `__OPENCLAW_REDACTED__`. Clearing the
 * field writes that sentinel back, which the gateway resolves to the stored secret (config RPC secret restoration).
 *
 * @param sectionValue Current raw JSON for the `gateway` section (pending edit or server value).
 * @param fieldErrors Field path → error message (e.g. `"gateway.port"` → `"…"`).
 * @param onEdit Called with the full updated section JSON.
 * @param onFieldError Called when a field validates or fails (field path, message or null).
 */
@Composable
fun GatewaySectionEditor(
    sectionValue: JsonElement,
    fieldErrors: Map<String, String>,
    onEdit: (JsonElement) -> Unit,
    onFieldError: (field: String, error: String?) -> Unit,
) {
    val section = remember(sectionValue) { sectionValue.toGatewaySectionOrNull() }
    val setLeaf: SetLeaf = { path, value -> onEdit(sectionValue.withLeaf(path, value)) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (section == null) {
            Text(
                text = stringResource(R.string.config_gateway_untyped_notice),
                style = MaterialTheme.typography.bodySmall,
                color = BrandTokens.colorAmberWarn,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            RawJsonSectionEditor(sectionKey = "gateway", sectionValue = sectionValue, onEdit = onEdit)
        } else {
            GatewayListenerFields(section, fieldErrors, setLeaf, onFieldError)
            Spacer(modifier = Modifier.height(Spacing.md))
            GatewayAuthFields(section, fieldErrors, setLeaf)
        }
    }
}

@Composable
private fun GatewayListenerFields(
    section: GatewaySection,
    fieldErrors: Map<String, String>,
    setLeaf: SetLeaf,
    onFieldError: (field: String, error: String?) -> Unit,
) {
    Text(text = "Listener", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(Spacing.xs))
    IntFieldRow(
        label = "Port",
        value = section.port,
        onValueChange = { port -> setLeaf(listOf("port"), JsonPrimitive(port)) },
        onError = { err -> onFieldError(FIELD_PORT, err) },
        min = 1,
        max = 65535,
    )
    Spacer(modifier = Modifier.height(Spacing.sm))
    StringFieldRow(
        label = "Bind (loopback, lan, tailnet, …)",
        value = section.bind.orEmpty(),
        onValueChange = { bind -> setLeaf(listOf("bind"), JsonPrimitive(bind)) },
        mono = true,
        error = fieldErrors[FIELD_BIND],
    )
}

@Composable
private fun GatewayAuthFields(
    section: GatewaySection,
    fieldErrors: Map<String, String>,
    setLeaf: SetLeaf,
) {
    val auth = section.auth
    Text(text = "Auth", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(Spacing.xs))
    EnumDropdownRow(
        label = if (auth.mode == null) "Mode (unset: inferred by the gateway)" else "Mode",
        selected = auth.mode ?: AuthMode.Token,
        options = AuthMode.entries,
        onSelected = { mode -> setLeaf(listOf("auth", "mode"), JsonPrimitive(mode.wireName)) },
        labelFor = { it.wireName },
    )
    if (auth.mode == AuthMode.None && section.bind != null && section.bind != BIND_LOOPBACK) {
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.config_gateway_auth_none_warning),
            style = MaterialTheme.typography.bodySmall,
            color = BrandTokens.colorAmberWarn,
        )
    }
    if (auth.mode == AuthMode.Token || auth.mode == null) {
        Spacer(modifier = Modifier.height(Spacing.sm))
        StringFieldRow(
            label = if (auth.token == OPENCLAW_REDACTED) "Token (set on the gateway; type to replace)" else "Token",
            value = auth.token?.takeIf { it != OPENCLAW_REDACTED }.orEmpty(),
            onValueChange = { token ->
                setLeaf(listOf("auth", "token"), JsonPrimitive(token.ifEmpty { OPENCLAW_REDACTED }))
            },
            mono = true,
            secret = true,
            error = fieldErrors[FIELD_TOKEN],
        )
    }
}
```

- [ ] **Step 6: Run the tests and the gate**

Run: `./gradlew testDebugUnitTest detekt lintDebug`
Expected: BUILD SUCCESSFUL (`GatewaySectionTest` 6 tests pass).

- [ ] **Step 7: Commit**

```bash
git add -A app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/config app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/sections/GatewaySectionEditor.kt app/src/main/res/values/strings.xml app/src/test/kotlin/com/openclaw/ghostcrab/domain/GatewaySectionTest.kt
git commit -m "feat(config): typed gateway editor for the real OpenClaw schema" -m "port, bind, mode and auth.mode none|token|password|trusted-proxy; the redacted token is never shown and is restored by the gateway when left empty (ADR-0007)."
```

---

### Task 17: Models over `models.list` + `agents.defaults.model.primary`

**Files:**
- Rewrite: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ModelRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/repository/ModelRepository.kt` (KDoc only)
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt`
- Rewrite: `app/src/test/kotlin/com/openclaw/ghostcrab/data/ModelRepositoryImplTest.kt`

`models.list` returns `{models:[{id, name, provider, available?, unavailableReason?, input?, reasoning?, …}]}` (`ModelsListResult` in the 2026.10.3 schema; the QA gateway had no provider, so the captured list is empty and the populated rows below follow the schema). The default model is `agents.defaults.model.primary` = `provider/model` (`/app/docs/cli/models.md`: `openclaw models set` writes it). There is no `models.set` RPC.

- [ ] **Step 1: Write the failing test**

Replace `app/src/test/kotlin/com/openclaw/ghostcrab/data/ModelRepositoryImplTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.gateway.GatewayRpcClient
import com.openclaw.ghostcrab.data.gateway.stringOrNull
import com.openclaw.ghostcrab.data.impl.ModelRepositoryImpl
import com.openclaw.ghostcrab.data.impl.modelRef
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.testutil.GatewayFixtures
import com.openclaw.ghostcrab.testutil.ScriptedWsSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ModelRepositoryImplTest {

    /** Schema-shaped `models.list` answer (ModelsListResult, protocol 2026.10.3). */
    private val modelsReply: JsonObject = Json.parseToJsonElement(
        """
        {"type":"res","ok":true,"payload":{"models":[
          {"id":"claude-sonnet-4-6","name":"Claude Sonnet 4.6","provider":"anthropic","available":true,
           "input":["text","image"],"reasoning":true},
          {"id":"gpt-6-astra","name":"GPT-6 Astra","provider":"openai","available":false,
           "unavailableReason":"missing-auth","input":["text"]}
        ],"accountSelection":{"kind":"automatic","label":"Automatic account selection"}}}
        """.trimIndent(),
    ).jsonObject

    private fun configWithPrimary(primary: String): JsonObject = Json.parseToJsonElement(
        """{"type":"res","ok":true,"payload":{"parsed":{"agents":{"defaults":{"model":{"primary":"$primary"}}}},"hash":"h-1"}}""",
    ).jsonObject

    private val okPatch: JsonObject = Json.parseToJsonElement("""{"type":"res","ok":true,"payload":{"ok":true}}""").jsonObject

    private fun repo(respond: (String, JsonObject) -> JsonObject?): Pair<ModelRepositoryImpl, ScriptedWsSession> {
        val session = ScriptedWsSession(respond = respond)
        val rpc = GatewayRpcClient.create(session, "ws://gw/ws", Dispatchers.Unconfined)
        return ModelRepositoryImpl { rpc } to session
    }

    @Test fun `models map to provider-qualified refs and the configured primary is active`() = runTest {
        val (models, _) = repo { method, _ ->
            when (method) {
                "models.list" -> modelsReply
                "config.get" -> configWithPrimary("anthropic/claude-sonnet-4-6")
                else -> null
            }
        }
        val list = models.getModels()
        assertEquals(listOf("anthropic/claude-sonnet-4-6", "openai/gpt-6-astra"), list.map { it.id })
        assertEquals("Claude Sonnet 4.6", list[0].displayName)
        assertTrue(list[0].isActive)
        assertEquals("ready", list[0].status)
        assertEquals(listOf("text", "image", "reasoning"), list[0].capabilities)
        assertFalse(list[1].isActive)
        assertEquals("missing-auth", list[1].status)
    }

    @Test fun `the real empty catalogue maps to an empty list`() = runTest {
        val (models, _) = repo { method, _ ->
            when (method) {
                "models.list" -> GatewayFixtures.frame("res-models-list")
                "config.get" -> GatewayFixtures.frame("res-config-get")
                else -> null
            }
        }
        assertTrue(models.getModels().isEmpty())
    }

    @Test fun `without operator admin the list still loads with nothing active`() = runTest {
        val (models, _) = repo { method, _ ->
            when (method) {
                "models.list" -> modelsReply
                "config.get" -> GatewayFixtures.frame("err-missing-scope")
                else -> null
            }
        }
        assertTrue(models.getModels().none { it.isActive })
    }

    @Test fun `setActiveModel patches agents defaults model primary against the current hash`() = runTest {
        val (models, session) = repo { method, _ ->
            when (method) {
                "config.get" -> configWithPrimary("anthropic/claude-sonnet-4-6")
                "config.patch" -> okPatch
                else -> null
            }
        }
        models.setActiveModel("openai/gpt-6-astra")
        val params = session.paramsOf("config.patch")
        assertEquals("""{"agents":{"defaults":{"model":{"primary":"openai/gpt-6-astra"}}}}""", params.stringOrNull("raw"))
        assertEquals("h-1", params.stringOrNull("baseHash"))
    }

    @Test fun `a rejected model is HTTP-422 style`() = runTest {
        val rejection = Json.parseToJsonElement(
            """{"type":"res","ok":false,"error":{"code":"INVALID_REQUEST","message":"invalid config: unknown model x/y"}}""",
        ).jsonObject
        val (models, _) = repo { method, _ ->
            when (method) {
                "config.get" -> configWithPrimary("a/b")
                "config.patch" -> rejection
                else -> null
            }
        }
        assertEquals(422, assertThrows<GatewayApiException> { models.setActiveModel("x/y") }.statusCode)
    }

    @Test fun `disconnected is GatewayNotConnectedException`() = runTest {
        val disconnected = ModelRepositoryImpl { throw GatewayNotConnectedException() }
        assertThrows<GatewayNotConnectedException> { disconnected.getModels() }
    }

    @Test fun `refs are provider-qualified exactly once`() {
        assertEquals("anthropic/claude", modelRef("anthropic", "claude"))
        assertEquals("openrouter/x", modelRef("openrouter", "openrouter/x"))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.ModelRepositoryImplTest"`
Expected: FAIL (`ModelRepositoryImpl` still takes the manager; `modelRef` unresolved).

- [ ] **Step 3: Rewrite the repository**

Replace `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ModelRepositoryImpl.kt`:
```kotlin
package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.gateway.GatewayRpcClient
import com.openclaw.ghostcrab.data.gateway.objectAt
import com.openclaw.ghostcrab.data.gateway.stringListOrEmpty
import com.openclaw.ghostcrab.data.gateway.stringOrNull
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException
import com.openclaw.ghostcrab.domain.model.ModelInfo
import com.openclaw.ghostcrab.domain.repository.ModelRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val METHOD_MODELS_LIST = "models.list"
private const val METHOD_CONFIG_GET = "config.get"
private const val METHOD_CONFIG_PATCH = "config.patch"

/**
 * [ModelRepository] over the live session socket (ADR-0007). The catalogue is `models.list`; "active" is the config
 * key `agents.defaults.model.primary` (`provider/model`), read with `config.get` and written with `config.patch`.
 * [ModelInfo.id] is that `provider/model` ref.
 *
 * @param session Returns the live session's socket; throws `GatewayNotConnectedException` while disconnected.
 */
class ModelRepositoryImpl(
    private val session: () -> GatewayRpcClient,
) : ModelRepository {

    /**
     * @return The catalogue; the entry matching `agents.defaults.model.primary` is active.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException on gateway errors.
     */
    override suspend fun getModels(): List<ModelInfo> {
        val rpc = session()
        val payload = rpc.request(METHOD_MODELS_LIST) as? JsonObject
            ?: throw GatewayProtocolException(rpc.endpoint, "models.list returned a non-object payload")
        val primary = primaryModel(rpc)
        return (payload["models"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toModelInfo(primary) }
    }

    /**
     * Writes `agents.defaults.model.primary`.
     *
     * @param modelId A [ModelInfo.id] (`provider/model`).
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayApiException 412 on a concurrent config change,
     *   422 when the gateway rejects the model.
     */
    override suspend fun setActiveModel(modelId: String) {
        val rpc = session()
        val hash = (rpc.request(METHOD_CONFIG_GET) as? JsonObject)?.stringOrNull("hash")
        val patch = buildJsonObject {
            putJsonObject("agents") { putJsonObject("defaults") { putJsonObject("model") { put("primary", modelId) } } }
        }
        try {
            rpc.request(METHOD_CONFIG_PATCH, configPatchParams(patch, hash), CONFIG_PATCH_TIMEOUT_MS)
        } catch (e: GatewayRpcException) {
            throw e.toConfigWriteException()
        }
    }

    /** `agents.defaults.model.primary` (object or string form); `null` when unset or without `operator.admin`. */
    private suspend fun primaryModel(rpc: GatewayRpcClient): String? {
        val config = try {
            rpc.request(METHOD_CONFIG_GET) as? JsonObject
        } catch (ignored: GatewayMissingScopeException) {
            return null // config.* is admin-only; the catalogue stays useful without the active marker
        }
        return when (val model = config?.objectAt("parsed", "agents", "defaults")?.get("model")) {
            is JsonObject -> model.stringOrNull("primary")
            is JsonPrimitive -> model.takeIf { it.isString }?.content
            else -> null
        }
    }
}

@Suppress("ReturnCount")
private fun JsonObject.toModelInfo(primary: String?): ModelInfo? {
    val id = stringOrNull("id") ?: return null
    val provider = stringOrNull("provider") ?: return null
    val ref = modelRef(provider, id)
    val available = (this["available"] as? JsonPrimitive)?.booleanOrNull
    val reasoning = (this["reasoning"] as? JsonPrimitive)?.booleanOrNull == true
    return ModelInfo(
        id = ref,
        provider = provider,
        displayName = stringOrNull("name") ?: id,
        isActive = primary != null && (primary == ref || primary == id),
        status = if (available == false) stringOrNull("unavailableReason") ?: "unavailable" else "ready",
        capabilities = stringListOrEmpty("input") + listOfNotNull("reasoning".takeIf { reasoning }),
    )
}

/**
 * @param provider `models.list` `provider`.
 * @param id `models.list` `id`.
 * @return `provider/id`, unless [id] already carries the provider prefix.
 */
internal fun modelRef(provider: String, id: String): String = if (id.startsWith("$provider/")) id else "$provider/$id"
```

In `domain/repository/ModelRepository.kt` change only the interface KDoc to:
```kotlin
/**
 * Queries and manages language models registered on the gateway (ADR-0007: `models.list`, and the
 * `agents.defaults.model.primary` config key for the active model).
 *
 * **Contract frozen at v1.0.**
 */
```

In `DataModule.kt` replace
```kotlin
    single<ModelRepository> { ModelRepositoryImpl(get<GatewayConnectionManagerImpl>()) }
```
with
```kotlin
    single<ModelRepository> { ModelRepositoryImpl(session = liveSession()) }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.ModelRepositoryImplTest" --tests "com.openclaw.ghostcrab.ui.ModelManagerViewModelTest" --tests "com.openclaw.ghostcrab.ui.DashboardViewModelTest" --tests "com.openclaw.ghostcrab.di.KoinModulesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ModelRepositoryImpl.kt app/src/main/kotlin/com/openclaw/ghostcrab/domain/repository/ModelRepository.kt app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/ModelRepositoryImplTest.kt
git commit -m "feat(models): list via models.list, activate via agents.defaults.model.primary" -m "No models.set upstream; activation is a config.patch of the primary model ref with baseHash (ADR-0007)."
```

---

### Task 18: AI recommendations become honestly unsupported

**Files:**
- Rewrite: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/AIRecommendationServiceImpl.kt`
- Modify: `app/build.gradle.kts` (`AI_PRO_ENABLED`)
- Rewrite: `app/src/test/kotlin/com/openclaw/ghostcrab/data/AIRecommendationServiceImplTest.kt`

No upstream method or route produces recommendations (QA §8.11). A `chat.send`-based redesign needs its own ADR (it changes ADR-004's contract). Until then the entry points are hidden (`AI_PRO_ENABLED = false` gates the Dashboard action and the nav route) and the service never claims availability.

- [ ] **Step 1: Write the failing test**

Replace `app/src/test/kotlin/com/openclaw/ghostcrab/data/AIRecommendationServiceImplTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.impl.AIRecommendationServiceImpl
import com.openclaw.ghostcrab.data.impl.GatewayConnectionManagerImpl
import com.openclaw.ghostcrab.domain.exception.AIServiceUnavailableException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.model.AuthRequirement
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.OpenClawConfig
import com.openclaw.ghostcrab.domain.model.RecommendationContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AIRecommendationServiceImplTest {

    private val url = "http://192.168.1.50:18789"
    private val context =
        RecommendationContext(OpenClawConfig(emptyMap(), null), hardwareInfo = null, activeModelId = null)

    private fun manager(state: GatewayConnection): GatewayConnectionManagerImpl = mockk {
        every { connectionState } returns MutableStateFlow(state)
    }

    private val connected = GatewayConnection.Connected(
        url = url,
        displayName = "GW",
        version = "2026.10.3",
        authRequirement = AuthRequirement.Token,
        isHttps = false,
        capabilities = listOf("ai.recommend", "config", "models", "skills"),
    )

    @Test fun `never available, whatever the gateway advertises`() = runTest {
        assertFalse(AIRecommendationServiceImpl(manager(connected)).isAvailable())
    }

    @Test fun `getRecommendation reports the feature unavailable without touching the session`() = runTest {
        val m = manager(connected)
        val service = AIRecommendationServiceImpl(m)
        val e = assertThrows<AIServiceUnavailableException> { service.getRecommendation("q", context) }
        assertEquals(url, e.url)
        verify(exactly = 0) { m.requireSession() }
    }

    @Test fun `getRecommendation while disconnected is GatewayNotConnectedException`() = runTest {
        assertThrows<GatewayNotConnectedException> {
            AIRecommendationServiceImpl(manager(GatewayConnection.Disconnected)).getRecommendation("q", context)
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.AIRecommendationServiceImplTest"`
Expected: FAIL (`isAvailable` returns `true` for a gateway advertising `ai.recommend`; the REST call is attempted).

- [ ] **Step 3: Implement**

Replace `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/AIRecommendationServiceImpl.kt`:
```kotlin
package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.domain.exception.AIServiceUnavailableException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.model.AIRecommendation
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.RecommendationContext
import com.openclaw.ghostcrab.domain.repository.AIRecommendationService

/**
 * OpenClaw gateways have no recommendation API: `POST /api/ai/recommend` is 404 and no WS method exists
 * (QA §8.11, ADR-0007). Until a `chat.send`-based design has its own ADR, the feature is unsupported: never
 * available, and a request fails with [AIServiceUnavailableException] without any network call.
 *
 * @param connectionManager Supplies the gateway URL for the error.
 */
class AIRecommendationServiceImpl(
    private val connectionManager: GatewayConnectionManagerImpl,
) : AIRecommendationService {

    /** @return Always `false`. */
    override suspend fun isAvailable(): Boolean = false

    /**
     * @throws AIServiceUnavailableException always, naming the connected gateway.
     * @throws GatewayNotConnectedException if no session is active.
     */
    override suspend fun getRecommendation(query: String, context: RecommendationContext): AIRecommendation {
        val connected = connectionManager.connectionState.value as? GatewayConnection.Connected
            ?: throw GatewayNotConnectedException()
        throw AIServiceUnavailableException(connected.url)
    }
}
```

In `app/build.gradle.kts` replace
```kotlin
        // AI recommendations feature flag — true for v1.0; flip false to gate behind a pro paywall.
        buildConfigField("Boolean", "AI_PRO_ENABLED", "true")
```
with
```kotlin
        // AI recommendations: OpenClaw has no recommendation API (ADR-0007). Hidden until a chat.send-based
        // design gets its own ADR.
        buildConfigField("Boolean", "AI_PRO_ENABLED", "false")
```

- [ ] **Step 4: Run the tests and the gate**

Run: `./gradlew testDebugUnitTest detekt lintDebug`
Expected: BUILD SUCCESSFUL. (`DashboardScreen` and `NavGraph` already gate on `BuildConfig.AI_PRO_ENABLED`; `AIRecommendationViewModelTest` mocks the service.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/AIRecommendationServiceImpl.kt app/build.gradle.kts app/src/test/kotlin/com/openclaw/ghostcrab/data/AIRecommendationServiceImplTest.kt
git commit -m "feat(ai): mark AI recommendations unsupported on OpenClaw gateways" -m "No upstream recommendation API exists; the service never claims availability and AI_PRO_ENABLED is off until a chat.send design gets its own ADR (ADR-0007, amends ADR-004)."
```

---
### Task 19: Pairing approval loop

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/PairingApproval.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/PairingApprovalTest.kt`

A device waiting for approval has no session: verified 2026-10-15, the gateway closes the socket with `1008` right after `PAIRING_REQUIRED`, and `device.pair.resolved {requestId, deviceId, decision}` reaches only an already-paired admin socket. The only way to notice the approval is to connect again; the `requestId` stays the same across those reconnects, so the panel's command stays valid. Upstream expires a pending device-pairing request after 5 minutes (`/app/docs/gateway/pairing.md`). The same panel covers `metadata-upgrade` (a changed `platform`, which Task 11 makes impossible for GhostCrab's own builds).

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/openclaw/ghostcrab/ui/PairingApprovalTest.kt`:
```kotlin
package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.exception.GatewayConnectAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import com.openclaw.ghostcrab.ui.connection.PAIRING_TIMEOUT_MS
import com.openclaw.ghostcrab.ui.connection.PairingOutcome
import com.openclaw.ghostcrab.ui.connection.PairingPrompt
import com.openclaw.ghostcrab.ui.connection.awaitPairingApproval
import com.openclaw.ghostcrab.ui.connection.connectAwaitingApproval
import com.openclaw.ghostcrab.ui.connection.toPrompt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class PairingApprovalTest {

    private val url = "ws://192.168.1.50:18789/ws"
    private val pending = GatewayPairingRequiredException(url, "req-1", "not-paired", listOf("operator.read"))

    @Test fun `retries every interval until approved and reports each newer request`() = runTest {
        var calls = 0
        val prompts = mutableListOf<PairingPrompt>()
        val outcome = awaitPairingApproval(
            first = pending,
            wakeUps = Channel(Channel.CONFLATED),
            onPrompt = { prompts.add(it) },
            attempt = {
                calls++
                if (calls < 3) throw GatewayPairingRequiredException(url, "req-$calls", "not-paired", emptyList())
            },
        )
        assertEquals(PairingOutcome.Approved, outcome)
        assertEquals(3, calls)
        assertEquals(listOf("req-1", "req-1", "req-2"), prompts.map { it.requestId })
        assertEquals(15_000, currentTime)
    }

    @Test fun `a wake-up retries without waiting`() = runTest {
        val wakeUps = Channel<Unit>(Channel.CONFLATED)
        wakeUps.trySend(Unit)
        assertEquals(PairingOutcome.Approved, awaitPairingApproval(pending, wakeUps, onPrompt = {}, attempt = {}))
        assertEquals(0, currentTime)
    }

    @Test fun `gives up when the pending request expires`() = runTest {
        val outcome =
            awaitPairingApproval(pending, Channel(Channel.CONFLATED), onPrompt = {}, attempt = { throw pending })
        assertEquals(PairingOutcome.TimedOut, outcome)
        assertEquals(PAIRING_TIMEOUT_MS, currentTime)
    }

    @Test fun `any other failure ends the wait`() = runTest {
        assertThrows<GatewayConnectAuthException> {
            awaitPairingApproval(pending, Channel(Channel.CONFLATED), onPrompt = {}, attempt = {
                throw GatewayConnectAuthException(url, "AUTH_DEVICE_TOKEN_MISMATCH", "revoked", null)
            })
        }
    }

    @Test fun `the prompt names the exact approve command`() {
        assertEquals("openclaw devices approve req-1", pending.toPrompt().approveCommand)
        assertEquals("openclaw devices list", PairingPrompt(null, "not-paired", emptyList()).approveCommand)
    }

    @Test fun `connectAwaitingApproval connects at once when no approval is needed`() = runTest {
        assertTrue(connectAwaitingApproval(Channel(Channel.CONFLATED), onPrompt = {}) {})
        assertEquals(0, currentTime)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.PairingApprovalTest"`
Expected: FAIL, `Unresolved reference 'awaitPairingApproval'`.

- [ ] **Step 3: Implement**

`app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/PairingApproval.kt`:
```kotlin
package com.openclaw.ghostcrab.ui.connection

import com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

/** How often a connect is re-attempted while the device awaits approval. */
const val PAIRING_RETRY_INTERVAL_MS: Long = 5_000L

/** Upstream expires a pending device-pairing request after 5 minutes. */
const val PAIRING_TIMEOUT_MS: Long = 5 * 60_000L

/** Shown when no approval arrived before the pending request expired. */
const val PAIRING_TIMEOUT_MESSAGE: String =
    "Device approval not received within 5 minutes; the pending request expired. On the gateway host run " +
        "`openclaw devices list`, approve this device, then connect again (PAIRING_REQUIRED)."

/**
 * What the gateway owner must do to approve this device (ADR-0007 decision 7).
 *
 * @param requestId The pending request, when the gateway sent one.
 * @param reason `not-paired`, `scope-upgrade`, `role-upgrade` or `metadata-upgrade`.
 * @param requestedScopes The scopes this device asked for.
 */
data class PairingPrompt(val requestId: String?, val reason: String, val requestedScopes: List<String>) {
    /** The exact command for the gateway host. */
    val approveCommand: String get() = requestId?.let { "openclaw devices approve $it" } ?: "openclaw devices list"
}

/** @return The prompt for this pairing requirement. */
fun GatewayPairingRequiredException.toPrompt(): PairingPrompt = PairingPrompt(requestId, reason, requestedScopes)

/** Result of [awaitPairingApproval]. */
enum class PairingOutcome { Approved, TimedOut }

/**
 * Re-runs [attempt] every [intervalMs] — or at once when [wakeUps] delivers ("Check now") — while it fails with
 * [GatewayPairingRequiredException], passing each prompt to [onPrompt]. Nothing is approved from the phone.
 *
 * @param first The failure that started the wait; its prompt is reported first.
 * @param wakeUps Each element triggers an immediate retry.
 * @param onPrompt Receives the first prompt and every newer one the gateway reports.
 * @param attempt The connect to retry.
 * @param intervalMs Delay between attempts.
 * @param timeoutMs Overall budget (the pending request's lifetime).
 * @return [PairingOutcome.Approved] once [attempt] succeeds, [PairingOutcome.TimedOut] after [timeoutMs].
 * @throws Exception whatever [attempt] throws other than [GatewayPairingRequiredException] (e.g. a revoked token).
 */
suspend fun awaitPairingApproval(
    first: GatewayPairingRequiredException,
    wakeUps: ReceiveChannel<Unit>,
    onPrompt: (PairingPrompt) -> Unit,
    attempt: suspend () -> Unit,
    intervalMs: Long = PAIRING_RETRY_INTERVAL_MS,
    timeoutMs: Long = PAIRING_TIMEOUT_MS,
): PairingOutcome {
    onPrompt(first.toPrompt())
    val approved = withTimeoutOrNull(timeoutMs) {
        var done = false
        while (!done) {
            withTimeoutOrNull(intervalMs) { wakeUps.receive() }
            done = try {
                attempt()
                true
            } catch (e: GatewayPairingRequiredException) {
                onPrompt(e.toPrompt())
                false
            }
        }
        true
    }
    return if (approved == true) PairingOutcome.Approved else PairingOutcome.TimedOut
}

/**
 * Connects once; on `PAIRING_REQUIRED` waits for approval with [awaitPairingApproval].
 *
 * @param wakeUps "Check now" signals.
 * @param onPrompt Receives approval prompts.
 * @param connect The connect call.
 * @return `true` once connected, `false` when the approval window expired.
 */
suspend fun connectAwaitingApproval(
    wakeUps: ReceiveChannel<Unit>,
    onPrompt: (PairingPrompt) -> Unit,
    connect: suspend () -> Unit,
): Boolean = try {
    connect()
    true
} catch (e: GatewayPairingRequiredException) {
    awaitPairingApproval(e, wakeUps, onPrompt, connect) == PairingOutcome.Approved
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.PairingApprovalTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/PairingApproval.kt app/src/test/kotlin/com/openclaw/ghostcrab/ui/PairingApprovalTest.kt
git commit -m "feat(pairing): approval wait loop for PAIRING_REQUIRED" -m "Re-attempts the connect every 5 s (or on Check now) for the 5-minute life of a pending request; any other failure ends the wait (ADR-0007)."
```

---

### Task 20: Approval panel on Manual Entry

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/PairingApprovalCard.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ManualEntryViewModel.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ManualEntryScreen.kt`, `app/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/ManualEntryViewModelTest.kt`

- [ ] **Step 1: Write the failing ViewModel tests**

Add to `ManualEntryViewModelTest` (new imports: `com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException`, `com.openclaw.ghostcrab.ui.connection.PAIRING_RETRY_INTERVAL_MS`, `com.openclaw.ghostcrab.ui.connection.PAIRING_TIMEOUT_MS`, `kotlinx.coroutines.test.advanceTimeBy`, `kotlinx.coroutines.test.runCurrent`):
```kotlin
    // ── Pairing approval (ADR-0007) ───────────────────────────────────────────

    private val pending =
        GatewayPairingRequiredException("ws://192.168.1.50:18789/ws", "req-1", "not-paired", listOf("operator.read"))

    private fun fillForm() {
        vm.onHostChange("192.168.1.50")
        vm.onTokenChange("shared-token")
    }

    @Test fun `pairing required shows the approval prompt and connects once approved`() = runTest {
        var calls = 0
        coEvery { connectionManager.connect(any(), any()) } coAnswers { if (calls++ == 0) throw pending }
        fillForm()
        vm.events.test {
            vm.connect()
            val waiting = vm.uiState.value as ManualEntryUiState.AwaitingApproval
            assertEquals("openclaw devices approve req-1", waiting.prompt.approveCommand)
            advanceTimeBy(PAIRING_RETRY_INTERVAL_MS + 1)
            assertEquals(ManualEntryEvent.NavigateToDashboard, awaitItem())
        }
        assertEquals(ManualEntryUiState.Idle, vm.uiState.value)
        coVerify(exactly = 2) { connectionManager.connect("http://192.168.1.50:18789", "shared-token") }
    }

    @Test fun `cancelPairing stops retrying and returns to Idle`() = runTest {
        coEvery { connectionManager.connect(any(), any()) } throws pending
        fillForm()
        vm.connect()
        vm.cancelPairing()
        advanceTimeBy(60_000)
        assertEquals(ManualEntryUiState.Idle, vm.uiState.value)
        coVerify(exactly = 1) { connectionManager.connect(any(), any()) }
    }

    @Test fun `checkPairingNow retries without waiting`() = runTest {
        var calls = 0
        coEvery { connectionManager.connect(any(), any()) } coAnswers { if (calls++ == 0) throw pending }
        fillForm()
        vm.connect()
        vm.checkPairingNow()
        runCurrent()
        coVerify(exactly = 2) { connectionManager.connect(any(), any()) }
        assertEquals(ManualEntryUiState.Idle, vm.uiState.value)
    }

    @Test fun `an expired approval window is an explicit error`() = runTest {
        coEvery { connectionManager.connect(any(), any()) } throws pending
        fillForm()
        vm.connect()
        advanceTimeBy(PAIRING_TIMEOUT_MS + 1)
        assertTrue((vm.uiState.value as ManualEntryUiState.Error).message.contains("PAIRING_REQUIRED"))
    }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.ManualEntryViewModelTest"`
Expected: FAIL, `Unresolved reference 'AwaitingApproval'`.

- [ ] **Step 3: Add the state and the ViewModel logic**

In `ManualEntryViewModel.kt`:

Add the import `import kotlinx.coroutines.Job`.

Replace the `ManualEntryUiState` declaration with:
```kotlin
sealed interface ManualEntryUiState {
    data object Idle : ManualEntryUiState
    data object Connecting : ManualEntryUiState

    /**
     * The gateway wants this device approved; the connect is retried until it is (ADR-0007).
     *
     * @param prompt What to run on the gateway host.
     */
    data class AwaitingApproval(val prompt: PairingPrompt) : ManualEntryUiState
    data class Error(val message: String) : ManualEntryUiState
}
```

Add these fields after `val events: Flow<ManualEntryEvent> = _events.receiveAsFlow()`:
```kotlin

    /** "Check now" on the approval card wakes the retry loop. */
    private val pairingWakeUps = Channel<Unit>(Channel.CONFLATED)

    /** The running connect, including any approval wait, so [cancelPairing] can stop it. */
    private var connectJob: Job? = null
```

Replace the whole `fun connect() { … }` with:
```kotlin
    fun connect() {
        if (connectJob?.isActive == true) return
        val state = _form.value
        val hostError = validateHost(state.host)
        val portError = validatePort(state.port)
        if (hostError != null || portError != null) {
            _form.update { it.copy(hostError = hostError, portError = portError) }
            return
        }

        val url = state.assembledUrl
        connectJob = viewModelScope.launch {
            _uiState.value = ManualEntryUiState.Connecting
            val token = state.token.trim().takeIf { it.isNotEmpty() }
            // Connect (waiting for device approval when asked) AND persist inside one guard: a Keystore/DataStore
            // failure after a successful connect must surface as an error, not crash the app.
            suspendRunCatching {
                val approved = connectAwaitingApproval(
                    wakeUps = pairingWakeUps,
                    onPrompt = { _uiState.value = ManualEntryUiState.AwaitingApproval(it) },
                ) { connectionManager.connect(url, token) }
                if (approved) {
                    saveConnectedProfile(url, token)
                    onboardingRepository.markCompleted()
                }
                approved
            }.onSuccess { approved ->
                if (approved) {
                    _uiState.value = ManualEntryUiState.Idle
                    _events.send(ManualEntryEvent.NavigateToDashboard)
                } else {
                    _uiState.value = ManualEntryUiState.Error(PAIRING_TIMEOUT_MESSAGE)
                }
            }.onFailure { e ->
                _uiState.value = ManualEntryUiState.Error(e.toUserMessage())
            }
        }
    }

    /** "Check now" on the approval card: retry the connect immediately. */
    fun checkPairingNow() {
        pairingWakeUps.trySend(Unit)
    }

    /** "Cancel" on the approval card: stop retrying. The pending request simply expires on the gateway. */
    fun cancelPairing() {
        connectJob?.cancel()
        connectJob = null
        _uiState.value = ManualEntryUiState.Idle
    }
```

- [ ] **Step 4: Run the ViewModel tests**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.ManualEntryViewModelTest"`
Expected: PASS (all existing tests plus the 4 new ones).

- [ ] **Step 5: Add the approval card**

Add to `app/src/main/res/values/strings.xml`:
```xml
    <string name="pairing_title">Waiting for approval on the gateway</string>
    <string name="pairing_body">This device is not approved yet (%1$s). OpenClaw never auto-approves remote devices. On the gateway host run:</string>
    <string name="pairing_request_id">Request %1$s</string>
    <string name="pairing_scopes">Requested scopes: %1$s</string>
    <string name="pairing_retry_hint">GhostCrab checks again every 5 s for up to 5 minutes. You can also approve it in the Control UI under Devices.</string>
    <string name="pairing_check_now">Check now</string>
    <string name="pairing_cancel">Cancel</string>
```

`app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/PairingApprovalCard.kt`:
```kotlin
package com.openclaw.ghostcrab.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openclaw.ghostcrab.R
import com.openclaw.ghostcrab.ui.components.CodeBlock
import com.openclaw.ghostcrab.ui.components.GlassSurface
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.MonoFontFamily
import com.openclaw.ghostcrab.ui.theme.Spacing

/**
 * Panel for `PAIRING_REQUIRED` (ADR-0007 decision 7): the reason, the request id, the exact command for the gateway
 * host and the requested scopes. GhostCrab keeps retrying in the background; nothing is approved from the phone.
 * The spinner marks an active wait (allowed motion: state transition / active scan).
 *
 * @param prompt What to approve.
 * @param onCheckNow Retry immediately instead of waiting for the next interval.
 * @param onCancel Stop waiting.
 * @param modifier Applied to the surface.
 */
@Composable
fun PairingApprovalCard(
    prompt: PairingPrompt,
    onCheckNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = BrandTokens.colorCyanPulse,
                    strokeWidth = 2.dp,
                )
                Text(
                    text = stringResource(R.string.pairing_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = BrandTokens.colorAmberWarn,
                )
            }
            Text(
                text = stringResource(R.string.pairing_body, prompt.reason),
                style = MaterialTheme.typography.bodySmall,
                color = BrandTokens.colorTextSecondary,
            )
            prompt.requestId?.let { id ->
                Text(
                    text = stringResource(R.string.pairing_request_id, id),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = BrandTokens.colorTextPrimary,
                )
            }
            CodeBlock(code = prompt.approveCommand)
            if (prompt.requestedScopes.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.pairing_scopes, prompt.requestedScopes.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = BrandTokens.colorTextSecondary,
                )
            }
            Text(
                text = stringResource(R.string.pairing_retry_hint),
                style = MaterialTheme.typography.bodySmall,
                color = BrandTokens.colorTextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(
                    onClick = onCheckNow,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandTokens.colorCyanPrimary,
                        contentColor = BrandTokens.colorAbyss,
                    ),
                ) {
                    Text(stringResource(R.string.pairing_check_now))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.pairing_cancel), color = BrandTokens.colorTextSecondary)
                }
            }
        }
    }
}
```

- [ ] **Step 6: Show it on Manual Entry**

In `ManualEntryScreen.kt` replace
```kotlin
    val isConnecting = uiState is ManualEntryUiState.Connecting
```
with
```kotlin
    val isConnecting = uiState is ManualEntryUiState.Connecting || uiState is ManualEntryUiState.AwaitingApproval
    val approval = (uiState as? ManualEntryUiState.AwaitingApproval)?.prompt
```
and replace the whole `Button(onClick = viewModel::connect, …) { … }` block at the end of the column with:
```kotlin
            if (approval != null) {
                PairingApprovalCard(
                    prompt = approval,
                    onCheckNow = viewModel::checkPairingNow,
                    onCancel = viewModel::cancelPairing,
                )
            } else {
                Button(
                    onClick = viewModel::connect,
                    enabled = !isConnecting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandTokens.colorCyanPrimary,
                        contentColor = BrandTokens.colorAbyss,
                        disabledContainerColor = BrandTokens.colorCyanPrimary.copy(alpha = 0.4f),
                    ),
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            color = BrandTokens.colorAbyss,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Connect", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
```

- [ ] **Step 7: Build, test, lint**

Run: `./gradlew testDebugUnitTest detekt lintDebug assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/PairingApprovalCard.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ManualEntryViewModel.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ManualEntryScreen.kt app/src/main/res/values/strings.xml app/src/test/kotlin/com/openclaw/ghostcrab/ui/ManualEntryViewModelTest.kt
git commit -m "feat(pairing): approval panel on Manual Entry" -m "PAIRING_REQUIRED shows the request id, the exact openclaw devices approve command and the requested scopes, retries every 5 s with Check now / Cancel, and saves the profile only once approved (ADR-0007)."
```

---

### Task 21: Approval panel on the profile list

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ConnectionPickerViewModel.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ConnectionPickerScreen.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/ConnectionPickerViewModelTest.kt`

- [ ] **Step 1: Write the failing tests**

Add to `ConnectionPickerViewModelTest` (new imports: `com.openclaw.ghostcrab.domain.exception.GatewayPairingRequiredException`, `com.openclaw.ghostcrab.ui.connection.ConnectionPickerUiState`, `com.openclaw.ghostcrab.ui.connection.PAIRING_RETRY_INTERVAL_MS`, `kotlinx.coroutines.test.advanceTimeBy`):
```kotlin
    @Test
    fun `pairing required shows the prompt on the list and connects once approved`() = runTest {
        val p = profile()
        var calls = 0
        val manager = object : FakeCpConnectionManager() {
            override suspend fun connect(url: String, token: String?) {
                if (calls++ == 0) {
                    throw GatewayPairingRequiredException("ws://gw/ws", "req-9", "not-paired", emptyList())
                }
            }
        }
        val vm = ConnectionPickerViewModel(FakeCpProfileRepo(listOf(p)), manager, FakeCpOnboardingRepo(true))
        vm.events.test {
            vm.connect(p)
            assertEquals("req-9", vm.uiState.value.pairingPrompt?.requestId)
            advanceTimeBy(PAIRING_RETRY_INTERVAL_MS + 1)
            assertEquals(ConnectionPickerEvent.NavigateToDashboard, awaitItem())
        }
        assertEquals(ConnectionPickerUiState(), vm.uiState.value)
    }

    @Test
    fun `cancelPairing clears the prompt and the connecting state`() = runTest {
        val p = profile()
        val manager = object : FakeCpConnectionManager() {
            override suspend fun connect(url: String, token: String?) {
                throw GatewayPairingRequiredException("ws://gw/ws", "req-9", "not-paired", emptyList())
            }
        }
        val vm = ConnectionPickerViewModel(FakeCpProfileRepo(listOf(p)), manager, FakeCpOnboardingRepo(true))
        vm.connect(p)
        vm.cancelPairing()
        assertEquals(ConnectionPickerUiState(), vm.uiState.value)
    }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.ConnectionPickerViewModelTest"`
Expected: FAIL, `Unresolved reference 'pairingPrompt'`.

- [ ] **Step 3: Implement**

In `ConnectionPickerViewModel.kt`, add the import `import kotlinx.coroutines.Job` and replace the state class with:
```kotlin
/**
 * Transient picker state.
 *
 * @param connectingProfileId Profile whose connect is in flight, or `null`.
 * @param errorMessage Last connect failure, formatted for display; cleared by
 *   [ConnectionPickerViewModel.clearError].
 * @param pairingPrompt Set while the gateway waits for this device to be approved (ADR-0007).
 */
data class ConnectionPickerUiState(
    val connectingProfileId: String? = null,
    val errorMessage: String? = null,
    val pairingPrompt: PairingPrompt? = null,
)
```
Add after `public val events: Flow<ConnectionPickerEvent> = _events.receiveAsFlow()`:
```kotlin

    /** "Check now" on the approval card wakes the retry loop. */
    private val pairingWakeUps = Channel<Unit>(Channel.CONFLATED)

    /** The running connect, including any approval wait, so [cancelPairing] can stop it. */
    private var connectJob: Job? = null
```
Replace `public fun connect(profile: ConnectionProfile) { … }` with:
```kotlin
    public fun connect(profile: ConnectionProfile) {
        if (_uiState.value.connectingProfileId != null) return
        _uiState.value = ConnectionPickerUiState(connectingProfileId = profile.id)
        connectJob = viewModelScope.launch {
            suspendRunCatching {
                val token = if (profile.hasToken) profileRepository.getToken(profile.id) else null
                connectAwaitingApproval(
                    wakeUps = pairingWakeUps,
                    onPrompt = { prompt -> _uiState.update { it.copy(pairingPrompt = prompt) } },
                ) { connectionManager.connect(profile.url, token) }
            }.onSuccess { approved ->
                if (approved) {
                    _uiState.value = ConnectionPickerUiState()
                    _events.send(ConnectionPickerEvent.NavigateToDashboard)
                } else {
                    _uiState.value = ConnectionPickerUiState(errorMessage = PAIRING_TIMEOUT_MESSAGE)
                }
            }.onFailure { e ->
                _uiState.value = ConnectionPickerUiState(errorMessage = e.toUserMessage())
            }
        }
    }

    /** "Check now" on the approval card: retry the connect immediately. */
    public fun checkPairingNow() {
        pairingWakeUps.trySend(Unit)
    }

    /** "Cancel" on the approval card: stop retrying and clear the connecting state. */
    public fun cancelPairing() {
        connectJob?.cancel()
        connectJob = null
        _uiState.value = ConnectionPickerUiState()
    }
```

In `ConnectionPickerScreen.kt`, inside the `LazyColumn { … }`, before `items(profiles, key = { it.id }) { … }`, add:
```kotlin
                pickerState.pairingPrompt?.let { prompt ->
                    item(key = "pairing-approval") {
                        PairingApprovalCard(
                            prompt = prompt,
                            onCheckNow = viewModel::checkPairingNow,
                            onCancel = viewModel::cancelPairing,
                        )
                    }
                }
```

- [ ] **Step 4: Run tests and the gate**

Run: `./gradlew testDebugUnitTest detekt lintDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ConnectionPickerViewModel.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ConnectionPickerScreen.kt app/src/test/kotlin/com/openclaw/ghostcrab/ui/ConnectionPickerViewModelTest.kt
git commit -m "feat(pairing): approval panel on the saved-gateway list" -m "Reconnecting a saved profile whose device was never approved (or needs a scope upgrade) shows the same approval panel and retry loop (ADR-0007)."
```

---

### Task 22: `openclaw qr` setup codes are explained; `ws(s)://` URLs accepted

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/util/GatewayUrl.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/domain/util/PairCode.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanViewModel.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/MainActivity.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/domain/GatewayUrlTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/domain/PairCodeTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/ui/QrScanViewModelTest.kt`

The setup code is base64url JSON `{url: "ws(s)://…", bootstrapToken, expiresAtMs, urls?}`, bare or as `oc-pair://<code>` (`/app/docs/cli/qr.md`, `/app/docs/channels/pairing.md`). Verified 2026-10-15: its bootstrap token authenticates only `role: "node"` connects; over plain `ws://` it yields a node token with `scopes: []` and no operator handoff, and a node token is refused for operator (`AUTH_DEVICE_TOKEN_MISMATCH`). GhostCrab connects as an operator, so a setup code can never authorize it. GhostCrab therefore recognises setup codes only to say so: the scanner shows what the code is, the gateway URL inside it, and what to do instead (gateway URL + token in Manual Entry, then `openclaw devices approve`). The bootstrap token is dropped while decoding; it never reaches a type, a log or the hand-off.

- [ ] **Step 1: Write the failing tests**

Add to `GatewayUrlTest`:
```kotlin
    @Test fun `ws and wss urls map to http and https base urls`() {
        assertEquals("http://172.17.0.2:18789", GatewayUrl.fromWebSocketUrl("ws://172.17.0.2:18789")?.toString())
        assertEquals("https://gw.example:443", GatewayUrl.fromWebSocketUrl("wss://gw.example/ws")?.toString())
        assertNull(GatewayUrl.fromWebSocketUrl("http://h:1"))
    }
```
(add `import org.junit.jupiter.api.Assertions.assertNull` if missing).

Add to `PairCodeTest`:
```kotlin
    // ── openclaw qr setup codes (ADR-0007): recognised and explained, never used ─────────────

    /** base64url of {"url":"ws://172.17.0.2:18789","bootstrapToken":"bt-test-0001","expiresAtMs":1792028000000}. */
    private val setupCode =
        "eyJ1cmwiOiJ3czovLzE3Mi4xNy4wLjI6MTg3ODkiLCJib290c3RyYXBUb2tlbiI6ImJ0LXRlc3QtMDAwMSIs" +
            "ImV4cGlyZXNBdE1zIjoxNzkyMDI4MDAwMDAwfQ"

    private fun encode(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test fun `a setup code is recognised with its gateway url and nothing else`() {
        val result = decodePairCode(setupCode) as PairCodeResult.SetupCode
        assertEquals("http://172.17.0.2:18789", result.url?.toString())
        assertFalse(result.toString().contains("bt-test-0001"))
    }

    @Test fun `oc-pair links are setup codes too`() {
        assertInstanceOf(PairCodeResult.SetupCode::class.java, decodePairCode("oc-pair://$setupCode"))
    }

    @Test fun `a wss setup code names the https gateway on 443`() {
        val code = encode("""{"url":"wss://gw.example/ws","bootstrapToken":"bt"}""")
        assertEquals("https://gw.example:443", (decodePairCode(code) as PairCodeResult.SetupCode).url?.toString())
    }

    @Test fun `the explanation says why and what to do instead`() {
        val text = setupCodeExplanation(GatewayUrl("http", "172.17.0.2", 18789))
        assertTrue(text.contains("http://172.17.0.2:18789"))
        assertTrue(text.contains("gateway token"))
        assertTrue(text.contains("openclaw devices approve"))
    }

    @Test fun `base64 json without a bootstrap token is not a setup code`() {
        assertInstanceOf(PairCodeResult.Invalid::class.java, decodePairCode(encode("""{"url":"ws://h:1"}""")))
    }

    @Test fun `a plain ws url is accepted as a gateway url`() {
        val result = decodePairCode("ws://192.168.1.50:18789") as PairCodeResult.PlainUrl
        assertEquals("http://192.168.1.50:18789", result.url.toString())
    }
```
(add the imports `com.openclaw.ghostcrab.domain.util.GatewayUrl` and `com.openclaw.ghostcrab.domain.util.setupCodeExplanation`).

Add to `QrScanViewModelTest`:
```kotlin
    @Test
    fun `onQrDecoded openclaw setup code explains instead of pairing`() = runTest {
        val handoff = PairingHandoff()
        val vm = QrScanViewModel(handoff)
        val code = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"url":"ws://192.168.1.50:18789","bootstrapToken":"bt-test-0001"}""".toByteArray())
        vm.onQrDecoded(code)
        val message = (vm.uiState.value as QrScanUiState.InvalidQr).message
        assertTrue(message.contains("http://192.168.1.50:18789"))
        assertTrue(message.contains("openclaw devices approve"))
        assertNull(handoff.take())
    }
```
(add `import java.util.Base64`, `assertNull` and `assertTrue` if missing).

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.*" --tests "com.openclaw.ghostcrab.ui.QrScanViewModelTest"`
Expected: FAIL (`fromWebSocketUrl`, `SetupCode` and `setupCodeExplanation` unresolved).

- [ ] **Step 3: Accept `ws(s)://` in `GatewayUrl`**

In `GatewayUrl.kt`, inside `companion object`, after `parse(...)`:
```kotlin
        /**
         * Parses a gateway WebSocket URL (`ws://` / `wss://`, as in `openclaw qr` setup codes) into the matching
         * `http(s)` base URL. Path, query and fragment are dropped, like [parse].
         *
         * @param raw Untrusted URL text.
         * @return The base URL, or `null` when [raw] is not a `ws(s)` URL that [parse] accepts as `http(s)`.
         */
        fun fromWebSocketUrl(raw: String): GatewayUrl? {
            val trimmed = raw.trim()
            val mapped = when {
                trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring("wss://".length)
                trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring("ws://".length)
                else -> return null
            }
            return parse(mapped)
        }
```

- [ ] **Step 4: Recognise setup codes**

In `PairCode.kt`:

Add to `PairCodeResult`, after `PlainUrl`:
```kotlin

    /**
     * An `openclaw qr` setup code. It pairs OpenClaw node apps and cannot authorize an operator client such as
     * GhostCrab (ADR-0007), so it is only explained (see [setupCodeExplanation]). Its bootstrap token is not kept.
     *
     * @property url The gateway URL inside the code as an `http(s)` base URL, or `null` when it has none usable.
     */
    data class SetupCode(val url: GatewayUrl?) : PairCodeResult
```

Add after `private const val PAIR_VERSION = 1`:
```kotlin
private const val SETUP_CODE_SCHEME = "oc-pair://"
private val BASE64URL_TEXT = Regex("^[A-Za-z0-9_-]+={0,2}$")
```

Add after `PairPayloadV1`:
```kotlin
/** The two `openclaw qr` setup-code fields GhostCrab reads; the token only proves it is a setup code. */
@Serializable
private data class SetupCodePayload(
    val url: String? = null,
    val bootstrapToken: String? = null,
) {
    override fun toString(): String =
        "SetupCodePayload(url=$url, bootstrapToken=${bootstrapToken?.let { REDACTED_PLACEHOLDER }})"
}
```

Replace `decodePairCode` (and its KDoc) with:
```kotlin
/**
 * Decodes [raw] from a QR code or a `VIEW` intent: a `ghostcrab://pair?p=…` bundle, a plain `http(s)` / `ws(s)`
 * gateway URL, or an `openclaw qr` setup code (bare base64url or `oc-pair://<code>`), which is only recognised.
 * Input longer than [MAX_PAIR_CODE_LENGTH] is rejected without decoding.
 *
 * @param raw Scanned text or deep-link URI string.
 * @return [PairCodeResult.Bundle], [PairCodeResult.PlainUrl], [PairCodeResult.SetupCode] or
 *   [PairCodeResult.Invalid]; never throws.
 */
fun decodePairCode(raw: String): PairCodeResult {
    val trimmed = raw.trim()
    return when {
        raw.length > MAX_PAIR_CODE_LENGTH ->
            PairCodeResult.Invalid("Pairing data is too large (over $MAX_PAIR_CODE_LENGTH characters)")
        // Every ghostcrab: link is pairing data, so a broken link never gets the plain-URL message.
        trimmed.startsWith(PAIR_SCHEME, ignoreCase = true) -> decodeBundle(trimmed)
        trimmed.startsWith(SETUP_CODE_SCHEME, ignoreCase = true) ->
            decodeSetupCode(trimmed.substring(SETUP_CODE_SCHEME.length))
                ?: PairCodeResult.Invalid("OpenClaw setup code could not be decoded")
        else -> GatewayUrl.parse(trimmed)?.let { PairCodeResult.PlainUrl(it) }
            ?: GatewayUrl.fromWebSocketUrl(trimmed)?.let { PairCodeResult.PlainUrl(it) }
            ?: trimmed.takeIf { BASE64URL_TEXT.matches(it) }?.let { decodeSetupCode(it) }
            ?: PairCodeResult.Invalid("QR code doesn't contain a valid gateway URL")
    }
}

/** @return The setup code's gateway URL, or `null` when [encoded] is not a setup code (no bootstrap token). */
private fun decodeSetupCode(encoded: String): PairCodeResult.SetupCode? {
    val payload = decodeBase64Text(encoded)?.let { decodeOrNull(SetupCodePayload.serializer(), it) }
    if (payload == null || payload.bootstrapToken.isNullOrBlank()) return null
    return PairCodeResult.SetupCode(payload.url?.let { GatewayUrl.fromWebSocketUrl(it) ?: GatewayUrl.parse(it) })
}

/**
 * User-facing explanation for a scanned or pasted setup code (ADR-0007): what it is, why GhostCrab cannot use it,
 * and what to do instead.
 *
 * @param url The gateway URL inside the code, when usable.
 * @return One paragraph naming the URL (when known), the gateway token and the approval command.
 */
fun setupCodeExplanation(url: GatewayUrl?): String =
    "This is an OpenClaw setup code (`openclaw qr`). Setup codes pair OpenClaw node apps; they cannot give " +
        "GhostCrab operator access (over plain ws:// they grant no access at all). Open Manual Entry, enter " +
        (url?.let { "$it" } ?: "the gateway URL") + " and the gateway token, then approve this device on the " +
        "gateway host with `openclaw devices approve <requestId>`."
```

In `QrScanViewModel.kt`, add the import `import com.openclaw.ghostcrab.domain.util.setupCodeExplanation` and, in `onQrDecoded`'s `when`, after the `PairCodeResult.Invalid` branch:
```kotlin
            is PairCodeResult.SetupCode -> _uiState.value = QrScanUiState.InvalidQr(setupCodeExplanation(result.url))
```
In `MainActivity.kt`, in `acceptPairIntent`'s `when`, after the `PairCodeResult.PlainUrl` branch:
```kotlin
            is PairCodeResult.SetupCode -> Unit // unreachable: pairLinkToHandle only passes ghostcrab: links
```

- [ ] **Step 5: Run the tests and the gate**

Run: `./gradlew testDebugUnitTest detekt lintDebug`
Expected: BUILD SUCCESSFUL (existing `PairCodeTest`, `PairLinkIntentTest`, `QrScanViewModelTest` stay green: `ghostcrab://pair` v1 and the invalid-QR message are unchanged).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/domain/util/GatewayUrl.kt app/src/main/kotlin/com/openclaw/ghostcrab/domain/util/PairCode.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanViewModel.kt app/src/main/kotlin/com/openclaw/ghostcrab/MainActivity.kt app/src/test/kotlin/com/openclaw/ghostcrab/domain app/src/test/kotlin/com/openclaw/ghostcrab/ui/QrScanViewModelTest.kt
git commit -m "feat(pairing): explain openclaw qr setup codes, accept ws(s) URLs" -m "Setup-code bootstrap tokens authenticate only node clients (verified against 2026.10.3), so a scanned code shows what it is, its gateway URL and the token + devices approve path instead of connecting; the token is dropped while decoding (ADR-0007)."
```

---

### Task 23: Discovery copy (Bonjour is opt-in off macOS)

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ScanScreen.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/ui/onboarding/TroubleshootingDrawer.kt`, `app/src/main/res/values/strings.xml`

The `bonjour` plugin is enabled by default only on macOS; Linux, Windows and container gateways need `openclaw plugins enable bonjour`, and Docker bridge networking does not carry mDNS (QA §4). Copy only; no logic changes, so no unit test.

- [ ] **Step 1: Update the copy**

`strings.xml`, add:
```xml
    <string name="scan_empty_results">No OpenClaw gateways detected on this network. Gateways on Linux, Windows or Docker advertise only after: openclaw plugins enable bonjour. Docker bridge networking does not carry mDNS. Try entering the URL manually.</string>
    <string name="onboarding_troubleshoot_scan_4">Linux, Windows or Docker gateway? Bonjour is off by default there: run openclaw plugins enable bonjour on the gateway host.</string>
```

`ScanScreen.kt`, in `EmptyResultsContent`, replace
```kotlin
            text = "No OpenClaw gateways detected on this network.\n" +
                "mDNS may be blocked — try entering the URL manually.",
```
with
```kotlin
            text = stringResource(R.string.scan_empty_results),
```
(add `import androidx.compose.ui.res.stringResource` and `import com.openclaw.ghostcrab.R` if missing).

`TroubleshootingDrawer.kt`, in the scan section's `items = listOf(...)`, add after `stringResource(R.string.onboarding_troubleshoot_scan_3),`:
```kotlin
                    stringResource(R.string.onboarding_troubleshoot_scan_4),
```

- [ ] **Step 2: Build and lint**

Run: `./gradlew assembleDebug lintDebug detekt`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ScanScreen.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/onboarding/TroubleshootingDrawer.kt app/src/main/res/values/strings.xml
git commit -m "docs(ui): tell Linux/Docker users to enable Bonjour on the gateway" -m "OpenClaw advertises _openclaw-gw._tcp only on macOS by default (QA section 4)."
```

---

### Task 24: Delete the dead REST and JSON-RPC paths

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/data/ws/WsSession.kt`
- Delete: `app/src/main/kotlin/com/openclaw/ghostcrab/data/ws/GatewayWsClient.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/data/ws/JsonRpc.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/data/api/dto/StatusResponse.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/data/api/dto/ModelDto.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/data/api/dto/AIRecommendationDto.kt`
- Delete: `app/src/test/kotlin/com/openclaw/ghostcrab/data/ws/GatewayWsClientTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/data/ws/JsonRpcTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientOriginTest.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/GatewayConnectionManagerImpl.kt`
- Modify: `app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientErrorMappingTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/crash/PrivacySafeUncaughtExceptionHandlerTest.kt`

After Tasks 10–18 nothing calls `/status`, `/config`, `/api/*`, the JSON-RPC client, or the HTTP bearer path. Removing the bearer plugin means no credential can ever be sent over HTTP (ADR-0007 decision 1).

- [ ] **Step 1: Add a regression test for the new secrets in crash logs**

Add to `PrivacySafeUncaughtExceptionHandlerTest`:
```kotlin
    @Test
    fun `sanitize redacts device and bootstrap tokens in gateway frames`() {
        val input = """{"auth":{"deviceToken":"dt-test-0001","bootstrapToken":"bt-test-0001","token":"shared-token"}}"""
        val result = PrivacySafeUncaughtExceptionHandler.sanitize(input)
        listOf("dt-test-0001", "bt-test-0001", "shared-token").forEach { assertFalse(result.contains(it), it) }
    }
```
(add `import org.junit.jupiter.api.Assertions.assertFalse` if missing).

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.crash.PrivacySafeUncaughtExceptionHandlerTest"`
Expected: PASS (the existing `token` field regex already covers `deviceToken` and `bootstrapToken`; this pins it).

- [ ] **Step 2: Move the socket seam to its own file**

`app/src/main/kotlin/com/openclaw/ghostcrab/data/ws/WsSession.kt` — move `WsSession` and `KtorWsSession` verbatim from `GatewayWsClient.kt`:
```kotlin
package com.openclaw.ghostcrab.data.ws

import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Thin abstraction over a Ktor WS session — enables injecting a fake in tests. */
interface WsSession {
    suspend fun send(text: String)
    fun incoming(): Flow<String>
    suspend fun close()
}

/**
 * Production [WsSession] backed by a real Ktor [io.ktor.client.plugins.websocket.DefaultClientWebSocketSession].
 *
 * @param delegate The Ktor session carrying raw WS frames.
 */
class KtorWsSession internal constructor(
    private val delegate: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
) : WsSession {
    override suspend fun send(text: String) {
        delegate.send(io.ktor.websocket.Frame.Text(text))
    }

    override fun incoming(): Flow<String> = flow {
        for (frame in delegate.incoming) {
            if (frame is io.ktor.websocket.Frame.Text) emit(frame.readText())
        }
    }

    override suspend fun close() {
        delegate.close()
    }
}
```

- [ ] **Step 3: Delete the dead files**

```bash
git rm app/src/main/kotlin/com/openclaw/ghostcrab/data/ws/GatewayWsClient.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/ws/JsonRpc.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/api/dto/StatusResponse.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/api/dto/ModelDto.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/api/dto/AIRecommendationDto.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/ws/GatewayWsClientTest.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/ws/JsonRpcTest.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientOriginTest.kt
```

- [ ] **Step 4: Trim `OpenClawApiClient`**

Replace `app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt` entirely (what stays: `health()`, `openWsSession()`, `webSocketUrl()`, `close()`, the engine setup, `mapErrors` and `safeRequest`; what goes: every other endpoint, `authenticated()`, the bearer plugin, `isSameOrigin`, `isJson`):
```kotlin
package com.openclaw.ghostcrab.data.api

import com.openclaw.ghostcrab.BuildConfig
import com.openclaw.ghostcrab.data.api.dto.HealthResponse
import com.openclaw.ghostcrab.data.ws.KtorWsSession
import com.openclaw.ghostcrab.data.ws.WsSession
import com.openclaw.ghostcrab.domain.exception.CleartextBlockedException
import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayAuthException
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayTlsException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * Ktor client for the gateway's HTTP probe (`GET /health`) and for opening the control-plane WebSocket
 * (ADR-0007). It carries no credentials: authentication happens in the WS `connect` frame.
 *
 * Create via [unauthenticated]; call [close] when the session ends.
 * Timeouts: [CONNECT_TIMEOUT_MS], [REQUEST_TIMEOUT_MS], [SOCKET_TIMEOUT_MS].
 */
class OpenClawApiClient private constructor(
    val baseUrl: String,
    /** Underlying Ktor client: cleartext interceptor, timeouts, sanitized logging. */
    private val httpClient: HttpClient,
) {

    /**
     * GET `/health` — unauthenticated liveness check.
     *
     * @throws GatewayUnreachableException if the host cannot be reached.
     * @throws GatewayTimeoutException if the request times out.
     * @throws GatewayTlsException on SSL/TLS failure.
     */
    suspend fun health(): HealthResponse = safeRequest(baseUrl) {
        httpClient.get("$baseUrl/health").let { response ->
            response.mapErrors(baseUrl)
            response.body()
        }
    }

    /**
     * Opens the gateway control-plane WebSocket (`<baseUrl>/ws`, ADR-0007) on this client's engine, so the
     * upgrade honours the cleartext policy, timeouts and sanitized logging of every HTTP call.
     *
     * @return The raw socket. Wrap it in a `GatewayRpcClient`, which then owns and closes it.
     * @throws GatewayUnreachableException if the host cannot be reached.
     * @throws GatewayTimeoutException if the upgrade times out.
     * @throws CleartextBlockedException if app policy blocks cleartext to a public IP.
     */
    suspend fun openWsSession(): WsSession = safeRequest(baseUrl) {
        KtorWsSession(httpClient.webSocketSession(webSocketUrl()))
    }

    /** @return `ws(s)://host:port/ws` for [baseUrl] (`https` becomes `wss`). */
    fun webSocketUrl(): String = baseUrl.replaceFirst(Regex("^http"), "ws").trimEnd('/') + "/ws"

    /** Releases underlying HTTP connections. Call on disconnect. */
    fun close() {
        httpClient.close()
    }

    companion object {

        /** Test-only: create a client backed by a pre-built [HttpClient] (e.g. MockEngine). */
        internal fun forTest(baseUrl: String, httpClient: HttpClient): OpenClawApiClient =
            OpenClawApiClient(baseUrl, httpClient)

        /**
         * The only kind of client: no credentials (ADR-0007).
         *
         * @param allowCleartextPublicIPs Read on every request, so a Settings change applies immediately.
         */
        fun unauthenticated(
            baseUrl: String,
            allowCleartextPublicIPs: () -> Boolean = { false },
        ): OpenClawApiClient = OpenClawApiClient(baseUrl, buildHttpClient(allowCleartextPublicIPs))

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val SOCKET_TIMEOUT_MS = 30_000L

        private fun buildHttpClient(allowCleartextPublicIPs: () -> Boolean): HttpClient = HttpClient(OkHttp) {
            engine {
                addInterceptor(CleartextPublicIpInterceptor(allowCleartextPublicIPs))
            }
            install(WebSockets)
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
            // Header-level request logging is a debug-only diagnostic. Release builds install
            // no logger at all (and R8 strips Log.d call sites — see proguard-rules.pro).
            if (BuildConfig.DEBUG) {
                install(Logging) {
                    level = LogLevel.HEADERS
                    logger = object : Logger {
                        override fun log(message: String) {
                            android.util.Log.d("OpenClawApiClient", message)
                        }
                    }
                    // Defence in depth: no request carries Authorization any more, but never log one.
                    sanitizeHeader { header -> header == HttpHeaders.Authorization }
                }
            }
        }
    }
}

// ── Error mapping ─────────────────────────────────────────────────────────────

private fun io.ktor.client.statement.HttpResponse.mapErrors(url: String) {
    if (status.isSuccess()) return
    when (status) {
        HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden ->
            throw GatewayAuthException(url, status.value)
        else ->
            throw GatewayApiException(url, status.value)
    }
}

private suspend fun <T> safeRequest(url: String, block: suspend () -> T): T {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: GatewayException) {
        throw e
    } catch (e: HttpRequestTimeoutException) {
        throw GatewayTimeoutException(url, e)
    } catch (e: ConnectTimeoutException) {
        throw GatewayTimeoutException(url, e)
    } catch (e: SocketTimeoutException) {
        throw GatewayTimeoutException(url, e)
    } catch (e: SSLException) {
        throw GatewayTlsException(url, e)
    } catch (e: CleartextBlockedIOException) {
        throw CleartextBlockedException(url, e.host, e)
    } catch (e: IOException) {
        // DNS failure, refused/reset connection, no route: the host is unreachable.
        throw GatewayUnreachableException(url, e)
    } catch (e: SerializationException) {
        throw GatewayProtocolException(url, "response deserialization failed: ${e.message}", e)
    } catch (e: ContentConvertException) {
        throw GatewayProtocolException(url, "response deserialization failed: ${e.message}", e)
    } catch (e: Exception) {
        // Anything else is a client/protocol bug, not a connectivity problem — never call it "unreachable".
        throw GatewayProtocolException(url, e::class.java.simpleName + (e.message?.let { ": $it" } ?: ""), e)
    }
}
```

In `GatewayConnectionManagerImpl.kt`:
- delete `requireClient()`;
- in `OpenClawApiClientFactory` and `DefaultClientFactory` delete `authenticated(...)`.

- [ ] **Step 5: Trim the client tests**

In `OpenClawApiClientTest.kt` delete every test from `status returns StatusResponse on 200` through `getAIRecommendation 429 throws GatewayApiException with statusCode 429`, and `getModels treats an HTML answer as no models`, plus imports that become unused. Keep the `health …` tests and the `… is retryable` / `… is not retryable` tests.

In `OpenClawApiClientErrorMappingTest.kt` delete `a 202 from ai recommend is decoded instead of reported unreachable`, `openWebSocket refused upgrade request maps to GatewayUnreachableException` and `openWebSocket to a refusing port maps to GatewayUnreachableException` (Task 10 added the `openWsSession` equivalent), plus imports that become unused.

- [ ] **Step 6: Verify nothing references the removed code**

Run: `git grep -nE "GatewayWsClient|JsonRpc|StatusResponse|ModelDto|AIRecommendationRequestDto|requireClient|openWebSocket\(|/api/models|/api/ai|scopedBearerPlugin" -- app/src`
Expected: no output.

- [ ] **Step 7: Full gate, release build**

Run: `./gradlew testDebugUnitTest detekt lintDebug assembleRelease :app:assembleDebugAndroidTest`
Expected: BUILD SUCCESSFUL (`assembleDebugAndroidTest` compiles `StoreScreenshotTest` and the other instrumented tests against the slimmed client). If R8 warns about a removed DTO in `app/proguard-rules.pro`, delete that rule.

- [ ] **Step 8: Commit**

```bash
git add -A app/src
git commit -m "refactor: remove the speculative REST and JSON-RPC client paths" -m "/status, /config, /api/models/*, /api/ai/recommend, JSON-RPC 2.0 and the HTTP bearer plugin are gone; the only HTTP call left is /health and no credential is ever sent over HTTP (ADR-0007)."
```

---

### Task 25: Mock gateway speaks the real protocol

**Files:**
- Create: `scripts/mock-gateway/lib/device.mjs`, `scripts/mock-gateway/lib/client.mjs`, `scripts/mock-gateway/test/conformance.test.mjs`, `scripts/mock-gateway/probe-real.mjs`
- Rewrite: `scripts/mock-gateway/server.mjs`, `scripts/mock-gateway/README.md`, `scripts/mock-gateway/package.json`
- Modify: `scripts/mock-gateway/.gitignore`, `.github/workflows/ci.yml`, `.github/workflows/android-screenshots.yml`, `.github/workflows/ios-screenshots.yml` (the store-screenshot jobs from PR #131 start this mock)
- Rename: `scripts/mock-gateway/server.mjs` → `scripts/mock-gateway/legacy-rest.mjs` (today's REST + JSON-RPC mock, byte-for-byte; `server.mjs` serves it with `LEGACY_REST=1` until the iOS plan's Task 20 deletes it, because `ios-screenshots.yml` drives the not-yet-aligned iOS app)
- Delete: `scripts/mock-gateway/smoke-ws.mjs`

The mock is what on-device QA and the conformance tests run against, so it must reject everything the real gateway rejects. Error and success shapes are compared against the fixtures from Task 1, including the frames pinned on 2026-10-15 (client-id allow-list, platform pinning, stable `requestId`, operator-refused bootstrap tokens, `config.patch` errors and success, `skills.update` + `config.changed`, ClawHub install entries). Node 24 (built-in `WebSocket` client, `node:test`).

- [ ] **Step 1: Write the shared device helpers**

`scripts/mock-gateway/lib/device.mjs`:
```js
// Ed25519 device identity helpers shared by the mock gateway, its tests and probe-real.mjs (ADR-0007).
import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign, verify } from "node:crypto";

/** Upstream normalizeDeviceMetadataForAuth: trim, then lowercase ASCII A-Z only. */
export const normalizeMeta = (value) =>
  typeof value === "string" ? value.trim().replace(/[A-Z]/g, (c) => c.toLowerCase()) : "";

/** Upstream buildDeviceAuthPayloadV3. */
export function payloadV3({ deviceId, clientId, clientMode, role, scopes, signedAtMs, token, nonce, platform, deviceFamily }) {
  return [
    "v3", deviceId, clientId, clientMode, role, scopes.join(","), String(signedAtMs), token ?? "", nonce,
    normalizeMeta(platform), normalizeMeta(deviceFamily),
  ].join("|");
}

/** hex(sha256(raw public key)). */
export const deviceIdFor = (publicKeyB64Url) =>
  createHash("sha256").update(Buffer.from(publicKeyB64Url, "base64url")).digest("hex");

/** A fresh identity: { jwk (private, sensitive), publicKey (raw, base64url), deviceId }. */
export function newIdentity() {
  const { privateKey } = generateKeyPairSync("ed25519");
  const jwk = privateKey.export({ format: "jwk" });
  return { jwk, publicKey: jwk.x, deviceId: deviceIdFor(jwk.x) };
}

export function signPayload(identity, payload) {
  return sign(null, Buffer.from(payload, "utf8"), createPrivateKey({ key: identity.jwk, format: "jwk" })).toString("base64url");
}

export function verifyPayload(publicKeyB64Url, payload, signatureB64Url) {
  try {
    const key = createPublicKey({ key: { kty: "OKP", crv: "Ed25519", x: publicKeyB64Url }, format: "jwk" });
    return verify(null, Buffer.from(payload, "utf8"), key, Buffer.from(signatureB64Url ?? "", "base64url"));
  } catch {
    return false; // malformed key or signature: simply not valid
  }
}
```

- [ ] **Step 2: Write the protocol-4 test client**

`scripts/mock-gateway/lib/client.mjs`:
```js
// Minimal protocol-4 client: challenge -> signed connect -> req/res/events. Used by the tests and probe-real.mjs.
import { newIdentity, payloadV3, signPayload } from "./device.mjs";

export const OPERATOR_SCOPES = ["operator.read", "operator.write", "operator.admin"];
export const DEFAULT_CLIENT = { id: "openclaw-android", version: "0.3.0-test", platform: "android", mode: "ui", displayName: "GhostCrab test" };

/**
 * @returns {Promise<{hello, error, closed, request, nextEvent, close, identity}>} hello is the hello-ok payload, or
 *   null with error set; closed resolves to the socket's {code, reason}.
 */
export async function connect(url, { identity = newIdentity(), auth = {}, scopes = OPERATOR_SCOPES, withDevice = true, tamperSignature = false, client = DEFAULT_CLIENT } = {}) {
  const ws = new WebSocket(url);
  const inbox = [];
  const waiters = [];
  let ended = null;
  const wake = () => waiters.splice(0).forEach((resolve) => resolve());
  const closed = new Promise((resolve) => ws.addEventListener("close", (ev) => resolve({ code: ev.code, reason: ev.reason })));
  ws.addEventListener("message", (ev) => { inbox.push(JSON.parse(String(ev.data))); wake(); });
  ws.addEventListener("close", (ev) => { ended = { code: ev.code, reason: ev.reason }; wake(); });
  await new Promise((resolve, reject) => {
    ws.addEventListener("open", resolve, { once: true });
    ws.addEventListener("error", reject, { once: true });
  });
  const next = async (predicate, timeoutMs = 5000) => {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const index = inbox.findIndex(predicate);
      if (index >= 0) return inbox.splice(index, 1)[0];
      if (ended || Date.now() > deadline) return null;
      await new Promise((resolve) => { waiters.push(resolve); setTimeout(resolve, Math.max(0, deadline - Date.now())); });
    }
  };
  let seq = 0;
  const request = async (method, params = {}) => {
    const id = `t-${++seq}`;
    ws.send(JSON.stringify({ type: "req", id, method, params }));
    return next((frame) => frame.type === "res" && frame.id === id);
  };
  const nextEvent = (name, timeoutMs = 2000) => next((frame) => frame.type === "event" && frame.event === name, timeoutMs);
  const challenge = await next((frame) => frame.type === "event" && frame.event === "connect.challenge");
  const { nonce, ts } = challenge.payload;
  const params = { minProtocol: 4, maxProtocol: 4, client, role: "operator", scopes, caps: [], commands: [], permissions: {}, locale: "en-US", userAgent: "ghostcrab-test/0" };
  if (Object.keys(auth).length) params.auth = auth;
  if (withDevice) {
    const payload = payloadV3({
      deviceId: identity.deviceId, clientId: client.id, clientMode: client.mode, role: "operator", scopes,
      signedAtMs: ts, token: auth.token ?? auth.deviceToken ?? auth.bootstrapToken, nonce,
      platform: client.platform, deviceFamily: client.deviceFamily,
    });
    const signature = signPayload(identity, tamperSignature ? `${payload}x` : payload);
    params.device = { id: identity.deviceId, publicKey: identity.publicKey, signature, signedAt: ts, nonce };
  }
  const res = await request("connect", params);
  return {
    identity,
    hello: res?.ok ? res.payload : null,
    error: res?.ok ? null : res?.error ?? null,
    closed,
    request,
    nextEvent,
    close: () => ws.close(),
  };
}
```

- [ ] **Step 3: Write the failing conformance tests**

`scripts/mock-gateway/test/conformance.test.mjs`:
```js
import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { createMockGateway } from "../server.mjs";
import { connect, DEFAULT_CLIENT, OPERATOR_SCOPES } from "../lib/client.mjs";
import { newIdentity } from "../lib/device.mjs";

const FIXTURES = new URL("../../../fixtures/gateway-protocol/2026.10.3/", import.meta.url);
const fixture = (name) => JSON.parse(fs.readFileSync(new URL(`${name}.json`, FIXTURES), "utf8"));
const keys = (object) => Object.keys(object).sort();
const TOKEN = "shared-token";
let gateway;
let url;
let base;

before(async () => {
  gateway = createMockGateway({ token: TOKEN });
  const { port } = await gateway.listen(0, "127.0.0.1");
  url = `ws://127.0.0.1:${port}/ws`;
  base = `http://127.0.0.1:${port}`;
});
after(() => gateway.close());

const approve = () => fetch(`${base}/__mock/devices/approve`, { method: "POST" });

async function pairedSession(identity = newIdentity()) {
  const first = await connect(url, { identity, auth: { token: TOKEN } });
  assert.equal(first.error?.details?.code, "PAIRING_REQUIRED");
  first.close();
  await approve();
  const second = await connect(url, { identity, auth: { token: TOKEN } });
  assert.ok(second.hello, JSON.stringify(second.error));
  return second;
}

const skillNamed = async (session, key) =>
  (await session.request("skills.status")).payload.skills.find((skill) => skill.skillKey === key);

test("HTTP surface: JSON probes, HTML elsewhere, 404 for /api", async () => {
  const health = await fetch(`${base}/health`);
  assert.equal(health.headers.get("content-type"), "application/json");
  assert.deepEqual(await health.json(), { ok: true, status: "live" });
  assert.match((await fetch(`${base}/status`)).headers.get("content-type"), /text\/html/);
  assert.equal((await fetch(`${base}/api/models/status`)).status, 404);
});

test("a non-connect first frame closes 1008", async () => {
  const ws = new WebSocket(url);
  const closed = new Promise((resolve) => ws.addEventListener("close", resolve));
  await new Promise((resolve) => ws.addEventListener("message", resolve, { once: true }));
  ws.send(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "auth.whoami", params: null }));
  const ev = await closed;
  assert.equal(ev.code, 1008);
  assert.equal(ev.reason, "invalid request frame");
});

test("credential and device failures carry the captured error shapes", async () => {
  assert.deepEqual((await connect(url, { withDevice: false })).error, fixture("err-device-identity-required").error);
  assert.deepEqual((await connect(url, {})).error, fixture("err-token-missing").error);
  assert.deepEqual((await connect(url, { auth: { token: "wrong" } })).error, fixture("err-token-mismatch").error);
  assert.equal((await connect(url, { auth: { token: TOKEN }, tamperSignature: true })).error.details.code, "DEVICE_AUTH_SIGNATURE_INVALID");
});

test("a token without device identity gets no scopes, like the real gateway", async () => {
  const s = await connect(url, { withDevice: false, auth: { token: TOKEN } });
  assert.deepEqual(s.hello.auth.scopes, []);
  assert.deepEqual((await s.request("status")).error, fixture("err-missing-scope").error);
  assert.equal((await s.request("health")).ok, true);
  s.close();
});

test("client id and mode are allow-lists checked before auth", async () => {
  const badId = await connect(url, { client: { ...DEFAULT_CLIENT, id: "ghostcrab-android" }, auth: { token: TOKEN } });
  assert.deepEqual(badId.error, fixture("err-client-id-invalid").error);
  assert.equal((await badId.closed).code, 1008);
  const badMode = await connect(url, { client: { ...DEFAULT_CLIENT, mode: "operator" }, auth: { token: TOKEN } });
  assert.match(badMode.error.message, /at \/client\/mode: must be equal to one of the allowed values/);
});

test("pairing: one stable requestId until approved, then scopes and a reusable device token", async () => {
  const identity = newIdentity();
  const first = await connect(url, { identity, auth: { token: TOKEN } });
  const expected = fixture("err-pairing-required").error;
  assert.equal(first.error.code, expected.code);
  assert.deepEqual(keys(first.error.details), keys(expected.details));
  assert.equal((await first.closed).code, 1008);
  const retry = await connect(url, { identity, auth: { token: TOKEN } });
  assert.equal(retry.error.details.requestId, first.error.details.requestId);
  await approve();
  const second = await connect(url, { identity, auth: { token: TOKEN } });
  assert.deepEqual(second.hello.auth.scopes, OPERATOR_SCOPES);
  assert.deepEqual(keys(second.hello), keys(fixture("hello-ok-paired").payload));
  second.close();
  const reuse = await connect(url, { identity, auth: { deviceToken: second.hello.auth.deviceToken }, scopes: ["operator.read"] });
  assert.equal(reuse.hello.auth.method, "device-token");
  assert.deepEqual(reuse.hello.auth.scopes, ["operator.read"]);
  reuse.close();
});

test("a changed platform needs re-approval; a changed client id or letter case does not", async () => {
  const identity = newIdentity();
  (await pairedSession(identity)).close();
  const otherId = await connect(url, { identity, client: { ...DEFAULT_CLIENT, id: "openclaw-ios" }, auth: { token: TOKEN } });
  assert.ok(otherId.hello, JSON.stringify(otherId.error));
  otherId.close();
  const upperCase = await connect(url, { identity, client: { ...DEFAULT_CLIENT, platform: "Android" }, auth: { token: TOKEN } });
  assert.ok(upperCase.hello, JSON.stringify(upperCase.error));
  upperCase.close();
  const moved = await connect(url, { identity, client: { ...DEFAULT_CLIENT, platform: "ios" }, auth: { token: TOKEN } });
  assert.equal(moved.error.details.reason, "metadata-upgrade");
  assert.deepEqual(keys(moved.error.details), keys(fixture("err-pairing-metadata-upgrade").error.details));
});

test("skills: verified shapes, and skills.status catches up on config.changed", async () => {
  const s = await pairedSession();
  const status = await s.request("skills.status");
  for (const key of ["name", "skillKey", "source", "bundled", "disabled"]) assert.ok(key in status.payload.skills[0], key);
  assert.deepEqual((await s.request("skills.list")).error, fixture("err-unknown-method").error);
  const notFound = await s.request("skills.install", { source: "clawhub", slug: "missing-skill", force: false });
  assert.equal(notFound.error.code, "UNAVAILABLE");
  assert.match(notFound.error.message, /not found on ClawHub/);
  const installed = await s.request("skills.install", { source: "clawhub", slug: "@someone/demo-skill", force: false });
  assert.deepEqual(keys(installed.payload), keys(fixture("res-skills-install").payload));
  assert.equal(installed.payload.slug, "demo-skill");
  const entry = await skillNamed(s, "demo-skill");
  assert.equal(entry.source, "openclaw-workspace");
  assert.equal(entry.clawhub.installedVersion, "1.0.0");
  const update = await s.request("skills.update", { skillKey: "demo-skill", enabled: false });
  assert.deepEqual(keys(update.payload), keys(fixture("res-skills-update").payload));
  assert.equal((await skillNamed(s, "demo-skill")).disabled, false); // stale until config.changed
  assert.ok(await s.nextEvent("config.changed"));
  assert.equal((await skillNamed(s, "demo-skill")).disabled, true);
  assert.equal((await s.request("skills.update", { skillKey: "no-such-skill", enabled: false })).ok, true);
  assert.ok(Array.isArray((await s.request("models.list")).payload.models));
  s.close();
});

test("config.patch: verified error and success shapes, redacted secrets restored", async () => {
  const s = await pairedSession();
  const first = (await s.request("config.get")).payload;
  assert.equal(first.parsed.gateway.auth.token, "__OPENCLAW_REDACTED__");
  const raw = JSON.stringify({ gateway: { port: 18790 } });
  assert.deepEqual((await s.request("config.patch", { raw, baseHash: "stale" })).error, fixture("err-config-stale-hash").error);
  assert.match((await s.request("config.patch", { raw })).error.message, /^config base hash required for gateway\.port; /);
  const tooBig = await s.request("config.patch", { raw: JSON.stringify({ gateway: { port: 99999 } }), baseHash: first.hash });
  assert.deepEqual(tooBig.error, fixture("err-config-invalid").error);
  const patch = { gateway: { port: 18790, auth: { token: "__OPENCLAW_REDACTED__" } } };
  const done = await s.request("config.patch", { raw: JSON.stringify(patch), baseHash: first.hash });
  assert.deepEqual(keys(done.payload), keys(fixture("res-config-patch").payload));
  assert.ok(await s.nextEvent("config.changed"));
  const next = (await s.request("config.get")).payload;
  assert.equal(next.parsed.gateway.port, 18790);
  assert.equal(next.hash, done.payload.hash);
  assert.notEqual(next.hash, first.hash);
  assert.equal(gateway.state.config.gateway.auth.token, TOKEN);
  s.close();
});

test("setup codes never authenticate an operator client", async () => {
  const { bootstrapToken, setupCode } = await (await fetch(`${base}/__mock/setup-code`, { method: "POST" })).json();
  assert.equal(JSON.parse(Buffer.from(setupCode, "base64url").toString()).bootstrapToken, bootstrapToken);
  const attempt = await connect(url, { auth: { bootstrapToken } });
  assert.equal(attempt.error.details.code, "AUTH_BOOTSTRAP_TOKEN_INVALID");
  assert.equal(attempt.error.details.recommendedNextStep, "review_auth_configuration");
});
```

Run: `cd scripts/mock-gateway && npm install && node --test test/conformance.test.mjs`
Expected: FAIL (`createMockGateway` is not exported by the old `server.mjs`).

- [ ] **Step 4: Rewrite the server**

Keep today's mock for the store-screenshot job of the iOS app, which speaks the old REST contract until the iOS plan lands:
```bash
git mv scripts/mock-gateway/server.mjs scripts/mock-gateway/legacy-rest.mjs
```
Then write the new server.

`scripts/mock-gateway/server.mjs`:
```js
#!/usr/bin/env node
// Mock OpenClaw gateway speaking the real control plane (WS protocol 4), as captured from
// ghcr.io/openclaw/openclaw 2026.10.3 (docs/QA_REAL_GATEWAY_2026-10.md, docs/adr/0007-gateway-protocol-alignment.md).
//   HTTP: /health, /ready (JSON); Control-UI-style HTML for other GETs; 404 text/plain for /api/* and other methods.
//   WS (any path): connect.challenge -> signed connect -> hello-ok | PAIRING_REQUIRED, then req/res RPCs, and
//   config.changed events ~80 ms after writes.
//   Mock-only routes (never on a real gateway): POST /__mock/devices/approve, POST /__mock/setup-code.
// Env: PORT (18790), HOST (0.0.0.0), TOKEN (shared token; unset = auth mode none), SCOPES (granted subset),
//      AUTO_APPROVE=1 (approve new devices at once), FAIL_MODE (comma list: install-unavailable),
//      MOCK_DISPLAY_NAME (presence host = the app's gateway name), MOCK_VERSION (server.version),
//      LEGACY_REST=1 (serve the pre-ADR-0007 REST mock in legacy-rest.mjs instead; ios-screenshots.yml only,
//      until the iOS plan's Task 20 removes it).
import http from "node:http";
import { createHash, randomUUID } from "node:crypto";
import { pathToFileURL } from "node:url";
import { WebSocketServer } from "ws";
import { deviceIdFor, normalizeMeta, payloadV3, verifyPayload } from "./lib/device.mjs";

export const PROTOCOL = 4;
export const MOCK_VERSION = "2026.10.3-mock";
export const ALL_SCOPES = ["operator.read", "operator.write", "operator.admin"];
export const REDACTED = "__OPENCLAW_REDACTED__";
const CONFIG_PATH = "/mock/openclaw.json";

/** The ids and modes GhostCrab and these tests send. Upstream's allow-lists are longer; anything else is refused before auth. */
const CLIENT_IDS = new Set(["openclaw-android", "openclaw-ios"]);
const CLIENT_MODES = new Set(["ui", "cli"]);
/** The real gateway emits config.changed ~80 ms after a write; skills.status is stale until then. */
const CONFIG_CHANGED_DELAY_MS = 80;
/** The real gateway closes a socket that never sends connect after ~15 s. */
const CONNECT_DEADLINE_MS = 15_000;
const BOOTSTRAP_INVALID =
  "unauthorized: setup code invalid, expired, revoked, or already used (create a new code; review `openclaw devices list`)";

/** Scope each method needs; null = none (health works on a scope-less socket, as upstream). */
const METHOD_SCOPE = {
  health: null,
  status: "operator.read",
  "models.list": "operator.read",
  "skills.status": "operator.read",
  "skills.install": "operator.admin",
  "skills.update": "operator.admin",
  "config.get": "operator.admin",
  "config.patch": "operator.admin",
};

export function createMockGateway({
  token = null,
  scopes = ALL_SCOPES,
  autoApprove = false,
  failModes = [],
  displayName = "mock-gateway",
  version = MOCK_VERSION,
} = {}) {
  const fail = new Set(failModes);
  const state = {
    paired: new Map(), // deviceId -> { deviceToken, scopes, platform }
    pending: new Map(), // deviceId -> { requestId, scopes, platform }
    config: {
      gateway: { mode: "local", port: 18789, bind: "lan", auth: token ? { mode: "token", token } : { mode: "none" } },
      agents: { entries: { main: {} } },
    },
    models: [
      { id: "claude-sonnet-4-6", name: "Claude Sonnet 4.6", provider: "anthropic", available: true, input: ["text", "image"], reasoning: true },
      { id: "gpt-6-astra", name: "GPT-6 Astra", provider: "openai", available: false, unavailableReason: "missing-auth", input: ["text"] },
    ],
    skills: [
      { name: "browser-automation", skillKey: "browser-automation", source: "openclaw-extra", bundled: false, disabled: false, eligible: true },
      { name: "1password", skillKey: "1password", source: "openclaw-bundled", bundled: true, disabled: true, eligible: false },
    ],
  };
  let revision = 1;
  const configHash = () =>
    "mock-sha256:v1:" + createHash("sha256").update(`${revision}:${JSON.stringify(state.config)}`).digest("base64url");
  const peers = new Set(); // { ws, session } of authenticated sockets
  const timers = new Set();
  const later = (fn, delayMs = CONFIG_CHANGED_DELAY_MS) => {
    const timer = setTimeout(() => {
      timers.delete(timer);
      fn();
    }, delayMs);
    timer.unref?.();
    timers.add(timer);
    return timer;
  };

  const server = http.createServer((req, res) => handleHttp(req, res));
  const wss = new WebSocketServer({ noServer: true });
  server.on("upgrade", (req, socket, head) => wss.handleUpgrade(req, socket, head, (ws) => handleSocket(ws)));

  // ── HTTP ──────────────────────────────────────────────────────────────────
  function handleHttp(req, res) {
    const path = new URL(req.url, "http://mock").pathname;
    const json = (status, body) => {
      res.writeHead(status, { "content-type": "application/json" });
      res.end(JSON.stringify(body));
    };
    if (req.method === "GET" && (path === "/health" || path === "/healthz")) return json(200, { ok: true, status: "live" });
    if (req.method === "GET" && (path === "/ready" || path === "/readyz")) return json(200, { ready: true });
    if (req.method === "POST" && path === "/__mock/devices/approve") return json(200, { approved: approveAll() });
    if (req.method === "POST" && path === "/__mock/setup-code") {
      // For the QR explanation flow: like upstream, the token never authenticates an operator client.
      const setup = { url: `ws://${req.headers.host}`, bootstrapToken: randomUUID(), expiresAtMs: Date.now() + 10 * 60_000 };
      return json(200, { setupCode: Buffer.from(JSON.stringify(setup)).toString("base64url"), ...setup });
    }
    if (req.method === "GET" && !path.startsWith("/api/")) {
      res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
      return res.end("<!doctype html><html data-openclaw-control-ui-mock></html>");
    }
    res.writeHead(404, { "content-type": "text/plain" });
    return res.end("Not Found");
  }

  function approveAll() {
    const approved = [];
    for (const [deviceId, request] of state.pending) {
      const previous = state.paired.get(deviceId);
      state.paired.set(deviceId, {
        deviceToken: previous?.deviceToken ?? `dt-${randomUUID()}`,
        scopes: request.scopes,
        platform: request.platform,
      });
      approved.push(request.requestId);
    }
    state.pending.clear();
    return approved;
  }

  // ── WebSocket ─────────────────────────────────────────────────────────────
  function handleSocket(ws) {
    const nonce = randomUUID();
    const ts = Date.now();
    let session = null;
    const send = (frame) => ws.send(JSON.stringify(frame));
    const deadline = later(() => {
      if (!session) ws.close(1000);
    }, CONNECT_DEADLINE_MS);
    ws.on("close", () => {
      clearTimeout(deadline);
      timers.delete(deadline);
      for (const peer of peers) if (peer.ws === ws) peers.delete(peer);
    });
    send({ type: "event", event: "connect.challenge", payload: { nonce, ts, capabilities: [] } });
    ws.on("message", (data) => {
      let frame;
      try {
        frame = JSON.parse(String(data));
      } catch {
        return ws.close(1008, "invalid request frame");
      }
      if (frame?.type !== "req" || typeof frame.id !== "string" || typeof frame.method !== "string") {
        return ws.close(1008, "invalid request frame");
      }
      if (!session) {
        if (frame.method !== "connect") return ws.close(1008, "invalid request frame");
        const result = authenticate(frame.params ?? {}, nonce, ts);
        if (result.error) {
          send({ type: "res", id: frame.id, ok: false, error: result.error });
          return ws.close(1008, result.error.message.slice(0, 120));
        }
        session = result.session;
        clearTimeout(deadline);
        timers.delete(deadline);
        peers.add({ ws, session });
        return send({ type: "res", id: frame.id, ok: true, payload: hello(session) });
      }
      return send({ type: "res", id: frame.id, ...dispatch(session, frame.method, frame.params ?? {}) });
    });
  }

  function broadcastConfigChanged() {
    const frame = JSON.stringify({ type: "event", event: "config.changed", payload: { path: CONFIG_PATH, hash: configHash(), ts: Date.now() } });
    for (const peer of peers) {
      if (peer.session.scopes.length && peer.ws.readyState === peer.ws.OPEN) peer.ws.send(frame);
    }
  }

  const reject = (code, message, details) => ({ error: { code, message, ...(details ? { details } : {}) } });
  const rejectAuth = (detail, message, authReason, recommendedNextStep) =>
    reject("INVALID_REQUEST", message, { code: detail, authReason, canRetryWithDeviceToken: false, recommendedNextStep });
  const invalidParam = (field) =>
    reject("INVALID_REQUEST", `invalid connect params: at /client/${field}: must be equal to one of the allowed values`);

  function authenticate(params, nonce, ts) {
    if (!(params.minProtocol <= PROTOCOL && PROTOCOL <= params.maxProtocol)) {
      return reject("INVALID_REQUEST", "protocol mismatch", { code: "PROTOCOL_MISMATCH" });
    }
    const client = params.client ?? {};
    // Schema validation runs before any credential check (verified): wrong ids or modes never reach auth.
    if (!CLIENT_IDS.has(client.id)) return invalidParam("id");
    if (!CLIENT_MODES.has(client.mode)) return invalidParam("mode");
    if (params.role !== "operator") return reject("INVALID_REQUEST", "the mock gateway serves operator clients only");
    const auth = params.auth ?? {};
    const device = params.device;
    if (!device) {
      // Upstream: a valid shared token without a device identity connects with no scopes (QA §3).
      if (token && auth.token === token) return { session: { deviceId: null, method: "token", scopes: [] } };
      return reject("NOT_PAIRED", "device identity required", { code: "DEVICE_IDENTITY_REQUIRED" });
    }
    const deviceId = deviceIdFor(device.publicKey ?? "");
    if (device.id !== deviceId) return reject("INVALID_REQUEST", "device identity mismatch", { code: "DEVICE_AUTH_DEVICE_ID_MISMATCH", reason: "device-id-mismatch" });
    if (device.nonce !== nonce) return reject("INVALID_REQUEST", "device nonce mismatch", { code: "DEVICE_AUTH_NONCE_MISMATCH", reason: "device-nonce-mismatch" });
    if (device.signedAt !== ts) return reject("INVALID_REQUEST", "device signature expired", { code: "DEVICE_AUTH_SIGNATURE_EXPIRED", reason: "device-signature-stale" });
    const requested = Array.isArray(params.scopes) ? params.scopes : [];
    const signed = payloadV3({
      deviceId, clientId: client.id, clientMode: client.mode, role: params.role, scopes: requested,
      signedAtMs: device.signedAt, token: auth.token ?? auth.deviceToken ?? auth.bootstrapToken, nonce: device.nonce,
      platform: client.platform, deviceFamily: client.deviceFamily,
    });
    if (!verifyPayload(device.publicKey, signed, device.signature)) {
      return reject("INVALID_REQUEST", "device signature invalid", { code: "DEVICE_AUTH_SIGNATURE_INVALID", reason: "device-signature" });
    }
    const paired = state.paired.get(deviceId);
    let method = "none";
    if (auth.token !== undefined) {
      if (token && auth.token !== token) {
        return rejectAuth("AUTH_TOKEN_MISMATCH", "unauthorized: gateway token mismatch (provide gateway auth token)", "token_mismatch", "update_auth_credentials");
      }
      method = "token";
    } else if (auth.deviceToken !== undefined) {
      if (paired?.deviceToken !== auth.deviceToken) {
        return rejectAuth("AUTH_DEVICE_TOKEN_MISMATCH", "unauthorized: device token mismatch (rotate/reissue device token)", "device_token_mismatch", "update_auth_credentials");
      }
      method = "device-token";
    } else if (auth.bootstrapToken !== undefined) {
      // Verified: setup-code tokens authenticate role "node" only; for an operator they are simply invalid.
      return rejectAuth("AUTH_BOOTSTRAP_TOKEN_INVALID", BOOTSTRAP_INVALID, "bootstrap_token_invalid", "review_auth_configuration");
    } else if (token) {
      return rejectAuth("AUTH_TOKEN_MISSING", "unauthorized: gateway token missing (provide gateway auth token)", "token_missing", "update_auth_configuration");
    }
    const platform = normalizeMeta(client.platform);
    if (!paired && autoApprove) state.paired.set(deviceId, { deviceToken: `dt-${randomUUID()}`, scopes: requested, platform });
    const grant = state.paired.get(deviceId);
    if (!grant || grant.platform !== platform) {
      // Verified: one requestId per pending device across reconnects; platform is pinned at approval.
      const requestId = state.pending.get(deviceId)?.requestId ?? randomUUID();
      state.pending.set(deviceId, { requestId, scopes: requested, platform });
      const upgrade = Boolean(grant);
      return reject("NOT_PAIRED", upgrade ? "pairing required: device identity changed and must be re-approved" : "pairing required: device is not approved yet", {
        code: "PAIRING_REQUIRED",
        reason: upgrade ? "metadata-upgrade" : "not-paired",
        requestId,
        remediationHint: upgrade
          ? "Review the refreshed device details, then approve the pending request."
          : "Approve this device from the pending pairing requests.",
        deviceId,
        requestedRole: params.role,
        requestedScopes: requested,
        ...(upgrade ? { approvedRoles: ["operator"], approvedScopes: grant.scopes } : {}),
      });
    }
    const granted = requested.filter((scope) => grant.scopes.includes(scope) && scopes.includes(scope));
    return { session: { deviceId, method, scopes: granted, deviceToken: grant.deviceToken } };
  }

  function hello(session) {
    return {
      type: "hello-ok",
      protocol: PROTOCOL,
      server: { version, connId: randomUUID() },
      features: { methods: Object.keys(METHOD_SCOPE), events: ["connect.challenge", "tick", "health", "config.changed", "skills.changed"], capabilities: [] },
      snapshot: {
        presence: [{ host: displayName, mode: "gateway", reason: "self", version }],
        stateVersion: { presence: 1, health: 1 },
        uptimeMs: Math.round(process.uptime() * 1000),
        ...(session.scopes.length ? { authMode: token ? "token" : "none" } : {}),
      },
      auth: { method: session.method, role: "operator", scopes: session.scopes, deviceToken: session.deviceToken, issuedAtMs: Date.now() },
      policy: { maxPayload: 26214400, maxBufferedBytes: 52428800, tickIntervalMs: 30000 },
    };
  }

  const ok = (payload) => ({ ok: true, payload });
  const err = (code, message, details) => ({ ok: false, error: { code, message, ...(details ? { details } : {}) } });

  function dispatch(session, method, params) {
    if (!Object.hasOwn(METHOD_SCOPE, method)) return err("INVALID_REQUEST", `unknown method: ${method}`);
    const need = METHOD_SCOPE[method];
    if (need && !session.scopes.includes(need)) {
      return err("FORBIDDEN", `missing scope: ${need}`, { code: "MISSING_SCOPE", missingScope: need, requiredScopes: [need] });
    }
    switch (method) {
      case "health": return ok({ ok: true, ts: Date.now(), durationMs: 1, defaultAgentId: "main" });
      case "status": return ok({ runtimeVersion: version, sessions: { count: 0, defaults: { model: state.config.agents?.defaults?.model?.primary ?? null } } });
      case "models.list": return ok({ models: state.models, accountSelection: { kind: "automatic", label: "Automatic account selection" } });
      case "skills.status": return ok({ workspaceDir: "/mock/workspace", managedSkillsDir: "/mock/skills", agentId: "main", skills: state.skills });
      case "skills.install": return installSkill(params);
      case "skills.update": return updateSkill(params);
      case "config.get": return ok(configSnapshot());
      default: return patchConfig(params); // config.patch
    }
  }

  function installSkill({ source, slug, version: requestedVersion }) {
    if (source !== "clawhub" || typeof slug !== "string" || !slug) return err("INVALID_REQUEST", "invalid skills.install params");
    if (fail.has("install-unavailable") || slug.startsWith("missing")) {
      const message = `Skill "${slug}" not found on ClawHub. Run \`openclaw skills search ${slug}\` to find the right skill reference.`;
      return { ok: false, payload: { ok: false, error: message }, error: { code: "UNAVAILABLE", message } };
    }
    // Verified: "@owner/key" installs as "key", listed with source openclaw-workspace plus a clawhub object.
    const parts = slug.replace(/^@/, "").split("/");
    const skillKey = parts.at(-1);
    const installedVersion = requestedVersion ?? "1.0.0";
    state.skills = state.skills.filter((skill) => skill.skillKey !== skillKey);
    state.skills.push({
      name: skillKey, skillKey, source: "openclaw-workspace", bundled: false, disabled: false, eligible: true,
      clawhub: {
        status: "linked", valid: true, registry: "https://clawhub.ai", slug: skillKey,
        ownerHandle: parts.length > 1 ? parts[0] : "mock", installedVersion, installedAt: Date.now(),
      },
      skillCard: { present: false },
    });
    return ok({
      ok: true, message: `Installed ${skillKey}@${installedVersion}`, stdout: "", stderr: "", code: 0,
      slug: skillKey, version: installedVersion, targetDir: `/mock/workspace/skills/${skillKey}`,
    });
  }

  function updateSkill({ skillKey, enabled }) {
    if (typeof skillKey !== "string" || typeof enabled !== "boolean") {
      return err("INVALID_REQUEST", "invalid skills.update params: must have required property 'skillKey'");
    }
    // Verified: ok:true even for keys no skill has (the entry is written to config); skills.status catches up only
    // when config.changed fires.
    const entries = state.config.skills?.entries ?? {};
    state.config.skills = { ...state.config.skills, entries: { ...entries, [skillKey]: { ...entries[skillKey], enabled } } };
    revision += 1;
    later(() => {
      const skill = state.skills.find((entry) => entry.skillKey === skillKey);
      if (skill) skill.disabled = !enabled;
      broadcastConfigChanged();
    });
    return ok({ ok: true, skillKey, config: { enabled } });
  }

  const redact = (value) =>
    JSON.parse(JSON.stringify(value), (key, v) => ((key === "token" || key === "password") && typeof v === "string" ? REDACTED : v));

  function configSnapshot() {
    const view = redact(state.config);
    return { path: CONFIG_PATH, exists: true, raw: JSON.stringify(view, null, 2), parsed: view, config: view, valid: true, issues: [], warnings: [], hash: configHash() };
  }

  /** Dotted paths of a patch's leaves, e.g. {gateway:{port:1}} -> ["gateway.port"]. */
  function leafPaths(value, prefix = "") {
    if (value === null || typeof value !== "object" || Array.isArray(value)) return prefix ? [prefix] : [];
    return Object.entries(value).flatMap(([key, child]) => leafPaths(child, prefix ? `${prefix}.${key}` : key));
  }

  function patchConfig({ raw, baseHash }) {
    let patch;
    try {
      patch = JSON.parse(raw);
    } catch (e) {
      return err("INVALID_REQUEST", `SyntaxError: JSON5: ${e.message} (the mock parses JSON only)`);
    }
    // Verified messages; neither carries details.
    if (baseHash === undefined) {
      return err("INVALID_REQUEST", `config base hash required for ${leafPaths(patch)[0] ?? "<root>"}; re-run config.get and retry with baseHash`);
    }
    if (baseHash !== configHash()) return err("INVALID_REQUEST", "config changed since last load; re-run config.get and retry");
    const next = mergePatch(structuredClone(state.config), patch, state.config);
    const port = next.gateway?.port;
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      const message = Number.isInteger(port) && port > 65535
        ? "Too big: expected number to be <=65535 (maximum: 65535)"
        : "Invalid input: expected an integer between 1 and 65535";
      return err("INVALID_REQUEST", `invalid config: gateway.port: ${message}`, { issues: [{ path: "gateway.port", message }] });
    }
    state.config = next;
    revision += 1;
    later(() => broadcastConfigChanged());
    return ok({
      ok: true,
      path: CONFIG_PATH,
      hash: configHash(),
      config: redact(state.config),
      changedPaths: leafPaths(patch),
      sentinel: { persisted: true, payload: { kind: "config-patch", status: "ok" } },
    });
  }

  /** RFC 7396 merge patch; the redaction sentinel keeps the current secret (upstream "secret restoration"). */
  function mergePatch(target, patch, current) {
    if (patch === null || typeof patch !== "object" || Array.isArray(patch)) return patch;
    const out = target && typeof target === "object" && !Array.isArray(target) ? target : {};
    for (const [key, value] of Object.entries(patch)) {
      if (value === null) delete out[key];
      else if (value === REDACTED) out[key] = current?.[key];
      else out[key] = mergePatch(out[key], value, current?.[key]);
    }
    return out;
  }

  return {
    server,
    state,
    approveAll,
    listen(port = 0, host = "127.0.0.1") {
      return new Promise((resolve) => server.listen(port, host, () => resolve(server.address())));
    },
    close() {
      for (const timer of timers) clearTimeout(timer);
      timers.clear();
      for (const client of wss.clients) client.terminate();
      wss.close();
      server.closeAllConnections();
      return new Promise((resolve) => server.close(() => resolve()));
    },
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.env.LEGACY_REST === "1") {
    // Today's REST + JSON-RPC mock (it listens on import), kept only for ios-screenshots.yml until the iOS app
    // speaks protocol 4; the iOS plan's Task 20 deletes legacy-rest.mjs and this branch.
    await import("./legacy-rest.mjs");
  } else {
    const gateway = createMockGateway({
      token: process.env.TOKEN || null,
      scopes: (process.env.SCOPES || ALL_SCOPES.join(",")).split(",").map((s) => s.trim()).filter(Boolean),
      autoApprove: process.env.AUTO_APPROVE === "1",
      failModes: (process.env.FAIL_MODE || "").split(",").map((s) => s.trim()).filter(Boolean),
      displayName: process.env.MOCK_DISPLAY_NAME || "mock-gateway",
      version: process.env.MOCK_VERSION || MOCK_VERSION,
    });
    const address = await gateway.listen(Number(process.env.PORT || 18790), process.env.HOST || "0.0.0.0");
    console.log(`mock OpenClaw gateway (protocol ${PROTOCOL}) on ${address.address}:${address.port}`);
    console.log(`approve pending devices: curl -X POST http://127.0.0.1:${address.port}/__mock/devices/approve`);
  }
}
```

- [ ] **Step 5: Add the real-gateway probe (used by Task 26)**

`scripts/mock-gateway/probe-real.mjs`:
```js
#!/usr/bin/env node
// Re-checks a real OpenClaw gateway against the behaviour pinned in fixtures/gateway-protocol/2026.10.3 (run it when a
// new gateway release ships). Uses the conformance tests' client; prints frames, never tokens.
// usage: node probe-real.mjs <ws-url> <shared-token> [pair|no-token|config|skills]
//   pair      connect with a persistent identity (.probe-identity.json, gitignored) and print hello-ok.auth
//   no-token  credential-less connect from a never-seen device: does the gateway answer AUTH_TOKEN_MISSING (what
//             probeAuth assumes) or PAIRING_REQUIRED first? Not captured so far.
//   config  config.patch with a wrong and with no baseHash (both rejected; nothing changes)
//   skills  skills.update disable+enable of SKILL_KEY (default browser-automation) with the config.changed timing;
//           optional INSTALL_SLUG install
import fs from "node:fs";
import { connect } from "./lib/client.mjs";
import { newIdentity } from "./lib/device.mjs";
const [url, token, scenario = "pair"] = process.argv.slice(2);
if (!url || !token) {
  console.error("usage: node probe-real.mjs <ws-url> <shared-token> [pair|no-token|config|skills]");
  process.exit(2);
}
if (scenario === "no-token") {
  const probe = await connect(url, { identity: newIdentity() });
  console.log("credential-less connect, new device:", JSON.stringify(probe.hello ? probe.hello.auth : probe.error));
  probe.close();
  process.exit(0);
}
const identityFile = new URL("./.probe-identity.json", import.meta.url); // holds a private key: gitignored
const identity = fs.existsSync(identityFile) ? JSON.parse(fs.readFileSync(identityFile, "utf8")) : newIdentity();
fs.writeFileSync(identityFile, JSON.stringify(identity), { mode: 0o600 });
const print = (label, value) => console.log(label, JSON.stringify(value, (key, v) => (key === "deviceToken" ? "<redacted>" : v)));
const session = await connect(url, { identity, auth: { token } });
if (!session.hello) {
  print("connect error:", session.error);
  const requestId = session.error?.details?.requestId;
  if (requestId) console.log(`approve on the gateway host: openclaw devices approve ${requestId}`);
  process.exit(1);
}
print("hello-ok.auth:", session.hello.auth);
if (scenario === "config") {
  print("config.patch, stale baseHash:", await session.request("config.patch", { raw: "{}", baseHash: "stale" }));
  print("config.patch, no baseHash:", await session.request("config.patch", { raw: JSON.stringify({ gateway: { port: 18789 } }) }));
} else if (scenario === "skills") {
  const skillKey = process.env.SKILL_KEY ?? "browser-automation";
  for (const enabled of [false, true]) {
    const started = Date.now();
    print(`skills.update enabled=${enabled}:`, await session.request("skills.update", { skillKey, enabled }));
    const changed = await session.nextEvent("config.changed");
    console.log(`config.changed ${changed ? `after ${Date.now() - started} ms` : "not seen within 2 s"}`);
  }
  if (process.env.INSTALL_SLUG) {
    print("skills.install:", await session.request("skills.install", { source: "clawhub", slug: process.env.INSTALL_SLUG, force: false, timeoutMs: 120000 }));
  }
  const status = await session.request("skills.status");
  print("skills.status sources:", [...new Set(status.payload.skills.map((skill) => skill.source))]);
}
session.close();
```

- [ ] **Step 6: Package, ignore file, README, remove the old smoke test**

`scripts/mock-gateway/package.json`:
```json
{
  "name": "ghostcrab-mock-gateway",
  "version": "0.2.0",
  "private": true,
  "type": "module",
  "description": "Mock OpenClaw gateway speaking the real WS protocol 4 (ADR-0007), for conformance tests and on-device QA.",
  "engines": {
    "node": ">=22"
  },
  "scripts": {
    "start": "node server.mjs",
    "test": "node --test test/*.test.mjs"
  },
  "dependencies": {
    "ws": "^8.22.0"
  },
  "overrides": {
    "brace-expansion@1": "^1.1.16",
    "brace-expansion@2": "^2.1.2",
    "brace-expansion@5": "^5.0.7"
  }
}
```

Append to `scripts/mock-gateway/.gitignore`:
```
# probe-real.mjs device identity (contains an Ed25519 private key)
.probe-identity.json
```

`git rm scripts/mock-gateway/smoke-ws.mjs`

Replace `scripts/mock-gateway/README.md`:
````markdown
# mock-gateway

Stand-in OpenClaw gateway that speaks the **real** control plane of `ghcr.io/openclaw/openclaw` 2026.10.3
(WS protocol 4, see `docs/adr/0007-gateway-protocol-alignment.md`): `connect.challenge`, Ed25519-signed
`connect`, `PAIRING_REQUIRED` until approved, `req`/`res`/`event` frames with string ids and string error codes.
Its error shapes are checked against frames captured from the real gateway (`fixtures/gateway-protocol/2026.10.3/`).

## Start

```bash
cd scripts/mock-gateway
npm install
TOKEN=shared-token npm start          # :18790; unset TOKEN = auth mode none
```

Point GhostCrab at `http://<LAN-IP>:18790` (token `shared-token`). The first connect shows the approval panel;
approve the phone with:

```bash
curl -X POST http://127.0.0.1:18790/__mock/devices/approve
```

A setup code to test the QR explanation (GhostCrab recognises it and explains that setup codes cannot authorize an operator client; as on a real gateway, its token is refused for operators):

```bash
curl -X POST http://127.0.0.1:18790/__mock/setup-code
```

| Var | Default | Purpose |
|---|---|---|
| `PORT` | `18790` | avoids a real gateway on 18789 |
| `HOST` | `0.0.0.0` | `127.0.0.1` to keep it local |
| `TOKEN` | *(none: auth mode none)* | shared gateway token |
| `SCOPES` | `operator.read,operator.write,operator.admin` | the most a device can be granted |
| `AUTO_APPROVE` | *(off)* | `1` approves new devices immediately |
| `FAIL_MODE` | *(none)* | `install-unavailable`: every `skills.install` fails like an unknown ClawHub slug |
| `MOCK_DISPLAY_NAME` | `mock-gateway` | gateway host in `hello-ok` presence: the name the apps show (store screenshots use `Home Lab`) |
| `MOCK_VERSION` | `2026.10.3-mock` | `hello-ok.server.version` |
| `LEGACY_REST` | *(off)* | `1` serves the pre-ADR-0007 REST mock (`legacy-rest.mjs`, with its old `STATUS_AUTH` / `MOCK_HARDWARE` switches) for `ios-screenshots.yml` until the iOS plan's Task 20 removes it |

Methods: `health`, `status`, `models.list`, `skills.status`, `skills.install`, `skills.update`, `config.get`,
`config.patch` (`baseHash` required; `gateway.port` validated; `__OPENCLAW_REDACTED__` restores the secret).
Anything else answers `INVALID_REQUEST unknown method: …`, as upstream. Writes (`config.patch`, `skills.update`)
emit `config.changed` ~80 ms later, and `skills.status` reflects a `skills.update` only from then on, as upstream.
`client.id` must be `openclaw-android` or `openclaw-ios` and `client.mode` `ui` or `cli`; a paired device that
changes `platform` must be approved again.

## Tests

```bash
npm test
```

CI runs them in the `mock-gateway` job of `.github/workflows/ci.yml`.

## Probing a real gateway

`node probe-real.mjs ws://<host>:18789/ws <token> [pair|no-token|config|skills]` connects with a persistent device
identity (`.probe-identity.json`, gitignored) and prints frames with device tokens redacted. Run it when a new
gateway release ships, to re-check what the fixtures pin (`baseHash` errors, `skills.update` and the
`config.changed` timing, ClawHub skill entries).

## Not covered

TLS (`wss://`), JSON5 in `config.patch` (`raw` must be JSON), events beyond the challenge and `config.changed`,
scope/role upgrade requests, and `role: "node"` clients (the only ones setup codes serve).
````

- [ ] **Step 7: Run the conformance tests**

Run: `cd scripts/mock-gateway && npm install && npm test`
Expected: PASS (10 tests).

Then check the legacy mode still serves the old REST surface:
```bash
cd scripts/mock-gateway
LEGACY_REST=1 PORT=18795 node server.mjs & MOCK_PID=$!
sleep 2; curl -fsS http://127.0.0.1:18795/status; kill "$MOCK_PID"
```
Expected: the old `/status` JSON (`{"displayName":"Mock OpenClaw Gateway",…}`).

- [ ] **Step 8: Add the CI job**

In `.github/workflows/ci.yml`, add under `jobs:` after the `ci:` job:
```yaml
  mock-gateway:
    # Conformance of the protocol-4 mock gateway against the captured real-gateway frames (ADR-0007).
    runs-on: ubuntu-latest
    permissions:
      contents: read
    defaults:
      run:
        working-directory: scripts/mock-gateway
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-node@v7
        with:
          node-version: "24"
          cache: npm
          cache-dependency-path: scripts/mock-gateway/package-lock.json
      - run: npm ci
      - run: npm test
```

- [ ] **Step 9: Keep the store-screenshot jobs working**

`android-screenshots.yml` and `ios-screenshots.yml` (PR #131) start this mock with the old REST-only switches. The Android app is aligned in this same PR, so its job moves to the protocol-4 mock: `STATUS_AUTH` and `MOCK_HARDWARE` no longer exist (no `/status` probe, no hardware line), and without `AUTO_APPROVE=1` the app would sit on the approval panel instead of reaching the Dashboard. The iOS app still speaks REST until the iOS plan lands, so its job keeps today's mock through `LEGACY_REST=1`.

In `.github/workflows/android-screenshots.yml`, replace
```yaml
          # STATUS_AUTH: the app probes /status without the token to detect token auth.
          PORT=$GATEWAY_PORT HOST=127.0.0.1 TOKEN="$GATEWAY_TOKEN" STATUS_AUTH=1 \
            MOCK_DISPLAY_NAME="Home Lab" MOCK_VERSION="2026.10.1" \
            MOCK_HARDWARE="arm64 · 32 GB · Ubuntu 24.04" \
```
with
```yaml
          # AUTO_APPROVE: the emulator is a new device; skip the pairing approval for screenshots.
          PORT=$GATEWAY_PORT HOST=127.0.0.1 TOKEN="$GATEWAY_TOKEN" AUTO_APPROVE=1 \
            MOCK_DISPLAY_NAME="Home Lab" MOCK_VERSION="2026.10.1" \
```
In `.github/workflows/ios-screenshots.yml`, replace
```yaml
          # STATUS_AUTH: the app probes /status without the token to detect token auth.
          PORT=$PORT HOST=127.0.0.1 TOKEN="$GATEWAY_TOKEN" STATUS_AUTH=1 \
            MOCK_DISPLAY_NAME="$GATEWAY_NAME" MOCK_VERSION="2026.10.1" \
            MOCK_HARDWARE="arm64 · 32 GB · Ubuntu 24.04" \
```
with
```yaml
          # LEGACY_REST: the iOS app speaks the pre-ADR-0007 REST contract until the iOS protocol-alignment plan
          # lands (its Task 20 removes this). STATUS_AUTH: that app probes /status without the token.
          LEGACY_REST=1 PORT=$PORT HOST=127.0.0.1 TOKEN="$GATEWAY_TOKEN" STATUS_AUTH=1 \
            MOCK_DISPLAY_NAME="$GATEWAY_NAME" MOCK_VERSION="2026.10.1" \
            MOCK_HARDWARE="arm64 · 32 GB · Ubuntu 24.04" \
```
Both workflows keep their `curl …/health` readiness check (JSON in both modes). The Android AI screenshot step is skipped once the AI entry points are hidden (the test's `waitForText` returns `false` without recording a miss).

- [ ] **Step 10: Commit**

```bash
cd scripts/mock-gateway && npm install --package-lock-only && cd ../..
git add -A scripts/mock-gateway .github/workflows/ci.yml .github/workflows/android-screenshots.yml .github/workflows/ios-screenshots.yml   # includes the legacy-rest.mjs rename
git commit -m "test(mock-gateway): speak the real OpenClaw protocol 4" -m "Challenge, Ed25519-signed connect with the client-id/mode allow-lists, pairing approval with a stable requestId and platform pinning, device tokens, operator-refused setup codes, string error codes, skills.status/install/update with config.changed, config.get/patch with the verified baseHash errors. Conformance tests compare shapes with the captured 2026.10.3 frames and run in CI; probe-real.mjs re-checks a new gateway release."
```

---

### Task 26: ⚠ manual — verify against the real gateway and a device

**Files:**
- Modify: `docs/QA_REAL_GATEWAY_2026-10.md` (append §10)
- Possibly modify (only if the gateway release differs from 2026.10.3): `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ConfigWrites.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImpl.kt`, `scripts/mock-gateway/server.mjs`, `fixtures/gateway-protocol/`

Needs Docker Desktop, an Android emulator or device, and a human. The emulator reaches the host's Docker port at `10.0.2.2`, which the gateway treats as remote, so pairing is exercised.

- [ ] **Step 1: Start the real gateway** ⚠ manual

```bash
docker volume create openclaw-qa-state
docker create --name openclaw-qa -p 18789:18789 -v openclaw-qa-state:/home/node/.openclaw ghcr.io/openclaw/openclaw:latest
printf '%s' '{"gateway":{"mode":"local","port":18789,"bind":"lan","auth":{"mode":"token","token":"<pick a long random token>"}}}' > openclaw.json
docker cp openclaw.json openclaw-qa:/home/node/.openclaw/openclaw.json && rm openclaw.json
docker start openclaw-qa
docker inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' ghcr.io/openclaw/openclaw:latest
```
Record the version. The frames the plan relies on were pinned against `2026.10.3` (M5 plus the 2026-10-15 verification run, ADR-0007), so Step 2 is needed only for a different release.

- [ ] **Step 2: Check the one ordering never captured, and re-check the rest on a different release** ⚠ manual

Always run (a fresh identity each time; the gateway has never seen it):
```bash
cd scripts/mock-gateway
node probe-real.mjs ws://127.0.0.1:18789/ws '<token>' no-token
```
`probeAuth` assumes the answer is `AUTH_TOKEN_MISSING` (token gateway → Token). If it is `PAIRING_REQUIRED` instead, the probe would report None for a token gateway. In that case run the same mode against a gateway with `gateway.auth.mode: none` and compare the two `PAIRING_REQUIRED` frames: map on whatever differs (`details.reason`, say); if nothing differs, map `PAIRING_REQUIRED` to `AuthRequirement.Token` (asking for a token an open gateway does not need is the safe error). Save the frame(s) under `fixtures/gateway-protocol/2026.10.3/` as `err-no-token-new-device*.json` (tokens replaced) and pin the mapping in `GatewayConnectionManagerImplTest`. Then, only if the version is not `2026.10.3`:

```bash
cd scripts/mock-gateway
node probe-real.mjs ws://127.0.0.1:18789/ws '<token>' pair          # prints the requestId; then:
docker exec openclaw-qa node openclaw.mjs devices approve <requestId>
node probe-real.mjs ws://127.0.0.1:18789/ws '<token>' config
INSTALL_SLUG=@steipete/weather node probe-real.mjs ws://127.0.0.1:18789/ws '<token>' skills
```
Compare with `fixtures/gateway-protocol/2026.10.3/` (`err-config-stale-hash`, `err-config-base-hash-required`, `res-skills-update`, `evt-config-changed`, `res-skills-install`, the `weather` entry of `res-skills-status`). On any difference, add a `fixtures/gateway-protocol/<version>/` set (tokens replaced with `dt-test-0001`), adapt `ConfigWrites.isStaleBaseHash()`, `skillSourceOf()` or the mock, and record it.

- [ ] **Step 3: Run the app against it** ⚠ manual (emulator or device)

`./gradlew installDebug`, then check each item and note pass/fail:
1. Manual Entry `http://10.0.2.2:18789` with the token → the approval panel shows a request id and `openclaw devices approve <id>`; `docker exec openclaw-qa node openclaw.mjs devices approve <id>` → Dashboard within ~5 s.
2. Dashboard: version `2026.10.3`, gateway host name, capabilities `config · models · skills`, **no** "no auth" banner.
3. Disconnect, reconnect from the saved profile → no approval panel (device token reused).
4. Wrong token → error shows the URL, `AUTH_TOKEN_MISMATCH` and `GatewayConnectAuthException`; no approval panel.
5. Config editor: the `gateway` section opens typed (port 18789, bind lan, auth token, token field empty). Change a hot-reloadable value in the raw editor (find one with `docker exec openclaw-qa node openclaw.mjs gateway call config.schema.lookup --params '{"path":"agents.defaults"}'`, `reloadKind: "hot"`), save, reload, change it back. Do **not** change `gateway.port`/`bind` on the live gateway (that restarts it).
6. Concurrent edit: change the same value with `docker exec … gateway call config.patch` between load and save → the editor reloads with the concurrent-edit message.
7. Models: empty list without a provider. If you configure one, activating a model makes `docker exec openclaw-qa node openclaw.mjs models status` show it as default.
8. Installed Skills (debug build): 51 skills, disabled ones marked; Disable one → the row shows "disabled" right after the write (the list is re-read after `config.changed`).
9. `adb logcat -d | grep -iE "bearer|deviceToken|bootstrapToken|<token>"` → no output for the whole session.
10. `docker stop openclaw-qa` → Dashboard shows Degraded within 60 s.
11. QR: `docker exec openclaw-qa node openclaw.mjs qr --url ws://<host LAN IP>:18789/ws` prints a setup-code QR; scan it on a **device** → the scanner explains that setup codes pair node apps, names `http://<host LAN IP>:18789` and points to the gateway token and `openclaw devices approve`; nothing connects and nothing is stored.
12. Store screenshots: `gh workflow run android-screenshots.yml` (uses this mock with `AUTO_APPROVE=1`, Task 25 Step 9), then check the artifact: `05-dashboard` shows `Home Lab` and the `hello-ok` version, no AI screenshot (action hidden). Commit refreshed PNGs under `screenshots/store/android/` only if they look right.

- [ ] **Step 4: Record and commit**

Append `## 10. Post-alignment verification (ADR-0007, <date>)` to `docs/QA_REAL_GATEWAY_2026-10.md` with the image digest/version, any Step 2 differences (tokens redacted), and the Step 3 results per item.

```bash
git add docs/QA_REAL_GATEWAY_2026-10.md fixtures/gateway-protocol app/src scripts/mock-gateway
git commit -m "docs: verify the protocol alignment against the real gateway" -m "Records the post-ADR-0007 device pass (and, for a release other than 2026.10.3, what probe-real.mjs found)."
```

---

### Task 27: Docs, changelog, CLAUDE.md

**Files:**
- Modify: `docs/adr/0002-auth-mode-probing.md`, `docs/adr/0004-gateway-proxied-ai.md`, `docs/adr/0005-additive-repository-methods.md`, `CHANGELOG.md`, `CLAUDE.md`, `docs/IMPLEMENTATION_PLAN.md`

- [ ] **Step 1: Mark the superseded decisions** (the only place the ADR-002 / ADR-004 status lines change; the iOS plan does not touch them)

In `docs/adr/0002-auth-mode-probing.md` replace `**Status:** Accepted` with:
```markdown
**Status:** Superseded by ADR-0007 (2026-10-15). The real gateway serves HTML at `/status` to every caller; auth is detected from the WS `connect` outcome instead.
```
In `docs/adr/0004-gateway-proxied-ai.md` replace `**Status:** Accepted` with:
```markdown
**Status:** Accepted; amended by ADR-0007 (2026-10-15): OpenClaw has no recommendation API, so the feature is disabled (`AI_PRO_ENABLED = false`) until a `chat.send`-based design gets its own ADR. The rule "no provider keys on the device" stands.
```
In `docs/adr/0005-additive-repository-methods.md`, after the last instance list, add:
```markdown

Instances on `feat/protocol-alignment-android` (ADR-0007, Android):

- `InstalledSkillRepository.setSkillEnabled()`
- `InstalledSkill.disabled` (additive model field with a default)
```

- [ ] **Step 2: Changelog**

In `CHANGELOG.md`, directly under `## [Unreleased]`, add:
```markdown
### Android

#### Changed

- **Speaks the real OpenClaw gateway protocol** (ADR-0007): one WebSocket control plane per session (`connect.challenge` → Ed25519-signed `connect` → `hello-ok`), `req`/`res`/`event` frames with string ids and string error codes. Only `GET /health` is still HTTP, and no credential is sent over HTTP.
- **Device pairing**: each install has an Ed25519 identity (Tink, stored encrypted). A new phone shows an approval panel with the request id and `openclaw devices approve <id>`, and retries every 5 s until approved. The gateway's per-device token is stored per gateway and reused.
- **Auth detection** comes from the connect handshake (ADR-002 superseded): token gateways are reported as such, and a wrong token fails with the URL, `AUTH_TOKEN_MISMATCH` and the exception class.
- **Config editor** reads `config.get` and writes `config.patch` with a `baseHash` guard; the typed `gateway` form follows the real schema (port, bind, mode, auth mode none/token/password/trusted-proxy).
- **Models** come from `models.list`; activating one sets `agents.defaults.model.primary`.
- **Skills** use `skills.status` / `skills.install`; OpenClaw cannot remove skills, so the screen offers Disable (`skills.update`).

#### Added

- `ws(s)://` gateway URLs in QR codes. `openclaw qr` setup codes (bare or `oc-pair://`) are recognised and explained: they pair OpenClaw node apps and cannot authorize an operator client, so the scanner points to the gateway token and `openclaw devices approve` instead.
- Copy telling Linux, Windows and Docker users to run `openclaw plugins enable bonjour`.
- `scripts/mock-gateway` now speaks protocol 4 and has conformance tests in CI.

#### Removed

- AI Recommendations are hidden: OpenClaw has no recommendation API.
- The speculative REST client (`/status`, `/config`, `/api/models/*`, `/api/ai/recommend`) and the JSON-RPC 2.0 client.
```

- [ ] **Step 3: CLAUDE.md**

Replace the `data/ws/` row of the package table with:
```markdown
| `data/gateway/` | Protocol-4 control plane (ADR-0007): `GatewayFrames` codec, `GatewayRpcClient` (string-id correlation, events, challenge), `DeviceIdentity` (Ed25519 via Tink), `DeviceAuthPayload` (v3 signature), `GatewayHandshake` / `HelloOk`, `DeviceTokenStore` |
| `data/ws/` | `WsSession` seam + `KtorWsSession` adapter |
```
Replace the `data/api/` row with:
```markdown
| `data/api/` | `OpenClawApiClient` (Ktor): `GET /health` and opening the WS; carries no credentials |
```
Replace the `data/storage/` row with:
```markdown
| `data/storage/` | `ConnectionProfileStore` (DataStore metadata + EncryptedSharedPreferences profile and device tokens), `DeviceIdentityStore` (encrypted Ed25519 seed) |
```
Replace the "Upstream gateway quirk" bullet with:
```markdown
- **Gateway protocol (ADR-0007)**: the real gateway (`ghcr.io/openclaw/openclaw`, protocol 4) is WebSocket-only for control: `connect.challenge` → signed `connect` → `hello-ok`, then `req`/`res` RPCs (`health`, `config.get`/`config.patch`, `models.list`, `skills.status`/`skills.install`/`skills.update`). HTTP has only `/health` and `/ready` as JSON; `/status` and `/config` are the Control UI's HTML. New remote devices need `openclaw devices approve <requestId>`. Frames captured from 2026.10.3 live in `fixtures/gateway-protocol/` — test against them, never against a guessed shape.
```
Replace the "BuildConfig fields" bullet with:
```markdown
- **BuildConfig fields**: `AI_PRO_ENABLED` (Boolean, `false`: OpenClaw has no recommendation API, ADR-0007; gates the AI route and Dashboard action), `SKILLS_INSTALL_ENABLED` (debug only), `GIT_SHA` (baked in at build, shown on About screen).
```

- [ ] **Step 4: Implementation plan bookkeeping**

In `docs/IMPLEMENTATION_PLAN.md`:
- §5: append ` — **Resolved by ADR-0007** (2026-10-15).` to Open Questions 1, 2 and 5.
- §6: add the row
```markdown
| 2026-10-15 | 2, 6, 7, 8, 12 | Clients move to the real WS protocol 4 (ADR-0007): handshake + device pairing, `config.get`/`config.patch`, `models.list` + `agents.defaults.model.primary`, `skills.status`/`install`/`update`; AI recommendations disabled; mock gateway rewritten. | M5 showed every speculative REST/JSON-RPC path fails against `ghcr.io/openclaw/openclaw` 2026.10.3 (`docs/QA_REAL_GATEWAY_2026-10.md`). | stevenfackley |
```
- Progress Ledger: add the row
```markdown
| — | Protocol alignment, Android (roadmap M7) | 🟢 Done | <start> | <end> | PR #<n> | — | Plan `docs/superpowers/plans/2026-10-15-protocol-alignment-android.md`, ADR-0007. |
```

- [ ] **Step 5: Commit, push, PR**

```bash
git add docs/adr/0002-auth-mode-probing.md docs/adr/0004-gateway-proxied-ai.md docs/adr/0005-additive-repository-methods.md CHANGELOG.md CLAUDE.md docs/IMPLEMENTATION_PLAN.md
git commit -m "docs: record the Android protocol alignment" -m "ADR-002 superseded, ADR-004 amended, changelog, CLAUDE.md package map and gateway notes, implementation-plan ledger (ADR-0007)."
git push -u origin feat/protocol-alignment-android
gh pr create --title "feat(android): align with the real OpenClaw gateway protocol (ADR-0007)" --body "Implements docs/superpowers/plans/2026-10-15-protocol-alignment-android.md. Real-gateway verification results: docs/QA_REAL_GATEWAY_2026-10.md section 10."
```

---

## Self-review

Coverage of every M5 mismatch (`docs/QA_REAL_GATEWAY_2026-10.md` §2, §3, §8):

| Mismatch | Task |
|---|---|
| `/status` HTML: auth probe always None, wrong token connects (P0 #1) | 9, 10 |
| Identity/version/capabilities from `/status` (P0 #2) | 9, 10 |
| JSON-RPC envelope, numeric ids, numeric error codes (#3) | 2, 3, 4 |
| No challenge handling, connect frame, hello-ok (#4) | 4, 9, 10 |
| Device identity, v3 signature, device token persistence (#5) | 5, 6, 7, 8, 10 |
| Pairing UX (`PAIRING_REQUIRED`) (#6) | 19, 20, 21 |
| `auth.whoami` → `hello-ok.auth.scopes` (#7) | 12 |
| `skills.list`/progress/`uninstall` → `skills.status`/single res/`skills.update` (#8) | 13, 14 |
| `/config` + `PATCH` → `config.get`/`config.patch` + `baseHash` (#9) | 15, 16 |
| `/api/models/*` → `models.list` + `agents.defaults.model.primary` (#10) | 17 |
| AI recommend has no upstream equivalent (#11) | 18 |
| `openclaw qr` setup code and `ws(s)://` (#12): setup codes recognised and explained, never used (node-only tokens) | 22 |
| Bonjour opt-in, TXT has no `version` (#13) | 23 (version now comes from `hello-ok`, Task 10) |
| Mock gateway validates a contract that does not exist (#14) | 25 |
| Bearer on the WS upgrade is useless; logcat bearer check | 10, 24 |
| Behaviour pinned by the 2026-10-15 verification run: client-id/mode allow-list and platform pinning; stable `requestId`, 1008 after `PAIRING_REQUIRED`; node-only bootstrap tokens; `baseHash` errors, validation `issues`, `config.patch` success; `skills.update` + `config.changed`; ClawHub install entry | 1, 11, 13, 14, 15, 19, 22, 25 |
| A new gateway release | 26 (probe-real.mjs re-check) |
| New device without a credential: `AUTH_TOKEN_MISSING` before `PAIRING_REQUIRED` (assumed, ADR-0007 item 8) | 26 (Step 2, `no-token`) |
| `ios-screenshots.yml` still needs the REST mock until the iOS plan lands | 25 (`LEGACY_REST=1`; the iOS plan's Task 20 removes it) |
