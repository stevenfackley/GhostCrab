# Gateway Protocol Alignment (iOS) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the iOS / Mac Catalyst client a WebSocket control plane that speaks the real OpenClaw gateway protocol (WS protocol 4: `connect.challenge` → Ed25519-signed `connect` → `hello-ok`, device pairing, `req`/`res`/`event` frames) and move connect, auth detection, scopes, config, models and skills onto it, as decided in ADR-0007.

**Architecture:** iOS has no WebSocket client today (every feature calls a REST path that is `404` or HTML on the real gateway). A new `Data/Gateway/` layer adds a frame codec, a `GatewayWebSocket` seam (`URLSessionWebSocketTask` in production, a scripted actor in tests), the `GatewayRPCClient` actor (string-id correlation, events, challenge capture, timeouts), a CryptoKit `Curve25519.Signing` device identity kept in the Keychain, and `GatewayHandshake`. `GatewayConnectionManagerImpl` opens one socket per session after a `/health` check; repositories call RPCs on it. Frozen `Domain/Repository` protocols keep their signatures; additive members follow ADR-0005. A pairing-approval card replaces the error path for `PAIRING_REQUIRED`.

**Tech Stack:** Swift 6 (`SWIFT_STRICT_CONCURRENCY: complete`), SwiftUI, `@Observable @MainActor` ViewModels, `URLSessionWebSocketTask`, CryptoKit (`Curve25519.Signing`, `SHA256`), Security (Keychain), XcodeGen, Swift Testing (`@Suite`, `@Test`, `#expect`, `#require`), iOS 18 / Mac Catalyst 17.

---

## Read first

- `docs/adr/0007-gateway-protocol-alignment.md` (the decision), `docs/QA_REAL_GATEWAY_2026-10.md` §3, §5, §8 (the evidence), `docs/adr/0005-additive-repository-methods.md`, `docs/adr/0006-ios-ats-local-networking.md` (ATS applies to `ws://` exactly as to `http://`).
- The Android plan `docs/superpowers/plans/2026-10-15-protocol-alignment-android.md` implements the same design in Kotlin; its "Read first" section lists the protocol facts. The test vectors in this plan are the same ones (RFC 8032 TEST 1, the fingerprint the real gateway accepted, a Node-26-produced signature).
- Pinned by the verification run of 2026-10-15 against the same 2026.10.3 image (frames in Task 1; ADR-0007 "Verification"):
  - `client.id` and `client.mode` are allow-lists checked before auth (`ghostcrab-ios` or `mode: "operator"` → `INVALID_REQUEST invalid connect params …`, close 1008). On a paired device a different `platform` (case-insensitive) re-triggers pairing (`metadata-upgrade`); a different `client.id` does not. GhostCrab sends one constant identity: `openclaw-ios`, `ui`, `ios` (also on Mac Catalyst), no `deviceFamily`.
  - Pairing: the `requestId` is stable across reconnects; the gateway closes an unpaired socket with 1008 right after `PAIRING_REQUIRED`; `device.pair.resolved` reaches only an already-paired admin socket; a socket that never sends `connect` is closed after ~15 s.
  - Setup-code bootstrap tokens authenticate only `role: "node"`; over `ws://` they yield a node token with `scopes: []` and no operator handoff. GhostCrab (an operator client) never connects with one; it explains setup codes instead (Task 18).
  - `config.patch`: stale or unknown `baseHash` → `"config changed since last load; re-run config.get and retry"`, missing → `"config base hash required for <path>; …"` (both `INVALID_REQUEST`, no `details`); validation → `"invalid config: <path>: …"` with `details.issues[]`; success → `{ok, path, hash, config, changedPaths, sentinel}`.
  - `skills.update` → `{ok:true, skillKey, config:{enabled}}`, also for unknown keys; `skills.status` is stale until the `config.changed` event (~80 ms later, sometimes before the response); there is no `skills.changed` event. A ClawHub install answers `{ok, message, slug, version, …}` and is listed with `source: "openclaw-workspace"` plus `clawhub {slug, installedVersion, installedAt, …}`.
- Conventions (same as `docs/superpowers/plans/2026-10-13-ios-finish.md`):
  - This plan was written on Windows. Every build and test step is marked **⚠ must run on the Mac Mini (steve-mac-mini, Xcode)**. Steps that need a person, a real gateway or a device are marked **⚠ manual**.
  - Always `cd ios && xcodegen generate` before `xcodebuild`; new files only appear after regenerating.
  - Test command (replace `<Suite>` with the `@Suite` struct name):
    `cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/<Suite> -quiet`
    On Xcode 26 use any name from `xcrun simctl list devices available | grep iPhone`.
  - Every build rewrites `ios/GhostCrab/App/BuildInfo.swift`. Never stage it: each commit step starts with `git checkout -- ios/GhostCrab/App/BuildInfo.swift`.
  - Do not change existing signatures in `ios/GhostCrab/Domain/Repository/*.swift`. New members/protocols there are additive and carry `Additive (v1.1)` in their doc comment (ADR-0005).
  - Branch `git switch -c feat/protocol-alignment-ios`. One commit per task, Conventional Commits, subject + body only, no co-author trailer and no AI attribution. One PR at the end, squash-merged.
  - Baseline first: run the whole unit suite (`-only-testing:GhostCrabTests`). If it is red on a clean checkout, stop and report that.
  - Secrets: never log a token, bootstrap token, device token or seed. Every new type holding one redacts `description`, `debugDescription` and its mirror. Test strings `shared-token`, `dt-test-0001`, `bt-test-0001` are dummies.
  - `ITSAppUsesNonExemptEncryption` stays `false`: Ed25519 is used only for authentication, which is exempt.

## Task order

1. Tasks 1–8 build the protocol layer bottom-up; nothing uses it until Task 9.
2. Task 9 switches `GatewayConnectionManagerImpl` to the WS handshake (repositories still compile through `requireClient()`).
3. Tasks 10–14 move scopes, skills, config, models and AI onto the session socket.
4. Tasks 15–19 are the UI (pairing loop, approval card on both screens, setup-code explanation, discovery copy).
5. Task 20 deletes the dead REST code. Task 21 is the ⚠ manual real-gateway pass. Task 22 is docs.

If the Android plan already ran, `fixtures/gateway-protocol/2026.10.3/` and `scripts/mock-gateway` (protocol 4) exist; Task 21 can also use the mock.

## File structure

| File | Status | Responsibility |
|---|---|---|
| `fixtures/gateway-protocol/2026.10.3/*.json` | create (if absent) | 23 frames from the real gateway; shared with Android and the mock |
| `ios/project.yml` | modify | fixtures folder as a test resource |
| `ios/GhostCrab/Domain/Error/GatewayError.swift` | modify | `rpc`, `methodUnsupported`, `missingScope`, `pairingRequired`, `connectRejected`, `protocolViolation` |
| `ios/GhostCrab/Data/Gateway/GatewayFrames.swift` | create | frame types, codec, `AnyCodable` helpers |
| `ios/GhostCrab/Data/Gateway/GatewayErrorMapping.swift` | create | error shape → `GatewayError` |
| `ios/GhostCrab/Data/Gateway/GatewayWebSocket.swift` | create | socket seam + `URLSessionWebSocketTask` implementation + factory |
| `ios/GhostCrab/Data/Gateway/GatewayRPCClient.swift` | create | actor: correlation, events, challenge, timeouts |
| `ios/GhostCrab/Data/Gateway/DeviceIdentity.swift` | create | CryptoKit Ed25519 identity, `Base64URL` |
| `ios/GhostCrab/Data/Gateway/DeviceAuthPayload.swift` | create | v3 signature payload |
| `ios/GhostCrab/Data/Gateway/GatewayHandshake.swift` | create | client info, credential selection, challenge, hello, `connect` params |
| `ios/GhostCrab/Data/Gateway/GatewayWebSocketURL.swift` | create | `http(s)` base → `ws(s)://…/ws`, cleartext policy for sockets |
| `ios/GhostCrab/Data/Storage/Keychain.swift` | modify | configurable service |
| `ios/GhostCrab/Data/Storage/DeviceIdentityStore.swift`, `DeviceTokenStore.swift` | create | Keychain seed; per-gateway device tokens |
| `ios/GhostCrab/Data/Impl/*` | rewrite | manager, scope probe, skills, config, models, AI on the socket |
| `ios/GhostCrab/UI/Connection/PairingApproval.swift`, `PairingApprovalCard.swift` | create | retry loop, prompt, card |
| `ios/GhostCrab/UI/Connection/ManualEntry*`, `ConnectionPicker*`, `QrScanViewModel.swift`, `Domain/Util/PairingLink.swift` | modify | pairing UI, setup codes recognised and explained (never used) |
| `ios/GhostCrabTests/Support/*`, `ios/GhostCrabTests/Gateway/*` | create | fixtures loader, scripted socket, stores, tests |

---

### Task 1: Real-gateway protocol fixtures as test resources

**Files:**
- Create (if absent): `fixtures/gateway-protocol/2026.10.3/*.json`
- Modify: `ios/project.yml` (target `GhostCrabTests` → `sources`), `.github/workflows/ios-pr.yml` (`paths`)
- Create: `ios/GhostCrabTests/Support/GatewayFixtures.swift`
- Test: `ios/GhostCrabTests/Gateway/GatewayFixturesTests.swift`

- [ ] **Step 1: Create the fixture files (skip if the Android plan already did)**

Frames from the M5 captures against 2026.10.3 (`docs/QA_REAL_GATEWAY_2026-10.md` §3, §9), plus nine from the verification run of 2026-10-15 against the same image (client-id allow-list, metadata-upgrade pairing, `config.patch` errors and success, `skills.update`, `config.changed`, `skills.install`; `res-skills-status` gained the ClawHub-installed `weather` entry). Trimmed: `hello-ok.features` keeps the methods/events GhostCrab uses, `snapshot` keeps only the self presence entry, `skills.status` keeps two of 51 skills, `config.get` keeps the fields GhostCrab reads. Replaced: the issued device token (`dt-test-0001`); `recoveryScope` removed. Everything else is byte-for-byte what the gateway sent. The Android plan's Task 1 creates the same 23 files; if they exist, check them with `git diff --no-index` against the contents below and skip this step.

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

- [ ] **Step 2: Bundle the fixture folder into the test target**

In `ios/project.yml`, replace the `GhostCrabTests` line `sources: [GhostCrabTests]` with:
```yaml
    sources:
      - path: GhostCrabTests
      # Frames captured from the real gateway (ADR-0007), shared with Android and the mock gateway.
      # A folder reference: the bundle gets a "2026.10.3" directory.
      - path: ../fixtures/gateway-protocol/2026.10.3
        type: folder
        buildPhase: resources
```

In `.github/workflows/ios-pr.yml`, add to `on.pull_request.paths` (a fixture change must run the iOS lane too):
```yaml
      - "fixtures/gateway-protocol/**"
```

- [ ] **Step 3: Write the failing test**

`ios/GhostCrabTests/Gateway/GatewayFixturesTests.swift`:
```swift
import Foundation
import Testing
@testable import GhostCrab

@Suite("Gateway fixtures")
struct GatewayFixturesTests {

    @Test(arguments: GatewayFixtures.all)
    func everyFixtureIsAResOrEventFrame(name: String) throws {
        let object = try #require(try JSONSerialization.jsonObject(with: Data(try GatewayFixtures.text(name).utf8)) as? [String: Any])
        let type = object["type"] as? String
        #expect(type == "res" || type == "event", "\(name) has type \(type ?? "nil")")
    }
}
```

- [ ] **Step 4: Run it to make sure it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run: `cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests/GatewayFixturesTests -quiet`
Expected: FAIL to compile, `cannot find 'GatewayFixtures' in scope`.

- [ ] **Step 5: Write the loader and shared test helpers**

`ios/GhostCrabTests/Support/GatewayFixtures.swift`:
```swift
import CryptoKit
import Foundation
@testable import GhostCrab

/// Frames captured from `ghcr.io/openclaw/openclaw` 2026.10.3 during roadmap M5 (`docs/QA_REAL_GATEWAY_2026-10.md`) and
/// the 2026-10-15 verification run, trimmed, issued device token replaced by `dt-test-0001`. Bundled from
/// `fixtures/gateway-protocol/2026.10.3/`.
enum GatewayFixtures {

    static let version = "2026.10.3"

    static let all = [
        "challenge", "hello-ok-paired", "hello-ok-noscope",
        "err-pairing-required", "err-token-mismatch", "err-token-missing", "err-device-identity-required",
        "err-missing-scope", "err-unknown-method", "err-skill-not-found",
        "res-health", "res-skills-status", "res-models-list", "res-config-get",
        "err-client-id-invalid", "err-pairing-metadata-upgrade",
        "err-config-stale-hash", "err-config-base-hash-required", "err-config-invalid", "res-config-patch",
        "res-skills-update", "evt-config-changed", "res-skills-install",
    ]

    struct Missing: Error, CustomStringConvertible {
        let name: String
        var description: String { "missing test fixture \(name).json (fixtures folder not bundled? run xcodegen)" }
    }

    private final class BundleToken {}

    /// - Parameter name: Fixture name without `.json`.
    /// - Returns: The raw frame text.
    static func text(_ name: String) throws -> String {
        guard let url = Bundle(for: BundleToken.self).url(forResource: name, withExtension: "json", subdirectory: version) else {
            throw Missing(name: name)
        }
        return try String(contentsOf: url, encoding: .utf8)
    }
}

/// RFC 8032 §7.1 TEST 1 and values derived from it.
enum TestKeys {
    static let rfcSeed = Data(hex: "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    static let rfcPublicKey = Data(hex: "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    static let rfcDeviceId = "21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9"
    static let operatorScopes = ["operator.read", "operator.write", "operator.admin"]
}

extension Data {
    /// - Parameter hex: An even-length hex string.
    init(hex: String) {
        var bytes: [UInt8] = []
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            bytes.append(UInt8(hex[index..<next], radix: 16) ?? 0)
            index = next
        }
        self.init(bytes)
    }
}

/// Parses a JSON object for assertions.
func jsonObject(_ text: String) throws -> [String: Any] {
    try #require(try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
}

/// Runs `body` and returns the `GatewayError` it threw, or `nil`.
func gatewayError(_ body: () async throws -> Void) async -> GatewayError? {
    do {
        try await body()
        return nil
    } catch let error as GatewayError {
        return error
    } catch {
        return nil
    }
}
```

- [ ] **Step 6: Run the test to verify it passes** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run the Step 4 command. Expected: PASS (23 cases).

- [ ] **Step 7: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add fixtures/gateway-protocol ios/project.yml .github/workflows/ios-pr.yml ios/GhostCrabTests/Support/GatewayFixtures.swift ios/GhostCrabTests/Gateway/GatewayFixturesTests.swift
git commit -m "test(ios): bundle the real-gateway protocol fixtures" -m "Shared fixtures/gateway-protocol/2026.10.3 folder (ADR-0007) as a test resource, plus RFC 8032 keys and JSON helpers for the protocol tests."
```

---

### Task 2: `GatewayError` cases for the WS protocol

**Files:**
- Modify: `ios/GhostCrab/Domain/Error/GatewayError.swift`
- Modify: `ios/GhostCrab/Domain/Model/SkillInstallError.swift`, `ios/GhostCrab/Data/Impl/InstalledSkillRepositoryImpl.swift` (exhaustive switch), `ios/GhostCrab/UI/InstalledSkills/InstalledSkillsScreen.swift` (label)
- Modify: `ios/GhostCrabTests/Api/OpenClawAPIClientDecodingTests.swift` (one expectation)
- Test: `ios/GhostCrabTests/Gateway/GatewayErrorCasesTests.swift`

- [ ] **Step 1: Write the failing test**

`ios/GhostCrabTests/Gateway/GatewayErrorCasesTests.swift`:
```swift
import Foundation
import Testing
@testable import GhostCrab

@Suite("GatewayError protocol-4 cases")
struct GatewayErrorCasesTests {

    private let url = "ws://192.168.1.50:18789/ws"

    @Test func pairingRequiredIsRetryableAndNamesTheApproveCommand() {
        let error = GatewayError.pairingRequired(url: url, requestId: "req-1", reason: "not-paired", requestedScopes: ["operator.read"])
        #expect(error.isRetryable)
        #expect(error.errorDescription?.contains("openclaw devices approve req-1") == true)
    }

    @Test func connectRejectedNamesUrlAndCode() {
        let error = GatewayError.connectRejected(url: url, detailCode: "AUTH_TOKEN_MISMATCH", message: "token mismatch", recommendedNextStep: nil)
        #expect(!error.isRetryable)
        #expect(error.errorDescription?.contains(url) == true)
        #expect(error.errorDescription?.contains("AUTH_TOKEN_MISMATCH") == true)
    }

    @Test func rpcHonoursTheRetryableHint() {
        #expect(GatewayError.rpc(url: url, method: "x", code: "UNAVAILABLE", detailCode: nil, message: "busy", retryable: true).isRetryable)
        #expect(!GatewayError.rpc(url: url, method: "x", code: "UNAVAILABLE", detailCode: nil, message: "busy", retryable: false).isRetryable)
    }

    @Test func installMappingUsesTheNewCases() {
        #expect(GatewayError.missingScope(url: url, method: "skills.install", scope: "operator.admin").toSkillInstallError()
            == .unauthorized(missingScope: "operator.admin"))
        let notFound = GatewayError.rpc(url: url, method: "skills.install", code: "UNAVAILABLE", detailCode: nil,
                                        message: "Skill \"x\" not found on ClawHub.", retryable: false)
        #expect(notFound.toSkillInstallError(slug: "x") == .notFound(slug: "x"))
        #expect(GatewayError.rpc(url: url, method: "skills.install", code: "INVALID_REQUEST", detailCode: nil, message: "bad", retryable: false)
            .toSkillInstallError() == .protocol(code: "INVALID_REQUEST", message: "bad"))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run the test command with `GatewayErrorCasesTests`. Expected: FAIL to compile (`type 'GatewayError' has no member 'pairingRequired'`).

- [ ] **Step 3: Add the cases**

In `GatewayError.swift`, add after `case cleartextBlocked(url: String, host: String)`:
```swift

    /// A gateway RPC answered `ok:false` with a code GhostCrab has no more specific case for (ADR-0007).
    ///
    /// - Parameters:
    ///   - url: The gateway WebSocket endpoint.
    ///   - method: The RPC method.
    ///   - code: Top-level error code (e.g. `UNAVAILABLE`).
    ///   - detailCode: `error.details.code`, when present.
    ///   - message: The server's message, verbatim.
    ///   - retryable: The server's `retryable` hint.
    case rpc(url: String, method: String, code: String, detailCode: String?, message: String, retryable: Bool)

    /// The gateway does not implement `method` (`INVALID_REQUEST unknown method: …`).
    case methodUnsupported(url: String, method: String)

    /// The session lacks the operator scope `method` needs (`FORBIDDEN` / `MISSING_SCOPE`).
    case missingScope(url: String, method: String, scope: String)

    /// The device must be approved on the gateway host (`NOT_PAIRED` / `PAIRING_REQUIRED`). Retryable: the same
    /// connect succeeds once `openclaw devices approve <requestId>` ran.
    case pairingRequired(url: String, requestId: String?, reason: String, requestedScopes: [String])

    /// The `connect` handshake's credential or device proof was rejected (`AUTH_*` / `DEVICE_*` detail codes).
    case connectRejected(url: String, detailCode: String, message: String, recommendedNextStep: String?)

    /// The gateway answered, but not in a shape this client understands (malformed challenge or hello,
    /// unexpected payload). Not a connectivity problem.
    case protocolViolation(url: String, detail: String)
```
Replace `isRetryable` with:
```swift
    public var isRetryable: Bool {
        switch self {
        case .unreachable, .timeout, .pairingRequired:
            return true
        case let .rpc(_, _, _, _, _, retryable):
            return retryable
        case .auth, .api, .tls, .profileNeedsReauth,
             .aiServiceUnavailable, .aiQuotaExceeded, .configValidation,
             .cleartextBlocked, .methodUnsupported, .missingScope, .connectRejected, .protocolViolation:
            return false
        }
    }
```
In `errorDescription`, add before the closing brace of the `switch`:
```swift
        case let .rpc(url, method, code, detailCode, message, _):
            return "Gateway RPC \(method) failed at \(url): \(code)\(detailCode.map { "/\($0)" } ?? "") \u{2014} \(message)"
        case let .methodUnsupported(url, method):
            return "Gateway at \(url) does not support '\(method)' (INVALID_REQUEST unknown method)"
        case let .missingScope(url, method, scope):
            return "Gateway at \(url) refused '\(method)': missing scope \(scope) (FORBIDDEN/MISSING_SCOPE)"
        case let .pairingRequired(url, requestId, reason, _):
            let command = requestId.map { "openclaw devices approve \($0)" } ?? "openclaw devices list"
            return "Gateway at \(url) needs this device approved (\(reason)). On the gateway host run: \(command)"
        case let .connectRejected(url, detailCode, message, _):
            return "Gateway at \(url) rejected the connect handshake: \(detailCode) \u{2014} \(message)"
        case let .protocolViolation(url, detail):
            return "Unexpected gateway response at \(url): \(detail)"
```

- [ ] **Step 4: String error codes for install failures**

In `SkillInstallError.swift` replace
```swift
    case `protocol`(rpcCode: Int, message: String)
```
with
```swift
    /// The gateway rejected the install with string `code` (e.g. `UNAVAILABLE`) and `message` (ADR-0007).
    case `protocol`(code: String, message: String)
```

In `InstalledSkillRepositoryImpl.swift`, replace the whole `extension GatewayError { … toSkillInstallError() … }` with:
```swift
extension GatewayError {
    /// Maps a ``GatewayError`` to the install-time ``SkillInstallError`` taxonomy.
    ///
    /// - missingScope → `.unauthorized(missingScope:)`
    /// - rpc `UNAVAILABLE` "not found on ClawHub" → `.notFound(slug:)`; other rpc → `.protocol(code:message:)`
    /// - methodUnsupported → `.protocol(code: "INVALID_REQUEST", …)`
    /// - REST leftovers (removed in Task 20): api → `.protocol(code: "HTTP <status>", …)`, auth → `.unauthorized`
    /// - timeout / unreachable / tls → `.network(cause:)`; cleartextBlocked → `.cleartextBlocked(message:)`
    ///
    /// - Parameter slug: The slug being installed (for `.notFound`). Internal so tests can pin the mapping.
    func toSkillInstallError(slug: String = "") -> SkillInstallError {
        switch self {
        case let .missingScope(_, _, scope):
            return .unauthorized(missingScope: scope)
        case let .rpc(_, _, code, _, message, _):
            if code == "UNAVAILABLE", message.contains("not found on ClawHub") {
                return .notFound(slug: slug.isEmpty ? message : slug)
            }
            return .protocol(code: code, message: message)
        case let .methodUnsupported(_, method):
            return .protocol(code: "INVALID_REQUEST", message: "unknown method: \(method)")
        case .auth, .profileNeedsReauth, .pairingRequired, .connectRejected:
            return .unauthorized(missingScope: "operator.admin")
        case let .api(_, statusCode, body):
            let bodyText = body ?? ""
            switch statusCode {
            case 404: return .notFound(slug: bodyText)
            case 409: return .dependencyConflict(conflicts: bodyText.split(separator: ",").map { String($0) })
            case 422: return .verificationFailed(expected: "", actual: bodyText)
            default: return .protocol(code: "HTTP \(statusCode)", message: bodyText)
            }
        case let .timeout(url, _):
            return .network(cause: "timeout at \(url)")
        case let .unreachable(url, _):
            return .network(cause: "unreachable at \(url)")
        case let .tls(url, _):
            return .network(cause: "TLS error at \(url)")
        case let .configValidation(field, reason):
            return .protocol(code: "CONFIG_VALIDATION", message: "\(field): \(reason)")
        case .cleartextBlocked:
            return .cleartextBlocked(message: errorDescription ?? "Cleartext HTTP blocked by app policy")
        case let .protocolViolation(url, detail):
            return .protocol(code: "PROTOCOL", message: "\(detail) at \(url)")
        case let .aiServiceUnavailable(url):
            return .unknown(cause: "ai service unavailable at \(url)")
        case let .aiQuotaExceeded(url):
            return .unknown(cause: "ai quota exceeded at \(url)")
        }
    }
}
```

In `InstalledSkillsScreen.swift` the label line `case .protocol(let code, let message): return "Protocol error \(code): \(message)"` compiles unchanged.

In `OpenClawAPIClientDecodingTests.swift`, test `skillInstall200WithGarbageBodyIsAProtocolErrorWithCode200`, replace `#expect(rpcCode == 200)` with `#expect(rpcCode == "HTTP 200")`.

- [ ] **Step 5: Run the tests** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run the test command with `-only-testing:GhostCrabTests` (whole unit suite). Expected: PASS, including the 4 new tests.

- [ ] **Step 6: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Domain/Error/GatewayError.swift ios/GhostCrab/Domain/Model/SkillInstallError.swift ios/GhostCrab/Data/Impl/InstalledSkillRepositoryImpl.swift ios/GhostCrabTests/Api/OpenClawAPIClientDecodingTests.swift ios/GhostCrabTests/Gateway/GatewayErrorCasesTests.swift
git commit -m "feat(ios): GatewayError cases for the WS control plane" -m "rpc, methodUnsupported, missingScope, pairingRequired, connectRejected, protocolViolation; skill install errors carry string codes (ADR-0007)."
```

---

### Task 3: Frame codec

**Files:**
- Create: `ios/GhostCrab/Data/Gateway/GatewayFrames.swift`
- Test: `ios/GhostCrabTests/Gateway/GatewayFramesTests.swift`

- [ ] **Step 1: Write the failing test**

`ios/GhostCrabTests/Gateway/GatewayFramesTests.swift`:
```swift
import Foundation
import Testing
@testable import GhostCrab

@Suite("Gateway frames (protocol 4)")
struct GatewayFramesTests {

    @Test func challengeDecodesAsAnEventWithNonceAndTs() throws {
        guard case .event(let event)? = GatewayFrameCodec.decode(try GatewayFixtures.text("challenge")) else {
            Issue.record("not an event frame")
            return
        }
        #expect(event.event == "connect.challenge")
        let payload = try #require(event.payload?.objectValue)
        #expect(payload["nonce"]?.value as? String == "71d63897-7ebd-44e8-a27a-20ac1598b846")
        #expect(payload["ts"]?.value as? Int == 1_792_027_507_354)
    }

    @Test func helloDecodesAsAnOkResponseWithAStringId() throws {
        guard case .response(let response)? = GatewayFrameCodec.decode(try GatewayFixtures.text("hello-ok-paired")) else {
            Issue.record("not a response frame")
            return
        }
        #expect(response.id == "qa-1")
        #expect(response.ok)
        #expect(response.payload?.objectValue?["type"]?.value as? String == "hello-ok")
        #expect(response.error == nil)
    }

    @Test func errorResponseExposesCodeAndDetails() throws {
        guard case .response(let response)? = GatewayFrameCodec.decode(try GatewayFixtures.text("err-pairing-required")) else {
            Issue.record("not a response frame")
            return
        }
        #expect(!response.ok)
        #expect(response.error?.code == "NOT_PAIRED")
        #expect(response.error?.detailCode == "PAIRING_REQUIRED")
        #expect(response.error?.detailString("requestId") == "af0b5d81-7bd2-4b86-80b0-4a489b541974")
        #expect(response.error?.detailStrings("requestedScopes") == TestKeys.operatorScopes)
    }

    @Test(arguments: [
        "not json",
        "[1,2,3]",
        #"{"jsonrpc":"2.0","id":1,"result":{}}"#,
        #"{"type":"res","id":7,"ok":true}"#,
        #"{"type":"res","id":"x"}"#,
        #"{"type":"event"}"#,
    ])
    func nonProtocolInputDecodesToNil(text: String) {
        #expect(GatewayFrameCodec.decode(text) == nil)
    }

    @Test func requestEncodesTypeReqWithAStringId() throws {
        let object = try jsonObject(try GatewayFrameCodec.encodeRequest(id: "gc-1", method: "health", params: EmptyParams()))
        #expect(object["type"] as? String == "req")
        #expect(object["id"] as? String == "gc-1")
        #expect(object["method"] as? String == "health")
        #expect((object["params"] as? [String: Any])?.isEmpty == true)
    }

    @Test func payloadsReDecodeIntoTypedValues() throws {
        struct Health: Decodable { let ok: Bool; let defaultAgentId: String }
        guard case .response(let response)? = GatewayFrameCodec.decode(try GatewayFixtures.text("res-health")) else {
            Issue.record("not a response frame")
            return
        }
        let health = try #require(response.payload).decoded(as: Health.self)
        #expect(health.ok)
        #expect(health.defaultAgentId == "main")
    }
}
```

- [ ] **Step 2: Run it to make sure it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run the test command with `GatewayFramesTests`. Expected: FAIL to compile (`cannot find 'GatewayFrameCodec' in scope`).

- [ ] **Step 3: Implement the codec**

`ios/GhostCrab/Data/Gateway/GatewayFrames.swift`:
```swift
import Foundation

/// Gateway WebSocket protocol constants (ADR-0007).
public enum GatewayProtocol {
    /// Wire version GhostCrab implements (`connect.minProtocol` = `maxProtocol`).
    public static let version = 4
}

/// `{}` request params.
public struct EmptyParams: Encodable, Sendable {
    public init() {}
}

/// Client → gateway request: `{"type":"req","id":"…","method":"…","params":{…}}`.
struct GatewayRequestFrame<Params: Encodable & Sendable>: Encodable, Sendable {
    let type = "req"
    let id: String
    let method: String
    let params: Params
}

/// `error` of a failed response (upstream `ErrorShape`).
public struct GatewayErrorShape: Decodable, Sendable, Equatable {
    /// Top-level code: `INVALID_REQUEST`, `FORBIDDEN`, `NOT_PAIRED`, `UNAVAILABLE`, ….
    public let code: String
    /// Human-readable server text, shown verbatim.
    public let message: String
    /// Structured details; `details.code` is the machine-readable sub-code.
    public let details: AnyCodable?
    /// Server retry hint; absent means "not retryable".
    public let retryable: Bool?
    /// Server back-off hint.
    public let retryAfterMs: Int?

    public init(code: String, message: String, details: AnyCodable? = nil, retryable: Bool? = nil, retryAfterMs: Int? = nil) {
        self.code = code
        self.message = message
        self.details = details
        self.retryable = retryable
        self.retryAfterMs = retryAfterMs
    }

    /// `details.code` (e.g. `PAIRING_REQUIRED`, `MISSING_SCOPE`, `AUTH_TOKEN_MISMATCH`).
    public var detailCode: String? { detailString("code") }

    /// - Returns: The string at `details[key]`, or `nil`.
    public func detailString(_ key: String) -> String? {
        details?.objectValue?[key]?.value as? String
    }

    /// - Returns: The string elements of the array at `details[key]` (others skipped).
    public func detailStrings(_ key: String) -> [String] {
        (details?.objectValue?[key]?.value as? [AnyCodable])?.compactMap { $0.value as? String } ?? []
    }
}

/// Answer to a request.
public struct GatewayResponseFrame: Sendable {
    public let id: String
    public let ok: Bool
    public let payload: AnyCodable?
    public let error: GatewayErrorShape?
}

/// Server push (`connect.challenge`, `tick`, `health`, `skills.changed`, …).
public struct GatewayEventFrame: Sendable {
    public let event: String
    public let payload: AnyCodable?
    public let seq: Int?
}

/// A decoded gateway → client frame.
public enum GatewayFrame: Sendable {
    case response(GatewayResponseFrame)
    case event(GatewayEventFrame)
}

/// Encodes requests and decodes everything the gateway sends.
public enum GatewayFrameCodec {

    private struct Envelope: Decodable {
        let type: String?
        let id: String?
        let ok: Bool?
        let event: String?
        let payload: AnyCodable?
        let error: GatewayErrorShape?
        let seq: Int?
    }

    /// - Parameter text: One text frame from the socket.
    /// - Returns: The frame, or `nil` for anything that is not a well-formed protocol-4 `res` / `event` frame
    ///   (malformed JSON, JSON-RPC, numeric ids). The official clients ignore such frames too; never throws.
    public static func decode(_ text: String) -> GatewayFrame? {
        guard let envelope = try? JSONDecoder().decode(Envelope.self, from: Data(text.utf8)) else { return nil }
        switch envelope.type {
        case "res":
            guard let id = envelope.id, let ok = envelope.ok else { return nil }
            return .response(GatewayResponseFrame(id: id, ok: ok, payload: envelope.payload, error: envelope.error))
        case "event":
            guard let name = envelope.event else { return nil }
            return .event(GatewayEventFrame(event: name, payload: envelope.payload, seq: envelope.seq))
        default:
            return nil
        }
    }

    /// - Returns: The request frame as JSON text.
    static func encodeRequest<P: Encodable & Sendable>(id: String, method: String, params: P) throws -> String {
        let data = try JSONEncoder().encode(GatewayRequestFrame(id: id, method: method, params: params))
        return String(decoding: data, as: UTF8.self)
    }
}

