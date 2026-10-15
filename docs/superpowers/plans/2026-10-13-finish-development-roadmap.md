# GhostCrab — Finish-Development Roadmap (2026-10-13)

Master list of what is left before GhostCrab is "finished" (Android on Play, iOS on TestFlight, both verified against a real OpenClaw gateway). Detailed, executable steps live in the two plan files below; this file only orders the work.

| Plan | Covers |
|---|---|
| [`2026-10-13-android-fixes-and-refactor.md`](2026-10-13-android-fixes-and-refactor.md) (42 tasks) | M1, M2, cheap parts of M3 + M6 |
| [`2026-10-13-ios-finish.md`](2026-10-13-ios-finish.md) (25 tasks) | M4 |
| [`2026-10-15-protocol-alignment-android.md`](2026-10-15-protocol-alignment-android.md) (27 tasks) | M7, Android |
| [`2026-10-15-protocol-alignment-ios.md`](2026-10-15-protocol-alignment-ios.md) (22 tasks) | M7, iOS |

Sources: audit reports of 2026-10-13 (`02-android-audit` = Android, `03-ios-audit` = iOS, `01-docs-state` = docs), `docs/IMPLEMENTATION_PLAN.md`, `docs/HANDOFF.md`, specs under `docs/superpowers/specs/`. Baseline on `main` @ 70a3ce8: 249 unit tests green, detekt clean, lint 0 errors / 63 warnings.

Legend: size **S** ≤ ½ day, **M** ≤ 2 days, **L** > 2 days. **⚠ manual** = cannot be done from the Windows dev box (needs the Mac Mini, a real device, a real gateway, Play Console or App Store Connect).

## Execution order

1. **M1** first (one PR). 2. **M2** (one or two PRs). 3. **M3-cheap + M6-docs** (one PR) → cut **v0.2.0**. 4. **M4** in parallel with 2–3 on the Mac Mini. 5. **M5** before any store submission; its outcome may reopen M3. 6. **M7** (protocol alignment, decided by M5): the Android and iOS plans are independent and can run in parallel; the mock-gateway rewrite lives in the Android plan (Task 25) and the iOS plan reuses it. 7. **M6-release** last, after M7: no store build ships the speculative protocol.

## M1 — Android P1 bug fixes

| # | Item | Source | Size | Plan task |
|---|---|---|---|---|
| 1.1 | Edited profile token never used (`hasToken` stale) | Android audit, "Other bugs" P1 | S | B1 |
| 1.2 | `getToken` outside guard → crash on known gateway after keystore reset | Android B P1 | S | B2 |
| 1.3 | Skill install crashes when disconnected (`wsFactory()` outside try) | Android B P1 | S | B3 |
| 1.4 | Gateway config incl. bearer/API keys sent to AI unredacted | Android E P1 | S | B4 |
| 1.5 | AI suggestions all pre-selected; auth changes apply in one tap | Android E P1 | S | B5a–B5d |
| 1.6 | ETag never refreshed after PATCH → 412 on 2nd change; cache survives reconnect | Android "Other bugs" P1 | M | B6, B7a, B7b |
| 1.7 | `GatewaySectionEditor` shows defaults on decode failure; default-valued edits silently dropped | Android "Other bugs" P1 | M | B8 |
| 1.8 | Dashboard health ignores cleartext opt-in, new client per poll, DTO in ViewModel | Android A P1 | S | B9 |
| 1.9 | Errors lack exception class; cleartext block reported as "unreachable" | Android C P1 | M | B10, B11 |

## M2 — Android refactor to best practices

| # | Item | Source | Size | Plan task |
|---|---|---|---|---|
| 2.1 | `requireClient` throws `IllegalStateException` → `GatewayNotConnectedException`; `@Volatile` client | Android A/B P2 | S | B12 |
| 2.2 | `runCatching` swallowing `CancellationException` (13 sites) → `suspendRunCatching` | Android B P2 | S | B13, B24 |
| 2.3 | WS client: no request timeout, pending never failed on close, Unconfined in prod, manual auth header | Android B/E P2 | M | B14, B15 |
| 2.4 | Corrupt profile JSON wipes every saved profile on next save | Android "Other bugs" P2 | S | B16 |
| 2.5 | QR scanner leaks executor + ML Kit client, double navigation, unchecked `Activity` cast | Android F P2 | S | B17 |
| 2.6 | Hard-coded `Dispatchers.IO` → injected via Koin | Android B P3 | S | B18 |
| 2.7 | Cleartext opt-in snapshotted at connect time | Android E P2 | S | B19 |
| 2.8 | Token in `rememberSaveable`, unmasked gateway token field, token in diff sheet | Android E P2 | S | B20 |
| 2.9 | URL parsing duplicated; NSD IPv6 URLs invalid | Android C P2 | S | B21a, B21b |
| 2.10 | API client content-type sniff ×3, magic timeouts | Android C P2/P3 | S | B22 |
| 2.11 | Config save drops other sections' pending edits; VM passed into child composable | Android C P2, F P2 | S | B23 |
| 2.12 | Three one-shot-event patterns; picker uses result callback | Android B P2/P3 | M | B24 |
| 2.13 | `collectAsState` ×13, in-body `koinViewModel()`, `!!` ×6 | Android B/F P2, C P3 | S | B25 |
| 2.14 | Split the 3 largest screens (692 / 632 / 460 lines) | Android top-15 #15 | M | B26, B27 |
| 2.15 | Untested: Settings/Onboarding repos, Koin graph, `safeRequest` matrix, skills repo | Android D | M | B10, B28–B30 |
| 2.16 | Dead code; `AI_PRO_ENABLED` never read | Android C P2/P3 | S | B31 |
| 2.17 | Lint: 18 UnusedResources, 10 IconDuplicates, 8 Typos | Gradle baseline | S | B32 |
| 2.18 | Deferred — need an ADR (frozen surface / domain model): drop `tokenOrNull` from `Connected`; `InstalledSkillRepository` → throw instead of `Result`; explicit-ETag `ConfigRepository`; shared AI/editor ETag cache | Android top-15 "Deferred" | M | — (needs ADR-0008 first) |
| 2.19 | Deferred hygiene: ~55 hard-coded strings → resources; `@Serializable` nav routes; `@Preview` + stateful/stateless split for remaining screens; shared `test/fakes/`; Dashboard recovery from Degraded; `security-crypto` deprecation (Tink); NSD resolve queue / `onServiceLost` ⚠ manual (device) | Android C/D/F P3, "Other bugs" P2/P3 | L | — (next plan) |

## M3 — Android unfinished features

| # | Item | Source | Size | Plan task |
|---|---|---|---|---|
| 3.1 | QR pairing decoder (`ghostcrab://pair?p=<b64url json>`) in the in-app scanner; token handed off in memory, never in a nav route | qr-pairing-repurpose spec | S | B33a, B33b |
| 3.2 | `ghostcrab://pair` deep link (manifest + `MainActivity`) — prefill only, no auto-connect | same spec | S | B34 (⚠ manual: `adb` device check) |
| 3.3 | Upstream gateway `GET /pair` route (PR to `ghcr.io/openclaw/openclaw`) | same spec | M | ⚠ manual (external repo) |
| 3.4 | Retire `docker/tunnel-qr` + rewrite `docs/TUNNEL_SETUP.md` once 3.3 lands | same spec | S | — |
| 3.5 | Phase 12 on-device QA of the Skills screen against `scripts/mock-gateway` (:18790); then decide flipping `SKILLS_INSTALL_ENABLED` for release | `IMPLEMENTATION_PLAN` ledger row 12 | S | ⚠ manual (device) |
| 3.6 | **DECISION:** ClawHub catalog browser (`ClawHubCatalogClient`). Spec is Draft with an unresolved ClawHub endpoint. Recommendation: defer until M5 confirms the real `skills.*` protocol; otherwise it is built on a guess twice. | phase-12 spec | L | — |
| 3.7 | **DECISION:** UI rework (connected shell, bottom nav, Overview/Config workspace, shared primitives) | `PROJECT_REVIEW_UI_PLAN.md` | L | — |
| 3.8 | Onboarding ADR "onboarding never executes privileged actions" (reserved as ADR-005, never written; 0005–0007 are now taken, so it gets the next free number) | `IMPLEMENTATION_PLAN` §7 | S | — |

## M4 — iOS finish