extension AnyCodable {
    /// The value as a JSON object, or `nil`.
    var objectValue: [String: AnyCodable]? { value as? [String: AnyCodable] }

    /// The nested object at `path`, or `nil` as soon as a step is missing or not an object.
    func object(at path: String...) -> [String: AnyCodable]? {
        path.reduce(objectValue) { current, key in current?[key]?.objectValue }
    }

    /// Re-decodes this JSON value as `T` (frames are decoded once as `AnyCodable`, then typed per method).
    func decoded<T: Decodable>(as type: T.Type) throws -> T {
        try JSONDecoder().decode(T.self, from: JSONEncoder().encode(self))
    }
}
```

- [ ] **Step 4: Run the test to verify it passes** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: PASS (11 cases).

- [ ] **Step 5: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Gateway/GatewayFrames.swift ios/GhostCrabTests/Gateway/GatewayFramesTests.swift
git commit -m "feat(ios): protocol-4 frame codec" -m "req/res/event frames with string ids and string error codes; decoding never throws (ADR-0007)."
```

---

### Task 4: Error shape → `GatewayError`

**Files:**
- Create: `ios/GhostCrab/Data/Gateway/GatewayErrorMapping.swift`
- Test: `ios/GhostCrabTests/Gateway/GatewayErrorMappingTests.swift`

- [ ] **Step 1: Write the failing test**

`ios/GhostCrabTests/Gateway/GatewayErrorMappingTests.swift`:
```swift
import Foundation
import Testing
@testable import GhostCrab

@Suite("Gateway error mapping")
struct GatewayErrorMappingTests {

    private let url = "ws://192.168.1.50:18789/ws"

    private func shape(_ fixture: String) throws -> GatewayErrorShape {
        guard case .response(let response)? = GatewayFrameCodec.decode(try GatewayFixtures.text(fixture)) else {
            throw GatewayFixtures.Missing(name: fixture)
        }
        return try #require(response.error)
    }

    @Test func pairingRequiredCarriesRequestIdReasonAndScopes() throws {
        guard case let .pairingRequired(_, requestId, reason, scopes) = try shape("err-pairing-required").toGatewayError(url: url, method: "connect") else {
            Issue.record("expected pairingRequired")
            return
        }
        #expect(requestId == "af0b5d81-7bd2-4b86-80b0-4a489b541974")
        #expect(reason == "not-paired")
        #expect(scopes == TestKeys.operatorScopes)
    }

    @Test(arguments: [
        ("err-token-mismatch", "AUTH_TOKEN_MISMATCH"),
        ("err-token-missing", "AUTH_TOKEN_MISSING"),
        ("err-device-identity-required", "DEVICE_IDENTITY_REQUIRED"),
    ])
    func credentialFailuresAreConnectRejected(fixture: String, expected: String) throws {
        guard case let .connectRejected(_, detailCode, _, _) = try shape(fixture).toGatewayError(url: url, method: "connect") else {
            Issue.record("expected connectRejected for \(fixture)")
            return
        }
        #expect(detailCode == expected)
    }

    @Test func missingScopeNamesTheScope() throws {
        guard case let .missingScope(_, method, scope) = try shape("err-missing-scope").toGatewayError(url: url, method: "status") else {
            Issue.record("expected missingScope")
            return
        }
        #expect(method == "status")
        #expect(scope == "operator.read")
    }

    @Test func legacyMissingScopeMessageStillMaps() {
        guard case let .missingScope(_, _, scope) = GatewayErrorShape(code: "FORBIDDEN", message: "missing scope: operator.admin")
            .toGatewayError(url: url, method: "config.get") else {
            Issue.record("expected missingScope")
            return
        }
        #expect(scope == "operator.admin")
    }

    @Test func unknownMethodIsUnsupported() throws {
        guard case let .methodUnsupported(_, method) = try shape("err-unknown-method").toGatewayError(url: url, method: "skills.list") else {
            Issue.record("expected methodUnsupported")
            return
        }
        #expect(method == "skills.list")
    }

    @Test func anythingElseIsAGenericRPCError() throws {
        guard case let .rpc(_, _, code, detailCode, message, retryable) = try shape("err-skill-not-found").toGatewayError(url: url, method: "skills.install") else {
            Issue.record("expected rpc")
            return
        }
        #expect(code == "UNAVAILABLE")
        #expect(detailCode == nil)
        #expect(message.hasPrefix("Skill \"qa-nonexistent-skill-zz9\" not found on ClawHub"))
        #expect(!retryable)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: FAIL to compile (`value of type 'GatewayErrorShape' has no member 'toGatewayError'`).

- [ ] **Step 3: Implement**

`ios/GhostCrab/Data/Gateway/GatewayErrorMapping.swift`:
```swift
import Foundation

extension GatewayErrorShape {

    private static let missingScopePrefix = "missing scope: "
    private static let unknownMethodPrefix = "unknown method:"

    /// Maps a failed response onto ``GatewayError``. Branches on `code` and `details.code` (upstream's contract);
    /// the legacy `missing scope: …` / `unknown method: …` messages are a fallback only.
    ///
    /// - Parameters:
    ///   - url: The gateway WebSocket endpoint.
    ///   - method: The RPC method that failed.
    /// - Returns: The most specific case for this error.
    func toGatewayError(url: String, method: String) -> GatewayError {
        let detail = detailCode
        if detail == "PAIRING_REQUIRED" {
            return .pairingRequired(
                url: url,
                requestId: detailString("requestId"),
                reason: detailString("reason") ?? "not-paired",
                requestedScopes: detailStrings("requestedScopes")
            )
        }
        if let detail, detail.hasPrefix("AUTH_") || detail.hasPrefix("DEVICE_") {
            return .connectRejected(url: url, detailCode: detail, message: message, recommendedNextStep: detailString("recommendedNextStep"))
        }
        if code == "FORBIDDEN", detail == "MISSING_SCOPE" || message.hasPrefix(Self.missingScopePrefix) {
            let scope = detailString("missingScope") ?? String(message.dropFirst(Self.missingScopePrefix.count))
            return .missingScope(url: url, method: method, scope: scope)
        }
        if code == "INVALID_REQUEST", message.hasPrefix(Self.unknownMethodPrefix) {
            return .methodUnsupported(url: url, method: method)
        }
        return .rpc(url: url, method: method, code: code, detailCode: detail, message: message, retryable: retryable ?? false)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: PASS (8 cases).

- [ ] **Step 5: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Gateway/GatewayErrorMapping.swift ios/GhostCrabTests/Gateway/GatewayErrorMappingTests.swift
git commit -m "feat(ios): map gateway error shapes to typed GatewayError cases" -m "PAIRING_REQUIRED, AUTH_*/DEVICE_* connect rejections, MISSING_SCOPE and unknown methods (ADR-0007)."
```

---

### Task 5: WebSocket seam and the `GatewayRPCClient` actor

**Files:**
- Create: `ios/GhostCrab/Data/Gateway/GatewayWebSocket.swift`, `ios/GhostCrab/Data/Gateway/GatewayRPCClient.swift`
- Create: `ios/GhostCrabTests/Support/ScriptedGatewayWebSocket.swift`
- Test: `ios/GhostCrabTests/Gateway/GatewayRPCClientTests.swift`

- [ ] **Step 1: Add the socket seam and its production implementation**

`ios/GhostCrab/Data/Gateway/GatewayWebSocket.swift`:
```swift
import Foundation

/// Transport seam for the gateway control-plane socket. Production: ``URLSessionGatewayWebSocket``;
/// tests: a scripted actor.
public protocol GatewayWebSocket: Sendable {
    /// Sends one text frame.
    func send(_ text: String) async throws
    /// - Returns: The next text frame, or `nil` once the socket has closed.
    func receive() async throws -> String?
    /// Closes the socket. Idempotent.
    func close() async
}

/// Builds sockets; injectable into ``GatewayConnectionManagerImpl``.
public protocol GatewaySocketFactory: Sendable {
    /// - Parameter url: `ws(s)://host:port/ws`.
    func makeSocket(url: URL) -> any GatewayWebSocket
}

/// ``GatewaySocketFactory`` producing ``URLSessionGatewayWebSocket``s.
public struct URLSessionGatewaySocketFactory: GatewaySocketFactory {
    public init() {}
    public func makeSocket(url: URL) -> any GatewayWebSocket { URLSessionGatewayWebSocket(url: url) }
}

/// ``GatewayWebSocket`` over `URLSessionWebSocketTask`. ATS applies to `ws://` exactly as to `http://`
/// (ADR-0006); the app's own public-IP cleartext policy is enforced before the socket is built
/// (``GatewayWebSocketURL``), because custom `URLProtocol`s never see WebSocket tasks.
///
/// `@unchecked Sendable`: both stored properties are immutable and the URLSession types are thread-safe.
public final class URLSessionGatewayWebSocket: GatewayWebSocket, @unchecked Sendable {

    private let session: URLSession
    private let task: URLSessionWebSocketTask

    /// - Parameters:
    ///   - url: `ws(s)://host:port/ws`.
    ///   - timeout: Idle timeout for the opening handshake.
    public init(url: URL, timeout: TimeInterval = 15) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = timeout
        configuration.waitsForConnectivity = false
        session = URLSession(configuration: configuration)
        task = session.webSocketTask(with: url)
        task.maximumMessageSize = 32 * 1024 * 1024 // hello-ok.policy.maxPayload is 25 MiB
        task.resume()
    }

    public func send(_ text: String) async throws {
        try await task.send(.string(text))
    }

    public func receive() async throws -> String? {
        do {
            switch try await task.receive() {
            case .string(let text):
                return text
            case .data(let data):
                return String(data: data, encoding: .utf8)
            @unknown default:
                return nil
            }
        } catch {
            if task.closeCode != .invalid { return nil } // orderly close (e.g. 1008 after a failed connect)
            throw error
        }
    }

    public func close() async {
        task.cancel(with: .normalClosure, reason: nil)
        session.invalidateAndCancel()
    }
}
```

- [ ] **Step 2: Add the scripted socket for tests**

`ios/GhostCrabTests/Support/ScriptedGatewayWebSocket.swift`:
```swift
import Foundation
@testable import GhostCrab

/// Fake gateway socket. Emits the captured `connect.challenge` first (unless `challenge` is false), then answers
/// each request with the next queued frame for its method, its `id` rewritten to the request's id. The last queued
/// frame of a method repeats. A method without frames gets no answer. `eventsBeforeReply` frames are pushed right
/// before a method's reply (the real gateway's `config.changed` can precede the response of the write that caused
/// it). Methods in `closeAfterReplyTo` end the socket after the reply, as the real gateway does after a failed
/// `connect` (close 1008).
actor ScriptedGatewayWebSocket: GatewayWebSocket {

    private var inbox: [String?]
    private var waiter: CheckedContinuation<String?, Never>?
    private var replies: [String: [String]]
    private let eventsBefore: [String: [String]]
    private let closeAfter: Set<String>
    private var sentFrames: [String] = []

    /// `true` once the client called `close()`.
    private(set) var closedByClient = false

    init(
        challenge: Bool = true,
        replies: [String: [String]] = [:],
        eventsBeforeReply: [String: [String]] = [:],
        closeAfterReplyTo: Set<String> = []
    ) throws {
        self.inbox = challenge ? [try GatewayFixtures.text("challenge")] : []
        self.replies = replies
        self.eventsBefore = eventsBeforeReply
        self.closeAfter = closeAfterReplyTo
    }

    /// The raw text of the first request the client sent for `method`.
    func sentRequest(_ method: String) -> String? {
        sentRequests(method).first
    }

    /// The raw text of every request the client sent for `method`, in order.
    func sentRequests(_ method: String) -> [String] {
        sentFrames.filter { text in
            (try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])?["method"] as? String == method
        }
    }

    /// Methods the client called, in order.
    func sentMethods() -> [String] {
        sentFrames.compactMap { text in
            (try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])?["method"] as? String
        }
    }

    func send(_ text: String) async throws {
        sentFrames.append(text)
        guard let request = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              let id = request["id"] as? String,
              let method = request["method"] as? String,
              var queue = replies[method], let reply = queue.first
        else { return }
        if queue.count > 1 {
            queue.removeFirst()
            replies[method] = queue
        }
        eventsBefore[method]?.forEach { deliver($0) }
        deliver(try Self.rewritingId(reply, to: id))
        if closeAfter.contains(method) { deliver(nil) }
    }

    func receive() async throws -> String? {
        if !inbox.isEmpty { return inbox.removeFirst() }
        return await withCheckedContinuation { waiter = $0 }
    }

    func close() async {
        closedByClient = true
        deliver(nil)
    }

    /// Delivers a server-pushed frame.
    func push(_ text: String) { deliver(text) }

    /// Simulates the gateway dropping the socket.
    func drop() { deliver(nil) }

    private func deliver(_ text: String?) {
        if let waiting = waiter {
            waiter = nil
            waiting.resume(returning: text)
        } else {
            inbox.append(text)
        }
    }

    private static func rewritingId(_ frame: String, to id: String) throws -> String {
        var object = try JSONSerialization.jsonObject(with: Data(frame.utf8)) as? [String: Any] ?? [:]
        object["id"] = id
        return String(decoding: try JSONSerialization.data(withJSONObject: object), as: UTF8.self)
    }
}

extension Dictionary where Key == String, Value == [String] {
    /// Scripted replies from fixture names by method, e.g. `.fixtures(["connect": "hello-ok-paired"])`.
    static func fixtures(_ byMethod: [String: String]) throws -> [String: [String]] {
        try byMethod.mapValues { [try GatewayFixtures.text($0)] }
    }
}

/// ``GatewaySocketFactory`` that hands out prepared sockets in order (the last one repeats).
final class FixedSocketFactory: GatewaySocketFactory, @unchecked Sendable {
    private let lock = NSLock()
    private var sockets: [ScriptedGatewayWebSocket]

    init(_ sockets: ScriptedGatewayWebSocket...) { self.sockets = sockets }

    func makeSocket(url: URL) -> any GatewayWebSocket {
        lock.withLock { sockets.count > 1 ? sockets.removeFirst() : sockets[0] }
    }
}
```

- [ ] **Step 3: Write the failing RPC tests**

`ios/GhostCrabTests/Gateway/GatewayRPCClientTests.swift`:
```swift
import Foundation
import Testing
@testable import GhostCrab

@Suite("GatewayRPCClient")
struct GatewayRPCClientTests {

    private func client(_ socket: ScriptedGatewayWebSocket, timeout: Duration = .seconds(5)) async -> GatewayRPCClient {
        let rpc = GatewayRPCClient(socket: socket, endpoint: "ws://test/ws", defaultTimeout: timeout)
        await rpc.start()
        return rpc
    }

    @Test func requestCorrelatesTheResponseByStringId() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["health": "res-health"]))
        let payload = try await client(socket).request("health")
        #expect(payload?.objectValue?["ok"]?.value as? Bool == true)
        let sent = try jsonObject(try #require(await socket.sentRequest("health")))
        #expect(sent["type"] as? String == "req")
        #expect((sent["id"] as? String)?.hasPrefix("gc-") == true)
    }

    @Test func okFalseBecomesTheTypedError() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["skills.list": "err-unknown-method", "status": "err-missing-scope"]))
        let rpc = await client(socket)
        guard case .methodUnsupported(_, "skills.list")? = await gatewayError({ _ = try await rpc.request("skills.list") }) else {
            Issue.record("expected methodUnsupported")
            return
        }
        guard case .missingScope(_, _, "operator.read")? = await gatewayError({ _ = try await rpc.request("status") }) else {
            Issue.record("expected missingScope")
            return
        }
    }

    @Test func aChallengeThatArrivedEarlyIsStillReturned() async throws {
        let rpc = await client(try ScriptedGatewayWebSocket())
        try await Task.sleep(for: .milliseconds(20))
        #expect(try await rpc.awaitChallenge().event == GatewayRPCClient.connectChallengeEvent)
    }

    @Test func noChallengeTimesOut() async throws {
        let rpc = await client(try ScriptedGatewayWebSocket(challenge: false))
        guard case .timeout(let url, _)? = await gatewayError({ _ = try await rpc.awaitChallenge(timeout: .milliseconds(50)) }) else {
            Issue.record("expected timeout")
            return
        }
        #expect(url == "ws://test/ws#connect.challenge")
    }

    @Test func anUnansweredRequestTimesOut() async throws {
        let rpc = await client(try ScriptedGatewayWebSocket(), timeout: .milliseconds(50))
        guard case .timeout(let url, _)? = await gatewayError({ _ = try await rpc.request("health") }) else {
            Issue.record("expected timeout")
            return
        }
        #expect(url == "ws://test/ws#health")
    }

    @Test func aDroppedSocketFailsPendingAndLaterRequests() async throws {
        let socket = try ScriptedGatewayWebSocket()
        let rpc = await client(socket)
        let pending = Task { await gatewayError { _ = try await rpc.request("health") } }
        try await Task.sleep(for: .milliseconds(20))
        await socket.drop()
        guard case .unreachable? = await pending.value else {
            Issue.record("expected unreachable for the pending request")
            return
        }
        guard case .unreachable? = await gatewayError({ _ = try await rpc.request("status") }) else {
            Issue.record("expected unreachable after close")
            return
        }
        #expect(await rpc.isClosed)
    }

    @Test func serverEventsReachSubscribers() async throws {
        let socket = try ScriptedGatewayWebSocket()
        let rpc = await client(socket)
        let events = await rpc.events()
        await socket.push(#"{"type":"event","event":"skills.changed","payload":{}}"#)
        var iterator = events.makeAsyncIterator()
        var names: [String] = []
        while let event = await iterator.next() {
            names.append(event.event)
            if event.event == "skills.changed" { break }
        }
        #expect(names.contains("skills.changed"))
    }

    @Test func closeClosesTheSocket() async throws {
        let socket = try ScriptedGatewayWebSocket()
        await client(socket).close()
        #expect(await socket.closedByClient)
    }
}
```
(The events test stops at `skills.changed` because the subscription may or may not see the early challenge first.)

- [ ] **Step 4: Run them to make sure they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: FAIL to compile (`cannot find 'GatewayRPCClient' in scope`).

- [ ] **Step 5: Implement the actor**

`ios/GhostCrab/Data/Gateway/GatewayRPCClient.swift`:
```swift
import Foundation