| # | Item | Source | Size | Plan | Status |
|---|---|---|---|---|---|
| 4.1 | QR pairing keeps token; register `ghostcrab://` URL scheme + `onOpenURL` | iOS A, top-10 #3 | M | C | Done, PR #127 |
| 4.2 | Model activation confirm dialog + in-flight Task guards | iOS A/C, #4 | S | C | Done, PR #127 |
| 4.3 | Bundle Inter + JetBrains Mono (`UIAppFonts`) — .ttf download ⚠ manual | iOS A/F, #2 | S | C | Done, PR #127 |
| 4.4 | `PrivacyInfo.xcprivacy` (UserDefaults CA92.1 …) — upload blocker | iOS F, #1 | S | C | Done, PR #127 |
| 4.5 | ATS: `NSAllowsLocalNetworking` (never `NSAllowsArbitraryLoads`); document FQDN limitation | iOS D P1 | S | C | Done, PR #127 |
| 4.6 | `RootView` VM-per-render, second `AppContainer` default, dead `PendingScreen` | iOS B | M | C | Done, PR #127 |
| 4.7 | `try?` swallowing (ProfileStore data loss, orphaned tokens, decode → "no models") | iOS C | M | C | Done, PR #127 |
| 4.8 | 15 s connect timeout (dead code today); per-client cleartext guard | iOS C P2 | M | C | Done, PR #127 |
| 4.9 | Tests via the unused `MockURLProtocol`; VM tests; empty UITests target | iOS E | L | C | Done, PR #127 |
| 4.10 | `ExportOptions` `destination: upload` vs altool double upload; `shred` on macOS; runner label | iOS F | S | C | Done, PR #127 |
| 4.11 | PR CI lane `ios-pr.yml` on `[self-hosted, macOS, ARM64]` (the runner has no `mac-mini-ios` label); also compiles Mac Catalyst | iOS E/F P1 | M | C | Done, PR #127 |
| 4.12 | ⚠ manual: App Store Connect record, categories, age rating, ASC API key + secrets `ASC_KEY_ID`/`ASC_ISSUER_ID`/`ASC_KEY_P8`, first TestFlight upload, Mac Catalyst smoke | `IOS_RELEASE_CHECKLIST.md` | M | — | Open |

## M5 — Real-gateway verification (biggest documented risk)

Phases 6/7/8/12 were built against a speculative REST + WS API; upstream exposes no `/api/*` routes and speaks a WS envelope with `connect.challenge` / `device.pair.*` / `skills.status` (`IMPLEMENTATION_PLAN` §5 OQ1/2/5, §6 deviation 2026-04-20). Everything here is **⚠ manual** (real gateway + Android device + iPhone). Record results in a new `docs/QA_REAL_GATEWAY_2026-10.md`.

- [ ] Run `ghcr.io/openclaw/openclaw:latest` on the LAN; record image digest + version.
- [ ] Android + iOS: mDNS scan finds it; manual entry by IP works; QR plain-URL works.
- [ ] `/health` JSON, `/status` HTML: connect succeeds, Dashboard renders (empty capabilities OK).
- [ ] Token gateway: probe says Token; wrong token shows URL + HTTP 401 + exception class (no "Oops").
- [ ] Config editor shows the NoConfigApi state (HTML at `/config`), never "disconnected".
- [ ] Models → NotSupported on 404; AI → SkillUnavailable; nothing crashes.
- [ ] Capture the real `/ws` handshake (`connect.challenge`, `device.pair.*`) and the `skills.*` method names; compare with `GatewayWsClient`/`InstalledSkillRepositoryImpl`.
- [ ] `http://<public IP>` blocked with the CleartextBlocked text; toggling the setting takes effect without reconnecting.
- [ ] Kill the gateway: Dashboard goes Degraded in < 60 s; note that it does not recover (M2 2.19).
- [ ] `adb logcat | grep -i bearer` is empty for a full session; Xcode console likewise.
- [ ] Phase 6/7 acceptance from `PROJECT_REVIEW_UI_PLAN.md`: port round-trip, port 99999 error, raw JSON round-trip, concurrent-edit reload, model swap visible on Dashboard.
- [x] **Outcome:** ADR-0007 "Gateway protocol alignment" (ADR numbers: 0005 = additive repository methods, 0006 = iOS ATS, 0007 = this) — decided: adapt the clients to the real WS protocol, no upstream REST PR. README/CLAUDE.md feature claims are corrected with the M7 work (7.4).