/// One gateway control-plane socket (ADR-0007). Request ids are `gc-1`, `gc-2`, …; each request waits for the
/// `res` frame with its id. `connect.challenge` is kept as soon as it arrives, so a late caller still gets it.
/// Every request has a timeout; once the socket ends, pending and later requests fail with
/// ``GatewayError/unreachable(url:underlying:)`` instead of hanging. Call ``start()`` once, ``close()`` at the end.
public actor GatewayRPCClient {

    /// First frame of every gateway socket.
    public static let connectChallengeEvent = "connect.challenge"

    /// The `ws(s)://…/ws` endpoint, for error messages.
    public nonisolated let endpoint: String

    private let socket: any GatewayWebSocket
    private let defaultTimeout: Duration
    private var nextId = 1
    private var pending: [String: CheckedContinuation<GatewayResponseFrame, any Error>] = [:]
    private var timers: [String: Task<Void, Never>] = [:]
    private var challenge: GatewayEventFrame?
    private var challengeWaiters: [UUID: CheckedContinuation<GatewayEventFrame, any Error>] = [:]
    private var subscribers: [UUID: AsyncStream<GatewayEventFrame>.Continuation] = [:]
    private var closedError: GatewayError?
    private var readerTask: Task<Void, Never>?

    /// - Parameters:
    ///   - socket: An open socket; the client owns it from now on.
    ///   - endpoint: The socket URL, for error messages.
    ///   - defaultTimeout: Per-request timeout.
    public init(socket: any GatewayWebSocket, endpoint: String, defaultTimeout: Duration = .seconds(30)) {
        self.socket = socket
        self.endpoint = endpoint
        self.defaultTimeout = defaultTimeout
    }

    /// `true` once the socket has ended.
    public var isClosed: Bool { closedError != nil }

    /// Starts the reader loop. Idempotent.
    public func start() {
        guard readerTask == nil else { return }
        readerTask = Task { await self.readLoop() }
    }

    private func readLoop() async {
        var failure: (any Error)?
        do {
            while let text = try await socket.receive() {
                dispatch(text)
            }
        } catch {
            failure = error
        }
        finish(with: .unreachable(url: endpoint, underlying: failure as? (any Error & Sendable)))
    }

    private func dispatch(_ text: String) {
        switch GatewayFrameCodec.decode(text) {
        case .response(let response)?:
            timers.removeValue(forKey: response.id)?.cancel()
            pending.removeValue(forKey: response.id)?.resume(returning: response)
        case .event(let event)?:
            if event.event == Self.connectChallengeEvent, challenge == nil {
                challenge = event
                let waiters = Array(challengeWaiters.values)
                challengeWaiters.removeAll()
                waiters.forEach { $0.resume(returning: event) }
            }
            subscribers.values.forEach { $0.yield(event) }
        case nil:
            break // not a protocol-4 frame: ignored, like the official clients do
        }
    }

    /// - Parameter timeout: How long to wait for the gateway's first frame (the real one sends it within ~2 ms).
    /// - Returns: The `connect.challenge` event.
    /// - Throws: ``GatewayError/timeout(url:underlying:)`` or ``GatewayError/unreachable(url:underlying:)``.
    public func awaitChallenge(timeout: Duration = .seconds(10)) async throws -> GatewayEventFrame {
        if let challenge { return challenge }
        if let closedError { throw closedError }
        let waiterId = UUID()
        let label = "\(endpoint)#\(Self.connectChallengeEvent)"
        return try await withCheckedThrowingContinuation { continuation in
            challengeWaiters[waiterId] = continuation
            Task {
                do { try await Task.sleep(for: timeout) } catch { return }
                await self.failChallengeWaiter(waiterId, with: .timeout(url: label, underlying: nil))
            }
        }
    }

    private func failChallengeWaiter(_ id: UUID, with error: GatewayError) {
        challengeWaiters.removeValue(forKey: id)?.resume(throwing: error)
    }

    /// Sends a request and waits for its response.
    ///
    /// - Parameters:
    ///   - method: RPC method, e.g. `skills.status`.
    ///   - params: Encodable params (``EmptyParams`` for `{}`).
    ///   - timeout: Overrides the default per-request timeout.
    /// - Returns: The response `payload`, if any.
    /// - Throws: The mapped ``GatewayError`` for `ok:false`; `.timeout`; `.unreachable` when the socket ended.
    public func request<P: Encodable & Sendable>(_ method: String, params: P, timeout: Duration? = nil) async throws -> AnyCodable? {
        if let closedError { throw closedError }
        try Task.checkCancellation()
        let id = "gc-\(nextId)"
        nextId += 1
        let text = try GatewayFrameCodec.encodeRequest(id: id, method: method, params: params)
        let limit = timeout ?? defaultTimeout
        let label = "\(endpoint)#\(method)"
        let response: GatewayResponseFrame = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<GatewayResponseFrame, any Error>) in
                pending[id] = continuation
                timers[id] = Task {
                    do { try await Task.sleep(for: limit) } catch { return }
                    await self.fail(id, with: GatewayError.timeout(url: label, underlying: nil))
                }
                Task { await self.send(text, id: id) }
            }
        } onCancel: {
            Task { await self.fail(id, with: CancellationError()) }
        }
        guard response.ok else {
            let shape = response.error ?? GatewayErrorShape(code: "UNKNOWN", message: "ok:false without an error object")
            throw shape.toGatewayError(url: endpoint, method: method)
        }
        return response.payload
    }

    /// `request(_:params:timeout:)` with `{}` params.
    public func request(_ method: String, timeout: Duration? = nil) async throws -> AnyCodable? {
        try await request(method, params: EmptyParams(), timeout: timeout)
    }

    private func send(_ text: String, id: String) async {
        do {
            try await socket.send(text)
        } catch {
            fail(id, with: GatewayError.unreachable(url: endpoint, underlying: error as? (any Error & Sendable)))
        }
    }

    private func fail(_ id: String, with error: any Error) {
        timers.removeValue(forKey: id)?.cancel()
        pending.removeValue(forKey: id)?.resume(throwing: error)
    }

    /// Server events. Best effort: a slow consumer drops the oldest beyond 64 buffered events.
    public func events() -> AsyncStream<GatewayEventFrame> {
        let (stream, continuation) = AsyncStream.makeStream(of: GatewayEventFrame.self, bufferingPolicy: .bufferingNewest(64))
        if closedError != nil {
            continuation.finish()
            return stream
        }
        let id = UUID()
        subscribers[id] = continuation
        continuation.onTermination = { _ in Task { await self.removeSubscriber(id) } }
        return stream
    }

    private func removeSubscriber(_ id: UUID) {
        subscribers.removeValue(forKey: id)
    }

    /// Stops reading and closes the socket. Idempotent.
    public func close() async {
        readerTask?.cancel()
        await socket.close()
        finish(with: .unreachable(url: endpoint, underlying: nil))
    }

    private func finish(with error: GatewayError) {
        guard closedError == nil else { return }
        closedError = error
        for (id, continuation) in pending {
            timers.removeValue(forKey: id)?.cancel()
            continuation.resume(throwing: error)
        }
        pending.removeAll()
        challengeWaiters.values.forEach { $0.resume(throwing: error) }
        challengeWaiters.removeAll()
        subscribers.values.forEach { $0.finish() }
        subscribers.removeAll()
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run with `GatewayRPCClientTests`. Expected: PASS (8 tests), no Swift 6 concurrency diagnostics as errors.

- [ ] **Step 7: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Gateway/GatewayWebSocket.swift ios/GhostCrab/Data/Gateway/GatewayRPCClient.swift ios/GhostCrabTests/Support/ScriptedGatewayWebSocket.swift ios/GhostCrabTests/Gateway/GatewayRPCClientTests.swift
git commit -m "feat(ios): WebSocket seam and GatewayRPCClient actor" -m "URLSessionWebSocketTask transport; string-id correlation, typed ok:false errors, early challenge capture, events stream, per-request timeouts, fail-fast after close (ADR-0007)."
```

---

### Task 6: Ed25519 device identity (CryptoKit) and the v3 payload

**Files:**
- Create: `ios/GhostCrab/Data/Gateway/DeviceIdentity.swift`, `ios/GhostCrab/Data/Gateway/DeviceAuthPayload.swift`
- Test: `ios/GhostCrabTests/Gateway/DeviceIdentityTests.swift`

CryptoKit's Ed25519 signatures are randomised, so the tests verify signatures instead of comparing bytes. The cross-check that the payload layout is byte-identical to what the gateway accepts: a signature Node 26 produced over the same fields with the RFC 8032 key must verify against our payload string.

- [ ] **Step 1: Write the failing test**

`ios/GhostCrabTests/Gateway/DeviceIdentityTests.swift`:
```swift
import CryptoKit
import Foundation
import Testing
@testable import GhostCrab

@Suite("Device identity and v3 payload")
struct DeviceIdentityTests {

    @Test func rfc8032Test1SeedYieldsTheRFCPublicKeyAndDeviceId() throws {
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        #expect(identity.publicKeyRaw == TestKeys.rfcPublicKey)
        #expect(identity.publicKeyBase64URL == "11qYAYKxCrfVS_7TyWQHOg7hcvPapiMlrwIaaPcHURo")
        #expect(identity.deviceId == TestKeys.rfcDeviceId)
    }

    @Test func rfc8032Test1SignatureVerifiesWithCryptoKit() throws {
        let key = try Curve25519.Signing.PublicKey(rawRepresentation: TestKeys.rfcPublicKey)
        let signature = Data(hex: "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        #expect(key.isValidSignature(signature, for: Data()))
    }

    @Test func ourSignaturesVerify() throws {
        let identity = DeviceIdentity.generate()
        let signature = try #require(Base64URL.decode(try identity.sign("payload")))
        let key = try Curve25519.Signing.PublicKey(rawRepresentation: identity.publicKeyRaw)
        #expect(key.isValidSignature(signature, for: Data("payload".utf8)))
        #expect(DeviceIdentity.generate().deviceId != identity.deviceId)
    }

    @Test func deviceIdMatchesTheFingerprintTheRealGatewayAccepted() throws {
        let raw = try #require(Base64URL.decode("e9iojoSOwE3ajNKyeYkso69DO4ByRKR_kJv2IrzMS3Q"))
        #expect(DeviceIdentity.deviceId(forRawPublicKey: raw) == "f044a1f50388e949137206dbf72f4c79c9b499af0e2a4048b005bbf217a9f2e2")
    }

    @Test func descriptionShowsOnlyTheDeviceId() throws {
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        #expect(String(describing: identity) == "DeviceIdentity(deviceId: \(TestKeys.rfcDeviceId))")
        #expect(!String(reflecting: identity).contains("9d61"))
    }

    @Test func aSeedThatIsNot32BytesIsRejected() {
        #expect(throws: DeviceIdentityError.self) { try DeviceIdentity(seed: Data(count: 31)) }
    }

    @Test func v3LayoutMatchesTheFramesTheRealGatewayAccepted() {
        let payload = DeviceAuthPayload(
            deviceId: "f044a1f50388e949137206dbf72f4c79c9b499af0e2a4048b005bbf217a9f2e2",
            clientId: "openclaw-android", clientMode: "ui", role: "operator", scopes: TestKeys.operatorScopes,
            signedAtMs: 1_792_027_489_701, token: "shared-token", nonce: "80f21d17-5553-4b86-b6bf-5bbddbc4f807",
            platform: "android", deviceFamily: nil
        )
        #expect(payload.v3 == "v3|f044a1f50388e949137206dbf72f4c79c9b499af0e2a4048b005bbf217a9f2e2|openclaw-android|ui|operator|operator.read,operator.write,operator.admin|1792027489701|shared-token|80f21d17-5553-4b86-b6bf-5bbddbc4f807|android|")
        #expect(!String(describing: payload).contains("shared-token"))
    }

    @Test func metadataIsTrimmedAndASCIILowercased() {
        let payload = DeviceAuthPayload(deviceId: "d", clientId: "c", clientMode: "ui", role: "operator", scopes: [],
                                        signedAtMs: 1, token: nil, nonce: "n", platform: " iOS ", deviceFamily: "iPhone")
        #expect(payload.v3 == "v3|d|c|ui|operator||1||n|ios|iphone")
    }

    @Test func nodesSignatureOverTheSameFieldsVerifiesAgainstOurPayload() throws {
        // Node 26: crypto.sign(null, Buffer.from(payload), rfc8032Test1Key).toString("base64url").
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        let payload = DeviceAuthPayload(
            deviceId: identity.deviceId, clientId: "openclaw-ios", clientMode: "ui", role: "operator",
            scopes: TestKeys.operatorScopes, signedAtMs: 1_792_027_507_354, token: "shared-token",
            nonce: "71d63897-7ebd-44e8-a27a-20ac1598b846", platform: "iOS", deviceFamily: nil
        ).v3
        let nodeSignature = try #require(Base64URL.decode("a1H_WM8G73gnMrA-H2vH1q5eMLmxlXw4L_6iIfBM73InJ6BBBq29VsX3t8EM-f9BQGGM-aPWS1KY6DQ6JlJQCA"))
        let key = try Curve25519.Signing.PublicKey(rawRepresentation: identity.publicKeyRaw)
        #expect(key.isValidSignature(nodeSignature, for: Data(payload.utf8)))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: FAIL to compile (`cannot find 'DeviceIdentity' in scope`).

- [ ] **Step 3: Implement the identity**

`ios/GhostCrab/Data/Gateway/DeviceIdentity.swift`:
```swift
import CryptoKit
import Foundation

/// Why a device identity could not be built.
public enum DeviceIdentityError: Error, Equatable, Sendable {
    /// The stored seed is not 32 bytes.
    case invalidSeedLength(Int)
}

/// This install's Ed25519 device identity (ADR-0007). `deviceId` is `hex(sha256(raw public key))`, the fingerprint
/// the gateway checks against `device.publicKey`. Holds only the 32-byte seed; the CryptoKit key is rebuilt per
/// signature. `description`, `debugDescription` and the mirror show the device id and nothing else.
public struct DeviceIdentity: Sendable {

    /// Ed25519 seed length.
    public static let seedBytes = 32

    /// The private seed. Sensitive — never log; only ``DeviceIdentityStore`` persists it.
    let seed: Data

    /// Raw 32-byte public key.
    public let publicKeyRaw: Data

    /// `hex(sha256(publicKeyRaw))`.
    public var deviceId: String { Self.deviceId(forRawPublicKey: publicKeyRaw) }

    /// The raw public key, base64url without padding (`connect.params.device.publicKey`).
    public var publicKeyBase64URL: String { Base64URL.encode(publicKeyRaw) }

    /// - Parameter seed: A 32-byte Ed25519 seed.
    /// - Throws: ``DeviceIdentityError/invalidSeedLength(_:)``, or CryptoKit's error for an unusable key.
    public init(seed: Data) throws {
        guard seed.count == Self.seedBytes else { throw DeviceIdentityError.invalidSeedLength(seed.count) }
        let key = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
        self.seed = seed
        self.publicKeyRaw = key.publicKey.rawRepresentation
    }

    private init(validSeed: Data, publicKeyRaw: Data) {
        self.seed = validSeed
        self.publicKeyRaw = publicKeyRaw
    }

    /// - Returns: A fresh random identity.
    public static func generate() -> DeviceIdentity {
        let key = Curve25519.Signing.PrivateKey()
        return DeviceIdentity(validSeed: key.rawRepresentation, publicKeyRaw: key.publicKey.rawRepresentation)
    }

    /// - Parameter payload: The v3 device-auth payload.
    /// - Returns: The Ed25519 signature, base64url without padding.
    public func sign(_ payload: String) throws -> String {
        let key = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
        return Base64URL.encode(try key.signature(for: Data(payload.utf8)))
    }

    /// - Returns: `hex(sha256(raw))`.
    public static func deviceId(forRawPublicKey raw: Data) -> String {
        SHA256.hash(data: raw).map { String(format: "%02x", $0) }.joined()
    }
}

extension DeviceIdentity: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public var description: String { "DeviceIdentity(deviceId: \(deviceId))" }
    public var debugDescription: String { description }
    public var customMirror: Mirror { Mirror(self, children: ["deviceId": deviceId], displayStyle: .struct) }
}

/// RFC 4648 §5 base64url without padding, as the gateway uses for keys and signatures.
public enum Base64URL {
    public static func encode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    public static func decode(_ text: String) -> Data? {
        var base64 = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder > 0 { base64.append(String(repeating: "=", count: 4 - remainder)) }
        return Data(base64Encoded: base64)
    }
}
```

`ios/GhostCrab/Data/Gateway/DeviceAuthPayload.swift`:
```swift
import Foundation

/// Fields bound into the device signature of `connect` (upstream `buildDeviceAuthPayloadV3`, ADR-0007).
public struct DeviceAuthPayload: Sendable {
    public let deviceId: String
    public let clientId: String
    public let clientMode: String
    public let role: String
    public let scopes: [String]
    /// The challenge `ts` (also sent as `device.signedAt`).
    public let signedAtMs: Int64
    /// The credential sent in `auth` (shared or device token), or `nil`. Sensitive.
    public let token: String?
    /// The challenge nonce (also sent as `device.nonce`).
    public let nonce: String
    public let platform: String
    public let deviceFamily: String?

    /// `v3|deviceId|clientId|mode|role|scopes|signedAt|token|nonce|platform|deviceFamily`.
    public var v3: String {
        [
            "v3", deviceId, clientId, clientMode, role, scopes.joined(separator: ","), String(signedAtMs),
            token ?? "", nonce, Self.normalize(platform), Self.normalize(deviceFamily),
        ].joined(separator: "|")
    }

    /// Upstream `normalizeDeviceMetadataForAuth`: trim, then lowercase ASCII `A`–`Z` only.
    static func normalize(_ value: String?) -> String {
        guard let value else { return "" }
        return String(value.trimmingCharacters(in: .whitespacesAndNewlines).map { character in
            character.isASCII && character.isUppercase ? Character(character.lowercased()) : character
        })
    }
}

extension DeviceAuthPayload: CustomStringConvertible, CustomDebugStringConvertible {
    public var description: String {
        "DeviceAuthPayload(deviceId: \(deviceId), clientId: \(clientId), role: \(role), scopes: \(scopes), "
            + "signedAtMs: \(signedAtMs), token: \(token == nil ? "nil" : "<redacted>"), nonce: \(nonce))"
    }
    public var debugDescription: String { description }
}
```

- [ ] **Step 4: Run the test to verify it passes** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Gateway/DeviceIdentity.swift ios/GhostCrab/Data/Gateway/DeviceAuthPayload.swift ios/GhostCrabTests/Gateway/DeviceIdentityTests.swift
git commit -m "feat(ios): CryptoKit Ed25519 device identity and v3 auth payload" -m "deviceId = hex(sha256(raw public key)); RFC 8032 vectors, the fingerprint the 2026.10.3 gateway accepted, and a Node-produced signature that verifies against our payload (ADR-0007)."
```

---

### Task 7: Keychain stores for the identity and device tokens

**Files:**
- Modify: `ios/GhostCrab/Data/Storage/Keychain.swift` (configurable service)
- Create: `ios/GhostCrab/Data/Storage/DeviceIdentityStore.swift`, `ios/GhostCrab/Data/Storage/DeviceTokenStore.swift`
- Create: `ios/GhostCrabTests/Support/GatewayTestDoubles.swift`
- Test: `ios/GhostCrabTests/Storage/DeviceStoresTests.swift`

The seed lives under its own Keychain service so "Clear all saved gateways" keeps it (pairing stays valid). Device tokens use the token service with an account prefix, so `ProfileStore.clearAll()` (service-wide `deleteAllTokens()`) wipes them with the profile tokens.

- [ ] **Step 1: Write the failing test**

`ios/GhostCrabTests/Storage/DeviceStoresTests.swift`:
```swift
import Foundation
import Testing
@testable import GhostCrab

@Suite("Device identity and token stores")
struct DeviceStoresTests {

    private let token = StoredDeviceToken(token: "dt-test-0001", scopes: TestKeys.operatorScopes, role: "operator", deviceId: "device-1")

    @Test func identityIsCreatedOncePersistedAndReloaded() throws {
        let secrets = InMemoryTokenStore()
        let first = try DeviceIdentityStore(secrets: secrets).identity()
        #expect(secrets.token(for: DeviceIdentityStore.account) != nil)
        #expect(try DeviceIdentityStore(secrets: secrets).identity().deviceId == first.deviceId)
    }

    @Test func anUnusableSeedIsReplaced() throws {
        let secrets = InMemoryTokenStore(tokens: [DeviceIdentityStore.account: Data(count: 5).base64EncodedString()])
        let identity = try DeviceIdentityStore(secrets: secrets).identity()
        let stored = try #require(Data(base64Encoded: try #require(secrets.token(for: DeviceIdentityStore.account))))
        #expect(stored.count == 32)
        #expect(identity.deviceId.count == 64)
    }

    @Test func deviceTokensRoundTripPerGateway() throws {
        let store = DeviceTokenStore(tokens: InMemoryTokenStore())
        try store.save(token, gatewayKey: "http://gw.local:18789")
        #expect(store.load(gatewayKey: "http://gw.local:18789") == token)
        #expect(store.load(gatewayKey: "http://other:18789") == nil)
        store.clear(gatewayKey: "http://gw.local:18789")
        #expect(store.load(gatewayKey: "http://gw.local:18789") == nil)
    }

    @Test func anUndecodableDeviceTokenReadsAsNilAndIsRemoved() {
        let tokens = InMemoryTokenStore(tokens: [DeviceTokenStore.account(for: "a"): "{not json"])
        #expect(DeviceTokenStore(tokens: tokens).load(gatewayKey: "a") == nil)
        #expect(tokens.token(for: DeviceTokenStore.account(for: "a")) == nil)
    }

    @Test func clearAllSavedGatewaysAlsoWipesDeviceTokens() async throws {
        let tokens = InMemoryTokenStore()
        let store = DeviceTokenStore(tokens: tokens)
        try store.save(token, gatewayKey: "a")
        let defaults = try #require(UserDefaults(suiteName: "device-stores-\(UUID().uuidString)"))
        try await ProfileStore(defaults: defaults, keychain: tokens).clearAll()
        #expect(store.load(gatewayKey: "a") == nil)
    }

    @Test func gatewayKeysAreTheNormalisedBaseURL() {
        #expect(GatewayKey.of("http://192.168.1.50:18789/") == "http://192.168.1.50:18789")
        #expect(GatewayKey.of("http://gw.local") == "http://gw.local:18789")
        #expect(GatewayKey.of(" garbage/ ") == "garbage")
    }

    @Test func descriptionRedactsTheToken() {
        #expect(!String(describing: token).contains("dt-test-0001"))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: FAIL to compile (`cannot find 'DeviceIdentityStore' in scope`).

- [ ] **Step 3: Make the Keychain service configurable**

In `Keychain.swift`:
- add a stored property and initializer, replacing `public init() {}`:
```swift
    /// Keychain service of this instance's items.
    private let service: String

    /// - Parameter service: Defaults to the token service; ``DeviceIdentityStore`` uses its own.
    public init(service: String = Keychain.service) {
        self.service = service
    }
```
- in `setToken`, `getToken`, `deleteToken` and `deleteAllTokens`, replace every `kSecAttrService as String: Self.service,` with `kSecAttrService as String: service,`.

- [ ] **Step 4: Add the stores**

`ios/GhostCrab/Data/Storage/DeviceIdentityStore.swift`:
```swift
import Foundation

/// Supplies this install's device identity.
public protocol DeviceIdentityProviding: Sendable {
    /// - Returns: The identity, created and persisted on first use.
    /// - Throws: A Keychain error if a new identity cannot be stored.
    func identity() throws -> DeviceIdentity
}

/// Persists the Ed25519 seed (base64) as a Keychain generic password under its own service
/// (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, no iCloud sync), separate from the token service so
/// "Clear all saved gateways" keeps the identity (ADR-0007). An unreadable or malformed seed is replaced; the
/// gateway then shows a new pairing request for this device.
public final class DeviceIdentityStore: DeviceIdentityProviding, @unchecked Sendable {

    /// Keychain service of the seed item.
    public static let service = "com.qavren.ghostcrab.device-identity"

    /// Keychain account of the seed item.
    static let account = "ed25519-seed-v1"

    private let secrets: any TokenStoring
    private let lock = NSLock()
    private var cached: DeviceIdentity?

    /// - Parameter secrets: Storage; production uses a ``Keychain`` on ``service``.
    public init(secrets: any TokenStoring = Keychain(service: DeviceIdentityStore.service)) {
        self.secrets = secrets
    }

    public func identity() throws -> DeviceIdentity {
        try lock.withLock {
            if let cached { return cached }
            let identity = try loadOrCreate()
            cached = identity
            return identity
        }
    }

    private func loadOrCreate() throws -> DeviceIdentity {
        if let stored = try? secrets.getToken(for: Self.account),
           let seed = Data(base64Encoded: stored),
           let identity = try? DeviceIdentity(seed: seed) {
            return identity
        }
        // Unreadable (Keychain reset) or malformed: the only recovery is a new identity and a new pairing.
        try? secrets.deleteToken(for: Self.account)
        let fresh = DeviceIdentity.generate()
        try secrets.setToken(fresh.seed.base64EncodedString(), for: Self.account)
        return fresh
    }
}
```

`ios/GhostCrab/Data/Storage/DeviceTokenStore.swift`:
```swift
import Foundation

/// A device token the gateway issued in `hello-ok.auth.deviceToken` (ADR-0007).
public struct StoredDeviceToken: Codable, Sendable, Equatable {
    /// The bearer for `connect.params.auth.deviceToken`. Sensitive — never log.
    public let token: String
    /// The scopes it was granted (reused when reconnecting with it).
    public let scopes: [String]
    public let role: String
    /// The ``DeviceIdentity/deviceId`` it belongs to; a token for another identity is ignored.
    public let deviceId: String

    public init(token: String, scopes: [String], role: String, deviceId: String) {
        self.token = token
        self.scopes = scopes
        self.role = role
        self.deviceId = deviceId
    }
}

extension StoredDeviceToken: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public var description: String { "StoredDeviceToken(token: <redacted>, scopes: \(scopes), role: \(role), deviceId: \(deviceId))" }
    public var debugDescription: String { description }
    public var customMirror: Mirror {
        Mirror(self, children: ["token": "<redacted>", "scopes": scopes, "role": role, "deviceId": deviceId], displayStyle: .struct)
    }
}

/// Per-gateway device-token persistence, keyed by ``GatewayKey/of(_:)``.
public protocol DeviceTokenStoring: Sendable {
    /// - Returns: The stored token, or `nil` (also when unreadable; the entry is then removed).
    func load(gatewayKey: String) -> StoredDeviceToken?
    /// Stores `token`, replacing any previous one.
    func save(_ token: StoredDeviceToken, gatewayKey: String) throws
    /// Removes the token. No-op when absent.
    func clear(gatewayKey: String)
}

/// Device tokens as JSON in the token Keychain service (account `device-token:<gatewayKey>`), so
/// `ProfileStore.clearAll()` wipes them with the profile tokens.
public final class DeviceTokenStore: DeviceTokenStoring, Sendable {

    private let tokens: any TokenStoring

    /// - Parameter tokens: Defaults to the app's token ``Keychain``.
    public init(tokens: any TokenStoring = Keychain()) {
        self.tokens = tokens
    }

    static func account(for gatewayKey: String) -> String { "device-token:\(gatewayKey)" }

    public func load(gatewayKey: String) -> StoredDeviceToken? {
        guard let raw = try? tokens.getToken(for: Self.account(for: gatewayKey)) else { return nil }
        guard let decoded = try? JSONDecoder().decode(StoredDeviceToken.self, from: Data(raw.utf8)) else {
            clear(gatewayKey: gatewayKey)
            return nil
        }
        return decoded
    }

    public func save(_ token: StoredDeviceToken, gatewayKey: String) throws {
        let json = String(decoding: try JSONEncoder().encode(token), as: UTF8.self)
        try tokens.setToken(json, for: Self.account(for: gatewayKey))
    }

    public func clear(gatewayKey: String) {
        try? tokens.deleteToken(for: Self.account(for: gatewayKey)) // absent or unreadable: nothing left to clear
    }
}

/// Canonical per-gateway key.
public enum GatewayKey {
    /// - Returns: `scheme://host:port` (``PairingLink/gatewayURL(from:)``), or the trimmed input without trailing `/`.
    public static func of(_ url: String) -> String {
        if let normalised = PairingLink.gatewayURL(from: url) { return normalised.absoluteString }
        var trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        return trimmed
    }
}
```

- [ ] **Step 5: Shared test doubles for later tasks**

`ios/GhostCrabTests/Support/GatewayTestDoubles.swift`:
```swift
import Foundation
@testable import GhostCrab

/// Always returns the RFC 8032 TEST 1 identity.
struct FixedIdentityProvider: DeviceIdentityProviding {
    func identity() throws -> DeviceIdentity { try DeviceIdentity(seed: TestKeys.rfcSeed) }
}

/// Map-backed ``DeviceTokenStoring``.
final class InMemoryDeviceTokenStore: DeviceTokenStoring, @unchecked Sendable {
    private let lock = NSLock()
    private var tokens: [String: StoredDeviceToken]

    init(_ tokens: [String: StoredDeviceToken] = [:]) { self.tokens = tokens }

    func load(gatewayKey: String) -> StoredDeviceToken? { lock.withLock { tokens[gatewayKey] } }
    func save(_ token: StoredDeviceToken, gatewayKey: String) throws { lock.withLock { tokens[gatewayKey] = token } }
    func clear(gatewayKey: String) { lock.withLock { tokens[gatewayKey] = nil } }
}
```

- [ ] **Step 6: Run the tests** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run `DeviceStoresTests` and the existing `ProfileStoreTests` / `ConnectionProfileRepositoryImplTests`. Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Storage ios/GhostCrabTests/Support/GatewayTestDoubles.swift ios/GhostCrabTests/Storage/DeviceStoresTests.swift
git commit -m "feat(ios): Keychain device identity and per-gateway device tokens" -m "Seed under its own service (survives Clear all); device tokens in the token service (wiped by Clear all); unusable seeds are replaced (ADR-0007)."
```

---

### Task 8: Connect handshake

**Files:**
- Create: `ios/GhostCrab/Data/Gateway/GatewayHandshake.swift`
- Test: `ios/GhostCrabTests/Gateway/GatewayHandshakeTests.swift`

- [ ] **Step 1: Write the failing test**

`ios/GhostCrabTests/Gateway/GatewayHandshakeTests.swift`:
```swift
import CryptoKit
import Foundation
import Testing
@testable import GhostCrab

@Suite("Gateway handshake")
struct GatewayHandshakeTests {

    private let client = GatewayClientInfo(id: "openclaw-ios", version: "0.3.0", platform: "ios", displayName: "GhostCrab (iOS)", locale: "en-US")

    private func rpc(_ socket: ScriptedGatewayWebSocket) async -> GatewayRPCClient {
        let rpc = GatewayRPCClient(socket: socket, endpoint: "ws://gw/ws")
        await rpc.start()
        return rpc
    }

    private func connectParams(_ socket: ScriptedGatewayWebSocket) async throws -> [String: Any] {
        try #require(try jsonObject(try #require(await socket.sentRequest("connect")))["params"] as? [String: Any])
    }

    @Test func performSignsTheChallengeAndParsesHelloOk() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        let hello = try await GatewayHandshake.perform(rpc: await rpc(socket), client: client, auth: .sharedToken("shared-token"), identity: identity)

        #expect(hello.serverVersion == "2026.10.3")
        #expect(hello.scopes == TestKeys.operatorScopes)
        #expect(hello.deviceToken == "dt-test-0001")
        #expect(hello.gatewayAuthMode == "token")
        #expect(hello.gatewayHost == "ecb0a0611ff5")
        #expect(hello.methods.contains("skills.status"))

        let params = try await connectParams(socket)
        #expect(params["minProtocol"] as? Int == 4)
        #expect(params["role"] as? String == "operator")
        #expect((params["auth"] as? [String: Any])?["token"] as? String == "shared-token")
        #expect(params["userAgent"] as? String == "ghostcrab-ios/0.3.0")
        let device = try #require(params["device"] as? [String: Any])
        #expect(device["id"] as? String == TestKeys.rfcDeviceId)
        #expect(device["nonce"] as? String == "71d63897-7ebd-44e8-a27a-20ac1598b846")
        #expect(device["signedAt"] as? Int == 1_792_027_507_354)
        let expected = DeviceAuthPayload(
            deviceId: identity.deviceId, clientId: "openclaw-ios", clientMode: "ui", role: "operator",
            scopes: TestKeys.operatorScopes, signedAtMs: 1_792_027_507_354, token: "shared-token",
            nonce: "71d63897-7ebd-44e8-a27a-20ac1598b846", platform: "ios", deviceFamily: nil
        ).v3
        let signature = try #require(Base64URL.decode(try #require(device["signature"] as? String)))
        let key = try Curve25519.Signing.PublicKey(rawRepresentation: identity.publicKeyRaw)
        #expect(key.isValidSignature(signature, for: Data(expected.utf8)))
    }

    @Test func aStoredDeviceTokenIsSentWithItsScopes() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        _ = try await GatewayHandshake.perform(rpc: await rpc(socket), client: client,
                                               auth: .deviceToken("dt-old", scopes: ["operator.read"]),
                                               identity: try DeviceIdentity(seed: TestKeys.rfcSeed))
        let params = try await connectParams(socket)
        #expect((params["auth"] as? [String: Any])?["deviceToken"] as? String == "dt-old")
        #expect(params["scopes"] as? [String] == ["operator.read"])
    }

    @Test func noCredentialSendsNoAuthBlock() async throws {
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        let none = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        _ = try await GatewayHandshake.perform(rpc: await rpc(none), client: client, auth: .none, identity: identity)
        #expect(try await connectParams(none)["auth"] == nil)
    }

    @Test func theIOSIdentityIsTheAllowListedConstant() {
        let info = GatewayClientInfo.current()
        #expect(info.id == "openclaw-ios")
        #expect(info.mode == "ui")
        #expect(info.platform == "ios")
        #expect(info.deviceFamily == nil)
        #expect(info.displayName == "GhostCrab (iOS)")
    }

    @Test func anIdOutsideTheAllowListIsAPlainRPCRejection() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-client-id-invalid"]), closeAfterReplyTo: ["connect"])
        let channel = await rpc(socket)
        let wrongID = GatewayClientInfo(id: "ghostcrab-ios", version: "0.3.0", platform: "ios", displayName: "GhostCrab (iOS)", locale: "en-US")
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        guard case let .rpc(_, _, code, _, message, _)? = await gatewayError({
            _ = try await GatewayHandshake.perform(rpc: channel, client: wrongID, auth: .sharedToken("shared-token"), identity: identity)
        }) else {
            Issue.record("expected rpc")
            return
        }
        #expect(code == "INVALID_REQUEST")
        #expect(message.contains("/client/id"))
    }

    @Test func aPlatformChangeOnAPairedDeviceAsksForReApproval() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-pairing-metadata-upgrade"]), closeAfterReplyTo: ["connect"])
        let channel = await rpc(socket)
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        guard case let .pairingRequired(_, requestId, reason, _)? = await gatewayError({
            _ = try await GatewayHandshake.perform(rpc: channel, client: client, auth: .sharedToken("shared-token"), identity: identity)
        }) else {
            Issue.record("expected pairingRequired")
            return
        }
        #expect(reason == "metadata-upgrade")
        #expect(requestId == "6837c6b0-f559-445b-bc0c-a50fbe5b5998")
    }

    @Test func pairingRequiredAndTokenMismatchAreTyped() async throws {
        let identity = try DeviceIdentity(seed: TestKeys.rfcSeed)
        let pairing = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-pairing-required"]), closeAfterReplyTo: ["connect"])
        let pairingRPC = await rpc(pairing)
        guard case .pairingRequired(_, "af0b5d81-7bd2-4b86-80b0-4a489b541974", _, _)? = await gatewayError({
            _ = try await GatewayHandshake.perform(rpc: pairingRPC, client: client, auth: .sharedToken("t"), identity: identity)
        }) else {
            Issue.record("expected pairingRequired")
            return
        }
        let mismatch = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-token-mismatch"]), closeAfterReplyTo: ["connect"])
        let mismatchRPC = await rpc(mismatch)
        guard case .connectRejected(_, "AUTH_TOKEN_MISMATCH", _, _)? = await gatewayError({
            _ = try await GatewayHandshake.perform(rpc: mismatchRPC, client: client, auth: .sharedToken("wrong"), identity: identity)
        }) else {
            Issue.record("expected connectRejected")
            return
        }
    }

    @Test func aTokenOnlyHelloHasNoScopes() throws {
        guard case .response(let response)? = GatewayFrameCodec.decode(try GatewayFixtures.text("hello-ok-noscope")) else {
            Issue.record("fixture is not a response")
            return
        }
        let hello = try GatewayHandshake.parseHello(endpoint: "ws://gw/ws", payload: response.payload)
        #expect(hello.scopes.isEmpty)
        #expect(hello.gatewayAuthMode == nil)
        #expect(hello.gatewayHost == nil)
    }

    @Test func aChallengeWithoutTsIsAProtocolViolation() async throws {
        let socket = try ScriptedGatewayWebSocket(challenge: false)
        await socket.push(#"{"type":"event","event":"connect.challenge","payload":{"nonce":"n"}}"#)
        let channel = await rpc(socket)
        guard case .protocolViolation? = await gatewayError({
            _ = try await GatewayHandshake.perform(rpc: channel, client: client, auth: .none, identity: try DeviceIdentity(seed: TestKeys.rfcSeed))
        }) else {
            Issue.record("expected protocolViolation")
            return
        }
    }

    @Test func authRequirementCapabilitiesAndCredentialOrder() throws {
        guard case .response(let response)? = GatewayFrameCodec.decode(try GatewayFixtures.text("hello-ok-paired")) else {
            Issue.record("fixture is not a response")
            return
        }
        let hello = try GatewayHandshake.parseHello(endpoint: "ws://gw/ws", payload: response.payload)
        #expect(hello.authRequirement(token: nil) == .token)
        #expect(hello.capabilities == ["config", "models", "skills"])
        let stored = StoredDeviceToken(token: "dt", scopes: ["operator.read"], role: "operator", deviceId: TestKeys.rfcDeviceId)
        guard case .sharedToken = GatewayConnectAuth.select(sharedToken: "t", stored: stored),
              case .deviceToken = GatewayConnectAuth.select(sharedToken: nil, stored: stored),
              case .none = GatewayConnectAuth.select(sharedToken: nil, stored: nil) else {
            Issue.record("credential order differs from upstream selectGatewayConnectAuth")
            return
        }
    }
}
```
(`AuthRequirement` already conforms to `Equatable`; if it does not, compare with `if case .token = …`.)

- [ ] **Step 2: Run it to make sure it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: FAIL to compile (`cannot find 'GatewayClientInfo' in scope`).

- [ ] **Step 3: Implement the handshake**

`ios/GhostCrab/Data/Gateway/GatewayHandshake.swift`:
```swift
import Foundation

/// `connect.params.client` (ADR-0007). `id` and `mode` must be values upstream's allow-lists accept (checked before
/// auth: anything else is `INVALID_REQUEST invalid connect params`, close 1008); `version` and `displayName` stay
/// honest. `platform` is bound into the device signature and pinned when the device is approved: changing it
/// re-triggers pairing (`metadata-upgrade`), so it is a constant, never derived from the device. No `deviceFamily`.
public struct GatewayClientInfo: Sendable, Equatable {

    /// Upstream allow-listed id for the iOS app; any other id is rejected before auth (verified 2026-10-15).
    public static let iosClientID = "openclaw-ios"

    /// Pinned at approval (case-insensitive). The same on iPhone, iPad and Mac Catalyst; never derive it.
    public static let iosPlatform = "ios"

    public let id: String
    public let version: String
    public let platform: String
    public let displayName: String
    public let mode: String
    public let deviceFamily: String?
    public let locale: String

    public init(id: String, version: String, platform: String, displayName: String, mode: String = "ui",
                deviceFamily: String? = nil, locale: String) {
        self.id = id
        self.version = version
        self.platform = platform
        self.displayName = displayName
        self.mode = mode
        self.deviceFamily = deviceFamily
        self.locale = locale
    }

    /// `connect.params.userAgent`.
    public var userAgent: String { "ghostcrab-\(platform)/\(version)" }

    /// This build's client info: the constant id, mode and platform with this build's version.
    public static func current(bundle: Bundle = .main) -> GatewayClientInfo {
        GatewayClientInfo(
            id: iosClientID,
            version: bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0",
            platform: iosPlatform,
            displayName: "GhostCrab (iOS)",
            locale: Locale.current.identifier(.bcp47)
        )
    }
}

/// Credential for `connect.params.auth`. `description` redacts every secret.
public enum GatewayConnectAuth: Sendable {
    /// The gateway's shared token (`auth.token`).
    case sharedToken(String)
    /// A device token this gateway issued earlier (`auth.deviceToken`), reused with its granted scopes.
    case deviceToken(String, scopes: [String])
    /// No credential: `auth.mode: none` gateways and the auth probe.
    case none

    /// Upstream `selectGatewayConnectAuth` order for an operator client: shared token, stored device token, nothing.
    /// Setup-code bootstrap tokens are not a credential here: the gateway accepts them only for `role: "node"`
    /// (verified 2026-10-15, ADR-0007).
    public static func select(sharedToken: String?, stored: StoredDeviceToken?) -> GatewayConnectAuth {
        if let sharedToken { return .sharedToken(sharedToken) }
        if let stored { return .deviceToken(stored.token, scopes: stored.scopes) }
        return .none
    }

    /// The value bound into the signature's token field.
    var signatureToken: String? {
        switch self {
        case .sharedToken(let token), .deviceToken(let token, _): return token
        case .none: return nil
        }
    }

    /// Scopes to request: a device token's own (when it has some), else the operator set.
    var scopes: [String] {
        if case let .deviceToken(_, scopes) = self, !scopes.isEmpty { return scopes }
        return GatewayHandshake.operatorScopes
    }
}

extension GatewayConnectAuth: CustomStringConvertible, CustomDebugStringConvertible {
    public var description: String {
        switch self {
        case .sharedToken: return "sharedToken(<redacted>)"
        case let .deviceToken(_, scopes): return "deviceToken(<redacted>, scopes: \(scopes))"
        case .none: return "none"
        }
    }
    public var debugDescription: String { description }
}

/// `connect.challenge` payload.
public struct ConnectChallenge: Sendable, Equatable {
    public let nonce: String
    public let ts: Int64

    /// - Throws: ``GatewayError/protocolViolation(url:detail:)`` unless there is a non-blank `nonce` and a
    ///   non-negative integer `ts` (upstream treats anything else as invalid).
    public static func parse(endpoint: String, payload: AnyCodable?) throws -> ConnectChallenge {
        let object = payload?.objectValue
        guard let nonce = object?["nonce"]?.value as? String, !nonce.trimmingCharacters(in: .whitespaces).isEmpty,
              let ts = object?["ts"]?.value as? Int, ts >= 0 else {
            throw GatewayError.protocolViolation(url: endpoint, detail: "connect.challenge without a nonce and a non-negative integer ts")
        }
        return ConnectChallenge(nonce: nonce, ts: Int64(ts))
    }
}

/// The parts of `hello-ok` GhostCrab uses. `description` redacts the device token.
public struct GatewayHello: Sendable, Equatable {
    public let serverVersion: String
    public let methods: Set<String>
    /// `auth.method`: `token`, `device-token`, `bootstrap-token`, `none`, ….
    public let authMethod: String?
    public let role: String
    /// `auth.scopes`, in server order: the socket's live authority.
    public let scopes: [String]
    /// `auth.deviceToken`, when issued. Sensitive.
    public let deviceToken: String?
    /// `snapshot.authMode`; absent on a scope-less socket.
    public let gatewayAuthMode: String?
    /// `host` of the `snapshot.presence` entry with `reason: "self"`.
    public let gatewayHost: String?

    /// ADR-0007 replacement for ADR-002's probe.
    public func authRequirement(token: String?) -> AuthRequirement {
        switch gatewayAuthMode ?? authMethod {
        case "none": return .none
        case nil: return token == nil ? .none : .token
        default: return .token
        }
    }

    /// GhostCrab capability keys these methods support (Dashboard chips and gating).
    public var capabilities: [String] {
        var keys: [String] = []
        if methods.contains("config.get"), methods.contains("config.patch") { keys.append("config") }
        if methods.contains("models.list") { keys.append("models") }
        if methods.contains("skills.status") { keys.append("skills") }
        return keys
    }
}

extension GatewayHello: CustomStringConvertible {
    public var description: String {
        "GatewayHello(serverVersion: \(serverVersion), methods: \(methods.count), authMethod: \(authMethod ?? "nil"), "
            + "scopes: \(scopes), deviceToken: \(deviceToken == nil ? "nil" : "<redacted>"), "
            + "gatewayAuthMode: \(gatewayAuthMode ?? "nil"), gatewayHost: \(gatewayHost ?? "nil"))"
    }
}

/// `connect.params`.
struct ConnectParams: Encodable, Sendable {
    struct Client: Encodable, Sendable {
        let id: String
        let version: String
        let platform: String
        let mode: String
        let displayName: String
        let deviceFamily: String?
    }
    struct Auth: Encodable, Sendable {
        let token: String?
        let deviceToken: String?
    }
    struct Device: Encodable, Sendable {
        let id: String
        let publicKey: String
        let signature: String
        let signedAt: Int64
        let nonce: String
    }
    let minProtocol: Int
    let maxProtocol: Int
    let client: Client
    let role: String
    let scopes: [String]
    let caps: [String]
    let commands: [String]
    let permissions: [String: Bool]
    let auth: Auth?
    let locale: String
    let userAgent: String
    let device: Device
}

/// The `connect` exchange (ADR-0007 decision 3).
public enum GatewayHandshake {

    /// GhostCrab connects as an operator client.
    public static let roleOperator = "operator"

    /// Requested unless a stored device token says otherwise (`config.*` and `skills.install` need admin).
    public static let operatorScopes = ["operator.read", "operator.write", "operator.admin"]

    /// Time for the gateway to answer `connect`.
    public static let connectTimeout: Duration = .seconds(15)

    /// Waits for the challenge, sends the signed `connect`, parses `hello-ok`.
    ///
    /// - Throws: `.pairingRequired`, `.connectRejected`, `.protocolViolation`, `.timeout`, `.unreachable`.
    public static func perform(rpc: GatewayRPCClient, client: GatewayClientInfo, auth: GatewayConnectAuth,
                               identity: DeviceIdentity) async throws -> GatewayHello {
        let event = try await rpc.awaitChallenge()
        let challenge = try ConnectChallenge.parse(endpoint: rpc.endpoint, payload: event.payload)
        let params = try connectParams(client: client, auth: auth, identity: identity, challenge: challenge)
        let payload = try await rpc.request("connect", params: params, timeout: connectTimeout)
        return try parseHello(endpoint: rpc.endpoint, payload: payload)
    }

    static func connectParams(client: GatewayClientInfo, auth: GatewayConnectAuth, identity: DeviceIdentity,
                              challenge: ConnectChallenge) throws -> ConnectParams {
        let scopes = auth.scopes
        let signed = DeviceAuthPayload(
            deviceId: identity.deviceId, clientId: client.id, clientMode: client.mode, role: roleOperator,
            scopes: scopes, signedAtMs: challenge.ts, token: auth.signatureToken, nonce: challenge.nonce,
            platform: client.platform, deviceFamily: client.deviceFamily
        ).v3
        let authBlock: ConnectParams.Auth? = switch auth {
        case .sharedToken(let token): ConnectParams.Auth(token: token, deviceToken: nil)
        case .deviceToken(let token, _): ConnectParams.Auth(token: nil, deviceToken: token)
        case .none: nil
        }
        return ConnectParams(
            minProtocol: GatewayProtocol.version,
            maxProtocol: GatewayProtocol.version,
            client: .init(id: client.id, version: client.version, platform: client.platform, mode: client.mode,
                          displayName: client.displayName, deviceFamily: client.deviceFamily),
            role: roleOperator,
            scopes: scopes,
            caps: [],
            commands: [],
            permissions: [:],
            auth: authBlock,
            locale: client.locale,
            userAgent: client.userAgent,
            device: .init(id: identity.deviceId, publicKey: identity.publicKeyBase64URL,
                          signature: try identity.sign(signed), signedAt: challenge.ts, nonce: challenge.nonce)
        )
    }

    private struct HelloWire: Decodable {
        struct Server: Decodable { let version: String }
        struct Features: Decodable { let methods: [String]? }
        struct Presence: Decodable { let host: String?; let reason: String? }
        struct Snapshot: Decodable { let authMode: String?; let presence: [Presence]? }
        struct Auth: Decodable { let method: String?; let role: String?; let scopes: [String]?; let deviceToken: String? }
        enum CodingKeys: String, CodingKey { case type, protocolVersion = "protocol", server, features, snapshot, auth }
        let type: String
        let protocolVersion: Int
        let server: Server
        let features: Features?
        let snapshot: Snapshot?
        let auth: Auth
    }

    /// - Throws: ``GatewayError/protocolViolation(url:detail:)`` unless `payload` is a protocol-4 `hello-ok`.
    public static func parseHello(endpoint: String, payload: AnyCodable?) throws -> GatewayHello {
        guard let payload, let wire = try? payload.decoded(as: HelloWire.self), wire.type == "hello-ok" else {
            throw GatewayError.protocolViolation(url: endpoint, detail: "connect answered without a hello-ok payload")
        }
        guard wire.protocolVersion == GatewayProtocol.version else {
            throw GatewayError.protocolViolation(url: endpoint, detail: "gateway negotiated protocol \(wire.protocolVersion); GhostCrab speaks \(GatewayProtocol.version)")
        }
        return GatewayHello(
            serverVersion: wire.server.version,
            methods: Set(wire.features?.methods ?? []),
            authMethod: wire.auth.method,
            role: wire.auth.role ?? roleOperator,
            scopes: wire.auth.scopes ?? [],
            deviceToken: wire.auth.deviceToken,
            gatewayAuthMode: wire.snapshot?.authMode,
            gatewayHost: wire.snapshot?.presence?.first { $0.reason == "self" }?.host
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it passes** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Gateway/GatewayHandshake.swift ios/GhostCrabTests/Gateway/GatewayHandshakeTests.swift
git commit -m "feat(ios): connect handshake with signed device identity" -m "challenge -> v3-signed connect -> hello-ok; upstream credential order for operators (shared, device, none); the allow-listed client identity is a constant (openclaw-ios, ui, ios) because a changed platform re-triggers pairing; auth mode, scopes, device token, host and capabilities from hello-ok (ADR-0007)."
```

---
### Task 9: Connection manager on the WS handshake

**Files:**
- Create: `ios/GhostCrab/Data/Gateway/GatewayWebSocketURL.swift`
- Rewrite: `ios/GhostCrab/Data/Impl/GatewayConnectionManagerImpl.swift` (the actor; the factory, `SnapshotBox` and `AlwaysBlockCleartextSettingsRepository` stay)
- Modify: `ios/GhostCrab/App/AppContainer.swift`
- Modify: `ios/GhostCrabTests/Support/GatewayTestDoubles.swift`, `ios/GhostCrabTests/Api/GatewayConnectionManagerCancellationTests.swift`
- Test: `ios/GhostCrabTests/Gateway/GatewayWebSocketURLTests.swift`, `ios/GhostCrabTests/Gateway/GatewayConnectionManagerImplTests.swift`

After this task connect and the auth probe use the protocol-4 socket. Config, models, AI, skills and the scope probe still go through `requireClient()` until Tasks 10–14; against a real gateway they fail exactly as they do today.

- [ ] **Step 1: Write the failing URL test**

`ios/GhostCrabTests/Gateway/GatewayWebSocketURLTests.swift`:
```swift
import Foundation
import Testing
@testable import GhostCrab

@Suite("Gateway WebSocket URL")
struct GatewayWebSocketURLTests {

    private func make(_ base: String, allow: Bool = false) throws -> String {
        try GatewayWebSocketURL.make(from: try #require(URL(string: base)), allowCleartextPublicIPs: allow).absoluteString
    }

    @Test func httpBecomesWsAndHttpsBecomesWss() throws {
        #expect(try make("http://192.168.1.50:18789") == "ws://192.168.1.50:18789/ws")
        #expect(try make("https://gw.example:443/") == "wss://gw.example:443/ws")
    }

    @Test func cleartextToAPublicIPNeedsTheOptIn() async throws {
        guard case .cleartextBlocked(let url, let host)? = await gatewayError({ _ = try make("http://8.8.8.8:18789") }) else {
            Issue.record("expected cleartextBlocked")
            return
        }
        #expect(url == "ws://8.8.8.8:18789/ws")
        #expect(host == "8.8.8.8")
        #expect(try make("http://8.8.8.8:18789", allow: true) == "ws://8.8.8.8:18789/ws")
        #expect(try make("https://8.8.8.8:443") == "wss://8.8.8.8:443/ws")
    }

    @Test func nonHTTPBaseURLsAreRejected() async {
        guard case .api(_, 0, _)? = await gatewayError({ _ = try make("ftp://gw.local:21") }) else {
            Issue.record("expected api(statusCode: 0)")
            return
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run the test command with `GatewayWebSocketURLTests`. Expected: FAIL to compile (`cannot find 'GatewayWebSocketURL' in scope`).

- [ ] **Step 3: Implement the URL builder**

`ios/GhostCrab/Data/Gateway/GatewayWebSocketURL.swift`:
```swift
import Foundation

/// The control-plane socket URL for a gateway base URL (ADR-0007), with the app's cleartext policy applied.
/// ``CleartextPublicIPGuard`` is a `URLProtocol` and never sees `URLSessionWebSocketTask`s, so the same rule is
/// enforced here before a socket is built. ATS still applies to `ws://` (ADR-0006).
public enum GatewayWebSocketURL {

    /// Path the gateway serves its WebSocket on.
    public static let path = "/ws"

    /// - Parameters:
    ///   - baseURL: `http(s)://host:port`; any path, query or fragment is replaced.
    ///   - allowCleartextPublicIPs: The Settings opt-in.
    /// - Returns: `ws(s)://host:port/ws`.
    /// - Throws: ``GatewayError/cleartextBlocked(url:host:)`` for `ws://` to a public IP literal without the
    ///   opt-in; ``GatewayError/api(url:statusCode:body:)`` (status 0) when `baseURL` is not `http(s)` with a host.
    public static func make(from baseURL: URL, allowCleartextPublicIPs: Bool) throws -> URL {
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false),
              let scheme = components.scheme?.lowercased(), scheme == "http" || scheme == "https",
              let host = components.host, !host.isEmpty
        else {
            throw GatewayError.api(url: baseURL.absoluteString, statusCode: 0, body: "Not an http(s) gateway URL")
        }
        components.scheme = scheme == "https" ? "wss" : "ws"
        components.path = path
        components.query = nil
        components.fragment = nil
        guard let url = components.url else {
            throw GatewayError.api(url: baseURL.absoluteString, statusCode: 0, body: "Malformed URL")
        }
        if scheme == "http", !allowCleartextPublicIPs, CleartextPublicIPGuard.isPublicIPLiteral(host) {
            throw GatewayError.cleartextBlocked(url: url.absoluteString, host: host)
        }
        return url
    }
}
```

- [ ] **Step 4: Run the URL test** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: PASS (3 tests).

- [ ] **Step 5: Shared doubles for manager tests**

Append to `ios/GhostCrabTests/Support/GatewayTestDoubles.swift`:
```swift

/// REST clients routed through ``MockURLProtocol`` (register a handler for the base URL to make `/health` answer).
struct MockRESTFactory: OpenClawAPIClientFactory {
    func unauthenticated(baseURL: URL, allowCleartextPublicIPs: Bool) -> OpenClawAPIClient {
        OpenClawAPIClient.makeForTesting(
            baseURL: baseURL,
            allowCleartextPublicIPs: allowCleartextPublicIPs,
            protocolClasses: [MockURLProtocol.self]
        )
    }

    func authenticated(baseURL: URL, token: String, allowCleartextPublicIPs: Bool) -> OpenClawAPIClient {
        unauthenticated(baseURL: baseURL, allowCleartextPublicIPs: allowCleartextPublicIPs)
    }
}

/// `params` of the first `method` request `socket` received.
func sentParams(_ socket: ScriptedGatewayWebSocket, _ method: String) async throws -> [String: Any] {
    let text = try #require(await socket.sentRequest(method))
    return try #require(try jsonObject(text)["params"] as? [String: Any])
}

/// `{"type":"res","ok":true,"payload":{"ok":true}}`: a minimal success for writes whose answer a test does not read.
/// Tests that read the answer use the captured frames (`res-config-patch`, `res-skills-update`, `res-skills-install`).
let okReply = #"{"type":"res","id":"x","ok":true,"payload":{"ok":true}}"#

/// An `ok:false` frame with `code`, `message` and optional `details.code`.
func rejectionFrame(code: String = "INVALID_REQUEST", message: String, detailCode: String? = nil) throws -> String {
    var error: [String: Any] = ["code": code, "message": message]
    if let detailCode { error["details"] = ["code": detailCode] }
    let frame: [String: Any] = ["type": "res", "id": "x", "ok": false, "error": error]
    return String(decoding: try JSONSerialization.data(withJSONObject: frame), as: UTF8.self)
}

/// A started RPC client over `socket` (no challenge needed for repository tests).
func startedRPC(_ socket: ScriptedGatewayWebSocket, endpoint: String = "ws://gw/ws") async -> GatewayRPCClient {
    let rpc = GatewayRPCClient(socket: socket, endpoint: endpoint)
    await rpc.start()
    return rpc
}
```

- [ ] **Step 6: Write the failing manager tests**

`ios/GhostCrabTests/Gateway/GatewayConnectionManagerImplTests.swift`:
```swift
import Foundation
import Testing
@testable import GhostCrab

@Suite("GatewayConnectionManagerImpl (protocol 4)")
struct GatewayConnectionManagerImplTests {

    private static let clientInfo = GatewayClientInfo(
        id: "openclaw-ios", version: "0.3.0", platform: "ios", displayName: "GhostCrab (iOS)", locale: "en-US"
    )

    /// Swift Testing builds a fresh suite value per test, so every test gets its own store.
    private let tokens = InMemoryDeviceTokenStore()

    /// A gateway at a fresh `.local` URL whose `/health` answers 200 (unless `healthy` is false) and whose socket is `socket`.
    private func makeManager(_ socket: ScriptedGatewayWebSocket, healthy: Bool = true) -> (GatewayConnectionManagerImpl, String) {
        let base = MockURLProtocol.uniqueBaseURL()
        if healthy {
            MockURLProtocol.register(base) { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        }
        let manager = GatewayConnectionManagerImpl(
            identityProvider: FixedIdentityProvider(),
            deviceTokens: tokens,
            clientInfo: Self.clientInfo,
            clientFactory: MockRESTFactory(),
            socketFactory: FixedSocketFactory(socket)
        )
        return (manager, base.absoluteString)
    }

    private func connectFailure(_ manager: GatewayConnectionManagerImpl, _ url: String, token: String?) async -> GatewayError? {
        await gatewayError { try await manager.connect(url: url, token: token) }
    }

    // MARK: connect

    @Test func sharedTokenConnectSignsTheHandshakeAndLandsConnected() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let (manager, url) = makeManager(socket)
        try await manager.connect(url: url, token: "shared-token")

        guard case let .connected(stateURL, name, version, auth, isHttps, capabilities, hardware, token) = manager.currentState else {
            Issue.record("expected connected, got \(manager.currentState)")
            return
        }
        #expect(stateURL == url)
        #expect(name == "ecb0a0611ff5")
        #expect(version == "2026.10.3")
        #expect(auth == .token)
        #expect(!isHttps)
        #expect(capabilities == ["config", "models", "skills"])
        #expect(hardware == nil)
        #expect(token == "shared-token")
        let params = try await sentParams(socket, "connect")
        #expect((params["auth"] as? [String: Any])?["token"] as? String == "shared-token")
        #expect((params["device"] as? [String: Any])?["id"] as? String == TestKeys.rfcDeviceId)
    }

    @Test func theIssuedDeviceTokenIsStoredWithTheGrantedScopes() async throws {
        let (manager, url) = makeManager(try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"])))
        try await manager.connect(url: url, token: "shared-token")
        let expected = StoredDeviceToken(token: "dt-test-0001", scopes: TestKeys.operatorScopes, role: "operator", deviceId: TestKeys.rfcDeviceId)
        #expect(tokens.load(gatewayKey: GatewayKey.of(url)) == expected)
    }

    @Test func pairingRequiredSurfacesTheRequestIdAndLandsInError() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-pairing-required"]), closeAfterReplyTo: ["connect"])
        let (manager, url) = makeManager(socket)
        guard case .pairingRequired(_, "af0b5d81-7bd2-4b86-80b0-4a489b541974", _, _)? = await connectFailure(manager, url, token: "shared-token") else {
            Issue.record("expected pairingRequired")
            return
        }
        guard case .error(_, .pairingRequired) = manager.currentState else {
            Issue.record("expected error state, got \(manager.currentState)")
            return
        }
        #expect(await socket.closedByClient)
    }

    @Test func aWrongTokenIsAConnectRejection() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-token-mismatch"]), closeAfterReplyTo: ["connect"])
        let (manager, url) = makeManager(socket)
        guard case .connectRejected(_, "AUTH_TOKEN_MISMATCH", _, _)? = await connectFailure(manager, url, token: "wrong") else {
            Issue.record("expected connectRejected")
            return
        }
    }

    @Test func anUnreachableGatewayNeverOpensTheSocket() async throws {
        let socket = try ScriptedGatewayWebSocket()
        let (manager, url) = makeManager(socket, healthy: false)
        guard case .unreachable? = await connectFailure(manager, url, token: nil) else {
            Issue.record("expected unreachable")
            return
        }
        #expect(await socket.sentMethods().isEmpty)
        guard case .error = manager.currentState else {
            Issue.record("expected error state")
            return
        }
    }

    @Test func withoutASharedTokenTheStoredDeviceTokenIsReusedWithItsScopes() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let (manager, url) = makeManager(socket)
        try tokens.save(StoredDeviceToken(token: "dt-old", scopes: ["operator.read"], role: "operator", deviceId: TestKeys.rfcDeviceId), gatewayKey: GatewayKey.of(url))
        try await manager.connect(url: url, token: nil)
        let params = try await sentParams(socket, "connect")
        #expect((params["auth"] as? [String: Any])?["deviceToken"] as? String == "dt-old")
        #expect(params["scopes"] as? [String] == ["operator.read"])
    }

    @Test func aDeviceTokenStoredForAnotherIdentityIsIgnored() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-token-missing"]), closeAfterReplyTo: ["connect"])
        let (manager, url) = makeManager(socket)
        try tokens.save(StoredDeviceToken(token: "dt-other", scopes: ["operator.read"], role: "operator", deviceId: "another-device"), gatewayKey: GatewayKey.of(url))
        guard case .connectRejected(_, "AUTH_TOKEN_MISSING", _, _)? = await connectFailure(manager, url, token: nil) else {
            Issue.record("expected connectRejected")
            return
        }
        #expect(try await sentParams(socket, "connect")["auth"] == nil)
    }

    @Test func aRejectedDeviceTokenIsForgotten() async throws {
        let rejection = try rejectionFrame(message: "unauthorized: device token mismatch", detailCode: "AUTH_DEVICE_TOKEN_MISMATCH")
        let socket = try ScriptedGatewayWebSocket(replies: ["connect": [rejection]])
        let (manager, url) = makeManager(socket)
        let key = GatewayKey.of(url)
        try tokens.save(StoredDeviceToken(token: "dt-revoked", scopes: TestKeys.operatorScopes, role: "operator", deviceId: TestKeys.rfcDeviceId), gatewayKey: key)
        _ = await connectFailure(manager, url, token: nil)
        #expect(tokens.load(gatewayKey: key) == nil)
    }

    // MARK: probeAuth

    @Test func probeAuthMapsAMissingTokenToTokenAndSendsNoCredential() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-token-missing"]))
        let (manager, url) = makeManager(socket)
        #expect(try await manager.probeAuth(url: url) == .token)
        #expect(try await sentParams(socket, "connect")["auth"] == nil)
        #expect(await socket.closedByClient)
    }

    @Test func probeAuthMapsAPendingApprovalToNone() async throws {
        let (manager, url) = makeManager(try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "err-pairing-required"])))
        #expect(try await manager.probeAuth(url: url) == .none)
    }

    @Test func probeAuthReadsTheAuthModeFromHelloOk() async throws {
        let (manager, url) = makeManager(try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"])))
        #expect(try await manager.probeAuth(url: url) == .token)
    }

    // MARK: session

    @Test func grantedScopesComeFromHelloOkAndDisconnectDropsThem() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired", "health": "res-health"]))
        let (manager, url) = makeManager(socket)
        try await manager.connect(url: url, token: "shared-token")
        #expect(await manager.grantedScopes() == Set(TestKeys.operatorScopes))
        _ = try await manager.requireSession().request("health")
        #expect(await socket.sentMethods() == ["connect", "health"])

        await manager.disconnect()
        #expect(await manager.grantedScopes() == nil)
        #expect(await socket.closedByClient)
        await #expect(throws: GatewayError.self) { _ = try await manager.requireSession() }
        guard case .disconnected = manager.currentState else {
            Issue.record("expected disconnected")
            return
        }
    }

    @Test(.timeLimit(.minutes(1)))
    func aDroppedSessionSocketEndsInError() async throws {
        let socket = try ScriptedGatewayWebSocket(replies: try .fixtures(["connect": "hello-ok-paired"]))
        let (manager, url) = makeManager(socket)
        try await manager.connect(url: url, token: "shared-token")
        await socket.drop()
        while case .connected = manager.currentState {
            await Task.yield()
        }
        guard case .error(_, .unreachable) = manager.currentState else {
            Issue.record("expected error(unreachable), got \(manager.currentState)")
            return
        }
        #expect(await manager.grantedScopes() == nil)
    }
}
```

- [ ] **Step 7: Run them to make sure they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run the test command with `GatewayConnectionManagerImplTests`. Expected: FAIL to compile (`extra arguments at positions #1, #2` / `incorrect argument labels in call`).


- [ ] **Step 8: Rewrite the manager**

In `ios/GhostCrab/Data/Impl/GatewayConnectionManagerImpl.swift`, keep the `// MARK: - Client factory` section (protocol and `DefaultOpenClawAPIClientFactory`) as it is, and replace everything from `// MARK: - Connection manager` down to, but not including, `// ── Snapshot box impl` with:
```swift
// MARK: - Connection manager

/// Production ``GatewayConnectionManager`` (ADR-0007): `GET /health` (reachability and the cleartext policy, before
/// any credential leaves the device), then one control-plane WebSocket per session opened with a signed `connect`.
/// Repositories call RPCs on ``requireSession()``.
///
/// The actor serialises `connect` / `disconnect` / `probeAuth`. State is exposed through ``connectionStates()``
/// (latest value replayed on subscribe) and the synchronous ``currentState`` snapshot. A session socket that the
/// gateway drops moves the state to `.error(.unreachable)`.
public actor GatewayConnectionManagerImpl: GatewayConnectionManager {

    /// The live session: REST client (`/health`, teardown), control-plane socket, and the hello that opened it.
    private struct ActiveSession {
        let client: OpenClawAPIClient
        let rpc: GatewayRPCClient
        let hello: GatewayHello
    }

    private static let deviceTokenMismatch = "AUTH_DEVICE_TOKEN_MISMATCH"
    private static let credentialMissingCodes: Set<String> = ["AUTH_TOKEN_MISSING", "AUTH_PASSWORD_MISSING", "AUTH_REQUIRED"]
    private static let defaultDisplayName = "OpenClaw Gateway"
    private static let log = PrivacySafeLogger(category: "gateway")

    // ── State ────────────────────────────────────────────────────────────────

    private var state: GatewayConnection = .disconnected
    private var subscribers: [UUID: AsyncStream<GatewayConnection>.Continuation] = [:]
    private var active: ActiveSession?
    private var watcher: Task<Void, Never>?

    private let identityProvider: any DeviceIdentityProviding
    private let deviceTokens: any DeviceTokenStoring
    private let clientInfo: GatewayClientInfo
    private let clientFactory: any OpenClawAPIClientFactory
    private let socketFactory: any GatewaySocketFactory
    private let settingsRepository: any SettingsRepository

    // ── Init ─────────────────────────────────────────────────────────────────

    /// - Parameters:
    ///   - identityProvider: This install's Ed25519 identity.
    ///   - deviceTokens: Per-gateway device tokens from earlier `hello-ok`s.
    ///   - clientInfo: `connect.params.client`; defaults to this build's.
    ///   - clientFactory: Builds the REST client for `/health`. Injectable for tests.
    ///   - socketFactory: Builds the control-plane socket. Injectable for tests.
    ///   - settingsRepository: Source of the cleartext-to-public-IP opt-in. Defaults to always-block.
    public init(
        identityProvider: any DeviceIdentityProviding,
        deviceTokens: any DeviceTokenStoring,
        clientInfo: GatewayClientInfo = .current(),
        clientFactory: any OpenClawAPIClientFactory = DefaultOpenClawAPIClientFactory(),
        socketFactory: any GatewaySocketFactory = URLSessionGatewaySocketFactory(),
        settingsRepository: any SettingsRepository = AlwaysBlockCleartextSettingsRepository()
    ) {
        self.identityProvider = identityProvider
        self.deviceTokens = deviceTokens
        self.clientInfo = clientInfo
        self.clientFactory = clientFactory
        self.socketFactory = socketFactory
        self.settingsRepository = settingsRepository
    }

    // ── GatewayConnectionManager: state ──────────────────────────────────────

    /// Synchronous snapshot of the current state (lock-protected; no actor hop).
    public nonisolated var currentState: GatewayConnection {
        snapshotBox.value
    }

    /// Replays the latest state on subscribe, then every transition. Each call yields an independent stream.
    public nonisolated func connectionStates() -> AsyncStream<GatewayConnection> {
        AsyncStream { continuation in
            let id = UUID()
            Task { [weak self] in
                guard let self else {
                    continuation.finish()
                    return
                }
                await self.registerAndReplay(id: id, continuation: continuation)
            }
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                Task { await self.unregister(id: id) }
            }
        }
    }

    // ── GatewayConnectionManager: actions ────────────────────────────────────

    /// ADR-0007 decision 8: `/health`, then a credential-less signed `connect`. `AUTH_TOKEN_MISSING`,
    /// `AUTH_PASSWORD_MISSING` or `AUTH_REQUIRED` → `.token`; `hello-ok` → its auth mode; `PAIRING_REQUIRED` →
    /// `.none` (only the approval is missing). Stores nothing; the socket is closed before returning.
    ///
    /// - Throws: ``GatewayError`` from `/health` or the handshake.
    public func probeAuth(url: String) async throws -> AuthRequirement {
        let baseURL = try parseURL(url)
        let allowCleartext = await firstAllowCleartext()
        let client = clientFactory.unauthenticated(baseURL: baseURL, allowCleartextPublicIPs: allowCleartext)
        var rpc: GatewayRPCClient?
        do {
            _ = try await client.health()
            let socket = try await openRPC(baseURL: baseURL, allowCleartext: allowCleartext)
            rpc = socket
            let hello = try await GatewayHandshake.perform(
                rpc: socket, client: clientInfo, auth: .none, identity: try identityProvider.identity()
            )
            await release(client, rpc)
            return hello.authRequirement(token: nil)
        } catch GatewayError.connectRejected(_, let detailCode, _, _) where Self.credentialMissingCodes.contains(detailCode) {
            await release(client, rpc)
            return .token
        } catch GatewayError.pairingRequired {
            await release(client, rpc)
            return .none
        } catch {
            await release(client, rpc)
            throw Self.typed(error, url: url)
        }
    }

    /// Connects with the shared gateway token, or (`nil`) with a stored device token or no credential.
    /// See ``GatewayConnectionManager/connect(url:token:)``. A cancelled handshake ends `.disconnected` and
    /// throws `CancellationError`; a cancellation after the handshake succeeded leaves `.connected`.
    public func connect(url: String, token: String?) async throws {
        try await establish(url: url, token: token)
    }

    private func establish(url: String, token: String?) async throws {
        await closeActive()
        let baseURL = try parseURL(url)
        publish(.connecting(url: url))
        let key = GatewayKey.of(url)
        // Held outside the `do` so every failure path can release what was opened so far.
        var client: OpenClawAPIClient?
        var rpc: GatewayRPCClient?
        do {
            let allowCleartext = await firstAllowCleartext()
            let rest = clientFactory.unauthenticated(baseURL: baseURL, allowCleartextPublicIPs: allowCleartext)
            client = rest
            _ = try await rest.health()
            let identity = try identityProvider.identity()
            let stored = deviceTokens.load(gatewayKey: key).flatMap { $0.deviceId == identity.deviceId ? $0 : nil }
            let socket = try await openRPC(baseURL: baseURL, allowCleartext: allowCleartext)
            rpc = socket
            let auth = GatewayConnectAuth.select(sharedToken: token, stored: stored)
            let hello = try await GatewayHandshake.perform(rpc: socket, client: clientInfo, auth: auth, identity: identity)
            rememberDeviceToken(hello, key: key, identity: identity, stored: stored)
            active = ActiveSession(client: rest, rpc: socket, hello: hello)
            watch(socket, url: url)
            publish(Self.connectedState(url: url, hello: hello, token: token))
        } catch {
            await release(client, rpc)
            if error is CancellationError || Task.isCancelled {
                publish(.disconnected)
                throw CancellationError()
            }
            let mapped = Self.typed(error, url: url)
            if case let .connectRejected(_, detailCode, _, _) = mapped, detailCode == Self.deviceTokenMismatch {
                deviceTokens.clear(gatewayKey: key)
            }
            publish(.error(url: url, cause: mapped))
            throw mapped
        }
    }

    /// Disconnects from the current gateway. No-op if already disconnected.
    public func disconnect() async {
        await closeActive()
        publish(.disconnected)
    }

    // ── Session access for repository impls ─────────────────────────────────

    /// - Returns: The live session's socket.
    /// - Throws: ``GatewayError/unreachable(url:underlying:)`` (`<no active connection>`) while disconnected.
    public func requireSession() throws -> GatewayRPCClient {
        guard let session = active else {
            throw GatewayError.unreachable(url: "<no active connection>", underlying: nil)
        }
        return session.rpc
    }

    /// - Returns: `hello-ok.auth.scopes` of the live session, or `nil` while disconnected.
    public func grantedScopes() -> Set<String>? {
        active.map { Set($0.hello.scopes) }
    }

    /// The live session's REST client. Only `/health` is real on the gateway; removed in Task 20.
    ///
    /// - Throws: ``GatewayError/unreachable(url:underlying:)`` while disconnected.
    public func requireClient() throws -> OpenClawAPIClient {
        guard let session = active else {
            throw GatewayError.unreachable(url: "<no active connection>", underlying: nil)
        }
        return session.client
    }

    // ── Session lifecycle ────────────────────────────────────────────────────

    private func openRPC(baseURL: URL, allowCleartext: Bool) async throws -> GatewayRPCClient {
        let socketURL = try GatewayWebSocketURL.make(from: baseURL, allowCleartextPublicIPs: allowCleartext)
        let rpc = GatewayRPCClient(socket: socketFactory.makeSocket(url: socketURL), endpoint: socketURL.absoluteString)
        await rpc.start()
        return rpc
    }

    private func rememberDeviceToken(_ hello: GatewayHello, key: String, identity: DeviceIdentity, stored: StoredDeviceToken?) {
        guard let issued = hello.deviceToken else { return }
        // Upstream rule: the same token keeps its stored scopes; a new token takes this socket's live scopes.
        let scopes = (stored?.token == issued ? stored?.scopes : nil) ?? hello.scopes
        do {
            let token = StoredDeviceToken(token: issued, scopes: scopes, role: hello.role, deviceId: identity.deviceId)
            try deviceTokens.save(token, gatewayKey: key)
        } catch {
            // The session works without it; the next connect falls back to the shared token or pairing.
            Self.log.error("Device token not stored", error: error)
        }
    }

    /// Moves the state to `.error(.unreachable)` when the gateway drops the session socket.
    private func watch(_ socket: GatewayRPCClient, url: String) {
        watcher?.cancel()
        watcher = Task { [weak self] in
            let events = await socket.events()
            for await _ in events {}
            await self?.socketEnded(socket, url: url)
        }
    }

    private func socketEnded(_ socket: GatewayRPCClient, url: String) async {
        guard let session = active, session.rpc === socket else { return } // a deliberate close cleared it first
        active = nil
        await session.client.close()
        publish(.error(url: url, cause: .unreachable(url: socket.endpoint, underlying: nil)))
    }

    private func closeActive() async {
        watcher?.cancel()
        watcher = nil
        guard let session = active else { return }
        active = nil
        await release(session.client, session.rpc)
    }

    private func release(_ client: OpenClawAPIClient?, _ rpc: GatewayRPCClient?) async {
        if let rpc { await rpc.close() }
        if let client { await client.close() }
    }

    private static func connectedState(url: String, hello: GatewayHello, token: String?) -> GatewayConnection {
        .connected(
            url: url,
            displayName: hello.gatewayHost ?? defaultDisplayName,
            version: hello.serverVersion,
            authRequirement: hello.authRequirement(token: token),
            isHttps: url.lowercased().hasPrefix("https://"),
            capabilities: hello.capabilities,
            hardwareInfo: nil,
            tokenOrNull: token
        )
    }

    /// Unclassified failures (Keychain, encoding) are client faults, not connectivity: never `.unreachable`.
    private static func typed(_ error: any Error, url: String) -> GatewayError {
        if let gatewayError = error as? GatewayError { return gatewayError }
        return .protocolViolation(url: url, detail: "\(type(of: error)): \(error.localizedDescription)")
    }

    // ── Subscriber management ────────────────────────────────────────────────

    private func registerAndReplay(id: UUID, continuation: AsyncStream<GatewayConnection>.Continuation) {
        subscribers[id] = continuation
        continuation.yield(state)
    }

    private func unregister(id: UUID) {
        subscribers.removeValue(forKey: id)
    }

    /// Writes the snapshot box first, so a racing synchronous read sees the new value before any subscriber.
    private func publish(_ next: GatewayConnection) {
        state = next
        snapshotBox.set(next)
        for continuation in subscribers.values {
            continuation.yield(next)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private func firstAllowCleartext() async -> Bool {
        for await value in settingsRepository.allowCleartextPublicIPs() {
            return value
        }
        return false
    }

    private nonisolated func parseURL(_ raw: String) throws -> URL {
        guard let url = URL(string: raw) else {
            throw GatewayError.api(url: raw, statusCode: 0, body: "Malformed URL")
        }
        return url
    }

    /// Synchronous mailbox for ``currentState`` (the protocol requires a non-async read).
    private let snapshotBox = SnapshotBox()
}

```

- [ ] **Step 9: Wire it in the container and fix the cancellation test**

In `AppContainer.swift` replace
```swift
        let gatewayImpl = GatewayConnectionManagerImpl(
            settingsRepository: self.settings
        )
```
with
```swift
        let gatewayImpl = GatewayConnectionManagerImpl(
            identityProvider: DeviceIdentityStore(),
            deviceTokens: DeviceTokenStore(),
            settingsRepository: self.settings
        )
```

In `GatewayConnectionManagerCancellationTests.swift` replace
```swift
        let manager = GatewayConnectionManagerImpl(clientFactory: NeverRespondingClientFactory())
```
with
```swift
        let manager = GatewayConnectionManagerImpl(
            identityProvider: FixedIdentityProvider(),
            deviceTokens: InMemoryDeviceTokenStore(),
            clientFactory: NeverRespondingClientFactory()
        )
```

- [ ] **Step 10: Run the tests** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run the test command with `-only-testing:GhostCrabTests` (the whole unit suite: the container, the cancellation test and every ViewModel test still compile against the fakes). Expected: PASS, including the 13 new manager tests.