**M5 result (2026-10-14, PR #129 merged, `docs/QA_REAL_GATEWAY_2026-10.md`):** server side done against `ghcr.io/openclaw/openclaw` 2026.10.3 (WS protocol 4); the device items stay open and move into the M7 manual tasks. Neither client works against the real gateway: `/status` and `/config` are the Control UI's HTML and every `/api/*` route is 404, so the auth probe always says none and a wrong token connects; the control plane is a WebSocket with `connect.challenge` → Ed25519-signed `connect` → `hello-ok`, mandatory device pairing for any scope, `req`/`res` frames with string ids and codes, and `skills.status` / `config.get` / `config.patch` / `models.list` instead of the methods the clients call. **Decision:** [ADR-0007](../../adr/0007-gateway-protocol-alignment.md), adapt both clients to the real protocol (supersedes ADR-002, amends ADR-004); no upstream REST PR. Work is M7. A verification run on 2026-10-15 against the same image pinned what M5 left open (ADR-0007 "Verification"): `client.id`/`client.mode` are server allow-lists and `platform` is pinned at approval, the pairing `requestId` is stable and only reconnecting reveals an approval, setup-code tokens serve node clients only, and the `config.patch` / `skills.update` / `skills.install` shapes are now fixtures.

## M7 — Protocol alignment (ADR-0007)

Both clients move to the real gateway protocol: frame codec and RPC client, Ed25519 device identity, `connect` handshake with device tokens, pairing-approval UX, `config.get`/`config.patch` with `baseHash`, `models.list` + `agents.defaults.model.primary`, `skills.status`/`install`/`update` (Disable replaces uninstall), AI recommendations honestly unsupported, `openclaw qr` setup codes recognised and explained (their tokens serve node clients only), Bonjour copy, deletion of the speculative REST/JSON-RPC code. Tests replay frames captured from 2026.10.3 (`fixtures/gateway-protocol/2026.10.3/`, shared by both platforms and the mock).

| # | Item | Plan | Size | Status |
|---|---|---|---|---|
| 7.1 | Android protocol alignment, incl. `scripts/mock-gateway` rewritten to protocol 4 with conformance tests in CI | [`2026-10-15-protocol-alignment-android.md`](2026-10-15-protocol-alignment-android.md) | L | Open |
| 7.2 | iOS / Mac Catalyst protocol alignment (first WebSocket client on iOS) | [`2026-10-15-protocol-alignment-ios.md`](2026-10-15-protocol-alignment-ios.md) | L | Open |
| 7.3 | ⚠ manual: real-gateway and device pass for both apps; closes the open M5 device items (the protocol unknowns were pinned on 2026-10-15; `probe-real.mjs` re-checks them only for a new gateway release) | Android Task 26, iOS Task 21 | M | Open |
| 7.4 | README / CLAUDE.md / CHANGELOG feature claims match the aligned clients | Android Task 27, iOS Task 22 | S | Open |

## M6 — Docs & release

| # | Item | Source | Size | Plan task |
|---|---|---|---|---|
| 6.1 | Stale docs: CLAUDE.md (iOS out-of-scope, `Routes`, missing packages), README (Phase 7 "in progress", DomainModule, status table, iOS requirement), site/README (Pages vs workflow), HANDOFF date, ledger rows 13/14 + iOS/site | docs-state §4/§6 | S | B35 |
| 6.2 | versionName 0.1.0 → 0.2.0 (versionCode 2); CHANGELOG `[Unreleased]` → `[0.2.0] — 2026-10-13`; iOS `MARKETING_VERSION` to match | docs-state §1 | S | B36, C |
| 6.3 | Mark the five 2026-04 superpowers plans + specs "Superseded/Shipped"; `audit-2026-04-16.md` resolution column | docs-state §6 | S | — |
| 6.4 | ⚠ manual: keystore backup; Play App Signing enrollment; Play Console listing, ≥2 screenshots, Data Safety form, content rating | RELEASE_SIGNING, PLAY_STORE_LISTING, DATA_SAFETY | M | — |
| 6.5 | **DECISION:** Android privacy URL (`steveackley.org/android/privacy` vs `getghostcrab.com/privacy`); fix "openclsw" typo and add QR/skills copy in `PLAY_STORE_LISTING.md` | docs-state §2/§6 | S | — |
| 6.6 | **DECISION:** release APK 26 MB vs < 15 MB target (AAB splits likely moot it) | IMPLEMENTATION_PLAN acceptance | S | — |
| 6.7 | ⚠ manual: instrumented tests on a device; R8 release smoke install; manual QA list (HTTP banner, no-auth banner, empty scan, kill-gateway degrade, fresh-install walkthrough) | HANDOFF "What's Next", PLAN acceptance | M | — |
| 6.8 | ⚠ manual: disconnect the Cloudflare dashboard Git integration now that `site-deploy.yml` deploys | `site-deploy.yml` header | S | — |
| 6.9 | ⚠ manual: first TestFlight build after M4 | IOS_RELEASE_CHECKLIST | S | — |