- [ ] **Step 11: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Gateway/GatewayWebSocketURL.swift ios/GhostCrab/Data/Impl/GatewayConnectionManagerImpl.swift ios/GhostCrab/App/AppContainer.swift ios/GhostCrabTests/Support/GatewayTestDoubles.swift ios/GhostCrabTests/Api/GatewayConnectionManagerCancellationTests.swift ios/GhostCrabTests/Gateway/GatewayWebSocketURLTests.swift ios/GhostCrabTests/Gateway/GatewayConnectionManagerImplTests.swift
git commit -m "feat(ios): connect over the protocol-4 WS handshake" -m "Replaces the /status auth probe (ADR-002, superseded by ADR-0007) with /health plus a signed connect. Auth mode, version, host and capabilities come from hello-ok; device tokens are stored per gateway and reused; a dropped session socket ends in .error."
```

---

### Task 10: Scope probe from `hello-ok`

**Files:**
- Rewrite: `ios/GhostCrab/Data/Impl/ScopeProbeImpl.swift`
- Modify: `ios/GhostCrab/Domain/Repository/ScopeProbe.swift` (doc comment only)
- Test: `ios/GhostCrabTests/Gateway/ScopeProbeImplTests.swift`

There is no `auth.whoami`; the scopes the gateway granted this socket are in `hello-ok.auth.scopes` (QA §3).

- [ ] **Step 1: Write the failing test**

`ios/GhostCrabTests/Gateway/ScopeProbeImplTests.swift`:
```swift
import Testing
@testable import GhostCrab

@Suite("ScopeProbeImpl")
struct ScopeProbeImplTests {

    @Test func grantedScopesAreKnown() async {
        let result = await ScopeProbeImpl(scopes: { Set(TestKeys.operatorScopes) }).probe()
        #expect(result.has("operator.admin"))
    }

    @Test func aTokenOnlySocketHasNoScopes() async {
        guard case .known(let scopes) = await ScopeProbeImpl(scopes: { [] }).probe() else {
            Issue.record("expected known")
            return
        }
        #expect(scopes.isEmpty)
    }

    @Test func disconnectedIsAFailedProbe() async {
        guard case .failed = await ScopeProbeImpl(scopes: { nil }).probe() else {
            Issue.record("expected failed")
            return
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: FAIL to compile (`incorrect argument label in call (have 'scopes:', expected 'connectionManager:')`).

- [ ] **Step 3: Implement**

Replace `ios/GhostCrab/Data/Impl/ScopeProbeImpl.swift`:
```swift
import Foundation

/// ``ScopeProbe`` from the live session's `hello-ok.auth.scopes` (ADR-0007). No network call: the gateway reports
/// the granted scopes in the handshake, and a token-only socket (no device identity) has none.
///
/// Never throws: disconnected → ``ScopeProbeResult/failed(cause:)``.
public final class ScopeProbeImpl: ScopeProbe, Sendable {

    private let scopes: @Sendable () async -> Set<String>?

    /// - Parameter scopes: The live session's granted scopes, or `nil` while disconnected.
    public init(scopes: @escaping @Sendable () async -> Set<String>?) {
        self.scopes = scopes
    }

    /// - Parameter connectionManager: Source of the live session's scopes.
    public convenience init(connectionManager: GatewayConnectionManagerImpl) {
        self.init(scopes: { await connectionManager.grantedScopes() })
    }

    public func probe() async -> ScopeProbeResult {
        guard let granted = await scopes() else {
            return .failed(cause: "Not connected to a gateway")
        }
        return .known(scopes: granted)
    }
}
```

In `ScopeProbe.swift`, replace the first doc line
```swift
/// Runs `auth.whoami` over WebSocket to learn what scopes the current token carries.
```
with
```swift
/// Reports the operator scopes the gateway granted the live session (`hello-ok.auth.scopes`, ADR-0007).
```

- [ ] **Step 4: Run the test to verify it passes** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Impl/ScopeProbeImpl.swift ios/GhostCrab/Domain/Repository/ScopeProbe.swift ios/GhostCrabTests/Gateway/ScopeProbeImplTests.swift
git commit -m "feat(ios): scope probe reads hello-ok scopes" -m "There is no auth.whoami upstream; the handshake reports the granted scopes (ADR-0007)."
```

---

### Task 11: Skills over `skills.status` / `skills.install`, disable via `skills.update`

**Files:**
- Modify: `ios/GhostCrab/Domain/Model/InstalledSkill.swift`, `ios/GhostCrab/Domain/Repository/InstalledSkillRepository.swift` (additive member)
- Modify: `ios/GhostCrab/Data/Impl/InstalledSkillRepositoryImpl.swift`
- Modify: `ios/GhostCrab/UI/InstalledSkills/InstalledSkillsViewModel.swift`, `ios/GhostCrab/UI/InstalledSkills/InstalledSkillsScreen.swift`
- Modify: `ios/GhostCrabTests/Support/Fakes.swift`
- Test: `ios/GhostCrabTests/Gateway/InstalledSkillRepositoryImplTests.swift`

The real gateway answers `skills.install` with one `res` frame and no progress events; `skills.uninstall` does not exist (QA §3, §8). Verified 2026-10-15: a ClawHub install answers `{ok, message, slug, version, targetDir, …}` with the bare key in `slug` and is listed with `source: "openclaw-workspace"` plus a `clawhub {slug, installedVersion, installedAt, …}` object; `skills.update {skillKey, enabled}` answers `{ok:true, skillKey, config:{enabled}}` even for unknown keys, so the key must be in the last `skills.status`; and `skills.status` stays stale until the `config.changed` event (~80 ms after the write, sometimes before its response; there is no `skills.changed` event), so the repository subscribes before writing and re-reads the list once the event arrives (2 s cap).

- [ ] **Step 1: Write the failing test**

`ios/GhostCrabTests/Gateway/InstalledSkillRepositoryImplTests.swift`:
```swift
import Foundation
import Testing
@testable import GhostCrab

@Suite("InstalledSkillRepositoryImpl (skills.*)")
struct InstalledSkillRepositoryImplTests {

    private func makeRepo(_ replies: [String: [String]]) async throws -> (InstalledSkillRepositoryImpl, ScriptedGatewayWebSocket) {
        let socket = try ScriptedGatewayWebSocket(challenge: false, replies: replies)
        let rpc = await startedRPC(socket)
        return (InstalledSkillRepositoryImpl(session: { rpc }), socket)
    }

    private func events(_ stream: AsyncStream<SkillInstallProgress>) async -> [SkillInstallProgress] {
        var collected: [SkillInstallProgress] = []
        for await event in stream { collected.append(event) }
        return collected
    }

    @Test func refreshMapsSkillsStatus() async throws {
        let (repo, _) = try await makeRepo(try .fixtures(["skills.status": "res-skills-status"]))
        let skills = try await repo.refreshFromGateway().get()
        #expect(skills.map(\.slug) == ["1password", "browser-automation", "weather"])
        #expect(skills[0].disabled)
        #expect(skills[0].source == .local)
        #expect(skills[0].installedVersion == "")
        #expect(!skills[1].disabled)
        #expect(skills[2].source == .clawHub)
        #expect(skills[2].installedVersion == "1.0.0")
        #expect(Int(skills[2].installedAt.timeIntervalSince1970) == 1_792_035_309)
    }

    @Test func installSendsTheClawHubParamsAndEndsSucceeded() async throws {
        let (repo, socket) = try await makeRepo(try .fixtures([
            "skills.install": "res-skills-install",
            "skills.status": "res-skills-status",
        ]))
        let progress = await events(repo.install(slug: "@steipete/weather", version: "1.0.0"))
        #expect(progress.first == .connecting(target: "@steipete/weather"))
        #expect(progress.contains(.applying(step: "skills.install")))
        guard case .succeeded(let installed)? = progress.last else {
            Issue.record("expected succeeded, got \(progress)")
            return
        }
        #expect(installed.slug == "weather")
        #expect(installed.installedVersion == "1.0.0")
        #expect(installed.source == .clawHub)
        let params = try await sentParams(socket, "skills.install")
        #expect(params["source"] as? String == "clawhub")
        #expect(params["slug"] as? String == "@steipete/weather")
        #expect(params["version"] as? String == "1.0.0")
        #expect(params["force"] as? Bool == false)
        #expect(params["timeoutMs"] as? Int == 120_000)
    }

    @Test func anUnknownSlugEndsNotFound() async throws {
        let (repo, _) = try await makeRepo(try .fixtures(["skills.install": "err-skill-not-found"]))
        let progress = await events(repo.install(slug: "qa-nonexistent-skill-zz9", version: nil))
        #expect(progress.last == .failed(error: .notFound(slug: "qa-nonexistent-skill-zz9")))
    }

    @Test func aMissingAdminScopeEndsUnauthorized() async throws {
        let (repo, _) = try await makeRepo(try .fixtures(["skills.install": "err-missing-scope"]))
        let progress = await events(repo.install(slug: "x", version: nil))
        #expect(progress.last == .failed(error: .unauthorized(missingScope: "operator.read")))
    }

    @Test func uninstallIsUnsupportedWithoutACall() async throws {
        let (repo, socket) = try await makeRepo([:])
        guard case .failure(let error) = await repo.uninstall(slug: "browser-automation"),
              case .methodUnsupported(_, "skills.uninstall")? = error as? GatewayError else {
            Issue.record("expected methodUnsupported")
            return
        }
        #expect(await socket.sentMethods().isEmpty)
    }

    @Test(.timeLimit(.minutes(1)))
    func disableWaitsForConfigChangedThenReReadsTheList() async throws {
        let socket = try ScriptedGatewayWebSocket(
            challenge: false,
            replies: try .fixtures(["skills.update": "res-skills-update", "skills.status": "res-skills-status"]),
            // Like the real gateway: config.changed can arrive before the skills.update response.
            eventsBeforeReply: ["skills.update": [try GatewayFixtures.text("evt-config-changed")]]
        )
        let rpc = await startedRPC(socket)
        let repo = InstalledSkillRepositoryImpl(session: { rpc })
        guard case .success = await repo.setSkillEnabled(skillKey: "someone/browser-automation", enabled: false) else {
            Issue.record("expected success")
            return
        }
        let params = try await sentParams(socket, "skills.update")
        #expect(params["skillKey"] as? String == "browser-automation")
        #expect(params["enabled"] as? Bool == false)
        #expect(await socket.sentMethods() == ["skills.status", "skills.update", "skills.status"])
    }

    @Test func aKeySkillsStatusDoesNotListIsRefusedWithoutAWrite() async throws {
        let (repo, socket) = try await makeRepo(try .fixtures(["skills.status": "res-skills-status"]))
        guard case .failure(let error) = await repo.setSkillEnabled(skillKey: "qa-does-not-exist", enabled: false),
              case .api(_, 404, _)? = error as? GatewayError else {
            Issue.record("expected api 404")
            return
        }
        #expect(await socket.sentMethods() == ["skills.status"])
    }

    @Test func statusEntriesMapToSkillSource() {
        #expect(SkillSource.from(statusSource: "openclaw-workspace", bundled: false, clawhub: true) == .clawHub)
        #expect(SkillSource.from(statusSource: "openclaw-workspace", bundled: false, clawhub: false) == .local)
        #expect(SkillSource.from(statusSource: "openclaw-bundled", bundled: true, clawhub: false) == .local)
        #expect(SkillSource.from(statusSource: "openclaw-extra", bundled: false, clawhub: false) == .local)
        #expect(SkillSource.from(statusSource: "workspace", bundled: false, clawhub: false) == .unknown)
        #expect(SkillSource.from(statusSource: nil, bundled: false, clawhub: false) == .unknown)
    }
}
```

- [ ] **Step 2: Run it to make sure it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: FAIL to compile (`extra argument 'session' in call`, `value of type 'InstalledSkill' has no member 'disabled'`).

- [ ] **Step 3: Model and protocol (additive)**

In `InstalledSkill.swift`, add after `public let installedAt: Date`:
```swift
    /// `true` when the gateway reports the skill disabled (`skills.status` `disabled`). Additive (v1.1).
    /// (`installedAt` comes from `clawhub.installedAt` for ClawHub installs; the epoch otherwise.)
    public let disabled: Bool
```
and replace the initializer with:
```swift
    public init(
        slug: String,
        installedVersion: String,
        source: SkillSource,
        installedAt: Date,
        disabled: Bool = false
    ) {
        self.slug = slug
        self.installedVersion = installedVersion
        self.source = source
        self.installedAt = installedAt
        self.disabled = disabled
    }
```

In `InstalledSkillRepository.swift`, add before the closing brace:
```swift

    /// Enables or disables a skill on the gateway (`skills.update {skillKey, enabled}`). OpenClaw has no skill
    /// removal, so the UI's "remove" action disables instead (ADR-0007).
    ///
    /// Additive (v1.1, ADR-0005).
    ///
    /// - Parameters:
    ///   - skillKey: The skill's key; an `owner/slug` is reduced to `slug`.
    ///   - enabled: The new state.
    /// - Returns: Success after the list was re-read, or the error (`GatewayError.api` 404 for a key `skills.status`
    ///   does not list, `GatewayError.missingScope` without `operator.admin`).
    func setSkillEnabled(skillKey: String, enabled: Bool) async -> Result<Void, any Error>
```

- [ ] **Step 4: Rewrite the repository**

In `InstalledSkillRepositoryImpl.swift`, replace everything from the top of the file down to, but not including, `// ── State box` with:
```swift
import Foundation

/// ``InstalledSkillRepository`` over the live session socket (ADR-0007): `skills.status` lists, `skills.install`
/// installs from ClawHub (one `res`, no progress events), `skills.update` enables or disables. The gateway has no
/// `skills.uninstall`, so ``uninstall(slug:)`` fails with ``GatewayError/methodUnsupported(url:method:)`` without
/// a call. State is held in a lock-protected box and fanned out to every ``observeInstalled()`` stream.
public final class InstalledSkillRepositoryImpl: InstalledSkillRepository, @unchecked Sendable {

    /// Sent as `skills.install.timeoutMs`: the gateway downloads and verifies before it answers.
    static let installTimeoutMs = 120_000
    private static let installMargin: Duration = .seconds(10)
    /// `config.changed` follows a write within ~80 ms; without it the list is re-read anyway after this long.
    static let configChangedWait: Duration = .seconds(2)

    private let session: @Sendable () async throws -> GatewayRPCClient
    private let stateBox = InstalledSkillStateBox()

    /// - Parameter session: The live session socket; throws while disconnected.
    public init(session: @escaping @Sendable () async throws -> GatewayRPCClient) {
        self.session = session
    }

    /// - Parameter connectionManager: Source of the live session socket.
    public convenience init(connectionManager: GatewayConnectionManagerImpl) {
        self.init(session: { try await connectionManager.requireSession() })
    }

    /// Hot list; starts empty, updated by every `skills.status` read.
    public func observeInstalled() -> AsyncStream<[InstalledSkill]> {
        stateBox.subscribe()
    }

    /// One-shot `skills.status`.
    public func refreshFromGateway() async -> Result<[InstalledSkill], any Error> {
        do {
            return .success(try await fetchStatus(try await session()))
        } catch {
            return .failure(error)
        }
    }

    /// Emits `.connecting`, `.applying("skills.install")`, then `.succeeded` (the refreshed entry) or `.failed`.
    public func install(slug: String, version: String?) -> AsyncStream<SkillInstallProgress> {
        AsyncStream { continuation in
            continuation.yield(.connecting(target: slug))
            let task = Task { [weak self] in
                guard let self else {
                    continuation.finish()
                    return
                }
                do {
                    let rpc = try await self.session()
                    continuation.yield(.applying(step: "skills.install"))
                    let result = try await rpc.request(
                        "skills.install",
                        params: SkillsInstallParams(slug: slug, version: version, timeoutMs: Self.installTimeoutMs),
                        timeout: .milliseconds(Self.installTimeoutMs) + Self.installMargin
                    )
                    let installed = await self.installedSkill(after: rpc, requested: slug, result: result?.objectValue)
                    continuation.yield(.succeeded(installed: installed))
                } catch let error as GatewayError {
                    continuation.yield(.failed(error: error.toSkillInstallError(slug: slug)))
                } catch is CancellationError {
                    // The caller cancelled the stream: no terminal event.
                } catch {
                    continuation.yield(.failed(error: .unknown(cause: String(describing: type(of: error)))))
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// OpenClaw cannot remove skills remotely; use ``setSkillEnabled(skillKey:enabled:)``.
    public func uninstall(slug: String) async -> Result<Void, any Error> {
        do {
            let rpc = try await session()
            return .failure(GatewayError.methodUnsupported(url: rpc.endpoint, method: "skills.uninstall"))
        } catch {
            return .failure(error)
        }
    }

    /// `skills.update {skillKey, enabled}`, then a `skills.status` re-read once the gateway emits `config.changed`
    /// (the list is stale until then, and the event can precede the response). The gateway answers `ok:true` even
    /// for unknown keys, so the key must be listed by `skills.status` first.
    public func setSkillEnabled(skillKey: String, enabled: Bool) async -> Result<Void, any Error> {
        do {
            let rpc = try await session()
            let key = Self.bareKey(skillKey)
            var listed = stateBox.current()
            if !listed.contains(where: { $0.slug == key }) {
                listed = try await fetchStatus(rpc)
            }
            guard listed.contains(where: { $0.slug == key }) else {
                return .failure(GatewayError.api(url: rpc.endpoint, statusCode: 404, body: "skill \(key) is not listed by skills.status"))
            }
            let changes = await rpc.events() // subscribed before the write, so an early config.changed is kept
            _ = try await rpc.request("skills.update", params: SkillsUpdateParams(skillKey: key, enabled: enabled))
            await Self.awaitConfigChanged(changes, timeout: Self.configChangedWait)
            _ = try await fetchStatus(rpc)
            return .success(())
        } catch {
            return .failure(error)
        }
    }

    /// Returns at the first `config.changed` in `events`, or after `timeout`.
    static func awaitConfigChanged(_ events: AsyncStream<GatewayEventFrame>, timeout: Duration) async {
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                for await event in events where event.event == "config.changed" { return }
            }
            group.addTask { _ = try? await Task.sleep(for: timeout) }
            await group.next()
            group.cancelAll()
        }
    }

    private func fetchStatus(_ rpc: GatewayRPCClient) async throws -> [InstalledSkill] {
        let payload = try await rpc.request("skills.status")
        guard let wire = try? payload?.decoded(as: SkillsStatusWire.self), let entries = wire.skills else {
            throw GatewayError.protocolViolation(url: rpc.endpoint, detail: "skills.status without a skills array")
        }
        let list = entries.compactMap(\.installedSkill)
        stateBox.set(list)
        return list
    }

    /// The install already succeeded; this only finds its entry. `skills.install` answers with the bare key and the
    /// installed version (`{ok, message, slug, version, targetDir, …}`). A failed refresh falls back to a synthetic
    /// entry rather than reporting a successful install as failed.
    private func installedSkill(after rpc: GatewayRPCClient, requested: String, result: [String: AnyCodable]?) async -> InstalledSkill {
        let key = result?["slug"]?.value as? String ?? Self.bareKey(requested)
        let refreshed = (try? await fetchStatus(rpc)) ?? []
        return refreshed.first { $0.slug == key }
            ?? InstalledSkill(
                slug: key,
                installedVersion: result?["version"]?.value as? String ?? "",
                source: .clawHub,
                installedAt: Date(timeIntervalSince1970: 0)
            )
    }

    /// `@owner/key` and `owner/key` installs are listed under their bare key.
    private static func bareKey(_ slug: String) -> String {
        slug.split(separator: "/").last.map(String.init) ?? slug
    }
}

```

In the existing `InstalledSkillStateBox` (below `// ── State box`), add after `private let lock = NSLock()`:
```swift

    /// The latest list, without subscribing.
    func current() -> [InstalledSkill] {
        lock.lock()
        defer { lock.unlock() }
        return stored
    }
```

In the same file, replace the whole `// ── Mapping` section (the `private extension InstalledSkillDTO { … }` block, up to but not including `extension GatewayError {`) with:
```swift
// ── Wire types ───────────────────────────────────────────────────────────────

/// `skills.install` params (2026.10.3 `SkillsInstallParams`, ClawHub form).
struct SkillsInstallParams: Encodable, Sendable {
    let source = "clawhub"
    let slug: String
    let version: String?
    let force = false
    let timeoutMs: Int
}

/// `skills.update` params (2026.10.3 `SkillsUpdateParams`).
struct SkillsUpdateParams: Encodable, Sendable {
    let skillKey: String
    let enabled: Bool
}

/// The parts of a `skills.status` payload GhostCrab shows.
struct SkillsStatusWire: Decodable {
    struct Entry: Decodable {
        let name: String?
        let skillKey: String?
        let source: String?
        let bundled: Bool?
        let disabled: Bool?
        let version: String?
        /// Present for ClawHub installs (verified against 2026.10.3).
        let clawhub: ClawHub?

        struct ClawHub: Decodable {
            let slug: String?
            let installedVersion: String?
            /// Epoch milliseconds.
            let installedAt: Double?
        }

        /// `skillKey` (falls back to `name`); version and install time from `clawhub` when present.
        var installedSkill: InstalledSkill? {
            guard let key = skillKey ?? name else { return nil }
            return InstalledSkill(
                slug: key,
                installedVersion: clawhub?.installedVersion ?? version ?? "",
                source: SkillSource.from(statusSource: source, bundled: bundled ?? false, clawhub: clawhub != nil),
                installedAt: Date(timeIntervalSince1970: (clawhub?.installedAt ?? 0) / 1000),
                disabled: disabled ?? false
            )
        }
    }

    let skills: [Entry]?
}

extension SkillSource {
    /// `skills.status` entry → ``SkillSource``. Verified against 2026.10.3: a ClawHub install reports
    /// `source: "openclaw-workspace"` plus a `clawhub {…}` object; bundled and plugin skills (`openclaw-bundled`,
    /// `openclaw-extra`) and hand-made workspace skills have no `clawhub` object and are local.
    static func from(statusSource source: String?, bundled: Bool, clawhub: Bool) -> SkillSource {
        if clawhub { return .clawHub }
        guard let source else { return .unknown }
        if bundled || source.hasPrefix("openclaw-") { return .local }
        return .unknown
    }
}

```

- [ ] **Step 5: Disable instead of uninstall in the UI**

In `InstalledSkillsViewModel.swift`, in `confirmUninstall()`, replace
```swift
            let result = await repo.uninstall(slug: slug)
```
with
```swift
            // OpenClaw has no skill removal: disable it (skills.update, enabled = false). ADR-0007.
            let result = await repo.setSkillEnabled(skillKey: slug, enabled: false)
```

In `InstalledSkillsScreen.swift`, replace the uninstall alert
```swift
        .alert(
            "Uninstall \(vm.pendingUninstallSlug ?? "")?",
            isPresented: uninstallAlertBinding,
            presenting: vm.pendingUninstallSlug
        ) { _ in
            Button("Uninstall", role: .destructive) { vm.confirmUninstall() }
            Button("Cancel", role: .cancel) { vm.cancelUninstall() }
        } message: { _ in
            Text("The skill will be removed from the gateway. You can reinstall it later.")
        }
```
with
```swift
        .alert(
            "Disable \(vm.pendingUninstallSlug ?? "")?",
            isPresented: uninstallAlertBinding,
            presenting: vm.pendingUninstallSlug
        ) { _ in
            Button("Disable", role: .destructive) { vm.confirmUninstall() }
            Button("Cancel", role: .cancel) { vm.cancelUninstall() }
        } message: { _ in
            Text("OpenClaw gateways cannot remove skills remotely. GhostCrab disables the skill instead (skills.update, enabled = false). It stays on the gateway host and can be enabled there again.")
        }
```
In `skillRow(_:)`, replace
```swift
                    Text(sourceLabel(skill.source))
                        .font(AppFont.body(11))
                        .foregroundStyle(DesignTokens.Color.textSecondary)
```
with
```swift
                    Text(sourceLabel(skill.source))
                        .font(AppFont.body(11))
                        .foregroundStyle(DesignTokens.Color.textSecondary)
                    if skill.disabled {
                        Text("· disabled")
                            .font(AppFont.body(11))
                            .foregroundStyle(DesignTokens.Color.amberWarn)
                    }
```
replace
```swift
            } else {
                Menu {
```
with
```swift
            } else if !skill.disabled {
                Menu {
```
and
```swift
                        Label("Uninstall", systemImage: "trash")
```
with
```swift
                        Label("Disable", systemImage: "pause.circle")
```

- [ ] **Step 6: Keep the fake conforming**

In `Fakes.swift`, in `FakeInstalledSkillRepository`, add `private var enabledLog: [String] = []` after `private var refreshCalls = 0`, `var enabledChanges: [String] { lock.withLock { enabledLog } }` after `var refreshCount`, and before its closing brace:
```swift

    func setSkillEnabled(skillKey: String, enabled: Bool) async -> Result<Void, any Error> {
        lock.withLock {
            enabledLog.append("\(skillKey)=\(enabled)")
            skills = skills.map {
                $0.slug == skillKey
                    ? InstalledSkill(slug: $0.slug, installedVersion: $0.installedVersion, source: $0.source, installedAt: $0.installedAt, disabled: !enabled)
                    : $0
            }
        }
        return .success(())
    }
```

- [ ] **Step 7: Run the tests** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run the test command with `-only-testing:GhostCrabTests`. Expected: PASS, including 8 new repository tests.

- [ ] **Step 8: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Domain/Model/InstalledSkill.swift ios/GhostCrab/Domain/Repository/InstalledSkillRepository.swift ios/GhostCrab/Data/Impl/InstalledSkillRepositoryImpl.swift ios/GhostCrab/UI/InstalledSkills ios/GhostCrabTests/Support/Fakes.swift ios/GhostCrabTests/Gateway/InstalledSkillRepositoryImplTests.swift
git commit -m "feat(ios): skills over skills.status/install, disable via skills.update" -m "No progress events and no uninstall upstream; ClawHub entries (openclaw-workspace + clawhub) and the install answer's bare key; additive InstalledSkillRepository.setSkillEnabled (ADR-0005) writes only listed keys and re-reads after config.changed; the screen offers Disable and marks disabled skills (ADR-0007)."
```

---

### Task 12: Config over `config.get` / `config.patch`

**Files:**
- Create: `ios/GhostCrab/Data/Impl/ConfigWrites.swift`
- Rewrite: `ios/GhostCrab/Data/Impl/ConfigRepositoryImpl.swift`
- Test: `ios/GhostCrabTests/Gateway/ConfigRepositoryImplTests.swift`

`config.get` returns the authored config as `parsed` (secrets redacted as `__OPENCLAW_REDACTED__`) and an opaque `hash`; `config.patch {raw, baseHash}` merges a JSON merge patch (`raw` is JSON5 text, and JSON is valid JSON5) and is admin-only, 30 writes/min (`/app/docs/gateway/configuration/config-rpc.md`). Verified 2026-10-15: a stale or unknown `baseHash` fails `INVALID_REQUEST "config changed since last load; re-run config.get and retry"`, a missing one `"config base hash required for <path>; …"` (neither has `details`), schema errors `"invalid config: <path>: …"` with `details.issues[{path, message, allowedValues?}]`; success answers `{ok, path, hash, config, changedPaths, sentinel}`, and that `hash` is the next `baseHash`. The frozen `ConfigRepository` keeps its 412 semantics, so `ConfigEditorViewModel` reloads on a concurrent edit unchanged. A rejected value becomes `GatewayError.api(statusCode: 422, body: <server text>)`, not `.configValidation`: `ConfigEditorScreen` never renders `fieldErrors`, so a `.configValidation` would vanish silently, while a 422 lands in the visible "Save failed" error (same mapping as Android).

- [ ] **Step 1: Write the failing test**

`ios/GhostCrabTests/Gateway/ConfigRepositoryImplTests.swift`:
```swift
import Foundation
import Testing
@testable import GhostCrab

@Suite("ConfigRepositoryImpl (config.*)")
struct ConfigRepositoryImplTests {

    private let fixtureHash = "hmac-sha256:v1:u_qU7JLKF7G9u0jc_GfUp-dKovJYwiLpy0gNxuKjka0"

    private func makeRepo(patch: [String]? = nil) async throws -> (ConfigRepositoryImpl, ScriptedGatewayWebSocket) {
        var replies = try [String: [String]].fixtures(["config.get": "res-config-get"])
        replies["config.patch"] = try patch ?? [GatewayFixtures.text("res-config-patch")]
        let socket = try ScriptedGatewayWebSocket(challenge: false, replies: replies)
        let rpc = await startedRPC(socket)
        return (ConfigRepositoryImpl(session: { rpc }), socket)
    }

    private func port(_ value: Int) -> AnyCodable {
        AnyCodable(["port": AnyCodable(value)])
    }

    @Test func getConfigMapsParsedSectionsAndTheHash() async throws {
        let (repo, _) = try await makeRepo()
        let config = try await repo.getConfig()
        #expect(config.etag == fixtureHash)
        #expect(config.sections.keys.sorted() == ["agents", "gateway"])
        #expect(config.sections["gateway"]?.objectValue?["port"]?.value as? Int == 18789)
        #expect(config.sections["gateway"]?.object(at: "auth")?["token"]?.value as? String == "__OPENCLAW_REDACTED__")
    }

    @Test func updateConfigPatchesOneSectionAgainstTheCachedHash() async throws {
        let (repo, socket) = try await makeRepo()
        _ = try await repo.getConfig()
        try await repo.updateConfig(section: "gateway", value: port(18790))
        let params = try await sentParams(socket, "config.patch")
        #expect(params["raw"] as? String == #"{"gateway":{"port":18790}}"#)
        #expect(params["baseHash"] as? String == fixtureHash)
        #expect(await socket.sentMethods() == ["config.get", "config.patch"])
    }

    @Test func updateConfigWithoutAPriorReadFetchesTheHashFirst() async throws {
        let (repo, socket) = try await makeRepo()
        try await repo.updateConfig(section: "gateway", value: port(18790))
        #expect(await socket.sentMethods() == ["config.get", "config.patch"])
        #expect(try await sentParams(socket, "config.patch")["baseHash"] as? String == fixtureHash)
    }

    @Test func theHashAPatchReturnsIsTheNextBaseHash() async throws {
        let (repo, socket) = try await makeRepo()
        try await repo.updateConfig(section: "gateway", value: port(18790))
        try await repo.updateConfig(section: "gateway", value: port(18791))
        #expect(await socket.sentMethods() == ["config.get", "config.patch", "config.patch"])
        let second = try jsonObject(try #require(await socket.sentRequests("config.patch").last))
        #expect((second["params"] as? [String: Any])?["baseHash"] as? String == "hmac-sha256:v1:9zRz_TNpxroYBBCZhMFq3HGpv1dFAcFxPnmwn7VTXYE")
    }

    @Test(arguments: ["err-config-stale-hash", "err-config-base-hash-required"])
    func aStaleOrMissingHashIsHTTP412SoTheEditorReloads(fixture: String) async throws {
        let (repo, _) = try await makeRepo(patch: [try GatewayFixtures.text(fixture)])
        guard case .api(_, 412, _)? = await gatewayError({ try await repo.updateConfig(section: "gateway", value: port(1)) }) else {
            Issue.record("expected api 412 for \(fixture)")
            return
        }
    }

    @Test func aRejectedValueIsHTTP422WithTheServerText() async throws {
        let (repo, _) = try await makeRepo(patch: [try GatewayFixtures.text("err-config-invalid")])
        guard case .api(_, 422, let body)? = await gatewayError({ try await repo.updateConfig(section: "gateway", value: port(99_999)) }) else {
            Issue.record("expected api 422")
            return
        }
        #expect(body == "invalid config: gateway.port: Too big: expected number to be <=65535 (maximum: 65535)")
    }

    @Test func aMissingAdminScopePassesThroughTyped() async throws {
        let (repo, _) = try await makeRepo(patch: [try GatewayFixtures.text("err-missing-scope")])
        guard case .missingScope? = await gatewayError({ try await repo.updateConfig(section: "gateway", value: port(1)) }) else {
            Issue.record("expected missingScope")
            return
        }
    }

    @Test func aHashFromAnEarlierSocketIsNeverReused() async throws {
        let first = try ScriptedGatewayWebSocket(challenge: false, replies: try .fixtures(["config.get": "res-config-get"]))
        var secondReplies = try [String: [String]].fixtures(["config.get": "res-config-get"])
        secondReplies["config.patch"] = [okReply]
        let second = try ScriptedGatewayWebSocket(challenge: false, replies: secondReplies)
        let firstRPC = await startedRPC(first)
        let secondRPC = await startedRPC(second)
        let sessions = SessionSequence([firstRPC, secondRPC])
        let repo = ConfigRepositoryImpl(session: { sessions.next() })

        _ = try await repo.getConfig()                                      // first socket
        try await repo.updateConfig(section: "gateway", value: port(18790)) // reconnected: second socket
        #expect(await second.sentMethods() == ["config.get", "config.patch"])
    }
}

/// Hands out sockets in order (the last one repeats), like a reconnect between calls.
private final class SessionSequence: @unchecked Sendable {
    private let lock = NSLock()
    private var sessions: [GatewayRPCClient]

    init(_ sessions: [GatewayRPCClient]) { self.sessions = sessions }

    func next() -> GatewayRPCClient {
        lock.withLock { sessions.count > 1 ? sessions.removeFirst() : sessions[0] }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: FAIL to compile (`incorrect argument label in call (have 'session:', expected 'connectionManager:')`).

- [ ] **Step 3: Add the shared write helpers**

`ios/GhostCrab/Data/Impl/ConfigWrites.swift`:
```swift
import Foundation

/// `config.patch` params: `raw` is a JSON merge patch as text (JSON is valid JSON5); `baseHash` is the
/// `config.get` hash the patch was made against (required once a config file exists).
struct ConfigPatchParams: Encodable, Sendable {
    let raw: String
    let baseHash: String?
}

/// Shared by ``ConfigRepositoryImpl`` and ``ModelRepositoryImpl`` (ADR-0007).
enum ConfigWrites {

    /// The gateway validates and may restart subsystems before it answers.
    static let patchTimeout: Duration = .seconds(60)

    /// - Returns: `config.patch` params with `patch` as compact JSON (sorted keys, so it is deterministic).
    static func patchParams(_ patch: [String: AnyCodable], baseHash: String?) throws -> ConfigPatchParams {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return ConfigPatchParams(raw: String(decoding: try encoder.encode(patch), as: UTF8.self), baseHash: baseHash)
    }

    /// Messages of the two `baseHash` rejections (verified against 2026.10.3; neither carries `details`).
    private static let staleHashPrefixes = ["config changed since last load", "config base hash required"]

    /// Maps a rejected `config.patch` onto the frozen `ConfigRepository` contract: a stale, unknown or missing
    /// `baseHash` becomes `.api(statusCode: 412)` (callers reload); any other `INVALID_REQUEST` (schema validation
    /// with `details.issues[{path, message, allowedValues?}]`, a JSON5 syntax error, array removal without
    /// `replacePaths`) `.api(statusCode: 422)` with the server text, which names the first failing path; anything
    /// else passes through.
    static func mapRejection(_ error: GatewayError) -> GatewayError {
        guard case let .rpc(url, _, code, _, message, _) = error, code == "INVALID_REQUEST" else { return error }
        if staleHashPrefixes.contains(where: { message.hasPrefix($0) }) {
            return .api(url: url, statusCode: 412, body: message)
        }
        return .api(url: url, statusCode: 422, body: message)
    }
}
```

- [ ] **Step 4: Rewrite the repository**

Replace `ios/GhostCrab/Data/Impl/ConfigRepositoryImpl.swift`:
```swift
import Foundation

/// ``ConfigRepository`` over the live session socket (ADR-0007). ``getConfig()`` reads `config.get` (`parsed`
/// sections, `hash` as the ETag); ``updateConfig(section:value:)`` sends `config.patch {raw: {"<section>": value},
/// baseHash}`. The cached hash is bound to the socket that produced it, so a reconnect never sends a stale one;
/// without a cached hash a write reads `config.get` first.
public actor ConfigRepositoryImpl: ConfigRepository {

    private let session: @Sendable () async throws -> GatewayRPCClient

    /// The last known hash and the socket it belongs to.
    private var cachedHash: (socket: ObjectIdentifier, hash: String?)?

    /// - Parameter session: The live session socket; throws while disconnected.
    public init(session: @escaping @Sendable () async throws -> GatewayRPCClient) {
        self.session = session
    }

    /// - Parameter connectionManager: Source of the live session socket.
    public init(connectionManager: GatewayConnectionManagerImpl) {
        self.init(session: { try await connectionManager.requireSession() })
    }

    /// - Returns: The authored config (secrets redacted by the gateway) and its `hash`.
    /// - Throws: `GatewayError.missingScope` without `operator.admin`; `.unreachable` while disconnected.
    public func getConfig() async throws -> OpenClawConfig {
        try await fetch(try await session())
    }

    /// - Throws: `GatewayError.api` 412 on a stale hash, 422 on a rejected value; `.missingScope` without admin.
    public func updateConfig(section: String, value: AnyCodable) async throws {
        let rpc = try await session()
        let baseHash: String?
        if let cachedHash, cachedHash.socket == ObjectIdentifier(rpc), let hash = cachedHash.hash {
            baseHash = hash
        } else {
            baseHash = try await fetch(rpc).etag
        }
        let params = try ConfigWrites.patchParams([section: value], baseHash: baseHash)
        let result: AnyCodable?
        do {
            result = try await rpc.request("config.patch", params: params, timeout: ConfigWrites.patchTimeout)
        } catch let error as GatewayError {
            throw ConfigWrites.mapRejection(error)
        }
        // 2026.10.3 answers {ok, path, hash, config, changedPaths, sentinel}; without a hash the next write re-reads.
        cachedHash = (ObjectIdentifier(rpc), result?.objectValue?["hash"]?.value as? String)
    }

    private func fetch(_ rpc: GatewayRPCClient) async throws -> OpenClawConfig {
        guard let payload = try await rpc.request("config.get")?.objectValue else {
            throw GatewayError.protocolViolation(url: rpc.endpoint, detail: "config.get returned a non-object payload")
        }
        let sections = payload["parsed"]?.objectValue ?? payload["config"]?.objectValue ?? [:]
        let hash = payload["hash"]?.value as? String
        cachedHash = (ObjectIdentifier(rpc), hash)
        return OpenClawConfig(sections: sections, etag: hash)
    }
}
```

- [ ] **Step 5: Run the tests** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run with `ConfigRepositoryImplTests` and `ConfigEditorViewModelTests`. Expected: PASS (9 new cases + existing).

- [ ] **Step 6: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Impl/ConfigWrites.swift ios/GhostCrab/Data/Impl/ConfigRepositoryImpl.swift ios/GhostCrabTests/Gateway/ConfigRepositoryImplTests.swift
git commit -m "feat(ios): config over config.get / config.patch" -m "parsed sections + hash as ETag; section merge patches with baseHash bound to the session socket and refreshed from each patch answer; stale or missing hash -> 412, rejected value -> 422 with the server text, matched on the verified 2026.10.3 messages (ADR-0007)."
```

---

### Task 13: Models over `models.list` + `agents.defaults.model.primary`

**Files:**
- Rewrite: `ios/GhostCrab/Data/Impl/ModelRepositoryImpl.swift`
- Modify: `ios/GhostCrab/Domain/Repository/ModelRepository.swift` (doc comments only)
- Test: `ios/GhostCrabTests/Gateway/ModelRepositoryImplTests.swift`

`models.list` returns `{models: [{id, name, provider, available?, unavailableReason?, input?, reasoning?, …}]}` (`ModelsListResult` in the 2026.10.3 schema; the QA gateway had no provider, so the captured list is empty and the populated rows below follow the schema). The default model is `agents.defaults.model.primary` = `provider/model` (`/app/docs/cli/models.md`: `openclaw models set` writes it). There is no `models.set` RPC.

- [ ] **Step 1: Write the failing test**

`ios/GhostCrabTests/Gateway/ModelRepositoryImplTests.swift`:
```swift
import Foundation
import Testing
@testable import GhostCrab

@Suite("ModelRepositoryImpl (models.list)")
struct ModelRepositoryImplTests {

    private let catalogue = #"{"type":"res","id":"x","ok":true,"payload":{"models":[{"id":"claude-sonnet-4-6","name":"Claude Sonnet 4.6","provider":"anthropic","available":true,"input":["text","image"],"reasoning":true},{"id":"gpt-6-astra","name":"GPT-6 Astra","provider":"openai","available":false,"unavailableReason":"missing-auth","input":["text"]}]}}"#

    private func configWithPrimary(_ primary: String) -> String {
        #"{"type":"res","id":"x","ok":true,"payload":{"parsed":{"agents":{"defaults":{"model":{"primary":""#
            + primary + #""}}}},"hash":"h-1"}}"#
    }

    private func makeRepo(_ replies: [String: [String]]) async throws -> (ModelRepositoryImpl, ScriptedGatewayWebSocket) {
        let socket = try ScriptedGatewayWebSocket(challenge: false, replies: replies)
        let rpc = await startedRPC(socket)
        return (ModelRepositoryImpl(session: { rpc }), socket)
    }

    @Test func modelsMapToProviderQualifiedRefsAndTheConfiguredPrimaryIsActive() async throws {
        let (repo, _) = try await makeRepo(["models.list": [catalogue], "config.get": [configWithPrimary("anthropic/claude-sonnet-4-6")]])
        let list = try await repo.getModels()
        #expect(list.map(\.id) == ["anthropic/claude-sonnet-4-6", "openai/gpt-6-astra"])
        #expect(list[0].isActive)
        #expect(list[0].displayName == "Claude Sonnet 4.6")
        #expect(list[0].status == "ready")
        #expect(list[0].capabilities == ["text", "image", "reasoning"])
        #expect(!list[1].isActive)
        #expect(list[1].status == "missing-auth")
    }

    @Test func theRealEmptyCatalogueMapsToAnEmptyList() async throws {
        let (repo, _) = try await makeRepo(try .fixtures(["models.list": "res-models-list", "config.get": "res-config-get"]))
        #expect(try await repo.getModels().isEmpty)
    }

    @Test func withoutOperatorAdminTheListStillLoadsWithNothingActive() async throws {
        let (repo, _) = try await makeRepo(["models.list": [catalogue], "config.get": [try GatewayFixtures.text("err-missing-scope")]])
        let list = try await repo.getModels()
        #expect(list.count == 2)
        #expect(list.allSatisfy { !$0.isActive })
    }

    @Test func setActiveModelPatchesThePrimaryAgainstTheCurrentHash() async throws {
        let (repo, socket) = try await makeRepo(["config.get": [configWithPrimary("anthropic/claude-sonnet-4-6")], "config.patch": [okReply]])
        try await repo.setActiveModel(modelId: "openai/gpt-6-astra")
        let params = try await sentParams(socket, "config.patch")
        #expect(params["raw"] as? String == #"{"agents":{"defaults":{"model":{"primary":"openai/gpt-6-astra"}}}}"#)
        #expect(params["baseHash"] as? String == "h-1")
    }

    @Test func aRejectedModelIsHTTP422() async throws {
        let rejection = try rejectionFrame(message: "invalid config: unknown model x/y")
        let (repo, _) = try await makeRepo(["config.get": [configWithPrimary("a/b")], "config.patch": [rejection]])
        guard case .api(_, 422, _)? = await gatewayError({ try await repo.setActiveModel(modelId: "x/y") }) else {
            Issue.record("expected api 422")
            return
        }
    }

    @Test func refsAreProviderQualifiedExactlyOnce() {
        #expect(modelRef(provider: "anthropic", id: "claude") == "anthropic/claude")
        #expect(modelRef(provider: "openrouter", id: "openrouter/x") == "openrouter/x")
    }
}
```

- [ ] **Step 2: Run it to make sure it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: FAIL to compile (`extra argument 'session' in call`, `cannot find 'modelRef' in scope`).

- [ ] **Step 3: Rewrite the repository**

Replace `ios/GhostCrab/Data/Impl/ModelRepositoryImpl.swift`:
```swift
import Foundation

/// ``ModelRepository`` over the live session socket (ADR-0007). The catalogue is `models.list`; the active model is
/// the config key `agents.defaults.model.primary` (`provider/model`), read with `config.get` and written with
/// `config.patch`. ``ModelInfo/id`` is that `provider/model` ref.
public final class ModelRepositoryImpl: ModelRepository, Sendable {

    private let session: @Sendable () async throws -> GatewayRPCClient

    /// - Parameter session: The live session socket; throws while disconnected.
    public init(session: @escaping @Sendable () async throws -> GatewayRPCClient) {
        self.session = session
    }

    /// - Parameter connectionManager: Source of the live session socket.
    public convenience init(connectionManager: GatewayConnectionManagerImpl) {
        self.init(session: { try await connectionManager.requireSession() })
    }

    /// - Returns: The catalogue; the entry matching `agents.defaults.model.primary` is active. Without
    ///   `operator.admin` the primary cannot be read and nothing is active.
    public func getModels() async throws -> [ModelInfo] {
        let rpc = try await session()
        let payload = try await rpc.request("models.list")
        let entries = (try? payload?.decoded(as: ModelsListWire.self))?.models ?? []
        let primary = try await primaryModel(rpc)
        return entries.compactMap { $0.modelInfo(primary: primary) }
    }

    /// Writes `agents.defaults.model.primary`.
    ///
    /// - Parameter modelId: A ``ModelInfo/id`` (`provider/model`).
    /// - Throws: `GatewayError.api` 412 on a concurrent config change, 422 when the gateway rejects the model;
    ///   `.missingScope` without `operator.admin`.
    public func setActiveModel(modelId: String) async throws {
        let rpc = try await session()
        let hash = try await rpc.request("config.get")?.objectValue?["hash"]?.value as? String
        let patch: [String: AnyCodable] = [
            "agents": AnyCodable(["defaults": AnyCodable(["model": AnyCodable(["primary": AnyCodable(modelId)])])]),
        ]
        do {
            _ = try await rpc.request("config.patch", params: try ConfigWrites.patchParams(patch, baseHash: hash), timeout: ConfigWrites.patchTimeout)
        } catch let error as GatewayError {
            throw ConfigWrites.mapRejection(error)
        }
    }

    /// `agents.defaults.model.primary` (object or string form); `nil` when unset or without `operator.admin`.
    private func primaryModel(_ rpc: GatewayRPCClient) async throws -> String? {
        let payload: AnyCodable?
        do {
            payload = try await rpc.request("config.get")
        } catch GatewayError.missingScope {
            return nil // config.* is admin-only; the catalogue stays useful without the active marker
        }
        let model = payload?.object(at: "parsed", "agents", "defaults")?["model"]?.value
        if let object = model as? [String: AnyCodable] {
            return object["primary"]?.value as? String
        }
        return model as? String
    }
}

/// - Returns: `provider/id`, unless `id` already carries the provider prefix.
func modelRef(provider: String, id: String) -> String {
    id.hasPrefix("\(provider)/") ? id : "\(provider)/\(id)"
}

/// The parts of a `models.list` payload GhostCrab shows.
struct ModelsListWire: Decodable {
    struct Entry: Decodable {
        let id: String?
        let name: String?
        let provider: String?
        let available: Bool?
        let unavailableReason: String?
        let input: [String]?
        let reasoning: Bool?

        func modelInfo(primary: String?) -> ModelInfo? {
            guard let id, let provider else { return nil }
            let ref = modelRef(provider: provider, id: id)
            return ModelInfo(
                id: ref,
                provider: provider,
                displayName: name ?? id,
                isActive: primary != nil && (primary == ref || primary == id),
                status: available == false ? (unavailableReason ?? "unavailable") : "ready",
                capabilities: (input ?? []) + (reasoning == true ? ["reasoning"] : [])
            )
        }
    }

    let models: [Entry]?
}
```

In `ModelRepository.swift`, replace
```swift
    /// - Returns: An array of `ModelInfo`, where exactly one entry has `isActive == true`
    ///   (or an empty list if no models are configured).
```
with
```swift
    /// - Returns: An array of `ModelInfo`, where at most one entry has `isActive == true`
    ///   (none when no primary model is configured or it cannot be read), or an empty list.
```

- [ ] **Step 4: Run the tests** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run with `ModelRepositoryImplTests` and `ModelManagerViewModelTests`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Impl/ModelRepositoryImpl.swift ios/GhostCrab/Domain/Repository/ModelRepository.swift ios/GhostCrabTests/Gateway/ModelRepositoryImplTests.swift
git commit -m "feat(ios): models via models.list, activate via agents.defaults.model.primary" -m "No models.set upstream; activation is a config.patch of the primary model ref with baseHash (ADR-0007)."
```

---

### Task 14: AI recommendations become honestly unsupported

**Files:**
- Rewrite: `ios/GhostCrab/Data/Impl/AIRecommendationServiceImpl.swift`
- Test: `ios/GhostCrabTests/Gateway/AIRecommendationServiceImplTests.swift`

No upstream method or route produces recommendations (QA §8.11). A `chat.send`-based redesign needs its own ADR. Until then the service never claims availability, so the Dashboard hides the AI tile (`DashboardViewModel.aiAvailable` stays `false`) and a direct call fails with the existing typed error.

- [ ] **Step 1: Write the failing test**

`ios/GhostCrabTests/Gateway/AIRecommendationServiceImplTests.swift`:
```swift
import Foundation
import Testing
@testable import GhostCrab

@Suite("AIRecommendationServiceImpl")
struct AIRecommendationServiceImplTests {

    private let context = RecommendationContext(activeConfig: OpenClawConfig(sections: [:]), hardwareInfo: nil, activeModelId: nil)

    @Test func neverAvailableEvenWhenConnected() async throws {
        let gateway = FakeGatewayConnectionManager()
        try await gateway.connect(url: "http://192.168.1.50:18789", token: nil)
        #expect(await AIRecommendationServiceImpl(connectionManager: gateway).isAvailable() == false)
    }

    @Test func aRecommendationIsUnavailableAtTheConnectedGateway() async throws {
        let gateway = FakeGatewayConnectionManager()
        try await gateway.connect(url: "http://192.168.1.50:18789", token: nil)
        let service = AIRecommendationServiceImpl(connectionManager: gateway)
        guard case .aiServiceUnavailable("http://192.168.1.50:18789")? = await gatewayError({
            _ = try await service.getRecommendation(query: "q", context: context)
        }) else {
            Issue.record("expected aiServiceUnavailable")
            return
        }
    }

    @Test func disconnectedIsUnreachable() async {
        let service = AIRecommendationServiceImpl(connectionManager: FakeGatewayConnectionManager())
        guard case .unreachable? = await gatewayError({ _ = try await service.getRecommendation(query: "q", context: context) }) else {
            Issue.record("expected unreachable")
            return
        }
    }
}
```
(`RecommendationContext`'s initializer labels are `activeConfig:hardwareInfo:activeModelId:`; adjust if they differ.)

- [ ] **Step 2: Run it to make sure it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: FAIL to compile (`cannot convert value of type 'FakeGatewayConnectionManager' to expected argument type 'GatewayConnectionManagerImpl'`).

- [ ] **Step 3: Implement**

Replace `ios/GhostCrab/Data/Impl/AIRecommendationServiceImpl.swift`:
```swift
import Foundation

/// ``AIRecommendationService`` for gateways without a recommendation API: every OpenClaw gateway as of 2026.10.3
/// (QA §8.11, ADR-0007). Never available; a direct call fails with ``GatewayError/aiServiceUnavailable(url:)``.
/// A `chat.send`-based implementation needs its own ADR (it changes ADR-004's contract).
public final class AIRecommendationServiceImpl: AIRecommendationService, Sendable {

    private let connectionManager: any GatewayConnectionManager

    /// - Parameter connectionManager: Supplies the connected gateway's URL for the error.
    public init(connectionManager: any GatewayConnectionManager) {
        self.connectionManager = connectionManager
    }

    /// Always `false`: no upstream method produces recommendations.
    public func isAvailable() async -> Bool {
        false
    }

    /// - Throws: ``GatewayError/aiServiceUnavailable(url:)`` naming the connected gateway;
    ///   ``GatewayError/unreachable(url:underlying:)`` while disconnected.
    public func getRecommendation(query: String, context: RecommendationContext) async throws -> AIRecommendation {
        guard case let .connected(url, _, _, _, _, _, _, _) = connectionManager.currentState else {
            throw GatewayError.unreachable(url: "<no active connection>", underlying: nil)
        }
        throw GatewayError.aiServiceUnavailable(url: url)
    }
}
```

- [ ] **Step 4: Run the tests** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run with `-only-testing:GhostCrabTests`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Data/Impl/AIRecommendationServiceImpl.swift ios/GhostCrabTests/Gateway/AIRecommendationServiceImplTests.swift
git commit -m "feat(ios): AI recommendations report unsupported" -m "No upstream method produces recommendations (QA 8.11); the Dashboard tile stays hidden and a direct call fails with aiServiceUnavailable (ADR-0007)."
```

---
### Task 15: Pairing approval loop

**Files:**
- Create: `ios/GhostCrab/UI/Connection/PairingApproval.swift`
- Test: `ios/GhostCrabTests/UI/PairingApprovalTests.swift`

ADR-0007 decision 7: on `PAIRING_REQUIRED` the app shows the exact approval command and re-attempts the connect every 5 s (or at once on "Check now") for the 5-minute life of a pending request. Nothing can be approved from the phone, and no event can reach an unpaired socket, so polling by reconnecting is the only signal.

- [ ] **Step 1: Write the failing test**

`ios/GhostCrabTests/UI/PairingApprovalTests.swift`:
```swift
import Foundation
import Testing
@testable import GhostCrab

/// Fails the first `failures` attempts with `error`, then succeeds.
@MainActor
private final class Attempts {
    private(set) var count = 0
    private let failures: Int
    private let error: GatewayError

    init(failures: Int, error: GatewayError = PairingApprovalTests.pending) {
        self.failures = failures
        self.error = error
    }

    func run() throws {
        count += 1
        if count <= failures { throw error }
    }
}

@MainActor
@Suite("Pairing approval loop")
struct PairingApprovalTests {

    static let pending = GatewayError.pairingRequired(url: "ws://gw/ws", requestId: "req-9", reason: "not-paired", requestedScopes: ["operator.read"])

    @Test func thePromptNamesTheExactCommand() throws {
        let prompt = try #require(PairingPrompt(error: Self.pending))
        #expect(prompt.approveCommand == "openclaw devices approve req-9")
        #expect(prompt.requestedScopes == ["operator.read"])
        let anonymous = GatewayError.pairingRequired(url: "u", requestId: nil, reason: "not-paired", requestedScopes: [])
        #expect(PairingPrompt(error: anonymous)?.approveCommand == "openclaw devices list")
        #expect(PairingPrompt(error: GatewayError.timeout(url: "u")) == nil)
    }

    @Test func aConnectThatSucceedsShowsNoPrompt() async throws {
        var prompts: [PairingPrompt] = []
        let attempts = Attempts(failures: 0)
        let connected = try await connectAwaitingApproval(wakeUp: PairingWakeUp(), onPrompt: { prompts.append($0) }) {
            try attempts.run()
        }
        #expect(connected)
        #expect(prompts.isEmpty)
        #expect(attempts.count == 1)
    }

    @Test func retriesUntilApproved() async throws {
        var prompts: [PairingPrompt] = []
        let attempts = Attempts(failures: 2)
        let connected = try await connectAwaitingApproval(
            wakeUp: PairingWakeUp(), onPrompt: { prompts.append($0) }, interval: .milliseconds(10), timeout: .seconds(10)
        ) {
            try attempts.run()
        }
        #expect(connected)
        #expect(attempts.count == 3)
        #expect(prompts.count == 2)
    }

    @Test func anyOtherFailureEndsTheWait() async {
        let attempts = Attempts(failures: 1)
        var calls = 0
        await #expect(throws: GatewayError.self) {
            _ = try await connectAwaitingApproval(wakeUp: PairingWakeUp(), onPrompt: { _ in }, interval: .milliseconds(10)) {
                calls += 1
                if calls == 1 { try attempts.run() }
                throw GatewayError.connectRejected(url: "u", detailCode: "AUTH_TOKEN_MISMATCH", message: "m", recommendedNextStep: nil)
            }
        }
        #expect(calls == 2)
    }

    @Test func noApprovalWithinTheWindowTimesOut() async throws {
        let attempts = Attempts(failures: .max)
        let connected = try await connectAwaitingApproval(
            wakeUp: PairingWakeUp(), onPrompt: { _ in }, interval: .milliseconds(10), timeout: .milliseconds(80)
        ) {
            try attempts.run()
        }
        #expect(!connected)
        #expect(attempts.count >= 2)
    }

    @Test(.timeLimit(.minutes(1)))
    func checkNowRetriesImmediately() async throws {
        let wakeUp = PairingWakeUp()
        let attempts = Attempts(failures: 1)
        let loop = Task {
            try await connectAwaitingApproval(wakeUp: wakeUp, onPrompt: { _ in }, interval: .seconds(60), timeout: .seconds(120)) {
                try attempts.run()
            }
        }
        while attempts.count < 1 { await Task.yield() }
        wakeUp.fireNow()
        #expect(try await loop.value)
        #expect(attempts.count == 2)
    }

    @Test(.timeLimit(.minutes(1)))
    func cancellingEndsTheWait() async {
        let attempts = Attempts(failures: .max)
        let loop = Task {
            try await connectAwaitingApproval(wakeUp: PairingWakeUp(), onPrompt: { _ in }, interval: .seconds(60)) {
                try attempts.run()
            }
        }
        while attempts.count < 1 { await Task.yield() }
        loop.cancel()
        await #expect(throws: CancellationError.self) { _ = try await loop.value }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run the test command with `PairingApprovalTests`. Expected: FAIL to compile (`cannot find 'PairingPrompt' in scope`).

- [ ] **Step 3: Implement**

`ios/GhostCrab/UI/Connection/PairingApproval.swift`:
```swift
import Foundation

/// What the gateway owner must do to approve this device (ADR-0007 decision 7).
public struct PairingPrompt: Equatable, Sendable {
    /// The pending request, when the gateway sent one.
    public let requestId: String?
    /// `not-paired`, `scope-upgrade`, `role-upgrade` or `metadata-upgrade`.
    public let reason: String
    /// The scopes this device asked for.
    public let requestedScopes: [String]

    public init(requestId: String?, reason: String, requestedScopes: [String]) {
        self.requestId = requestId
        self.reason = reason
        self.requestedScopes = requestedScopes
    }

    /// - Returns: The prompt for a ``GatewayError/pairingRequired(url:requestId:reason:requestedScopes:)``, `nil`
    ///   for any other error.
    public init?(error: any Error) {
        guard case let .pairingRequired(_, requestId, reason, scopes)? = error as? GatewayError else { return nil }
        self.init(requestId: requestId, reason: reason, requestedScopes: scopes)
    }

    /// The exact command for the gateway host.
    public var approveCommand: String {
        requestId.map { "openclaw devices approve \($0)" } ?? "openclaw devices list"
    }
}

/// Approval-loop timing and copy.
public enum PairingApproval {
    /// How often a connect is re-attempted while the device awaits approval.
    public static let retryInterval: Duration = .seconds(5)
    /// Upstream expires a pending device-pairing request after 5 minutes.
    public static let timeout: Duration = .seconds(300)
    /// Shown when no approval arrived before the pending request expired.
    public static let timeoutMessage = "Device approval not received within 5 minutes; the pending request expired. "
        + "On the gateway host run `openclaw devices list`, approve this device, then connect again (PAIRING_REQUIRED)."
}

/// Result of ``awaitPairingApproval(wakeUp:onPrompt:interval:timeout:attempt:)``.
public enum PairingOutcome: Equatable, Sendable {
    case approved
    case timedOut
}

/// "Check now" for the approval loop: ends the current wait early.
@MainActor
public final class PairingWakeUp {

    private static let tick: Duration = .milliseconds(100)
    private var wakeRequested = false

    public init() {}

    /// Ends the current (or the next) ``wait(_:)`` at once.
    public func fireNow() {
        wakeRequested = true
    }

    /// Sleeps for `interval`, or until ``fireNow()``.
    ///
    /// - Throws: `CancellationError` when the calling task is cancelled.
    func wait(_ interval: Duration) async throws {
        let deadline = ContinuousClock.now.advanced(by: interval)
        while !wakeRequested, ContinuousClock.now < deadline {
            try await Task.sleep(for: min(Self.tick, ContinuousClock.now.duration(to: deadline)))
        }
        wakeRequested = false
    }
}

/// Re-runs `attempt` every `interval` (or at once on ``PairingWakeUp/fireNow()``) while it fails with
/// `PAIRING_REQUIRED`, passing each prompt to `onPrompt`. Nothing is approved from the phone.
///
/// - Parameters:
///   - wakeUp: "Check now".
///   - onPrompt: Receives every prompt the gateway reports while waiting.
///   - interval: Delay between attempts.
///   - timeout: Overall budget (the pending request's lifetime).
///   - attempt: The connect to retry.
/// - Returns: `.approved` once `attempt` succeeds, `.timedOut` after `timeout`.
/// - Throws: Whatever `attempt` throws other than `PAIRING_REQUIRED` (e.g. a revoked token); `CancellationError`.
@MainActor
public func awaitPairingApproval(
    wakeUp: PairingWakeUp,
    onPrompt: (PairingPrompt) -> Void,
    interval: Duration = PairingApproval.retryInterval,
    timeout: Duration = PairingApproval.timeout,
    attempt: () async throws -> Void
) async throws -> PairingOutcome {
    let deadline = ContinuousClock.now.advanced(by: timeout)
    while ContinuousClock.now < deadline {
        try await wakeUp.wait(min(interval, ContinuousClock.now.duration(to: deadline)))
        do {
            try await attempt()
            return .approved
        } catch {
            guard let prompt = PairingPrompt(error: error) else { throw error }
            onPrompt(prompt)
        }
    }
    return .timedOut
}

/// Connects once; on `PAIRING_REQUIRED` reports the prompt and waits for approval with
/// ``awaitPairingApproval(wakeUp:onPrompt:interval:timeout:attempt:)``.
///
/// - Returns: `true` once connected, `false` when the approval window expired.
/// - Throws: Any other connect failure; `CancellationError`.
@MainActor
public func connectAwaitingApproval(
    wakeUp: PairingWakeUp,
    onPrompt: (PairingPrompt) -> Void,
    interval: Duration = PairingApproval.retryInterval,
    timeout: Duration = PairingApproval.timeout,
    connect: () async throws -> Void
) async throws -> Bool {
    do {
        try await connect()
        return true
    } catch {
        guard let prompt = PairingPrompt(error: error) else { throw error }
        onPrompt(prompt)
        let outcome = try await awaitPairingApproval(
            wakeUp: wakeUp, onPrompt: onPrompt, interval: interval, timeout: timeout, attempt: connect
        )
        return outcome == .approved
    }
}
```

- [ ] **Step 4: Run the test to verify it passes** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/UI/Connection/PairingApproval.swift ios/GhostCrabTests/UI/PairingApprovalTests.swift
git commit -m "feat(ios): approval wait loop for PAIRING_REQUIRED" -m "Re-attempts the connect every 5 s (or on Check now) for the 5-minute life of a pending request; any other failure ends the wait (ADR-0007)."
```

---

### Task 16: Approval card on Manual Entry

**Files:**
- Create: `ios/GhostCrab/UI/Connection/PairingApprovalCard.swift`
- Modify: `ios/GhostCrab/UI/Connection/ManualEntryViewModel.swift`, `ios/GhostCrab/UI/Connection/ManualEntryScreen.swift`
- Modify: `ios/GhostCrabTests/Support/Fakes.swift` (`FakeGatewayConnectionManager`)
- Test: `ios/GhostCrabTests/UI/ManualEntryViewModelTests.swift`

- [ ] **Step 1: Let the fake fail a few connects first**

In `Fakes.swift`, replace the whole `// MARK: - GatewayConnectionManager` class with:
```swift
// MARK: - GatewayConnectionManager

final class FakeGatewayConnectionManager: GatewayConnectionManager, @unchecked Sendable {
    private let lock = NSLock()
    private let connectError: GatewayError?
    private let probeResult: Result<AuthRequirement, GatewayError>
    private var queuedFailures: [GatewayError]
    private var state: GatewayConnection = .disconnected
    private var connectLog: [(url: String, token: String?)] = []
    private var probeLog: [String] = []
    private var disconnects = 0

    /// - Parameters:
    ///   - connectError: When set, every `connect(url:token:)` throws it (after `failFirst` is used up).
    ///   - probeResult: What every `probeAuth(url:)` returns or throws.
    ///   - failFirst: Thrown by the first connects, one per call, in order (e.g. `PAIRING_REQUIRED` twice).
    init(
        connectError: GatewayError? = nil,
        probeResult: Result<AuthRequirement, GatewayError> = .success(AuthRequirement.none),
        failFirst: [GatewayError] = []
    ) {
        self.connectError = connectError
        self.probeResult = probeResult
        self.queuedFailures = failFirst
    }

    var probedURLs: [String] { lock.withLock { probeLog } }
    var connectURLs: [String] { lock.withLock { connectLog.map { $0.url } } }
    var connectTokens: [String?] { lock.withLock { connectLog.map { $0.token } } }
    var disconnectCount: Int { lock.withLock { disconnects } }

    var currentState: GatewayConnection { lock.withLock { state } }

    func connectionStates() -> AsyncStream<GatewayConnection> {
        let snapshot = currentState
        return AsyncStream { continuation in
            continuation.yield(snapshot)
            continuation.finish()
        }
    }

    func probeAuth(url: String) async throws -> AuthRequirement {
        lock.withLock { probeLog.append(url) }
        return try probeResult.get()
    }

    func connect(url: String, token: String?) async throws {
        let queued: GatewayError? = lock.withLock {
            connectLog.append((url: url, token: token))
            return queuedFailures.isEmpty ? nil : queuedFailures.removeFirst()
        }
        if let queued { throw queued }
        if let connectError { throw connectError }
        lock.withLock {
            state = .connected(
                url: url,
                displayName: "Fake Gateway",
                version: "test",
                authRequirement: token == nil ? AuthRequirement.none : AuthRequirement.token,
                isHttps: url.lowercased().hasPrefix("https://"),
                capabilities: []
            )
        }
    }


    func disconnect() async {
        lock.withLock {
            disconnects += 1
            state = .disconnected
        }
    }
}
```

- [ ] **Step 2: Write the failing ViewModel tests**

In `ManualEntryViewModelTests.swift`, replace the `makeVM` helper with:
```swift
    private func makeVM(
        handoff: PairingHandoff,
        gateway: FakeGatewayConnectionManager = FakeGatewayConnectionManager(),
        profiles: FakeConnectionProfileRepository = FakeConnectionProfileRepository(),
        onboarding: FakeOnboardingRepository = FakeOnboardingRepository(),
        pairingRetryInterval: Duration = PairingApproval.retryInterval,
        pairingTimeout: Duration = PairingApproval.timeout
    ) -> ManualEntryViewModel {
        ManualEntryViewModel(
            connectionManager: gateway,
            profileRepository: profiles,
            onboardingRepository: onboarding,
            pairingHandoff: handoff,
            pairingRetryInterval: pairingRetryInterval,
            pairingTimeout: pairingTimeout
        )
    }

    private static let pending = GatewayError.pairingRequired(
        url: "ws://192.168.1.20:18789/ws", requestId: "req-7", reason: "not-paired", requestedScopes: ["operator.read"]
    )
```
and add these tests to the suite:
```swift
    @Test(.timeLimit(.minutes(1)))
    func pairingRequiredShowsTheCommandThenConnectsOnCheckNow() async {
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending])
        let profiles = FakeConnectionProfileRepository()
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway, profiles: profiles, pairingRetryInterval: .seconds(60))
        vm.setHost("192.168.1.20")

        vm.connect()
        while vm.pairingPrompt == nil { await Task.yield() }
        #expect(vm.pairingPrompt?.approveCommand == "openclaw devices approve req-7")
        #expect(vm.isConnecting)
        #expect(vm.lastError == nil)

        vm.checkPairingNow()
        await vm.connectTask?.value
        #expect(vm.navigateToDashboard)
        #expect(vm.pairingPrompt == nil)
        #expect(gateway.connectURLs.count == 2)
        #expect(profiles.savedProfiles.count == 1)
    }

    @Test func noApprovalInTimeShowsTheNextStep() async {
        let gateway = FakeGatewayConnectionManager(connectError: Self.pending)
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway, pairingRetryInterval: .milliseconds(10), pairingTimeout: .milliseconds(60))
        vm.setHost("192.168.1.20")
        vm.connect()
        await vm.connectTask?.value
        #expect(vm.lastError == PairingApproval.timeoutMessage)
        #expect(vm.pairingPrompt == nil)
        #expect(!vm.isConnecting)
        #expect(!vm.navigateToDashboard)
    }

    @Test(.timeLimit(.minutes(1)))
    func cancellingWhileWaitingClearsTheCard() async {
        let gateway = FakeGatewayConnectionManager(connectError: Self.pending)
        let vm = makeVM(handoff: PairingHandoff(), gateway: gateway, pairingRetryInterval: .seconds(60))
        vm.setHost("192.168.1.20")
        vm.connect()
        while vm.pairingPrompt == nil { await Task.yield() }
        vm.cancelConnect()
        #expect(vm.pairingPrompt == nil)
        #expect(!vm.isConnecting)
        #expect(vm.lastError == nil)
    }
```

- [ ] **Step 3: Run them to make sure they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run the test command with `ManualEntryViewModelTests`. Expected: FAIL to compile (`extra arguments at positions #5, #6`, `value of type 'ManualEntryViewModel' has no member 'pairingPrompt'`).

- [ ] **Step 4: ViewModel**

In `ManualEntryViewModel.swift`:

After `private let pairingHandoff: PairingHandoff` add:
```swift
    private let pairingRetryInterval: Duration
    private let pairingTimeout: Duration
    @ObservationIgnored private let pairingWakeUp = PairingWakeUp()
```
After `public private(set) var navigateToDashboard: Bool = false` add:
```swift

    /// Set while the connect waits for `openclaw devices approve` on the gateway host (ADR-0007); the screen
    /// shows ``PairingApprovalCard``. `nil` otherwise.
    public private(set) var pairingPrompt: PairingPrompt?
```
Replace the initializer with:
```swift
    /// - Parameters:
    ///   - connectionManager: Drives the connect flow.
    ///   - profileRepository: Persists the profile + token after a successful connect.
    ///   - onboardingRepository: Marked completed after a successful connect.
    ///   - pairingHandoff: One-shot source of a token from QR / deep-link pairing.
    ///   - pairingRetryInterval: Delay between connects while awaiting device approval.
    ///   - pairingTimeout: How long to wait for approval (upstream expires the request after 5 minutes).
    public init(
        connectionManager: any GatewayConnectionManager,
        profileRepository: any ConnectionProfileRepository,
        onboardingRepository: any OnboardingRepository,
        pairingHandoff: PairingHandoff,
        pairingRetryInterval: Duration = PairingApproval.retryInterval,
        pairingTimeout: Duration = PairingApproval.timeout
    ) {
        self.connectionManager = connectionManager
        self.profileRepository = profileRepository
        self.onboardingRepository = onboardingRepository
        self.pairingHandoff = pairingHandoff
        self.pairingRetryInterval = pairingRetryInterval
        self.pairingTimeout = pairingTimeout
    }
```
In `connect()`, replace
```swift
                try await self.connectionManager.connect(url: url, token: tokenValue)
                gatewayAccepted = true
```
with
```swift
                let connected = try await connectAwaitingApproval(
                    wakeUp: self.pairingWakeUp,
                    onPrompt: { self.pairingPrompt = $0 },
                    interval: self.pairingRetryInterval,
                    timeout: self.pairingTimeout
                ) {
                    try await self.connectionManager.connect(url: url, token: tokenValue)
                }
                self.pairingPrompt = nil
                guard connected else {
                    self.isConnecting = false
                    self.lastError = PairingApproval.timeoutMessage
                    return
                }
                gatewayAccepted = true
```
and in `connect()`'s `catch` block replace
```swift
            } catch {
                guard !Task.isCancelled else {
```
with
```swift
            } catch {
                self.pairingPrompt = nil
                guard !Task.isCancelled else {
```
Add after `cancelConnect()`:
```swift

    /// "Check now" on the approval card: re-attempts the connect at once.
    public func checkPairingNow() {
        pairingWakeUp.fireNow()
    }
```
and in `cancelConnect()`, after `isConnecting = false`, add:
```swift
        pairingPrompt = nil
```

- [ ] **Step 5: The card**

`ios/GhostCrab/UI/Connection/PairingApprovalCard.swift`:
```swift
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Shown while a connect waits for `openclaw devices approve` on the gateway host (ADR-0007 decision 7).
/// Nothing is approved from the phone: the card names the exact command and the connect retries every 5 s.
struct PairingApprovalCard: View {

    let prompt: PairingPrompt
    let onCheckNow: () -> Void
    let onCancel: () -> Void

    var body: some View {
        GlassSurface {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.sm) {
                HStack(spacing: DesignTokens.Spacing.sm) {
                    ProgressView()
                        .tint(DesignTokens.Color.cyanPulse)
                    Text("Waiting for approval on the gateway")
                        .font(AppFont.bodyBold(15))
                        .foregroundStyle(DesignTokens.Color.amberWarn)
                }
                Text("This device is not paired yet (\(prompt.reason)). On the gateway host run:")
                    .font(AppFont.body(13))
                    .foregroundStyle(DesignTokens.Color.textSecondary)
                Text(prompt.approveCommand)
                    .font(AppFont.mono(13))
                    .foregroundStyle(DesignTokens.Color.textPrimary)
                    .textSelection(.enabled)
                if !prompt.requestedScopes.isEmpty {
                    Text("Requested scopes: \(prompt.requestedScopes.joined(separator: ", "))")
                        .font(AppFont.mono(11))
                        .foregroundStyle(DesignTokens.Color.textSecondary)
                }
                Text("GhostCrab retries every 5 seconds. The request expires after 5 minutes.")
                    .font(AppFont.body(12))
                    .foregroundStyle(DesignTokens.Color.textSecondary)
                HStack(spacing: DesignTokens.Spacing.md) {
                    Button("Copy command") { copy(prompt.approveCommand) }
                    Button("Check now", action: onCheckNow)
                    Spacer(minLength: 0)
                    Button("Cancel", role: .cancel, action: onCancel)
                }
                .font(AppFont.bodyMedium(14))
                .tint(DesignTokens.Color.cyanPrimary)
                .buttonStyle(.borderless)
            }
            .padding(DesignTokens.Spacing.md)
        }
    }

    private func copy(_ text: String) {
        #if canImport(UIKit)
        UIPasteboard.general.string = text
        #endif
    }
}

#Preview {
    ZStack {
        DesignTokens.Color.abyss.ignoresSafeArea()
        PairingApprovalCard(
            prompt: PairingPrompt(requestId: "af0b5d81-7bd2-4b86-80b0-4a489b541974", reason: "not-paired", requestedScopes: ["operator.read", "operator.write"]),
            onCheckNow: {},
            onCancel: {}
        )
        .padding(DesignTokens.Spacing.md)
    }
}
```

In `ManualEntryScreen.swift`, in the last `Section` (the one with `Text(vm.assembledURL)`), replace
```swift
                    if let err = vm.lastError {
```
with
```swift
                    if let prompt = vm.pairingPrompt {
                        PairingApprovalCard(
                            prompt: prompt,
                            onCheckNow: { vm.checkPairingNow() },
                            onCancel: { vm.cancelConnect() }
                        )
                    }

                    if let err = vm.lastError {
```

- [ ] **Step 6: Run the tests** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run the test command with `-only-testing:GhostCrabTests`. Expected: PASS, including the 3 new ManualEntry tests.

- [ ] **Step 7: Look at it** ⚠ manual

Open `PairingApprovalCard.swift` in Xcode and check the preview: amber title, mono command, three buttons on one row at iPhone 16 width, no clipped text with Dynamic Type at the default size.

- [ ] **Step 8: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/UI/Connection/PairingApprovalCard.swift ios/GhostCrab/UI/Connection/ManualEntryViewModel.swift ios/GhostCrab/UI/Connection/ManualEntryScreen.swift ios/GhostCrabTests/Support/Fakes.swift ios/GhostCrabTests/UI/ManualEntryViewModelTests.swift
git commit -m "feat(ios): approval card on Manual Entry" -m "PAIRING_REQUIRED shows the request id and openclaw devices approve <id>, with Copy, Check now and Cancel; the connect continues once approved (ADR-0007)."
```

---

### Task 17: Approval card on the profile list

**Files:**
- Modify: `ios/GhostCrab/UI/Connection/ConnectionPickerViewModel.swift`, `ios/GhostCrab/UI/Connection/ConnectionPickerScreen.swift`
- Test: `ios/GhostCrabTests/UI/ConnectionPickerViewModelTests.swift`

A saved profile hits `PAIRING_REQUIRED` when the gateway forgot the device, or after a scope or metadata upgrade.

- [ ] **Step 1: Write the failing tests**

Add to `ConnectionPickerViewModelTests`:
```swift
    private static let pending = GatewayError.pairingRequired(
        url: "ws://192.168.1.20:18789/ws", requestId: "req-8", reason: "scope-upgrade", requestedScopes: []
    )

    @Test(.timeLimit(.minutes(1)))
    func pairingRequiredWaitsForApprovalThenNavigates() async {
        let profile = FakeConnectionProfileRepository.profile()
        let gateway = FakeGatewayConnectionManager(failFirst: [Self.pending])
        let vm = ConnectionPickerViewModel(
            profileRepository: FakeConnectionProfileRepository(profiles: [profile]),
            connectionManager: gateway,
            pairingRetryInterval: .seconds(60)
        )

        vm.connect(profile)
        while vm.pairingPrompt == nil { await Task.yield() }
        #expect(vm.pairingPrompt?.approveCommand == "openclaw devices approve req-8")
        #expect(vm.connectingId == profile.id)

        vm.checkPairingNow()
        await vm.connectTask?.value
        #expect(vm.navigateToDashboard)
        #expect(vm.pairingPrompt == nil)
        #expect(gateway.connectURLs == [profile.url, profile.url])
    }

    @Test(.timeLimit(.minutes(1)))
    func cancelPairingStopsWaiting() async {
        let profile = FakeConnectionProfileRepository.profile()
        let vm = ConnectionPickerViewModel(
            profileRepository: FakeConnectionProfileRepository(profiles: [profile]),
            connectionManager: FakeGatewayConnectionManager(connectError: Self.pending),
            pairingRetryInterval: .seconds(60)
        )
        vm.connect(profile)
        while vm.pairingPrompt == nil { await Task.yield() }

        vm.cancelPairing()
        #expect(vm.pairingPrompt == nil)
        #expect(vm.connectingId == nil)
        #expect(vm.connectTask == nil)
        #expect(vm.lastError == nil)
        #expect(!vm.navigateToDashboard)
    }
```

- [ ] **Step 2: Run them to make sure they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Expected: FAIL to compile (`extra argument 'pairingRetryInterval' in call`).

- [ ] **Step 3: ViewModel**

In `ConnectionPickerViewModel.swift`:

After `private let connectionManager: any GatewayConnectionManager` add:
```swift
    private let pairingRetryInterval: Duration
    private let pairingTimeout: Duration
    @ObservationIgnored private let pairingWakeUp = PairingWakeUp()
```
After `public private(set) var navigateToDashboard: Bool = false` add:
```swift

    /// Set while a connect waits for device approval on the gateway host (ADR-0007); `nil` otherwise.
    public private(set) var pairingPrompt: PairingPrompt?
```
Replace the initializer with:
```swift
    /// - Parameters:
    ///   - profileRepository: Saved profiles and their tokens.
    ///   - connectionManager: Drives the connect flow.
    ///   - pairingRetryInterval: Delay between connects while awaiting device approval.
    ///   - pairingTimeout: How long to wait for approval.
    public init(
        profileRepository: any ConnectionProfileRepository,
        connectionManager: any GatewayConnectionManager,
        pairingRetryInterval: Duration = PairingApproval.retryInterval,
        pairingTimeout: Duration = PairingApproval.timeout
    ) {
        self.profileRepository = profileRepository
        self.connectionManager = connectionManager
        self.pairingRetryInterval = pairingRetryInterval
        self.pairingTimeout = pairingTimeout
        self.connectionState = connectionManager.currentState
        self.startObserving()
    }
```
Replace the whole `connect(_:)` method with:
```swift
    /// Attempts to connect to the given saved profile, waiting for device approval when the gateway asks for it.
    /// On success, flips `navigateToDashboard`. Ignored while a connect is already in flight, so a double tap
    /// connects once.
    public func connect(_ profile: ConnectionProfile) {
        guard connectTask == nil else { return }
        self.connectingId = profile.id
        self.lastError = nil
        connectTask = Task {
            // A cancelled task leaves `connectTask` alone: `cancelPairing()` already cleared it.
            defer { if !Task.isCancelled { self.connectTask = nil } }
            do {
                let token = profile.hasToken
                    ? try await self.profileRepository.getToken(profileId: profile.id.uuidString)
                    : nil
                let connected = try await connectAwaitingApproval(
                    wakeUp: self.pairingWakeUp,
                    onPrompt: { self.pairingPrompt = $0 },
                    interval: self.pairingRetryInterval,
                    timeout: self.pairingTimeout
                ) {
                    try await self.connectionManager.connect(url: profile.url, token: token)
                }
                self.pairingPrompt = nil
                self.connectingId = nil
                guard connected else {
                    self.lastError = PairingApproval.timeoutMessage
                    return
                }
                guard !Task.isCancelled else {
                    await self.connectionManager.disconnect()
                    return
                }
                self.navigateToDashboard = true
            } catch {
                guard !Task.isCancelled else { return }
                self.pairingPrompt = nil
                self.connectingId = nil
                self.lastError = (error as? LocalizedError)?.errorDescription
                    ?? "Connection failed: \(String(describing: error))"
            }
        }
    }

    /// "Check now" on the approval card.
    public func checkPairingNow() {
        pairingWakeUp.fireNow()
    }

    /// "Cancel" on the approval card: stops waiting. No error is shown.
    public func cancelPairing() {
        connectTask?.cancel()
        connectTask = nil
        connectingId = nil
        pairingPrompt = nil
    }
```

- [ ] **Step 4: Screen**

In `ConnectionPickerScreen.swift`, replace
```swift
                if shouldShowStatusBar {
                    ConnectionStatusBar(connection: vm.connectionState)
                }
```
with
```swift
                if shouldShowStatusBar {
                    ConnectionStatusBar(connection: vm.connectionState)
                }

                if let prompt = vm.pairingPrompt {
                    PairingApprovalCard(
                        prompt: prompt,
                        onCheckNow: { vm.checkPairingNow() },
                        onCancel: { vm.cancelPairing() }
                    )
                    .padding(.horizontal, DesignTokens.Spacing.md)
                    .padding(.top, DesignTokens.Spacing.sm)
                }
```

- [ ] **Step 5: Run the tests** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run the test command with `ConnectionPickerViewModelTests`. Expected: PASS (existing + 2).

- [ ] **Step 6: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/UI/Connection/ConnectionPickerViewModel.swift ios/GhostCrab/UI/Connection/ConnectionPickerScreen.swift ios/GhostCrabTests/UI/ConnectionPickerViewModelTests.swift
git commit -m "feat(ios): approval card on the gateway list" -m "Saved profiles that hit PAIRING_REQUIRED wait for approval with the same card as Manual Entry (ADR-0007)."
```

---

### Task 18: `openclaw qr` setup codes are explained; `ws(s)://` URLs accepted

**Files:**
- Modify: `ios/GhostCrab/Domain/Util/PairingLink.swift`
- Modify: `ios/GhostCrab/UI/Connection/QrScanViewModel.swift`, `ios/GhostCrab/UI/Connection/ManualEntryViewModel.swift`
- Test: `ios/GhostCrabTests/Domain/PairingLinkTests.swift`, `ios/GhostCrabTests/UI/QrScanViewModelTests.swift`, `ios/GhostCrabTests/UI/ManualEntryViewModelTests.swift`

The setup code is base64url JSON `{url: "ws(s)://…", bootstrapToken, expiresAtMs, urls?}`, bare or as `oc-pair://<code>` (`/app/docs/cli/qr.md`). Verified 2026-10-15: its bootstrap token authenticates only `role: "node"` connects; over plain `ws://` it yields a node token with `scopes: []` and no operator handoff, and a node token is refused for operator (`AUTH_DEVICE_TOKEN_MISMATCH`). GhostCrab connects as an operator, so a setup code can never authorize it. GhostCrab therefore recognises setup codes only to say so: the QR scanner and the Pairing link field show what the code is, the gateway URL inside it, and what to do instead (gateway URL + token, then `openclaw devices approve`). The bootstrap token is not even decoded into a property, so it cannot reach a log, the hand-off or a route. The existing error messages stay as they are.

- [ ] **Step 1: Write the failing tests**

Add to `PairingLinkTests` (inside the suite):
```swift
    /// base64url of {"url":"ws://172.17.0.2:18789","bootstrapToken":"bt-test-0001","expiresAtMs":1792028000000}.
    private static let setupCode =
        "eyJ1cmwiOiJ3czovLzE3Mi4xNy4wLjI6MTg3ODkiLCJib290c3RyYXBUb2tlbiI6ImJ0LXRlc3QtMDAwMSIsImV4cGlyZXNBdE1zIjoxNzkyMDI4MDAwMDAwfQ"

    private func encode(_ json: String) -> String {
        Base64URL.encode(Data(json.utf8))
    }

    @Test func aSetupCodeIsRecognisedWithItsGatewayURLAndNothingElse() throws {
        let info = try #require(PairingLink.setupCode(Self.setupCode))
        #expect(info.gatewayURL?.absoluteString == "http://172.17.0.2:18789")
        #expect(!String(reflecting: info).contains("bt-test-0001"))
    }

    @Test func ocPairLinksAreSetupCodesEvenWhenUndecodable() {
        #expect(PairingLink.setupCode("oc-pair://" + Self.setupCode)?.gatewayURL?.absoluteString == "http://172.17.0.2:18789")
        #expect(PairingLink.setupCode("oc-pair://not-base64!") == SetupCodeInfo(gatewayURL: nil))
    }

    @Test func aWssSetupCodeNamesTheHTTPSGatewayOn443() {
        let code = encode(#"{"url":"wss://gw.example/ws","bootstrapToken":"bt"}"#)
        #expect(PairingLink.setupCode(code)?.gatewayURL?.absoluteString == "https://gw.example:443")
    }

    @Test func theExplanationSaysWhyAndWhatToDoInstead() throws {
        let text = PairingLink.setupCodeExplanation(try #require(PairingLink.setupCode(Self.setupCode)))
        #expect(text.contains("http://172.17.0.2:18789"))
        #expect(text.contains("gateway token"))
        #expect(text.contains("openclaw devices approve"))
    }

    @Test func otherInputIsNotASetupCode() {
        #expect(PairingLink.setupCode(encode(#"{"url":"ws://h:1"}"#)) == nil)
        #expect(PairingLink.setupCode("http://192.168.1.20:18789") == nil)
        #expect(PairingLink.setupCode("gw.example.com") == nil)
    }

    @Test func plainWsURLsMapToHTTPBaseURLs() {
        #expect(PairingLink.webSocketGatewayURL(from: "ws://192.168.1.50:18789")?.absoluteString == "http://192.168.1.50:18789")
        #expect(PairingLink.webSocketGatewayURL(from: "wss://gw.example/ws")?.absoluteString == "https://gw.example:443")
        #expect(PairingLink.webSocketGatewayURL(from: "http://h:1") == nil)
    }
```

Add to `QrScanViewModelTests`:
```swift
    @Test func aSetupCodeIsExplainedAndNothingIsPaired() throws {
        let handoff = PairingHandoff()
        let (vm, recorder) = makeVM(handoff: handoff)

        vm.onCodeScanned("eyJ1cmwiOiJ3czovLzE3Mi4xNy4wLjI6MTg3ODkiLCJib290c3RyYXBUb2tlbiI6ImJ0LXRlc3QtMDAwMSIsImV4cGlyZXNBdE1zIjoxNzkyMDI4MDAwMDAwfQ")

        #expect(recorder.urls.isEmpty)
        #expect(vm.error?.contains("http://172.17.0.2:18789") == true)
        #expect(vm.error?.contains("openclaw devices approve") == true)
        #expect(handoff.takePairing(for: try #require(URL(string: "http://172.17.0.2:18789"))) == nil)
    }

    @Test func aWsURLIsAcceptedAsTheGatewayURL() {
        let (vm, recorder) = makeVM()
        vm.onCodeScanned("ws://192.168.1.50:18789")
        #expect(recorder.urls.map(\.absoluteString) == ["http://192.168.1.50:18789"])
    }
```

Add to `ManualEntryViewModelTests`:
```swift
    @Test func aPastedSetupCodeIsExplainedAndFillsNothing() {
        let vm = makeVM(handoff: PairingHandoff())
        vm.pastedLink = "oc-pair://eyJ1cmwiOiJ3czovLzE3Mi4xNy4wLjI6MTg3ODkiLCJib290c3RyYXBUb2tlbiI6ImJ0LXRlc3QtMDAwMSIsImV4cGlyZXNBdE1zIjoxNzkyMDI4MDAwMDAwfQ"

        vm.applyPastedLink()

        #expect(vm.pastedLinkError?.contains("openclaw devices approve") == true)
        #expect(vm.host == "")
        #expect(vm.token == "")
        #expect(vm.pastedLink == "")
    }
```

- [ ] **Step 2: Run them to make sure they fail** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run with `PairingLinkTests`, `QrScanViewModelTests`, `ManualEntryViewModelTests`. Expected: FAIL to compile (`type 'PairingLink' has no member 'setupCode'`, `cannot find 'SetupCodeInfo' in scope`).

- [ ] **Step 3: Recognise setup codes**

In `PairingLink.swift`, add after the `PairingLinkError` `LocalizedError` extension:
```swift
/// What an `openclaw qr` setup code names (ADR-0007). Setup codes pair OpenClaw node apps; their bootstrap token
/// cannot authorize an operator client such as GhostCrab, so only the gateway URL is kept, to explain the code.
public struct SetupCodeInfo: Sendable, Equatable {
    /// The code's gateway URL as an `http(s)` base URL, or `nil` when it has none usable.
    public let gatewayURL: URL?

    public init(gatewayURL: URL?) {
        self.gatewayURL = gatewayURL
    }
}
```
In `enum PairingLink`, add after `defaultHTTPSPort`:
```swift

    /// Prefix of an `openclaw qr` setup-code link.
    public static let setupCodeScheme = "oc-pair://"
```
and before `// MARK: - Internals`:
```swift
    /// Recognises an `openclaw qr` setup code: base64url JSON with a `bootstrapToken`, bare or as `oc-pair://<code>`.
    /// The token is never returned or stored (ADR-0007).
    ///
    /// - Parameter raw: Untrusted text (QR payload or paste).
    /// - Returns: The code's gateway URL; `SetupCodeInfo(gatewayURL: nil)` for an `oc-pair://` link that does not
    ///   decode; `nil` when `raw` is not a setup code (callers then try other formats).
    public static func setupCode(_ raw: String) -> SetupCodeInfo? {
        guard raw.utf8.count <= maxLength else { return nil }
        var code = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let explicit = code.lowercased().hasPrefix(setupCodeScheme)
        if explicit { code = String(code.dropFirst(setupCodeScheme.count)) }
        guard code.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || "-_=".contains($0)) }),
              let json = base64URLDecode(code),
              let wire = try? JSONDecoder().decode(SetupCodeWire.self, from: json),
              wire.hasBootstrapToken
        else {
            return explicit ? SetupCodeInfo(gatewayURL: nil) : nil
        }
        return SetupCodeInfo(gatewayURL: wire.url.flatMap { webSocketGatewayURL(from: $0) ?? gatewayURL(from: $0) })
    }

    /// User-facing explanation for a scanned or pasted setup code: what it is, why GhostCrab cannot use it, and what
    /// to do instead.
    ///
    /// - Parameter info: The recognised code.
    /// - Returns: One paragraph naming the gateway URL (when known), the gateway token and the approval command.
    public static func setupCodeExplanation(_ info: SetupCodeInfo) -> String {
        "This is an OpenClaw setup code (`openclaw qr`). Setup codes pair OpenClaw node apps; they cannot give "
            + "GhostCrab operator access (over plain ws:// they grant no access at all). Enter "
            + (info.gatewayURL?.absoluteString ?? "the gateway URL")
            + " and the gateway token in Manual Entry, then approve this device on the gateway host with "
            + "`openclaw devices approve <requestId>`."
    }

    /// Maps a gateway WebSocket URL (`ws://` / `wss://`) to the matching `http(s)` base URL.
    ///
    /// - Parameter raw: Untrusted URL text.
    /// - Returns: ``gatewayURL(from:)`` of the `http(s)` form, or `nil` when `raw` is not a `ws(s)` URL it accepts.
    public static func webSocketGatewayURL(from raw: String) -> URL? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = trimmed.lowercased()
        if lower.hasPrefix("wss://") { return gatewayURL(from: "https://" + String(trimmed.dropFirst("wss://".count))) }
        if lower.hasPrefix("ws://") { return gatewayURL(from: "http://" + String(trimmed.dropFirst("ws://".count))) }
        return nil
    }

```
and next to `WireV1`:
```swift
    /// The two setup-code fields GhostCrab reads. The token only proves the input is a setup code; it is not kept.
    private struct SetupCodeWire: Decodable {
        let url: String?
        let hasBootstrapToken: Bool

        private enum CodingKeys: String, CodingKey { case url, bootstrapToken }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            url = try container.decodeIfPresent(String.self, forKey: .url)
            let token = try container.decodeIfPresent(String.self, forKey: .bootstrapToken)
            hasBootstrapToken = !(token?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
        }
    }
```

- [ ] **Step 4: Explain instead of pairing**

In `QrScanViewModel.swift`, in `onCodeScanned(_:)`, replace
```swift
        guard let url = PairingLink.gatewayURL(from: trimmed) else {
            error = "QR code doesn't contain a valid gateway URL"
            return
        }
        accept(url)
```
with
```swift
        if let setupCode = PairingLink.setupCode(trimmed) {
            // Its bootstrap token serves node clients only (ADR-0007): explain, never pair with it.
            error = PairingLink.setupCodeExplanation(setupCode)
            return
        }

        guard let url = PairingLink.gatewayURL(from: trimmed) ?? PairingLink.webSocketGatewayURL(from: trimmed) else {
            error = "QR code doesn't contain a valid gateway URL"
            return
        }
        accept(url)
```

In `ManualEntryViewModel.swift`, in `applyPastedLink()`, replace
```swift
        } else if let url = PairingLink.gatewayURL(from: trimmed) {
            setPrefillURL(url)
        } else {
            rejectPaste("Not an http(s) gateway URL or a ghostcrab://pair link")
            return
        }
```
with
```swift
        } else if let url = PairingLink.gatewayURL(from: trimmed) ?? PairingLink.webSocketGatewayURL(from: trimmed) {
            setPrefillURL(url)
        } else if let setupCode = PairingLink.setupCode(trimmed) {
            // Its bootstrap token serves node clients only (ADR-0007): explain, never pair with it.
            rejectPaste(PairingLink.setupCodeExplanation(setupCode))
            return
        } else {
            rejectPaste("Not an http(s) gateway URL or a ghostcrab://pair link")
            return
        }
```

- [ ] **Step 5: Run the tests** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run the test command with `-only-testing:GhostCrabTests`. Expected: PASS, including 9 new tests; the existing pairing-link, QR and paste tests pass unchanged.

- [ ] **Step 6: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/Domain/Util/PairingLink.swift ios/GhostCrab/UI/Connection/QrScanViewModel.swift ios/GhostCrab/UI/Connection/ManualEntryViewModel.swift ios/GhostCrabTests/Domain/PairingLinkTests.swift ios/GhostCrabTests/UI/QrScanViewModelTests.swift ios/GhostCrabTests/UI/ManualEntryViewModelTests.swift
git commit -m "feat(ios): explain openclaw qr setup codes, accept ws(s) URLs" -m "Setup-code bootstrap tokens authenticate only node clients (verified against 2026.10.3), so a scanned or pasted code shows what it is, its gateway URL and the token + devices approve path instead of connecting; the token is never decoded into a property (ADR-0007)."
```

---

### Task 19: Discovery copy (Bonjour is opt-in off macOS)

**Files:**
- Modify: `ios/GhostCrab/UI/Connection/ScanScreen.swift`

The `bonjour` plugin is enabled by default only on macOS; Linux, Windows and container gateways need `openclaw plugins enable bonjour`, and Docker bridge networking does not carry mDNS (QA §4). Copy only, so no unit test.

- [ ] **Step 1: Update the copy**

In `ScanScreen.swift`, replace
```swift
                        message: "Make sure the gateway is on this network and advertising `_openclaw-gw._tcp`. mDNS may be blocked by Wi-Fi isolation.",
```
with
```swift
                        message: "Make sure the gateway is on this network and advertising `_openclaw-gw._tcp`. Gateways on Linux, Windows or Docker advertise only after `openclaw plugins enable bonjour`, and Docker bridge networking does not carry mDNS. Wi-Fi isolation can also block it.",
```

- [ ] **Step 2: Build** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run: `cd ios && xcodegen generate && xcodebuild build -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -quiet`
Expected: BUILD SUCCEEDED.

- [ ] **Step 3: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add ios/GhostCrab/UI/Connection/ScanScreen.swift
git commit -m "docs(ios): tell Linux/Docker users to enable Bonjour on the gateway" -m "OpenClaw advertises _openclaw-gw._tcp only on macOS by default (QA section 4)."
```

---

### Task 20: Delete the dead REST paths

**Files:**
- Rewrite: `ios/GhostCrab/Data/Api/OpenClawAPIClient.swift`
- Delete: `ios/GhostCrab/Data/Api/DTO/StatusResponse.swift`, `ios/GhostCrab/Data/Api/DTO/ModelDTO.swift`, `ios/GhostCrab/Data/Api/DTO/AIRecommendationRequestDTO.swift`, `ios/GhostCrab/Data/Api/DTO/AIRecommendationResponseDTO.swift`, `ios/GhostCrab/Domain/Model/Config/` (four unused typed section models whose `gateway.http` / `gateway.mdns` shape does not exist upstream)
- Modify: `ios/GhostCrab/Data/Impl/GatewayConnectionManagerImpl.swift` (factory, `requireClient`), `ios/GhostCrab/Domain/Model/GatewayConnection.swift`, `ios/GhostCrab/Domain/Repository/GatewayConnectionManager.swift` (doc comments only)
- Delete: `ios/GhostCrabTests/Api/OpenClawAPIClientDecodingTests.swift`
- Rewrite: `ios/GhostCrabTests/Api/OpenClawAPIClientTests.swift`, `ios/GhostCrabTests/Api/OpenClawAPIClientTimeoutTests.swift`
- Modify: `ios/GhostCrabTests/Api/GatewayConnectionManagerCancellationTests.swift`, `ios/GhostCrabTests/Support/GatewayTestDoubles.swift`, `ios/GhostCrabTests/Gateway/GatewayErrorCasesTests.swift`

After Tasks 9–14 nothing calls `status()`, `getConfig()`, `updateConfig`, `getModels()`, `setActiveModel`, `getAIRecommendation`, `getInstalledSkills()`, `installSkill`, `uninstallSkill`, `whoami()` or `requireClient()`. Verify before deleting:

- [ ] **Step 1: Confirm there are no callers**

Run: `cd ios && grep -rnE "requireClient|\.status\(\)|getInstalledSkills|installSkill\(|uninstallSkill|whoami\(|getAIRecommendation|StatusResponse|ModelDTO|AIContextDTO|GatewaySection|GatewayHttpSection|GatewayMdnsSection|GatewayAuthSection" GhostCrab GhostCrabTests --include=*.swift | grep -v "Data/Api/OpenClawAPIClient.swift" | grep -v "Data/Api/DTO/" | grep -v "Domain/Model/Config/" | grep -v "OpenClawAPIClientDecodingTests\|OpenClawAPIClientTests\|OpenClawAPIClientTimeoutTests"`
Expected: only the `requireClient()` definition in `GatewayConnectionManagerImpl.swift`. Anything else: stop and move that caller to the session first.

- [ ] **Step 2: Rewrite the client**

Replace `ios/GhostCrab/Data/Api/OpenClawAPIClient.swift`:
```swift
import Foundation
import os

/// URLSession-backed HTTP client for the one JSON REST route GhostCrab uses: `GET /health` (ADR-0007). Everything
/// else goes over the control-plane WebSocket (``GatewayRPCClient``), and no credential is ever sent over HTTP.
/// Requests pass through ``CleartextPublicIPGuard``. Call ``close()`` when the session ends.
public actor OpenClawAPIClient {

    /// Request timeout policy. `URLRequest.timeoutInterval` is an idle timeout (it covers connection setup), so a
    /// dead or black-holed host fails after ``standard`` seconds; ``resource`` caps a whole transfer.
    enum Timeouts {
        /// `/health`.
        static let standard: TimeInterval = 15
        /// Upper bound for any single transfer.
        static let resource: TimeInterval = 180
    }

    // MARK: - State

    public let baseURL: URL
    /// This client's cleartext-to-public-IP opt-in; attached to every request in ``send(_:timeout:)``.
    private let allowCleartextPublicIPs: Bool
    private let session: URLSession
    private let decoder = JSONDecoder()

    private static let logger = Logger(subsystem: "com.qavren.ghostcrab", category: "OpenClawAPIClient")

    private init(baseURL: URL, allowCleartextPublicIPs: Bool, additionalProtocolClasses: [AnyClass] = []) {
        self.baseURL = baseURL
        self.allowCleartextPublicIPs = allowCleartextPublicIPs
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = Timeouts.standard
        config.timeoutIntervalForResource = Timeouts.resource
        config.waitsForConnectivity = false
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.protocolClasses = [CleartextPublicIPGuard.self] + additionalProtocolClasses + (config.protocolClasses ?? [])
        self.session = URLSession(configuration: config)
    }

    // MARK: - Factories

    /// - Parameters:
    ///   - baseURL: Gateway base URL (no trailing slash).
    ///   - allowCleartextPublicIPs: If `true`, the cleartext-public-IP guard is bypassed.
    public static func unauthenticated(baseURL: URL, allowCleartextPublicIPs: Bool = false) -> OpenClawAPIClient {
        OpenClawAPIClient(baseURL: baseURL, allowCleartextPublicIPs: allowCleartextPublicIPs)
    }

    /// Test seam: the production client plus extra `URLProtocol` classes (e.g. `MockURLProtocol`) consulted after
    /// ``CleartextPublicIPGuard``. Internal, so only reachable through `@testable import`.
    static func makeForTesting(baseURL: URL, allowCleartextPublicIPs: Bool = false, protocolClasses: [AnyClass]) -> OpenClawAPIClient {
        OpenClawAPIClient(
            baseURL: baseURL,
            allowCleartextPublicIPs: allowCleartextPublicIPs,
            additionalProtocolClasses: protocolClasses
        )
    }

    // MARK: - Public API

    /// `GET /health` — unauthenticated liveness check, run before the socket opens.
    ///
    /// - Throws: `GatewayError.unreachable`, `.timeout`, `.tls`, `.cleartextBlocked`, or `.api` / `.auth` for a
    ///   non-2xx status.
    public func health() async throws -> HealthResponse {
        let url = endpoint("/health")
        return try await safe(url: url) {
            let (data, response) = try await self.send(URLRequest(url: url))
            try self.mapErrors(url: url, response: response)
            // Tolerant: a non-JSON body still means the process answered.
            if let decoded = try? self.decoder.decode(HealthResponse.self, from: data) {
                return decoded
            }
            return HealthResponse(status: "ok")
        }
    }

    /// Releases the underlying URLSession. Call on disconnect.
    public func close() {
        session.invalidateAndCancel()
    }

    // MARK: - Helpers

    private func endpoint(_ path: String) -> URL {
        let trimmed = baseURL.absoluteString.hasSuffix("/")
            ? String(baseURL.absoluteString.dropLast())
            : baseURL.absoluteString
        return URL(string: trimmed + path) ?? baseURL.appendingPathComponent(path)
    }

    /// "Response deserialization failed (DecodingError.<kind>): <detail>".
    nonisolated private static func deserializationFailure(_ error: DecodingError) -> String {
        let kind: String
        let detail: String
        switch error {
        case .typeMismatch(_, let context):
            kind = "typeMismatch"
            detail = context.debugDescription
        case .valueNotFound(_, let context):
            kind = "valueNotFound"
            detail = context.debugDescription
        case .keyNotFound(let key, let context):
            kind = "keyNotFound"
            detail = "\(key.stringValue): \(context.debugDescription)"
        case .dataCorrupted(let context):
            kind = "dataCorrupted"
            detail = context.debugDescription
        @unknown default:
            kind = "unknown"
            detail = String(describing: error)
        }
        return "Response deserialization failed (DecodingError.\(kind)): \(detail)"
    }

    /// Sends `request` with this client's cleartext opt-in attached for ``CleartextPublicIPGuard``.
    private func send(_ request: URLRequest, timeout: TimeInterval = Timeouts.standard) async throws -> (Data, URLResponse) {
        var req = request
        req.timeoutInterval = timeout
        req = CleartextPublicIPGuard.tagged(req, allowCleartextPublicIPs: allowCleartextPublicIPs)
        Self.logDebug(req)
        return try await session.data(for: req)
    }

    /// Maps an HTTP response into a `GatewayError`. No-op on 2xx.
    nonisolated private func mapErrors(url: URL, response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { return }
        let code = http.statusCode
        if (200..<300).contains(code) { return }
        switch code {
        case 401, 403:
            throw GatewayError.auth(url: url.absoluteString, statusCode: code)
        default:
            throw GatewayError.api(url: url.absoluteString, statusCode: code)
        }
    }

    /// Same error-translation policy as before ADR-0007: a request ``CleartextPublicIPGuard`` refused becomes
    /// ``GatewayError/cleartextBlocked(url:host:)``, never `.unreachable`; a cancelled task stays a cancellation.
    private func safe<T>(url: URL, _ block: () async throws -> T) async throws -> T {
        do {
            return try await block()
        } catch let e as GatewayError {
            throw e
        } catch let e as URLError {
            switch e.code {
            case .cancelled where Task.isCancelled:
                throw CancellationError()
            case .timedOut:
                throw GatewayError.timeout(url: url.absoluteString, underlying: e)
            case .serverCertificateHasBadDate,
                 .serverCertificateUntrusted,
                 .serverCertificateHasUnknownRoot,
                 .serverCertificateNotYetValid,
                 .clientCertificateRejected,
                 .clientCertificateRequired,
                 .secureConnectionFailed:
                throw GatewayError.tls(url: url.absoluteString, underlying: e)
            default:
                throw GatewayError.unreachable(url: url.absoluteString, underlying: e)
            }
        } catch let e as DecodingError {
            throw GatewayError.api(url: url.absoluteString, statusCode: 0, body: Self.deserializationFailure(e))
        } catch is CancellationError {
            throw CancellationError()
        } catch let e as NSError where e.domain == CleartextPublicIPGuard.errorDomain {
            let host = (e.userInfo[CleartextPublicIPGuard.hostUserInfoKey] as? String) ?? url.host ?? "<unknown>"
            throw GatewayError.cleartextBlocked(url: url.absoluteString, host: host)
        } catch {
            throw GatewayError.unreachable(url: url.absoluteString, underlying: error)
        }
    }

    // MARK: - Logging

    /// Logs method, URL and header names at debug level. No credential is attached to any request; an
    /// `Authorization` header would still be left out.
    nonisolated private static func logDebug(_ req: URLRequest) {
        #if DEBUG
        let method = req.httpMethod ?? "GET"
        let url = req.url?.absoluteString ?? "<nil>"
        var headerNames = req.allHTTPHeaderFields?.keys.map { $0 } ?? []
        headerNames.removeAll { $0.caseInsensitiveCompare("Authorization") == .orderedSame }
        logger.debug("\(method, privacy: .public) \(url, privacy: .public) headers=\(headerNames.joined(separator: ","), privacy: .public)")
        #endif
    }
}
```

- [ ] **Step 3: Drop the token factory and `requireClient()`**

In `GatewayConnectionManagerImpl.swift`, replace the client-factory section with:
```swift
// MARK: - Client factory

/// Builds the REST client used for `GET /health` before the control-plane socket opens (ADR-0007).
/// Injectable so tests can substitute `MockURLProtocol`-backed clients.
public protocol OpenClawAPIClientFactory: Sendable {
    func unauthenticated(baseURL: URL, allowCleartextPublicIPs: Bool) -> OpenClawAPIClient
}

/// Default factory used in production.
public struct DefaultOpenClawAPIClientFactory: OpenClawAPIClientFactory {
    public init() {}

    public func unauthenticated(baseURL: URL, allowCleartextPublicIPs: Bool) -> OpenClawAPIClient {
        OpenClawAPIClient.unauthenticated(baseURL: baseURL, allowCleartextPublicIPs: allowCleartextPublicIPs)
    }
}
```
and delete the `requireClient()` method (its doc comment included).

In `GatewayTestDoubles.swift`, delete `MockRESTFactory.authenticated(baseURL:token:allowCleartextPublicIPs:)`. In `GatewayConnectionManagerCancellationTests.swift`, delete `NeverRespondingClientFactory.authenticated(baseURL:token:allowCleartextPublicIPs:)`.

- [ ] **Step 4: Doc comments on the frozen protocol and model**

In `GatewayConnectionManager.swift` replace the `probeAuth` doc block
```swift
    /// Determines the authentication requirement for a gateway without storing any state.
    ///
    /// Probes `GET /health` (unauthenticated) then `GET /status` (unauthenticated).
    /// - `/health` fails → throws `GatewayError.unreachable`
    /// - `/status` returns 401 or 403 → `.token`
    /// - `/status` returns 2xx → `.none`
```
with
```swift
    /// Determines the authentication requirement for a gateway without storing any state (ADR-0007).
    ///
    /// Probes `GET /health`, then a credential-less signed WebSocket `connect`.
    /// - `/health` fails → throws `GatewayError.unreachable`
    /// - `AUTH_TOKEN_MISSING` / `AUTH_PASSWORD_MISSING` / `AUTH_REQUIRED` → `.token`
    /// - `hello-ok` → its auth mode; `PAIRING_REQUIRED` → `.none` (only the device approval is missing)
```
and the `connect` doc steps
```swift
    /// 1. Emits `GatewayConnection.connecting`.
    /// 2. Calls `probeAuth(url:)` to determine auth mode.
    /// 3. Instantiates an authenticated API client.
    /// 4. Fetches `/status` to read version and capabilities.
    /// 5. Emits `GatewayConnection.connected` on success.
    /// 6. On any failure: emits `GatewayConnection.error` **and** rethrows the error.
```
with
```swift
    /// 1. Emits `GatewayConnection.connecting`.
    /// 2. Checks `GET /health`.
    /// 3. Opens the control-plane WebSocket and sends a signed `connect` (ADR-0007).
    /// 4. Reads version, auth mode and capabilities from `hello-ok`.
    /// 5. Emits `GatewayConnection.connected` on success.
    /// 6. On any failure: emits `GatewayConnection.error` **and** rethrows the error
    ///    (`GatewayError.pairingRequired` while the device awaits approval).
```
In `GatewayConnection.swift`, replace
```swift
    ///   - displayName: Human-readable name from the gateway's `/status` response.
```
with
```swift
    ///   - displayName: The gateway host from `hello-ok` (`snapshot.presence`, reason `self`), or "OpenClaw Gateway".
```
and
```swift
    ///   - hardwareInfo: Optional hardware description reported by the gateway's `/status`
    ///     (e.g. RAM, GPU). `nil` if not reported.
```
with
```swift
    ///   - hardwareInfo: Hardware description. Always `nil` since ADR-0007: the gateway reports none.
```

- [ ] **Step 5: Tests**

Delete `ios/GhostCrabTests/Api/OpenClawAPIClientDecodingTests.swift` after moving its last test into `GatewayErrorCasesTests`:
```swift
    @Test func cleartextBlockedInstallIsNotRetryable() {
        let error = GatewayError.cleartextBlocked(url: "ws://8.8.8.8:18789/ws", host: "8.8.8.8")
        let mapped = error.toSkillInstallError()
        // Its own case, shown as is: no "Protocol error" prefix in the skill UI.
        #expect(mapped == .cleartextBlocked(message: error.errorDescription ?? ""))
        #expect(!mapped.isRetryable)
    }
```

Replace `ios/GhostCrabTests/Api/OpenClawAPIClientTests.swift`:
```swift
import Foundation
import Testing
@testable import GhostCrab

@Suite("OpenClawAPIClient (/health)")
struct OpenClawAPIClientTests {

    private func makeClient(_ handler: @escaping MockURLProtocol.Handler) -> (client: OpenClawAPIClient, base: URL) {
        let base = MockURLProtocol.uniqueBaseURL()
        MockURLProtocol.register(base, handler: handler)
        return (OpenClawAPIClient.makeForTesting(baseURL: base, protocolClasses: [MockURLProtocol.self]), base)
    }

    @Test func healthDecodesJSON() async throws {
        let (client, base) = makeClient { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        #expect(try await client.health().status == "ok")
        #expect(MockURLProtocol.requests(to: base).first?.url?.path == "/health")
    }

    @Test func healthToleratesANonJSONBody() async throws {
        let (client, _) = makeClient { _ in .html() }
        #expect(try await client.health().status == "ok")
    }

    @Test(arguments: [401, 403])
    func authStatusesMapToAuthError(code: Int) async {
        let (client, base) = makeClient { _ in .init(status: code, json: "{}") }
        guard case .auth(let url, code)? = await gatewayError({ _ = try await client.health() }) else {
            Issue.record("expected GatewayError.auth")
            return
        }
        #expect(url == base.absoluteString + "/health")
    }

    @Test func serverErrorsMapToApi() async {
        let (client, _) = makeClient { _ in .init(status: 503, json: "{}") }
        guard case .api(_, 503, _)? = await gatewayError({ _ = try await client.health() }) else {
            Issue.record("expected GatewayError.api 503")
            return
        }
    }

    @Test func noCredentialIsEverSent() async throws {
        let (client, base) = makeClient { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        _ = try await client.health()
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
    }
}
```

Replace `ios/GhostCrabTests/Api/OpenClawAPIClientTimeoutTests.swift`:
```swift
import Foundation
import Testing
@testable import GhostCrab

@Suite("OpenClawAPIClient timeouts")
struct OpenClawAPIClientTimeoutTests {

    @Test func timeoutConstantsHaveTheDocumentedValues() {
        #expect(OpenClawAPIClient.Timeouts.standard == 15)
        #expect(OpenClawAPIClient.Timeouts.resource == 180)
    }

    @Test func healthUsesTheStandardTimeout() async throws {
        let base = MockURLProtocol.uniqueBaseURL()
        MockURLProtocol.register(base) { _ in .init(status: 200, json: #"{"status":"ok"}"#) }
        let client = OpenClawAPIClient.makeForTesting(baseURL: base, protocolClasses: [MockURLProtocol.self])
        _ = try await client.health()
        let request = try #require(MockURLProtocol.requests(to: base).first)
        #expect(request.timeoutInterval == OpenClawAPIClient.Timeouts.standard)
    }
}
```

- [ ] **Step 6: Delete the dead files**

```bash
git rm ios/GhostCrab/Data/Api/DTO/StatusResponse.swift ios/GhostCrab/Data/Api/DTO/ModelDTO.swift ios/GhostCrab/Data/Api/DTO/AIRecommendationRequestDTO.swift ios/GhostCrab/Data/Api/DTO/AIRecommendationResponseDTO.swift
git rm -r ios/GhostCrab/Domain/Model/Config
git rm ios/GhostCrabTests/Api/OpenClawAPIClientDecodingTests.swift
```

- [ ] **Step 7: Run everything** ⚠ must run on the Mac Mini (steve-mac-mini, Xcode)

Run: `cd ios && xcodegen generate && xcodebuild test -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:GhostCrabTests -quiet`
Expected: PASS. Then the Mac Catalyst compile the PR lane runs: `xcodebuild build -project GhostCrab.xcodeproj -scheme GhostCrab -destination 'platform=macOS,variant=Mac Catalyst' CODE_SIGNING_ALLOWED=NO -quiet` → BUILD SUCCEEDED.

- [ ] **Step 8: Commit**

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add -A ios
git commit -m "refactor(ios): delete the speculative REST client paths" -m "Only GET /health stays on HTTP and it carries no credential; /status, /config, /api/models/*, /api/ai/recommend, /api/skills/* and their DTOs are gone, as are the unused typed config sections (ADR-0007)."
```
(`git add -A ios` is safe here: `BuildInfo.swift` was just restored; check `git status` first and unstage anything that is not part of this task.)

---

### Task 21: ⚠ manual — verify against the real gateway

**Files:**
- Modify: `docs/QA_REAL_GATEWAY_2026-10.md` (append to §10, or create it if the Android pass has not run yet)
- Possibly modify (only if the gateway release differs from 2026.10.3): `ios/GhostCrab/Data/Impl/ConfigWrites.swift`, `ios/GhostCrab/Data/Impl/InstalledSkillRepositoryImpl.swift`, `fixtures/gateway-protocol/`

Needs the Mac Mini (simulator), an iPhone for the camera step, a Docker host on the same LAN, and a human. The gateway must see the app as a remote client, so point the app at the Docker host's LAN IP, not `127.0.0.1`.

- [ ] **Step 1: Start the real gateway** ⚠ manual

On the Docker host (as in M5):
```bash
docker volume create openclaw-qa-state
docker create --name openclaw-qa -p 18789:18789 -v openclaw-qa-state:/home/node/.openclaw ghcr.io/openclaw/openclaw:latest
printf '%s' '{"gateway":{"mode":"local","port":18789,"bind":"lan","auth":{"mode":"token","token":"<pick a long random token>"}}}' > openclaw.json
docker cp openclaw.json openclaw-qa:/home/node/.openclaw/openclaw.json && rm openclaw.json
docker start openclaw-qa
docker inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}}' ghcr.io/openclaw/openclaw:latest
```
Record the version. If it is not `2026.10.3`, diff fresh frames against `fixtures/gateway-protocol/2026.10.3/` before going on (the Android plan's `scripts/mock-gateway/probe-real.mjs` prints them, tokens redacted).

- [ ] **Step 2: Re-check the pinned behaviour on a different release** ⚠ manual, only if the version is not `2026.10.3`

The frames this plan relies on were pinned against `2026.10.3` (M5 plus the 2026-10-15 verification run, ADR-0007). For another release, reuse the Android pass (its Task 26 Step 2) or run `scripts/mock-gateway/probe-real.mjs` (`pair`, `config`, `skills` with `INSTALL_SLUG=@steipete/weather`) and compare with `fixtures/gateway-protocol/2026.10.3/`. On any difference, add a `fixtures/gateway-protocol/<version>/` set (tokens replaced with `dt-test-0001`), adapt `ConfigWrites.mapRejection`, `SkillSource.from(statusSource:bundled:clawhub:)` or the mock, and record it.

- [ ] **Step 3: Run the app against it** ⚠ manual (simulator on the Mac Mini, then a device)

`cd ios && xcodegen generate`, run the GhostCrab scheme on the iPhone 16 simulator, and check each item, noting pass/fail:
1. Manual Entry `http://<docker host LAN IP>:18789` with the token → the approval card shows a request id and `openclaw devices approve <id>`; run it on the Docker host → Dashboard within ~5 s.
2. Dashboard: version `2026.10.3`, gateway host name, capabilities, **no** "no auth" banner.
3. Disconnect, reconnect from the saved profile → no approval card (device token reused).
4. Wrong token → error shows the URL and `AUTH_TOKEN_MISMATCH`; no approval card.
5. Config editor: change a hot-reloadable value (find one with `docker exec openclaw-qa node openclaw.mjs gateway call config.schema.lookup --params '{"path":"agents.defaults"}'`, `reloadKind: "hot"`), save, reload, change it back. Do **not** change `gateway.port` / `bind` on the live gateway.
6. Concurrent edit: change the same value with `docker exec … gateway call config.patch` between load and save → the editor reloads with the concurrent-edit message.
7. Models: empty list without a provider; with one configured, activating a model makes `docker exec openclaw-qa node openclaw.mjs models status` show it as default.
8. Installed Skills: the gateway's skills listed, disabled ones marked; Disable one → the row shows "· disabled" right after the write (the list is re-read after `config.changed`).
9. Console.app filtered on the GhostCrab process: no token, device token or bootstrap token text for the whole session.
10. `docker stop openclaw-qa` → the Dashboard leaves Connected (the session socket drop ends in `.error`).
11. Device (camera): `docker exec openclaw-qa node openclaw.mjs qr --url ws://<docker host LAN IP>:18789/ws` → scan it → the scanner explains that setup codes pair node apps, names `http://<docker host LAN IP>:18789` and points to the gateway token and `openclaw devices approve`; nothing connects, nothing is stored.
12. Mac Catalyst: paste the same setup code (or an `oc-pair://` link) into Manual Entry's Pairing link field → the same explanation under the field; the form stays empty.
13. Store screenshots (needs the protocol-4 mock from the Android plan's Task 25 on `main`, which also switches `ios-screenshots.yml` to `AUTO_APPROVE=1`): `gh workflow run ios-screenshots.yml`, then check the artifact: `06-dashboard` shows the gateway name and version from `hello-ok`, the AI screenshot is skipped (tile hidden), `10-skills` lists the mock's skills with Disable instead of Uninstall. Commit refreshed PNGs under `screenshots/store/ios/` only if they look right.

- [ ] **Step 4: Record and commit**

Append an `### iOS` part to `## 10. Post-alignment verification (ADR-0007, <date>)` in `docs/QA_REAL_GATEWAY_2026-10.md` (create the section if the Android pass has not run): image version, any frames pinned in Step 2 (tokens redacted), and the Step 3 results per item.

```bash
git checkout -- ios/GhostCrab/App/BuildInfo.swift
git add docs/QA_REAL_GATEWAY_2026-10.md fixtures/gateway-protocol ios
git commit -m "docs: verify the iOS protocol alignment against the real gateway" -m "Records the post-ADR-0007 simulator and device pass and anything pinned from probe-real.mjs."
```

---

### Task 22: Docs, changelog, ADR bookkeeping

**Files:**
- Modify: `ios/README.md`, `CHANGELOG.md`, `docs/adr/0005-additive-repository-methods.md`, `docs/IMPLEMENTATION_PLAN.md`

- [ ] **Step 1: iOS README**

In `ios/README.md`, replace the first paragraph of `## Pairing links and deep links` with:
```markdown
A pairing link is `ghostcrab://pair?p=<base64url JSON>`; the v1 payload carries the gateway URL, an optional token and an optional name. GhostCrab also takes plain `ws(s)://` URLs. A setup code from `openclaw qr` (bare or `oc-pair://<code>`) is recognised and explained, not used: its bootstrap token serves OpenClaw node apps only and cannot authorize an operator client. Three ways in:
```
and add after that section:
```markdown
## Gateway protocol

The app speaks the real gateway's WebSocket protocol 4 (`docs/adr/0007-gateway-protocol-alignment.md`): `GET /health`, then `connect.challenge` → an Ed25519-signed `connect` → `hello-ok`, then `req`/`res` RPCs (`config.get`/`config.patch`, `models.list`, `skills.status`/`skills.install`/`skills.update`). `Data/Gateway/` holds the codec, the `GatewayRPCClient` actor, the CryptoKit device identity and the handshake. The identity seed lives in its own Keychain service (`com.qavren.ghostcrab.device-identity`) and survives "Clear all saved gateways"; device tokens live in the token service and do not. A new device needs `openclaw devices approve <requestId>` on the gateway host once; the app shows the command and retries every 5 s. Tests replay frames captured from 2026.10.3 (`fixtures/gateway-protocol/`, bundled into the test target by `project.yml`).
```
In the folder layout block, replace
```
│   ├── Data/{Api,Discovery,Storage,Impl}/
```
with
```
│   ├── Data/{Api,Gateway,Discovery,Storage,Impl}/
```

- [ ] **Step 2: Changelog**

In `CHANGELOG.md`, inside `### iOS` directly under its intro paragraph, add:
```markdown
#### Changed (protocol alignment, ADR-0007)

- **Speaks the real OpenClaw gateway protocol**: one WebSocket control plane per session (`connect.challenge` → Ed25519-signed `connect` → `hello-ok`, `req`/`res`/`event` frames with string ids and codes). Only `GET /health` is still HTTP, and no credential is sent over HTTP.
- **Device pairing**: each install has a CryptoKit Ed25519 identity in the Keychain. A new device shows an approval card with the request id and `openclaw devices approve <id>` and retries every 5 s until approved. The gateway's device token is stored per gateway and reused.
- **Auth detection** comes from the connect handshake (ADR-002 superseded); a wrong token fails with the URL and `AUTH_TOKEN_MISMATCH`.
- **Config** uses `config.get` / `config.patch` with a `baseHash` guard; **models** come from `models.list` and activation sets `agents.defaults.model.primary`; **skills** use `skills.status` / `skills.install`, and Disable (`skills.update`) replaces uninstall, which OpenClaw does not have.
- QR scan and the Pairing link field take `ws(s)://` URLs. `openclaw qr` setup codes (bare or `oc-pair://`) are recognised and explained: they pair OpenClaw node apps and cannot authorize an operator client, so GhostCrab points to the gateway token and `openclaw devices approve` instead.
- Scan screen tells Linux, Windows and Docker users to run `openclaw plugins enable bonjour`.

#### Removed (protocol alignment)

- AI Recommendations report unavailable: OpenClaw has no recommendation API.
- The speculative REST client paths (`/status`, `/config`, `/api/models/*`, `/api/ai/recommend`, `/api/skills/*`) and their DTOs.
```

- [ ] **Step 3: ADR-0005 instances**

In `docs/adr/0005-additive-repository-methods.md`, after the `feat/ios-finish` instance list, add:
```markdown

Instances on `feat/protocol-alignment-ios` (ADR-0007, iOS):

- `InstalledSkillRepository.setSkillEnabled(skillKey:enabled:)`
- `InstalledSkill.disabled` (additive model field with a default)
```

- [ ] **Step 4: Implementation plan ledger**

In `docs/IMPLEMENTATION_PLAN.md`, Progress Ledger, add the row:
```markdown
| — | Protocol alignment, iOS (roadmap M7) | 🟢 Done | <start> | <end> | PR #<n> | — | Plan `docs/superpowers/plans/2026-10-15-protocol-alignment-ios.md`, ADR-0007. |
```
(If the Android plan has not run yet, also make its §5 and §6 edits from the Android plan's Task 27 Step 4, worded for iOS.)

- [ ] **Step 5: Commit, push, PR**

```bash
git add ios/README.md CHANGELOG.md docs/adr/0005-additive-repository-methods.md docs/IMPLEMENTATION_PLAN.md
git commit -m "docs: record the iOS protocol alignment" -m "iOS README protocol section, changelog, ADR-0005 instances, implementation-plan ledger (ADR-0007)."
git push -u origin feat/protocol-alignment-ios
gh pr create --title "feat(ios): align with the real OpenClaw gateway protocol (ADR-0007)" --body "Implements docs/superpowers/plans/2026-10-15-protocol-alignment-ios.md. Real-gateway verification results: docs/QA_REAL_GATEWAY_2026-10.md section 10."
```
The iOS PR lane (`ios-pr.yml`) runs the unit suite and the Catalyst build on the Mac Mini; wait for it to pass before merging (squash).

---

## Self-review

Coverage of every M5 mismatch (`docs/QA_REAL_GATEWAY_2026-10.md` §2, §3, §8) on iOS:

| Mismatch | Task |
|---|---|
| `/status` HTML: auth probe always none, wrong token connects (P0 #1) | 8, 9 |
| Identity/version/capabilities from `/status` (P0 #2) | 8, 9 |
| No WS client at all on iOS; envelope with string ids and string error codes (#3) | 2, 3, 4, 5 |
| Challenge, connect frame, `hello-ok` (#4) | 5, 8, 9 |
| Device identity, v3 signature, device-token persistence (#5) | 6, 7, 8, 9 |
| Pairing UX (`PAIRING_REQUIRED`) (#6) | 15, 16, 17 |
| `auth.whoami` → `hello-ok.auth.scopes` (#7) | 10 |
| `/api/skills/*` → `skills.status` / single `res` / `skills.update` (#8) | 11 |
| `/config` + `PATCH` → `config.get` / `config.patch` + `baseHash` (#9) | 12 |
| `/api/models/*` → `models.list` + `agents.defaults.model.primary` (#10) | 13 |
| AI recommend has no upstream equivalent (#11) | 14 |
| `openclaw qr` setup code and `ws(s)://` (#12): setup codes recognised and explained, never used (node-only tokens) | 18 |
| Bonjour opt-in; version from `hello-ok`, not TXT (#13) | 19, 9 |
| Mock gateway validates a contract that does not exist (#14) | Android plan Task 25 (shared `scripts/mock-gateway`); usable in Task 21 |
| Bearer on HTTP; no credential may leave over HTTP | 9, 20 |
| Behaviour pinned by the 2026-10-15 verification run: client-id/mode allow-list and platform pinning; stable `requestId`, 1008 after `PAIRING_REQUIRED`; node-only bootstrap tokens; `baseHash` errors, validation `issues`, `config.patch` success; `skills.update` + `config.changed`; ClawHub install entry | 1, 5, 8, 11, 12, 15, 18 |
| A new gateway release | 21 (probe-real.mjs re-check) |

Placeholder scan: the only `<…>` left are values a person supplies at run time (gateway token, LAN IP, request id, dates and the PR number in the ledger row).

Type consistency checked across tasks: `GatewayRPCClient.request(_:params:timeout:)` / `request(_:timeout:)`, `GatewayErrorShape.toGatewayError(url:method:)`, `GatewayError.toSkillInstallError(slug:)`, `GatewayHandshake.perform(rpc:client:auth:identity:)`, `GatewayConnectAuth.select(sharedToken:stored:)`, `GatewayClientInfo.current(bundle:)` / `iosClientID` / `iosPlatform`, `GatewayKey.of(_:)`, `DeviceTokenStoring.load(gatewayKey:)` / `save(_:gatewayKey:)` / `clear(gatewayKey:)`, `GatewayConnectionManagerImpl.requireSession()` / `grantedScopes()`, `InstalledSkillRepository.setSkillEnabled(skillKey:enabled:)`, `InstalledSkillRepositoryImpl.awaitConfigChanged(_:timeout:)`, `ConfigWrites.patchParams(_:baseHash:)` / `mapRejection(_:)`, `connectAwaitingApproval(wakeUp:onPrompt:interval:timeout:connect:)`, `PairingLink.setupCode(_:)` / `setupCodeExplanation(_:)` / `webSocketGatewayURL(from:)`, `SetupCodeInfo`. Test helpers: `GatewayFixtures.text(_:)`, `[String: [String]].fixtures(_:)`, `ScriptedGatewayWebSocket(challenge:replies:eventsBeforeReply:closeAfterReplyTo:)` (+ `sentRequest(_:)` / `sentRequests(_:)`), `FixedSocketFactory`, `FixedIdentityProvider`, `InMemoryDeviceTokenStore`, `MockRESTFactory`, `sentParams(_:_:)`, `okReply`, `rejectionFrame(code:message:detailCode:)`, `startedRPC(_:endpoint:)`, `gatewayError(_:)`, `jsonObject(_:)`.
