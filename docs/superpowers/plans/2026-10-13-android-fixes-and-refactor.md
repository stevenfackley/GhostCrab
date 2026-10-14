# GhostCrab Android Fixes & Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix every Android P1 from the 2026-10-13 audit, pay down the P2 correctness/hygiene debt, add the cheap QR-pairing path, refresh stale docs and cut v0.2.0.

**Architecture:** Strict `UI → ViewModel → Domain → Data` layering is kept. Frozen `domain/repository/` interfaces keep their signatures; everything new in the domain is additive (new `GatewayException` leaves, a new `GatewayHealthChecker` interface, pure helpers in `domain/util/`). Every task is one independently green commit.

**Tech Stack:** Kotlin 2.4, Jetpack Compose, Ktor 3.6 (OkHttp engine), Koin 4.2, DataStore 1.2, kotlinx.serialization 1.11, JUnit 5 + kotlinx-coroutines-test + Turbine + mockk, detekt 1.23, Android lint.

Roadmap: [`2026-10-13-finish-development-roadmap.md`](2026-10-13-finish-development-roadmap.md) (milestones M1, M2, cheap parts of M3 and M6).

---

## Conventions (read once, apply to every task)

- **Branch:** cut `fix/android-audit-2026-10` from `main`; open one PR per milestone block (Tasks 1–11, 12–32, 33a–36). Never commit to `main`.
- **Commits:** Conventional Commits, subject + optional body only. **No `Co-Authored-By` trailer and no AI/assistant attribution line of any kind.**
- **Shell:** commands are written for Git Bash on Windows (`./gradlew`). In PowerShell use `.\gradlew.bat` with the same arguments.
- **Single test class:** `./gradlew testDebugUnitTest --tests "<FQCN>"`.
- **Full gate (run before every commit):** `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`. Baseline before Task 1: 249 tests, detekt clean, lint 0 errors / 63 warnings. The test count only goes up.
- **Paths:** `app/src/main/kotlin/com/openclaw/ghostcrab/` is the main source root, `app/src/test/kotlin/com/openclaw/ghostcrab/` the unit-test root. All paths below are repo-relative.
- **detekt facts that bite** (`config/detekt.yml` + defaults, applied to main AND test sources):
  - `TooManyFunctions`: a class with **11** functions (private included) fails. Keep ViewModels ≤ 10; move pure helpers to top-level functions.
  - `ThrowsCount` max 2, `ReturnCount` max 2 per function (use `@Suppress("ReturnCount")` only where the codebase already does for validators).
  - `TooGenericExceptionThrown` also runs on tests: never write `throw RuntimeException(...)` / `throw Exception(...)` in a test; use a domain exception (`GatewayTimeoutException("http://x")`) or `IllegalStateException`.
  - `MaxLineLength` 120 — split long string literals with `+`.
  - The baseline (`config/detekt-baseline.xml`) is keyed by **file name + full function signature**. Moving a function to another file or changing its signature re-reports already-baselined `LongMethod` debt. When a task says "refresh the baseline", run `./gradlew detektBaseline`, then `git diff config/detekt-baseline.xml` and confirm the diff only **renames/removes** IDs of functions you moved or re-signed (no new rule types, no new functions). If anything else appears, fix the code instead.
- **Error text:** after Task 11 every user-visible failure string is produced by `Throwable.toUserMessage()` (`ui/ErrorMessages.kt`). After Task 13 no bare `runCatching` wraps a suspend call; use `suspendRunCatching` (`domain/util/SuspendRunCatching.kt`).

## Task dependency order

Tasks are ordered so each one builds on what precedes it; execute top to bottom.

1. Tasks 1–3 (crash/token P1s) — independent of each other.
2. Task 4 → Tasks 5a → 5b → 5c → 5d (5a appends to `ConfigSensitivity.kt` and creates `SuggestedChanges.kt`; land 5c and 5d in the same PR).
3. Task 6 → Task 7a (`withLeaf`) → Task 7b → Task 8 (uses `withLeaf`).
4. Task 9 (independent).
5. Task 10 → Task 11 (Task 11 formats the exceptions Task 10 introduces).
6. Task 12 (needs 11 for message assertions) → Task 13 (needs 11) → Task 14 → Task 15 (needs 10, 12, 14).
7. Task 16 → Task 18 (both touch `ConnectionProfileStore`); Task 17 independent; Task 18 → Task 19.
8. Task 20 (needs 4, 8). Tasks 21a → 21b (21b needs 13 for the `ManualEntryViewModel` shape). Task 22 (needs 10, 15). Task 23. Task 24 (needs 11, 13). Task 25 (needs 24). Tasks 26–27 (need 5d, 20, 25).
9. Task 28 → Task 29 (Koin verify needs the final DataModule shape: 9, 15, 18, 28). Task 30 (needs 10, 14). Task 31. Task 32.
10. Tasks 33a → 33b (needs 17, 21b, 24) → Task 34. Task 35 → Task 36.

## File structure

Created:

| File | Responsibility |
|---|---|
| `domain/util/ConfigSensitivity.kt` | `isSecretKey`, `JsonElement.redactSecrets()`, `isSecurityAffectingConfigPath`, `REDACTED_PLACEHOLDER` |
| `domain/util/JsonPatch.kt` | `JsonElement.withLeaf(path, value)` — single-leaf JSON edits that preserve unknown keys |
| `domain/util/SuspendRunCatching.kt` | `suspendRunCatching {}` — `runCatching` that rethrows `CancellationException` |
| `domain/util/GatewayUrl.kt` | `GatewayUrl` value type: parse/format gateway base URLs (IPv6-safe), `DEFAULT_GATEWAY_PORT` |
| `domain/util/PairCode.kt` | `decodePairCode` for `ghostcrab://pair?p=…` and plain URLs; `PairBundle`, `PairCodeResult` |
| `domain/repository/GatewayHealthChecker.kt` | additive interface: `/health` on the live session |
| `ui/ErrorMessages.kt` | `Throwable.toUserMessage()` — URL + status + exception class + root cause |
| `ui/airecommend/SuggestedChanges.kt` | `isSecurityAffecting`, `parseSuggestedValue`, `sectionPatches` |
| `ui/airecommend/SkillUnavailableContent.kt`, `ui/airecommend/RecommendationReadyContent.kt` | split out of `AIRecommendationScreen.kt` |
| `ui/settings/SettingsDialogs.kt`, `ui/settings/SettingsSections.kt` | split out of `SettingsScreen.kt` |
| `ui/model/ModelManagerContent.kt` | split out of `ModelManagerScreen.kt` |
| `ui/connection/PairingHandoff.kt` | in-memory one-shot handoff of a scanned/deep-linked pair bundle |
| `app/lint.xml` | lint suppressions for false positives + `UnusedResources` as error |
| tests: `domain/ConfigSensitivityTest.kt`, `domain/JsonPatchTest.kt`, `domain/SuspendRunCatchingTest.kt`, `domain/GatewayUrlTest.kt`, `domain/PairCodeTest.kt`, `ui/SuggestedChangesTest.kt`, `ui/ErrorMessagesTest.kt`, `data/OpenClawApiClientErrorMappingTest.kt`, `data/SettingsRepositoryImplTest.kt`, `data/OnboardingRepositoryImplTest.kt`, `di/KoinModulesTest.kt` | |

Deleted: `ui/components/ConnectionStatusBar.kt` (never called).

---

### Task 1: Settings profile edit keeps `hasToken` in sync with the stored token

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/settings/SettingsViewModel.kt` (`saveProfileEdit`, ~line 115)
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/SettingsViewModelTest.kt`

Bug: adding a token to a profile saved without one stores the token but keeps `hasToken = false`; `ConnectionPickerViewModel` only reads tokens when `hasToken`, so the new token is never used. Clearing a token leaves `hasToken = true`.

- [ ] **Step 1: Make the fake repository return stored tokens**

In `SettingsViewModelTest.kt`, inside `private class FakeSettingsProfileRepository`, replace
```kotlin
    override suspend fun getToken(profileId: String): String? = null
```
with
```kotlin
    val tokens = mutableMapOf<String, String>()

    override suspend fun getToken(profileId: String): String? = tokens[profileId]
```

- [ ] **Step 2: Write the failing tests** (append inside `class SettingsViewModelTest`, after `saveProfileEdit persists updated display name`)

```kotlin
    @Test
    fun `saveProfileEdit adding a token flips hasToken to true`() = runTest {
        val p = profile("9", "NoTok") // hasToken = false
        val profileRepo = FakeSettingsProfileRepository(listOf(p))
        val vm = makeVm(profileRepo = profileRepo)

        vm.startEditProfile(p)
        vm.saveProfileEdit("NoTok", "fresh-token")

        val (saved, token) = profileRepo.savedProfiles.last()
        assertTrue(saved.hasToken)
        assertEquals("fresh-token", token)
    }

    @Test
    fun `saveProfileEdit clearing the token flips hasToken to false`() = runTest {
        val p = profile("10", "Tok").copy(hasToken = true)
        val profileRepo = FakeSettingsProfileRepository(listOf(p)).apply { tokens["10"] = "old" }
        val vm = makeVm(profileRepo = profileRepo)

        vm.startEditProfile(p)
        vm.saveProfileEdit("Tok", "")

        val (saved, token) = profileRepo.savedProfiles.last()
        assertFalse(saved.hasToken)
        assertNull(token)
    }

    @Test
    fun `saveProfileEdit with unchanged token keeps the stored token`() = runTest {
        val p = profile("11", "Keep").copy(hasToken = true)
        val profileRepo = FakeSettingsProfileRepository(listOf(p)).apply { tokens["11"] = "kept" }
        val vm = makeVm(profileRepo = profileRepo)

        vm.startEditProfile(p)
        vm.saveProfileEdit("Keep", null)

        val (saved, token) = profileRepo.savedProfiles.last()
        assertTrue(saved.hasToken)
        assertEquals("kept", token)
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.SettingsViewModelTest"`
Expected: FAIL — `saveProfileEdit adding a token flips hasToken to true` and `saveProfileEdit clearing the token flips hasToken to false` fail on the `hasToken` assertion; the other tests pass.

- [ ] **Step 4: Fix `saveProfileEdit`**

Replace the body of the `runCatching { … }` block in `saveProfileEdit` with:
```kotlin
            runCatching {
                val tokenToSave = when {
                    newToken == null -> profileRepository.getToken(current.id) // unchanged
                    newToken.isBlank() -> null // clear
                    else -> newToken
                }
                val updated = current.copy(
                    displayName = displayName.trim(),
                    hasToken = tokenToSave != null,
                )
                profileRepository.saveProfile(updated, tokenToSave)
            }.onFailure { e -> withReady { s -> s.copy(errorMessage = e.message) } }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.SettingsViewModelTest"`
Expected: PASS (15 tests).

- [ ] **Step 6: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/settings/SettingsViewModel.kt app/src/test/kotlin/com/openclaw/ghostcrab/ui/SettingsViewModelTest.kt
git commit -m "fix(settings): keep hasToken in sync with the saved token on profile edit"
```

---

### Task 2: Scan — reconnecting a known gateway never crashes on an unreadable token

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ScanViewModel.kt` (`onGatewaySelected`, ~line 157)
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/ScanViewModelTest.kt`

Bug: `profileRepository.getToken(match.id)` runs outside any guard; after a Keystore reset it throws `ProfileNeedsReauthException` inside `viewModelScope` → app crash. It is also called when `hasToken == false`.

- [ ] **Step 1: Teach the fake repository to fail and count token reads**

In `ScanViewModelTest.kt` replace the whole `private class FakeProfileRepository` with:
```kotlin
private class FakeProfileRepository(
    private val profiles: List<ConnectionProfile> = emptyList(),
    private val tokenError: GatewayException? = null,
) : ConnectionProfileRepository {
    var getTokenCalls = 0
    override fun getProfiles(): Flow<List<ConnectionProfile>> = flowOf(profiles)
    override suspend fun saveProfile(profile: ConnectionProfile, token: String?) {}
    override suspend fun getToken(profileId: String): String? {
        getTokenCalls++
        tokenError?.let { throw it }
        return null
    }
    override suspend fun deleteProfile(profileId: String) {}
}
```
Add imports:
```kotlin
import com.openclaw.ghostcrab.domain.exception.GatewayException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.ProfileNeedsReauthException
```

- [ ] **Step 2: Write the failing tests and retarget the existing failure test**

Replace the existing test `selecting known gateway emits Error on connection failure` with:
```kotlin
    @Test
    fun `selecting known gateway emits Error on connection failure`() = runTest {
        val gw = gateway()
        val vm = makeVm(
            profiles = listOf(profile(gw.url)),
            connectResult = Result.failure(GatewayTimeoutException(gw.url)),
        )

        vm.onGatewaySelected(gw)
        advanceUntilIdle()

        val state = vm.state.value as ScanState.Error
        assertTrue(state.reason.contains("timed out"))
    }

    @Test
    fun `known gateway with unreadable token emits Error instead of crashing`() = runTest {
        val gw = gateway()
        val vm = ScanViewModel(
            discoveryService = FakeDiscoveryService(),
            profileRepository = FakeProfileRepository(
                profiles = listOf(profile(gw.url).copy(hasToken = true)),
                tokenError = ProfileNeedsReauthException("id-1"),
            ),
            connectionManager = FakeConnectionManager(),
        )

        vm.onGatewaySelected(gw)
        advanceUntilIdle()

        val state = vm.state.value as ScanState.Error
        assertTrue(state.reason.contains("id-1"))
    }

    @Test
    fun `known gateway without token never touches the keystore`() = runTest {
        val gw = gateway()
        val repo = FakeProfileRepository(
            profiles = listOf(profile(gw.url)), // hasToken = false
            tokenError = ProfileNeedsReauthException("id-1"),
        )
        val vm = ScanViewModel(FakeDiscoveryService(), repo, FakeConnectionManager())

        vm.events.test {
            vm.onGatewaySelected(gw)
            assertEquals(ScanEvent.NavigateToDashboard, awaitItem())
        }
        assertEquals(0, repo.getTokenCalls)
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.ScanViewModelTest"`
Expected: FAIL — `known gateway with unreadable token…` (state stays `Idle`, `ClassCastException`) and `known gateway without token never touches the keystore` (`getTokenCalls` is 1).

- [ ] **Step 4: Guard the whole reconnect**

In `ScanViewModel.kt` add `import com.openclaw.ghostcrab.domain.exception.GatewayException` and replace the `if (match != null) { … } else { … }` block in `onGatewaySelected` with:
```kotlin
            if (match != null) {
                try {
                    val token = if (match.hasToken) profileRepository.getToken(match.id) else null
                    connectionManager.connect(match.url, token)
                    _events.emit(ScanEvent.NavigateToDashboard)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GatewayException) {
                    // connect() and getToken() both report failures as GatewayException
                    // (ProfileNeedsReauthException after a Keystore reset).
                    _state.value = ScanState.Error(e.message ?: "Connection failed")
                }
            } else {
                _events.emit(ScanEvent.NavigateToManualEntry(gateway.url))
            }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.ScanViewModelTest"`
Expected: PASS (12 tests).

- [ ] **Step 6: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ScanViewModel.kt app/src/test/kotlin/com/openclaw/ghostcrab/ui/ScanViewModelTest.kt
git commit -m "fix(scan): guard token read when reconnecting a known gateway"
```

---

### Task 3: Skill install reports `Failed(Network)` instead of crashing when no session can be opened

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImpl.kt` (`install`, ~line 75)
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImplTest.kt`

Bug: `install` calls `wsFactory()` before its `try`; the DI factory throws when disconnected, the exception escapes the flow, and `AIRecommendationViewModel.installSkill` (no try/catch around `collect`) crashes the app.

- [ ] **Step 1: Write the failing test** (append inside `class InstalledSkillRepositoryImplTest`)

```kotlin
    @Test
    fun `install emits Failed Network when no session can be opened`() = runTest {
        val repo = InstalledSkillRepositoryImpl { error("Cannot install skills while disconnected") }

        val progress = repo.install("foo/bar").toList()

        assertTrue(progress.first() is SkillInstallProgress.Connecting)
        val failed = progress.last() as SkillInstallProgress.Failed
        assertTrue(failed.error is SkillInstallError.Network)
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.impl.InstalledSkillRepositoryImplTest"`
Expected: FAIL with `java.lang.IllegalStateException: Cannot install skills while disconnected`.

- [ ] **Step 3: Open the session inside the guarded region**

In `InstalledSkillRepositoryImpl.kt` add `import kotlinx.coroutines.CancellationException` and replace
```kotlin
        send(SkillInstallProgress.Connecting(slug))
        val ws = wsFactory()
```
with
```kotlin
        send(SkillInstallProgress.Connecting(slug))
        val ws = try {
            wsFactory()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No live session (disconnected) or the WS upgrade failed: report, never crash the collector.
            send(SkillInstallProgress.Failed(SkillInstallError.Network(e.message ?: e::class.java.simpleName)))
            return@channelFlow
        }
```
(The class already carries `@Suppress("TooGenericExceptionCaught")`.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.impl.InstalledSkillRepositoryImplTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImpl.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImplTest.kt
git commit -m "fix(skills): report Failed(Network) when install cannot open a session"
```

---

### Task 4: Never send gateway secrets to the AI skill

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/util/ConfigSensitivity.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/AIRecommendationServiceImpl.kt` (`getRecommendation`)
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/RecommendationContext.kt` (KDoc)
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/domain/ConfigSensitivityTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/data/AIRecommendationServiceImplTest.kt`

Bug: the whole config (`gateway.auth.token`, provider API keys) is posted as AI context and, per ADR-0004, likely forwarded to an LLM provider.

- [ ] **Step 1: Write the failing domain test** — create `app/src/test/kotlin/com/openclaw/ghostcrab/domain/ConfigSensitivityTest.kt`:

```kotlin
package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.util.REDACTED_PLACEHOLDER
import com.openclaw.ghostcrab.domain.util.isSecretKey
import com.openclaw.ghostcrab.domain.util.redactSecrets
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigSensitivityTest {

    @Test
    fun `redacts nested tokens and api keys but keeps everything else`() {
        val config = Json.parseToJsonElement(
            """{"gateway":{"http":{"port":18789},"auth":{"mode":"bearer","token":"s3cr3t"}},""" +
                """"providers":[{"name":"openai","api_key":"sk-live"},{"name":"local","apiKey":"x"}]}""",
        )

        val redacted = config.redactSecrets().toString()

        assertFalse(redacted.contains("s3cr3t"))
        assertFalse(redacted.contains("sk-live"))
        assertTrue(redacted.contains("18789"))
        assertTrue(redacted.contains("\"mode\":\"bearer\""))
        assertEquals(3, redacted.split(REDACTED_PLACEHOLDER).size - 1)
    }

    @Test
    fun `isSecretKey matches common secret spellings case-insensitively`() {
        listOf("token", "Bearer_Token", "client_secret", "PASSWORD", "api-key", "apiKey", "private_key")
            .forEach { assertTrue(isSecretKey(it), it) }
        listOf("port", "mode", "host", "serviceName").forEach { assertFalse(isSecretKey(it), it) }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.ConfigSensitivityTest"`
Expected: FAIL — compilation error `Unresolved reference: redactSecrets`.

- [ ] **Step 3: Create `ConfigSensitivity.kt`**

```kotlin
package com.openclaw.ghostcrab.domain.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Placeholder written in place of any secret value. */
const val REDACTED_PLACEHOLDER = "[REDACTED]"

private val SECRET_KEY = Regex("(?i)(token|secret|password|passwd|api[_-]?key|private[_-]?key|credential)")

/**
 * `true` when [key] names a value that must never leave the device or be shown in clear.
 *
 * Case-insensitive substring match on `token`, `secret`, `password`, `passwd`,
 * `api_key` / `api-key` / `apikey`, `private_key` and `credential`.
 *
 * @param key A single JSON object key (not a dotted path).
 * @return Whether the value stored under [key] is a secret.
 */
fun isSecretKey(key: String): Boolean = SECRET_KEY.containsMatchIn(key)

/**
 * Deep copy of this element in which every value whose key satisfies [isSecretKey] is replaced by
 * [REDACTED_PLACEHOLDER]. Objects and arrays are traversed; primitives are returned unchanged.
 * A secret key holding an object or array is replaced wholesale.
 *
 * @return The redacted element; the receiver is not modified.
 */
fun JsonElement.redactSecrets(): JsonElement = when (this) {
    is JsonObject -> JsonObject(
        mapValues { (key, value) ->
            if (isSecretKey(key)) JsonPrimitive(REDACTED_PLACEHOLDER) else value.redactSecrets()
        },
    )
    is JsonArray -> JsonArray(map { it.redactSecrets() })
    else -> this
}
```

- [ ] **Step 4: Run the domain test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.ConfigSensitivityTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Write the failing service test** (append inside `class AIRecommendationServiceImplTest`)

```kotlin
    @Test fun `getRecommendation never sends secrets in the config context`() = runTest {
        val sent = slot<AIRecommendationRequestDto>()
        coEvery { client.getAIRecommendation(capture(sent)) } returns
            AIRecommendationResponseDto(recommendation = "ok", suggestedChanges = emptyList())
        val secretContext = RecommendationContext(
            activeConfig = OpenClawConfig(
                mapOf("gateway" to Json.parseToJsonElement("""{"auth":{"mode":"bearer","token":"s3cr3t"}}""")),
            ),
            hardwareInfo = null,
            activeModelId = null,
        )

        service.getRecommendation("q", secretContext)

        val sentConfig = sent.captured.context.activeConfig.toString()
        assertFalse(sentConfig.contains("s3cr3t"))
        assertTrue(sentConfig.contains("bearer"))
    }
```
Add imports:
```kotlin
import com.openclaw.ghostcrab.data.api.dto.AIRecommendationRequestDto
import io.mockk.slot
import kotlinx.serialization.json.Json
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.AIRecommendationServiceImplTest"`
Expected: FAIL — `getRecommendation never sends secrets…` (`sentConfig` contains `s3cr3t`).

- [ ] **Step 7: Redact before building the DTO**

In `AIRecommendationServiceImpl.kt` add
```kotlin
import com.openclaw.ghostcrab.domain.util.redactSecrets
import kotlinx.serialization.json.jsonObject
```
and replace `activeConfig = JsonObject(context.activeConfig.sections),` with
```kotlin
                // ADR-0004: the AI skill may forward context to an external LLM — never ship secrets.
                activeConfig = JsonObject(context.activeConfig.sections).redactSecrets().jsonObject,
```
In `RecommendationContext.kt` change the KDoc line `* Auto-collected from the current session; never includes secrets.` to
`* Auto-collected from the current session. Secrets are stripped by the data layer before sending.`

- [ ] **Step 8: Run the service test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.AIRecommendationServiceImplTest"`
Expected: PASS (11 tests).

- [ ] **Step 9: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/domain/util/ConfigSensitivity.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/AIRecommendationServiceImpl.kt app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/RecommendationContext.kt app/src/test/kotlin/com/openclaw/ghostcrab/domain/ConfigSensitivityTest.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/AIRecommendationServiceImplTest.kt
git commit -m "fix(ai): redact tokens and API keys from the config sent as AI context"
```

---

### Task 5a: Classify security-affecting AI suggestion paths

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/util/ConfigSensitivity.kt` (append)
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/SuggestedChanges.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/SuggestedChangesTest.kt`

Groundwork for Tasks 5b–5d (every suggestion is pre-selected today; one tap can apply `gateway.auth.mode = none`). `AIRecommendationViewModel` already has 10 functions (detekt limit), so the pure helpers live outside the class.

- [ ] **Step 1: Write the failing helper test** — create `app/src/test/kotlin/com/openclaw/ghostcrab/ui/SuggestedChangesTest.kt`:

```kotlin
package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.model.SuggestedChange
import com.openclaw.ghostcrab.ui.airecommend.isSecurityAffecting
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestedChangesTest {

    private fun change(section: String, key: String, value: String = "1") =
        SuggestedChange(section = section, key = key, currentValue = null, suggestedValue = value)

    @Test
    fun `auth, credential and exposure paths are security affecting`() {
        assertTrue(change("gateway", "auth.mode").isSecurityAffecting)
        assertTrue(change("providers", "openai.api_key").isSecurityAffecting)
        assertTrue(change("gateway", "http.host").isSecurityAffecting)
        assertTrue(change("gateway", "tls.cert").isSecurityAffecting)
    }

    @Test
    fun `ordinary tuning paths are not security affecting`() {
        assertFalse(change("gateway", "http.port").isSecurityAffecting)
        assertFalse(change("models", "timeout_ms").isSecurityAffecting)
        assertFalse(change("gateway", "max_connections").isSecurityAffecting)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.SuggestedChangesTest"`
Expected: FAIL — `Unresolved reference: isSecurityAffecting`.

- [ ] **Step 3: Add the domain predicate** — append to `ConfigSensitivity.kt`:

```kotlin
private val SECURITY_PATH =
    Regex("(?i)(auth|token|secret|password|api[_-]?key|tls|ssl|cert|cors|cleartext|host|bind|allow)")

/**
 * `true` when changing the config value at [path] can weaken the gateway's security posture
 * (auth mode, credentials, TLS, bind address, CORS, cleartext or allow-lists). Over-matching is
 * intentional: a false positive costs one confirmation tap, a false negative can disable auth.
 *
 * @param path Dotted config path, e.g. `"gateway.auth.mode"`.
 * @return Whether applying a change at [path] needs explicit user confirmation.
 */
fun isSecurityAffectingConfigPath(path: String): Boolean = SECURITY_PATH.containsMatchIn(path)
```

- [ ] **Step 4: Create `ui/airecommend/SuggestedChanges.kt`**

```kotlin
package com.openclaw.ghostcrab.ui.airecommend

import com.openclaw.ghostcrab.domain.model.SuggestedChange
import com.openclaw.ghostcrab.domain.util.isSecurityAffectingConfigPath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** `true` when applying this change can weaken gateway security; the UI must confirm it explicitly. */
internal val SuggestedChange.isSecurityAffecting: Boolean
    get() = isSecurityAffectingConfigPath("$section.$key")

/**
 * Parses an AI-suggested value as JSON, falling back to a JSON string so a malformed suggestion
 * never crashes the apply flow. A bare word such as `gpt-4o` (which the lenient tree parser would
 * otherwise turn into an unquoted literal and emit as invalid JSON) also becomes a string.
 *
 * @param value JSON-encoded value from the AI response.
 * @return Parsed element, or a string primitive when [value] is not a valid JSON value.
 */
internal fun parseSuggestedValue(value: String): JsonElement {
    val parsed = runCatching { Json.parseToJsonElement(value) }.getOrNull()
    val isBareWord = parsed is JsonPrimitive && !parsed.isString && parsed !is JsonNull &&
        parsed.booleanOrNull == null && parsed.doubleOrNull == null
    return if (parsed == null || isBareWord) JsonPrimitive(value) else parsed
}
```

- [ ] **Step 5: Run the helper test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.SuggestedChangesTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/domain/util/ConfigSensitivity.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/SuggestedChanges.kt app/src/test/kotlin/com/openclaw/ghostcrab/ui/SuggestedChangesTest.kt
git commit -m "feat(ai): classify security-affecting suggestion paths"
```

---

### Task 5b: AI suggestions start unselected

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/AIRecommendationUiState.kt` (`Ready`)
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/AIRecommendationViewModel.kt` (`submitQuery`)
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/AIRecommendationViewModelTest.kt`

- [ ] **Step 1: Rewrite the tests that assumed pre-selection** — in `AIRecommendationViewModelTest.kt` replace these four tests:

```kotlin
    @Test
    fun `submitQuery transitions to Loading then Ready with nothing selected`() = runTest {
        val rec = recommendation(change())
        val service = FakeAIService(available = true, recommendation = rec)
        val (vm, _) = makeVm(service = service)

        vm.submitQuery("best config for coding")

        val state = assertInstanceOf(AIRecommendationUiState.Ready::class.java, vm.state.value)
        assertEquals(rec.recommendation, state.recommendation.recommendation)
        assertEquals(1, state.recommendation.suggestedChanges.size)
        assertTrue(state.selectedChanges.isEmpty())
    }

    @Test
    fun `toggleChange selects an unselected change then deselects it`() = runTest {
        val c = change()
        val (vm, _) = makeVm(service = FakeAIService(available = true, recommendation = recommendation(c)))
        vm.submitQuery("query")

        vm.toggleChange(c)
        assertTrue(c in (vm.state.value as AIRecommendationUiState.Ready).selectedChanges)

        vm.toggleChange(c)
        assertFalse(c in (vm.state.value as AIRecommendationUiState.Ready).selectedChanges)
    }

    @Test
    fun `applySelectedChanges calls updateConfig for each selected change`() = runTest {
        val c1 = change("models", "timeout_ms")
        val c2 = change("gateway", "max_connections")
        val service = FakeAIService(available = true, recommendation = recommendation(c1, c2))
        val configRepo = FakeAIConfigRepository()
        val (vm, _) = makeVm(service = service, config = configRepo)

        vm.submitQuery("query")
        vm.toggleChange(c1)
        vm.toggleChange(c2)
        vm.applySelectedChanges()

        assertTrue((vm.state.value as AIRecommendationUiState.Ready).applySuccess)
        assertEquals(2, configRepo.updateCallCount)
    }

    @Test
    fun `applySelectedChanges with only one change selected applies only that one`() = runTest {
        val c1 = change("models", "timeout_ms")
        val c2 = change("gateway", "max_connections")
        val service = FakeAIService(available = true, recommendation = recommendation(c1, c2))
        val configRepo = FakeAIConfigRepository()
        val (vm, _) = makeVm(service = service, config = configRepo)

        vm.submitQuery("query")
        vm.toggleChange(c1)
        vm.applySelectedChanges()

        assertEquals(1, configRepo.updateCallCount)
        assertEquals("models", configRepo.lastSectionUpdated)
    }
```
In `applySelectedChanges on empty selection is a no-op` delete the two lines `// Deselect the only change` and `vm.toggleChange(c)`; in `clearApplyFlags clearSuccess resets applySuccess flag` insert `vm.toggleChange(c)` between `vm.submitQuery("query")` and `vm.applySelectedChanges()`.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.AIRecommendationViewModelTest"`
Expected: FAIL — `submitQuery transitions … with nothing selected`, `toggleChange selects …`, `applySelectedChanges with only one change selected …` and `applySelectedChanges on empty selection is a no-op` (everything still starts selected).

- [ ] **Step 3: Start with nothing selected**

In `AIRecommendationUiState.kt`, in `Ready`, change `val selectedChanges: Set<SuggestedChange> = recommendation.suggestedChanges.toSet(),` to `val selectedChanges: Set<SuggestedChange> = emptySet(),`, replace its `@param selectedChanges …` KDoc (4 lines) with `* @param selectedChanges Changes the user ticked. Starts **empty** — nothing is pre-selected.`, and fix the stale references `[AIRecommendationViewModel.clearApplySuccess]` / `[AIRecommendationViewModel.clearApplyError]` to `[AIRecommendationViewModel.clearApplyFlags]`.
In `AIRecommendationViewModel.submitQuery` change `selectedChanges = recommendation.suggestedChanges.toSet(),` to `selectedChanges = emptySet(),`.

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.AIRecommendationViewModelTest"`
Expected: PASS.

- [ ] **Step 5: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/AIRecommendationUiState.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/AIRecommendationViewModel.kt app/src/test/kotlin/com/openclaw/ghostcrab/ui/AIRecommendationViewModelTest.kt
git commit -m "fix(ai): stop pre-selecting AI suggestions"
```

---

### Task 5c: Security-affecting AI changes are withheld until confirmed (ViewModel)

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/AIRecommendationUiState.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/AIRecommendationViewModel.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/AIRecommendationViewModelTest.kt`

Land Tasks 5c and 5d in the same PR: after 5c alone, a risky selection is withheld but no dialog asks yet.

- [ ] **Step 1: Write the failing tests** (append inside `class AIRecommendationViewModelTest`)

```kotlin
    @Test
    fun `security-affecting selection is confirmed before anything is written`() = runTest {
        val risky = change("gateway", "auth.mode")
        val configRepo = FakeAIConfigRepository()
        val (vm, _) = makeVm(
            service = FakeAIService(available = true, recommendation = recommendation(risky)),
            config = configRepo,
        )
        vm.submitQuery("query")
        vm.toggleChange(risky)

        vm.applySelectedChanges()
        assertEquals(listOf(risky), (vm.state.value as AIRecommendationUiState.Ready).confirmSecurityChanges)
        assertEquals(0, configRepo.updateCallCount)

        vm.applySelectedChanges(securityConfirmed = true)
        assertEquals(1, configRepo.updateCallCount)
        assertTrue((vm.state.value as AIRecommendationUiState.Ready).confirmSecurityChanges.isEmpty())
    }

    @Test
    fun `dismissSecurityConfirmation applies nothing`() = runTest {
        val risky = change("gateway", "auth.mode")
        val configRepo = FakeAIConfigRepository()
        val (vm, _) = makeVm(
            service = FakeAIService(available = true, recommendation = recommendation(risky)),
            config = configRepo,
        )
        vm.submitQuery("query")
        vm.toggleChange(risky)
        vm.applySelectedChanges()

        vm.dismissSecurityConfirmation()

        assertTrue((vm.state.value as AIRecommendationUiState.Ready).confirmSecurityChanges.isEmpty())
        assertEquals(0, configRepo.updateCallCount)
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.AIRecommendationViewModelTest"`
Expected: FAIL — compilation error `No parameter with name 'securityConfirmed'` / `Unresolved reference: dismissSecurityConfirmation`.

- [ ] **Step 3: Add the state field** — in `AIRecommendationUiState.Ready` add the last property and its KDoc line:

```kotlin
        val confirmSecurityChanges: List<SuggestedChange> = emptyList(),
```
`* @param confirmSecurityChanges Non-empty while the UI must ask the user to confirm these security-affecting changes before anything is written.`

- [ ] **Step 4: Update `AIRecommendationViewModel`**

1. Remove the imports `kotlinx.serialization.json.Json` and `kotlinx.serialization.json.JsonPrimitive` (moved to `SuggestedChanges.kt`).
2. In `submitQuery`, change `val context = buildContext()` to
```kotlin
                val context = buildRecommendationContext(connectionManager, configRepository, modelRepository)
```
3. Replace the whole `applySelectedChanges()` function (KDoc included) with:
```kotlin
    /**
     * Applies [AIRecommendationUiState.Ready.selectedChanges] via [configRepository].
     *
     * If any selected change is security-affecting and [securityConfirmed] is `false`, nothing is
     * written: [AIRecommendationUiState.Ready.confirmSecurityChanges] is set so the screen can ask
     * first. Call again with `securityConfirmed = true` after the user accepts.
     *
     * No-op if state is not [AIRecommendationUiState.Ready] or nothing is selected.
     *
     * @param securityConfirmed `true` once the user explicitly accepted security-affecting changes.
     */
    fun applySelectedChanges(securityConfirmed: Boolean = false) {
        val snapshot = (_state.value as? AIRecommendationUiState.Ready)
            ?.takeIf { it.selectedChanges.isNotEmpty() } ?: return
        val risky = snapshot.selectedChanges.filter { it.isSecurityAffecting }
        if (risky.isNotEmpty() && !securityConfirmed) {
            _state.value = snapshot.copy(confirmSecurityChanges = risky)
        } else {
            launchApply(snapshot)
        }
    }

    /** Closes the security confirmation without applying anything. */
    fun dismissSecurityConfirmation() {
        _state.update { (it as? AIRecommendationUiState.Ready)?.copy(confirmSecurityChanges = emptyList()) ?: it }
    }

    private fun launchApply(snapshot: AIRecommendationUiState.Ready) {
        viewModelScope.launch {
            _state.update {
                (it as? AIRecommendationUiState.Ready)
                    ?.copy(isApplying = true, applyError = null, confirmSecurityChanges = emptyList()) ?: it
            }
            var appliedCount = 0
            try {
                snapshot.selectedChanges.forEach { change ->
                    val patch = buildJsonObject { put(change.key, parseSuggestedValue(change.suggestedValue)) }
                    configRepository.updateConfig(change.section, patch)
                    appliedCount++
                }
                _state.update {
                    (it as? AIRecommendationUiState.Ready)?.copy(isApplying = false, applySuccess = true) ?: it
                }
            } catch (e: GatewayException) {
                val total = snapshot.selectedChanges.size
                val errorMsg = if (appliedCount > 0) {
                    "Applied $appliedCount of $total changes; " +
                        "stopped at change ${appliedCount + 1}: " + (e.message ?: "Unknown error")
                } else {
                    e.message ?: "Failed to apply changes"
                }
                _state.update {
                    (it as? AIRecommendationUiState.Ready)?.copy(isApplying = false, applyError = errorMsg) ?: it
                }
            }
        }
    }
```
4. Delete the private member functions `buildContext()` and `parseJsonValue(...)` (with their KDoc) and add this **top-level** function at the end of the file, after the class:
```kotlin
/**
 * Assembles context for an AI query. Config/model failures are non-fatal — the request proceeds
 * with an empty config or no model. [CancellationException] is always rethrown.
 */
private suspend fun buildRecommendationContext(
    connectionManager: GatewayConnectionManager,
    configRepository: ConfigRepository,
    modelRepository: ModelRepository,
): RecommendationContext {
    val connState = connectionManager.connectionState.value as? GatewayConnection.Connected
    val activeConfig = runCatching { configRepository.getConfig() }
        .onFailure { if (it is CancellationException) throw it }
        .getOrElse { OpenClawConfig(emptyMap()) }
    val activeModelId = runCatching { modelRepository.getModels().firstOrNull { it.isActive }?.id }
        .onFailure { if (it is CancellationException) throw it }
        .getOrNull()
    return RecommendationContext(
        activeConfig = activeConfig,
        hardwareInfo = connState?.hardwareInfo,
        activeModelId = activeModelId,
    )
}
```
The class now has 10 functions (detekt limit respected).

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.AIRecommendationViewModelTest"`
Expected: PASS.

- [ ] **Step 6: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/AIRecommendationUiState.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/AIRecommendationViewModel.kt app/src/test/kotlin/com/openclaw/ghostcrab/ui/AIRecommendationViewModelTest.kt
git commit -m "fix(ai): withhold security-affecting changes until confirmed"
```

---

### Task 5d: Security confirmation dialog and badge (UI)

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/ApplySuggestionsSheet.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/AIRecommendationScreen.kt`

- [ ] **Step 1: Add strings** — in `app/src/main/res/values/strings.xml`, next to the other `ai_sheet_*` strings:

```xml
    <string name="ai_sheet_security_badge">Security-sensitive</string>
    <string name="ai_security_confirm_title">Security-sensitive changes</string>
    <string name="ai_security_confirm_body">These changes affect authentication, credentials, TLS or network exposure. Apply them anyway?</string>
    <string name="ai_security_confirm_apply">Apply anyway</string>
    <string name="ai_security_confirm_cancel">Cancel</string>
```

- [ ] **Step 2: Badge + confirmation dialog in `ApplySuggestionsSheet.kt`**

Add imports:
```kotlin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.openclaw.ghostcrab.domain.util.REDACTED_PLACEHOLDER
import com.openclaw.ghostcrab.domain.util.isSecretKey
```
In `SuggestionRow`, directly after the `Text` that renders `"${change.section}.${change.key}"`, insert:
```kotlin
            if (change.isSecurityAffecting) {
                Text(
                    text = stringResource(R.string.ai_sheet_security_badge),
                    style = MaterialTheme.typography.labelSmall.copy(color = BrandTokens.colorCrimsonError),
                )
            }
```
Append at the end of the file:
```kotlin
/**
 * Confirmation gate shown before applying AI suggestions that touch auth, credentials, TLS or
 * network exposure. Secret values are masked.
 *
 * @param changes The security-affecting changes the user selected.
 * @param onConfirm Called when the user explicitly accepts the risk.
 * @param onDismiss Called when the user backs out; nothing is applied.
 */
@Composable
fun SecurityConfirmDialog(
    changes: List<SuggestedChange>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BrandTokens.colorAbyssRaised,
        title = {
            Text(stringResource(R.string.ai_security_confirm_title), color = BrandTokens.colorCrimsonError)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(R.string.ai_security_confirm_body), color = BrandTokens.colorTextPrimary)
                changes.forEach { change ->
                    val shown = if (isSecretKey(change.key.substringAfterLast('.'))) {
                        REDACTED_PLACEHOLDER
                    } else {
                        change.suggestedValue
                    }
                    Text(
                        text = "${change.section}.${change.key} → $shown",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                        color = BrandTokens.colorCrimsonError,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.ai_security_confirm_apply), color = BrandTokens.colorCrimsonError)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ai_security_confirm_cancel), color = BrandTokens.colorTextSecondary)
            }
        },
    )
}
```

- [ ] **Step 3: Show the dialog from `AIRecommendationScreen`**

In `AIRecommendationScreen.kt` replace
```kotlin
        // Apply suggestions sheet
        if (showApplySheet && readyState != null) {
```
with
```kotlin
        val pendingRisky = readyState?.confirmSecurityChanges.orEmpty()
        if (pendingRisky.isNotEmpty()) {
            SecurityConfirmDialog(
                changes = pendingRisky,
                onConfirm = { viewModel.applySelectedChanges(securityConfirmed = true) },
                onDismiss = { viewModel.dismissSecurityConfirmation() },
            )
        }

        // Apply suggestions sheet
        if (showApplySheet && readyState != null) {
```

- [ ] **Step 4: Full gate, ⚠ manual check and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
⚠ manual (emulator + mock gateway returning an `auth.mode` suggestion): ticking it shows the crimson "Security-sensitive" badge; Apply opens the confirmation dialog; Cancel writes nothing.
```bash
git add app/src/main/res/values/strings.xml app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/ApplySuggestionsSheet.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/AIRecommendationScreen.kt
git commit -m "feat(ai): confirm security-affecting suggestions in a dialog"
```

---

### Task 6: Capture the ETag from every PATCH and bind the cache to the session

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt` (`updateConfig`, ~line 136)
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ConfigRepositoryImpl.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/data/ConfigRepositoryImplTest.kt`

Bug: the ETag is cached only by `getConfig()`; the second consecutive PATCH re-sends the pre-write tag and a strict gateway answers 412. The cache also survives a reconnect to a different gateway. (`ConfigRepository` is frozen; this is impl-only.)

- [ ] **Step 1: Write the failing client tests** (append inside `class OpenClawApiClientTest`, in the `updateConfig` block)

```kotlin
    @Test fun `updateConfig returns the ETag sent with the write response`() = runTest {
        val engine = MockEngine {
            respond("{}", HttpStatusCode.OK, Headers.build {
                append(HttpHeaders.ContentType, "application/json")
                append(HttpHeaders.ETag, "etag-v2")
            })
        }
        val client = OpenClawApiClient.forTest(BASE, HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        })

        assertEquals("etag-v2", client.updateConfig("gateway", JsonPrimitive("x"), "etag-v1"))
    }

    @Test fun `updateConfig returns null when the write response has no ETag`() = runTest {
        val client = mockClient(HttpStatusCode.NoContent to "")
        assertNull(client.updateConfig("gateway", JsonPrimitive("x"), null))
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.OpenClawApiClientTest"`
Expected: FAIL — `expected: <etag-v2> but was: <kotlin.Unit>` and `expected: <null> but was: <kotlin.Unit>`.

- [ ] **Step 3: Return the response ETag from `updateConfig`**

In `OpenClawApiClient.kt`, in the `updateConfig` KDoc add `* @return The ETag the gateway returned with the write, or null when it sent none.` after the `@param etag` line. Change the signature line to
```kotlin
    suspend fun updateConfig(section: String, value: JsonElement, etag: String?): String? =
```
and in its `when (response.status)` delete the line `HttpStatusCode.OK, HttpStatusCode.NoContent -> Unit` and replace `else -> response.mapErrors(url)` with
```kotlin
                else -> {
                    response.mapErrors(url)
                    response.headers[HttpHeaders.ETag]
                }
```
(The 412 and 422 branches stay as they are; every 2xx now falls into `else`.)

- [ ] **Step 4: Run the client tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.OpenClawApiClientTest"`
Expected: PASS (31 tests).

- [ ] **Step 5: Write the failing repository tests** (append inside `class ConfigRepositoryImplTest`)

```kotlin
    @Test
    fun `updateConfig sends the ETag returned by the previous write`() = runTest {
        coEvery { mockClient.getConfig() } returns Pair(sectionMap, "etag-v1")
        coEvery { mockClient.updateConfig("gateway", any(), "etag-v1") } returns "etag-v2"
        coEvery { mockClient.updateConfig("gateway", any(), "etag-v2") } returns "etag-v3"
        repo.getConfig()

        repo.updateConfig("gateway", JsonPrimitive("a"))
        repo.updateConfig("gateway", JsonPrimitive("b"))

        coVerify { mockClient.updateConfig("gateway", JsonPrimitive("b"), "etag-v2") }
    }

    @Test
    fun `updateConfig drops a stale ETag when the write response carries none`() = runTest {
        coEvery { mockClient.getConfig() } returns Pair(sectionMap, "etag-v1")
        coEvery { mockClient.updateConfig(any(), any(), any()) } returns null
        repo.getConfig()

        repo.updateConfig("gateway", JsonPrimitive("a"))
        repo.updateConfig("gateway", JsonPrimitive("b"))

        coVerify { mockClient.updateConfig("gateway", JsonPrimitive("b"), null) }
    }

    @Test
    fun `an ETag cached for one session is never sent after reconnecting`() = runTest {
        coEvery { mockClient.getConfig() } returns Pair(sectionMap, "etag-old-session")
        repo.getConfig()
        val newSessionClient = mockk<OpenClawApiClient>(relaxed = true)
        every { mockManager.requireClient() } returns newSessionClient

        repo.updateConfig("gateway", JsonPrimitive("x"))

        coVerify { newSessionClient.updateConfig("gateway", JsonPrimitive("x"), null) }
    }
```

- [ ] **Step 6: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.ConfigRepositoryImplTest"`
Expected: FAIL — the first two send `etag-v1` on the second write; the third sends `etag-old-session`.

- [ ] **Step 7: Rewrite `ConfigRepositoryImpl`**

Replace the class body (keep the package and add `import com.openclaw.ghostcrab.data.api.OpenClawApiClient`):
```kotlin
/**
 * Production [ConfigRepository] backed by [GatewayConnectionManagerImpl].
 *
 * Caches the ETag of the most recent read **or write** so the next [updateConfig] sends a current
 * `If-Match`. The cache is bound to the session client that produced it: after a reconnect the old
 * tag is never sent. When a write response carries no ETag the cache becomes `null` (the pre-write
 * tag is stale by definition and would only earn a 412).
 *
 * @param connectionManager Concrete manager used to obtain the active [OpenClawApiClient].
 */
class ConfigRepositoryImpl(
    private val connectionManager: GatewayConnectionManagerImpl,
) : ConfigRepository {

    private class CachedEtag(val client: OpenClawApiClient, val etag: String?)

    private val lastEtag = AtomicReference<CachedEtag?>(null)

    /**
     * Fetches the full config from the gateway and caches the returned ETag for this session.
     *
     * @return [OpenClawConfig] with all sections and the latest ETag.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException on any gateway failure.
     * @throws IllegalStateException if no active connection exists.
     */
    override suspend fun getConfig(): OpenClawConfig {
        val client = connectionManager.requireClient()
        val (sections, etag) = client.getConfig()
        lastEtag.set(CachedEtag(client, etag))
        return OpenClawConfig(sections = sections, etag = etag)
    }

    /**
     * Sends a JSON merge-patch for [section] with the current session's cached ETag, then caches the
     * ETag returned by the write.
     *
     * @param section Top-level config section key.
     * @param value JSON merge-patch value.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayApiException on HTTP error, including 412.
     * @throws com.openclaw.ghostcrab.domain.exception.ConfigValidationException on server-side validation failure.
     * @throws IllegalStateException if no active connection exists.
     */
    override suspend fun updateConfig(section: String, value: JsonElement) {
        val client = connectionManager.requireClient()
        val cached = lastEtag.get()?.takeIf { it.client === client }?.etag
        val newEtag = client.updateConfig(section, value, cached)
        lastEtag.set(CachedEtag(client, newEtag))
    }
}
```

- [ ] **Step 8: Run the repository tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.ConfigRepositoryImplTest"`
Expected: PASS (7 tests).

- [ ] **Step 9: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/ConfigRepositoryImpl.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientTest.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/ConfigRepositoryImplTest.kt
git commit -m "fix(config): refresh the ETag after each write and drop it on reconnect"
```

---

### Task 7a: `withLeaf` — single-leaf JSON edits that keep unknown keys

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/util/JsonPatch.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/domain/JsonPatchTest.kt`

Shared by Task 7b (nested AI merge-patches) and Task 8 (gateway section editor).

- [ ] **Step 1: Write the failing `withLeaf` tests** — create `app/src/test/kotlin/com/openclaw/ghostcrab/domain/JsonPatchTest.kt`:

```kotlin
package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.util.withLeaf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JsonPatchTest {

    @Test
    fun `withLeaf preserves unknown keys and writes values equal to model defaults`() {
        val original = Json.parseToJsonElement(
            """{"http":{"host":"127.0.0.1","port":18789,"tls":{"cert":"/etc/c.pem"}},"auth":{"mode":"oauth"}}""",
        )

        val edited = original.withLeaf(listOf("http", "host"), JsonPrimitive("0.0.0.0"))

        assertEquals(
            """{"http":{"host":"0.0.0.0","port":18789,"tls":{"cert":"/etc/c.pem"}},"auth":{"mode":"oauth"}}""",
            edited.toString(),
        )
    }

    @Test
    fun `withLeaf creates missing intermediate objects`() {
        val edited = JsonObject(emptyMap()).withLeaf(listOf("mdns", "enabled"), JsonPrimitive(false))
        assertEquals("""{"mdns":{"enabled":false}}""", edited.toString())
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.JsonPatchTest"`
Expected: FAIL — `Unresolved reference: withLeaf`.

- [ ] **Step 3: Create `domain/util/JsonPatch.kt`**

```kotlin
package com.openclaw.ghostcrab.domain.util

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Returns a copy of this JSON with the leaf at [path] set to [value]. Every other key — including
 * keys no typed model knows about — is preserved verbatim and in order; missing intermediate
 * objects are created; nothing is filled with defaults. A non-object receiver is treated as `{}`.
 *
 * @param path Keys from the receiver's root, e.g. `listOf("http", "port")`. Must not be empty.
 * @param value New leaf value.
 * @return The updated object.
 * @throws IllegalArgumentException if [path] is empty.
 */
fun JsonElement.withLeaf(path: List<String>, value: JsonElement): JsonObject {
    require(path.isNotEmpty()) { "path must not be empty" }
    val obj = this as? JsonObject ?: JsonObject(emptyMap())
    val head = path.first()
    val next = if (path.size == 1) value else (obj[head] ?: JsonObject(emptyMap())).withLeaf(path.drop(1), value)
    return JsonObject(obj + (head to next))
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.JsonPatchTest"`
Expected: PASS (2 tests).


- [ ] **Step 5: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/domain/util/JsonPatch.kt app/src/test/kotlin/com/openclaw/ghostcrab/domain/JsonPatchTest.kt
git commit -m "feat(domain): add withLeaf for single-leaf JSON edits"
```

---

### Task 7b: AI apply sends one nested merge-patch per section and reconciles after each write

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/SuggestedChanges.kt` (append)
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/AIRecommendationViewModel.kt` (`launchApply`)
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/SuggestedChangesTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/ui/AIRecommendationViewModelTest.kt`

Two problems: N changes to the same section were N PATCHes (each racing the ETag), and a dotted key `http.port` was sent as a literal key `{"http.port": …}` instead of `{"http":{"port": …}}`.

- [ ] **Step 1: Write the failing grouping tests** (append inside `class SuggestedChangesTest`; add imports `com.openclaw.ghostcrab.ui.airecommend.sectionPatches` and `org.junit.jupiter.api.Assertions.assertEquals`)

```kotlin
    @Test
    fun `changes in one section merge into a single nested patch`() {
        val patches = sectionPatches(
            listOf(
                change("gateway", "http.port", "443"),
                change("gateway", "http.host", "\"127.0.0.1\""),
                change("models", "timeout_ms", "60000"),
            ),
        )

        assertEquals(listOf("gateway", "models"), patches.keys.toList())
        assertEquals("""{"http":{"port":443,"host":"127.0.0.1"}}""", patches.getValue("gateway").toString())
        assertEquals("""{"timeout_ms":60000}""", patches.getValue("models").toString())
    }

    @Test
    fun `bare-word suggested values are sent as JSON strings`() {
        val patch = sectionPatches(listOf(change("models", "default", "gpt-4o"))).getValue("models")
        assertEquals("""{"default":"gpt-4o"}""", patch.toString())
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.SuggestedChangesTest"`
Expected: FAIL — `Unresolved reference: sectionPatches`.

- [ ] **Step 3: Append `sectionPatches` to `SuggestedChanges.kt`** (add imports `com.openclaw.ghostcrab.domain.util.withLeaf` and `kotlinx.serialization.json.JsonObject`)

```kotlin
/**
 * Groups [changes] into one RFC 7396 merge-patch per top-level section. Dotted keys become nested
 * objects (`"http.port"` → `{"http":{"port":…}}`), so the gateway never receives a literal
 * `"http.port"` key. Later changes to the same leaf win.
 *
 * @param changes Changes the user selected.
 * @return Section key → merge-patch object, in first-seen section order.
 */
internal fun sectionPatches(changes: Collection<SuggestedChange>): Map<String, JsonObject> =
    changes.groupBy { it.section }.mapValues { (_, sectionChanges) ->
        sectionChanges.fold(JsonObject(emptyMap())) { patch, change ->
            patch.withLeaf(change.key.split('.'), parseSuggestedValue(change.suggestedValue))
        }
    }
```

- [ ] **Step 4: Run the helper tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.SuggestedChangesTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Write the failing ViewModel test**

In `AIRecommendationViewModelTest.kt` replace `private class FakeAIConfigRepository` with:
```kotlin
private class FakeAIConfigRepository : ConfigRepository {
    var updateCallCount = 0
    var lastSectionUpdated: String? = null
    var updateError: Exception? = null
    val updatedSections = mutableListOf<String>()
    var getConfigCalls = 0

    override suspend fun getConfig(): OpenClawConfig {
        getConfigCalls++
        return OpenClawConfig(emptyMap())
    }

    override suspend fun updateConfig(section: String, value: JsonElement) {
        updateError?.let { throw it }
        updateCallCount++
        lastSectionUpdated = section
        updatedSections += section
    }
}
```
and append inside the test class:
```kotlin
    @Test
    fun `apply sends one patch per section and reconciles after each write`() = runTest {
        val c1 = change("gateway", "http.port")
        val c2 = change("gateway", "max_connections")
        val c3 = change("models", "timeout_ms")
        val configRepo = FakeAIConfigRepository()
        val (vm, _) = makeVm(
            service = FakeAIService(available = true, recommendation = recommendation(c1, c2, c3)),
            config = configRepo,
        )
        vm.submitQuery("query")
        listOf(c1, c2, c3).forEach(vm::toggleChange)
        val readsBeforeApply = configRepo.getConfigCalls

        vm.applySelectedChanges()

        assertEquals(listOf("gateway", "models"), configRepo.updatedSections)
        assertEquals(2, configRepo.getConfigCalls - readsBeforeApply)
        assertTrue((vm.state.value as AIRecommendationUiState.Ready).applySuccess)
    }
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.AIRecommendationViewModelTest"`
Expected: FAIL — `expected: <[gateway, models]> but was: <[gateway, gateway, models]>`.

- [ ] **Step 7: Rewrite `launchApply`** in `AIRecommendationViewModel.kt` (remove the now-unused imports `kotlinx.serialization.json.buildJsonObject` and `kotlinx.serialization.json.put`)

```kotlin
    private fun launchApply(snapshot: AIRecommendationUiState.Ready) {
        viewModelScope.launch {
            _state.update {
                (it as? AIRecommendationUiState.Ready)
                    ?.copy(isApplying = true, applyError = null, confirmSecurityChanges = emptyList()) ?: it
            }
            val patches = sectionPatches(snapshot.selectedChanges)
            var appliedSections = 0
            try {
                patches.forEach { (section, patch) ->
                    configRepository.updateConfig(section, patch)
                    appliedSections++
                    // ConfigRepository contract: reconcile after every write (this also refreshes the ETag).
                    configRepository.getConfig()
                }
                _state.update {
                    (it as? AIRecommendationUiState.Ready)?.copy(isApplying = false, applySuccess = true) ?: it
                }
            } catch (e: GatewayException) {
                val errorMsg = if (appliedSections > 0) {
                    "Applied $appliedSections of ${patches.size} sections; " +
                        "stopped at section ${appliedSections + 1}: " + (e.message ?: "Unknown error")
                } else {
                    e.message ?: "Failed to apply changes"
                }
                _state.update {
                    (it as? AIRecommendationUiState.Ready)?.copy(isApplying = false, applyError = errorMsg) ?: it
                }
            }
        }
    }
```

- [ ] **Step 8: Run the ViewModel tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.AIRecommendationViewModelTest"`
Expected: PASS.

- [ ] **Step 9: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/ app/src/test/kotlin/com/openclaw/ghostcrab/ui/SuggestedChangesTest.kt app/src/test/kotlin/com/openclaw/ghostcrab/ui/AIRecommendationViewModelTest.kt
git commit -m "fix(ai): apply suggestions as one nested merge-patch per section"
```

---

### Task 8: Gateway section editor — raw-JSON fallback and single-leaf edits

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/config/GatewaySection.kt`
- Modify (full rewrite): `app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/sections/GatewaySectionEditor.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/domain/GatewaySectionTest.kt`

Bug: when the `gateway` section does not decode (e.g. an upstream `auth.mode` the enum lacks) the form silently shows defaults (`0.0.0.0:18789`, `auth=none`) as if they were the gateway's values. And because `toJsonElement()` omits values equal to their defaults (`encodeDefaults = false`), a user who sets a field *to* its default value (e.g. host `0.0.0.0`) has the edit silently dropped from the PATCH.

- [ ] **Step 1: Write the failing tests** (append inside `class GatewaySectionTest`; add import `com.openclaw.ghostcrab.domain.model.config.toGatewaySectionOrNull`)

```kotlin
    @Test fun `toGatewaySectionOrNull returns null for an auth mode the model does not know`() {
        val json = Json.parseToJsonElement("""{"auth":{"mode":"oauth"}}""")
        assertNull(json.toGatewaySectionOrNull())
    }

    @Test fun `toGatewaySectionOrNull decodes a known section`() {
        val json = Json.parseToJsonElement("""{"http":{"host":"127.0.0.1","port":9000}}""")
        assertEquals(9000, json.toGatewaySectionOrNull()?.http?.port)
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.GatewaySectionTest"`
Expected: FAIL — `Unresolved reference: toGatewaySectionOrNull`.

- [ ] **Step 3: Add the null-returning decoder and fix the stale KDoc** in `GatewaySection.kt`

Replace the `toGatewaySection` KDoc + function with:
```kotlin
/**
 * Decodes this [JsonElement] into a [GatewaySection].
 *
 * @return Parsed [GatewaySection].
 * @throws IllegalArgumentException (incl. `SerializationException`) when the JSON does not fit the
 *   model, e.g. an unknown `auth.mode`. UI code should use [toGatewaySectionOrNull].
 */
fun JsonElement.toGatewaySection(): GatewaySection =
    configJson.decodeFromJsonElement(this)

/**
 * Decodes this [JsonElement] into a [GatewaySection], or returns `null` when the JSON cannot be
 * represented by the typed model. Callers must then fall back to raw JSON editing — never to
 * defaults, which would misrepresent the gateway's real settings.
 *
 * @return Parsed [GatewaySection], or `null` on any decoding failure.
 */
fun JsonElement.toGatewaySectionOrNull(): GatewaySection? =
    runCatching { configJson.decodeFromJsonElement<GatewaySection>(this) }.getOrNull()
```

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.GatewaySectionTest"`
Expected: PASS (9 tests).

- [ ] **Step 5: Add the notice string** to `strings.xml` (Config Editor block):

```xml
    <string name="config_gateway_untyped_notice">This section contains values GhostCrab cannot model (for example an unknown auth mode). Edit it as raw JSON; nothing is replaced with defaults.</string>
```

- [ ] **Step 6: Rewrite `GatewaySectionEditor.kt`** (whole file)

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
import com.openclaw.ghostcrab.domain.model.config.GatewayAuthSection
import com.openclaw.ghostcrab.domain.model.config.GatewayHttpSection
import com.openclaw.ghostcrab.domain.model.config.GatewayMdnsSection
import com.openclaw.ghostcrab.domain.model.config.toGatewaySectionOrNull
import com.openclaw.ghostcrab.domain.util.withLeaf
import com.openclaw.ghostcrab.ui.components.EnumDropdownRow
import com.openclaw.ghostcrab.ui.components.IntFieldRow
import com.openclaw.ghostcrab.ui.components.StringFieldRow
import com.openclaw.ghostcrab.ui.components.ToggleRow
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.Spacing
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Writes one leaf (path from the section root) into the section JSON. */
private typealias SetLeaf = (path: List<String>, value: JsonElement) -> Unit

/**
 * Typed form editor for the `gateway` top-level config section.
 *
 * - When [sectionValue] decodes, typed fields are shown and each edit rewrites **exactly one leaf**
 *   of the original JSON ([withLeaf]): unknown keys survive, absent objects stay absent, and a value
 *   equal to a model default is still written.
 * - When it does not decode (e.g. an `auth.mode` this app does not know), the raw JSON editor is
 *   shown with a notice — never a form pre-filled with defaults.
 *
 * @param sectionValue Current raw JSON for the `gateway` section (pending edit or server value).
 * @param fieldErrors Field path → error message (e.g. `"gateway.http.port"` → `"…"`).
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
            GatewayHttpFields(section.http, fieldErrors, setLeaf, onFieldError)
            Spacer(modifier = Modifier.height(Spacing.md))
            GatewayAuthFields(section.auth, fieldErrors, setLeaf)
            Spacer(modifier = Modifier.height(Spacing.md))
            GatewayMdnsFields(section.mdns, fieldErrors, setLeaf)
        }
    }
}

@Composable
private fun GatewayHttpFields(
    http: GatewayHttpSection,
    fieldErrors: Map<String, String>,
    setLeaf: SetLeaf,
    onFieldError: (field: String, error: String?) -> Unit,
) {
    Text(text = "HTTP", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(Spacing.xs))
    StringFieldRow(
        label = "Host",
        value = http.host,
        onValueChange = { host -> setLeaf(listOf("http", "host"), JsonPrimitive(host)) },
        mono = true,
        error = fieldErrors["gateway.http.host"],
    )
    Spacer(modifier = Modifier.height(Spacing.sm))
    IntFieldRow(
        label = "Port",
        value = http.port,
        onValueChange = { port -> setLeaf(listOf("http", "port"), JsonPrimitive(port)) },
        onError = { err -> onFieldError("gateway.http.port", err) },
        min = 1,
        max = 65535,
    )
}

@Composable
private fun GatewayAuthFields(
    auth: GatewayAuthSection,
    fieldErrors: Map<String, String>,
    setLeaf: SetLeaf,
) {
    Text(text = "Auth", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(Spacing.xs))
    EnumDropdownRow(
        label = "Mode",
        selected = auth.mode,
        options = AuthMode.entries,
        onSelected = { mode -> setLeaf(listOf("auth", "mode"), JsonPrimitive(mode.name)) },
    )
    if (auth.mode == AuthMode.bearer) {
        Spacer(modifier = Modifier.height(Spacing.sm))
        StringFieldRow(
            label = "Bearer Token",
            value = auth.token ?: "",
            onValueChange = { token ->
                // Merge-patch semantics: JSON null removes the key.
                setLeaf(listOf("auth", "token"), if (token.isEmpty()) JsonNull else JsonPrimitive(token))
            },
            mono = true,
            error = fieldErrors["gateway.auth.token"],
        )
    }
}

@Composable
private fun GatewayMdnsFields(
    mdns: GatewayMdnsSection,
    fieldErrors: Map<String, String>,
    setLeaf: SetLeaf,
) {
    Text(text = "mDNS", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(Spacing.xs))
    ToggleRow(
        label = "Enabled",
        checked = mdns.enabled,
        onCheckedChange = { enabled -> setLeaf(listOf("mdns", "enabled"), JsonPrimitive(enabled)) },
    )
    Spacer(modifier = Modifier.height(Spacing.sm))
    StringFieldRow(
        label = "Service Name",
        value = mdns.serviceName,
        onValueChange = { name -> setLeaf(listOf("mdns", "serviceName"), JsonPrimitive(name)) },
        mono = true,
        error = fieldErrors["gateway.mdns.serviceName"],
    )
}
```

- [ ] **Step 7: Compile and run the gate**

Run: `./gradlew testDebugUnitTest detekt lintDebug`
Expected: `BUILD SUCCESSFUL` (the old `LongMethod` baseline entry for `GatewaySectionEditor` simply stops matching; no new findings because every composable is < 60 lines).

- [ ] **Step 8: ⚠ manual smoke on an emulator** — connect to `scripts/mock-gateway`, open Configure → gateway, change the port, open the diff sheet: "After" equals "Before" except `http.port`. Then hand-edit the mock config's `auth.mode` to `"oauth"`: the section shows the amber notice and the raw editor.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/config/GatewaySection.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/sections/GatewaySectionEditor.kt app/src/main/res/values/strings.xml app/src/test/kotlin/com/openclaw/ghostcrab/domain/GatewaySectionTest.kt
git commit -m "fix(config): edit single gateway leaves and fall back to raw JSON on decode failure"
```

---

### Task 9: Dashboard health check runs on the live session client

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/repository/GatewayHealthChecker.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/GatewayConnectionManagerImpl.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/dashboard/DashboardViewModel.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/di/UiModule.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/GatewayConnectionManagerImplTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/ui/DashboardViewModelTest.kt`

Bug: `gatewayHealthCheck` builds a fresh unauthenticated client every 30 s that ignores the user's cleartext opt-in (default false), so a user who opted in goes Degraded after two polls; it also churns an OkHttp pool per poll, and the ViewModel imports the data DTO `HealthResponse`.

- [ ] **Step 1: Create the additive domain interface** `domain/repository/GatewayHealthChecker.kt`:

```kotlin
package com.openclaw.ghostcrab.domain.repository

/**
 * Liveness probe against the gateway of the **current** session. Uses the live session client, so
 * it honours the same cleartext policy, timeouts and origin-scoped auth as every other call.
 *
 * Additive interface (v1.1) — no frozen v1.0 contract changes.
 */
interface GatewayHealthChecker {

    /**
     * Performs one `GET /health` on the active session.
     *
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException on any failure. Until the
     *   not-connected leaf exists, a missing session surfaces as `IllegalStateException`.
     */
    suspend fun check()
}
```

- [ ] **Step 2: Write the failing manager test** (append inside `class GatewayConnectionManagerImplTest`; add import `io.mockk.coVerify`)

```kotlin
    @Test
    fun `check probes health on the live session client`() = runTest {
        coEvery { probeClient.health() } returns HealthResponse("ok")
        coEvery { probeClient.status() } throws GatewayAuthException(url, 401)
        coEvery { sessionClient.status() } returns statusOk
        manager.connect(url, token)

        manager.check()

        coVerify { sessionClient.health() }
    }
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.GatewayConnectionManagerImplTest"`
Expected: FAIL — `Unresolved reference: check`.

- [ ] **Step 4: Implement it on the manager** — in `GatewayConnectionManagerImpl.kt` add `import com.openclaw.ghostcrab.domain.repository.GatewayHealthChecker`, change the class header to
```kotlin
class GatewayConnectionManagerImpl(
    private val clientFactory: OpenClawApiClientFactory = DefaultClientFactory,
    private val settingsRepository: SettingsRepository = AlwaysBlockCleartextSettingsRepository,
) : GatewayConnectionManager, GatewayHealthChecker {
```
and add after `disconnect()`:
```kotlin
    /** One `/health` call on the active session client (see [GatewayHealthChecker]). */
    override suspend fun check() {
        requireClient().health()
    }
```

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.GatewayConnectionManagerImplTest"`
Expected: PASS (12 tests).

- [ ] **Step 6: Switch the ViewModel to the interface**

In `DashboardViewModel.kt`: delete `import com.openclaw.ghostcrab.data.api.dto.HealthResponse`, add `import com.openclaw.ghostcrab.domain.repository.GatewayHealthChecker`, change the KDoc line `@param healthChecker …` to `* @param healthChecker Runs GET /health on the live session client. Injected for testability.`, the constructor parameter to
```kotlin
    private val healthChecker: GatewayHealthChecker,
```
and in `startDashboard` replace `healthChecker(conn.url)` with `healthChecker.check()`.

- [ ] **Step 7: Update the ViewModel test**

In `DashboardViewModelTest.kt` delete `import com.openclaw.ghostcrab.data.api.dto.HealthResponse`, add `import com.openclaw.ghostcrab.domain.repository.GatewayHealthChecker`, and add at the bottom of the file:
```kotlin
private fun healthChecker(block: suspend () -> Unit): GatewayHealthChecker = object : GatewayHealthChecker {
    override suspend fun check() = block()
}
```
Then make these replacements:
- `makeVm` parameter: `healthResult: suspend () -> HealthResponse = { HealthResponse("ok") },` → `healthResult: suspend () -> Unit = {},`
- inside `makeVm`: `healthChecker = { healthResult() },` → `healthChecker = healthChecker { healthResult() },`
- in `one failure then success resets consecutive count to zero`: `if (callCount == 1) throw RuntimeException("timeout") else HealthResponse("ok")` → `if (callCount == 1) throw RuntimeException("timeout")`
- in `disconnect() delegates to connectionManager`: `healthChecker = { HealthResponse("ok") },` → `healthChecker = healthChecker {},`
- in `Ready state is emitted before first health poll completes`: replace the `healthChecker = { … }` argument with
```kotlin
            healthChecker = healthChecker {
                readySeenBeforeHealth = vm.state.value is DashboardUiState.Ready
            },
```

- [ ] **Step 8: Rewire Koin**

`DataModule.kt`: delete the `gatewayHealthCheck` function at the bottom of the file and the imports `com.openclaw.ghostcrab.data.api.OpenClawApiClient` and `com.openclaw.ghostcrab.data.api.dto.HealthResponse`; add `import com.openclaw.ghostcrab.domain.repository.GatewayHealthChecker` and, after `single<GatewayConnectionManager> { get<GatewayConnectionManagerImpl>() }`:
```kotlin
    single<GatewayHealthChecker> { get<GatewayConnectionManagerImpl>() }
```
`UiModule.kt`: replace `healthChecker = ::gatewayHealthCheck,` with `healthChecker = get(),`.

- [ ] **Step 9: Verify no caller of the old function remains**

Run: `git grep -n "gatewayHealthCheck"`
Expected: no output.

- [ ] **Step 10: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/domain/repository/GatewayHealthChecker.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/GatewayConnectionManagerImpl.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/dashboard/DashboardViewModel.kt app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt app/src/main/kotlin/com/openclaw/ghostcrab/di/UiModule.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/GatewayConnectionManagerImplTest.kt app/src/test/kotlin/com/openclaw/ghostcrab/ui/DashboardViewModelTest.kt
git commit -m "fix(dashboard): poll /health on the live session client"
```

---

### Task 10: Precise transport error mapping — cleartext blocks and protocol bugs are not "unreachable"

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/exception/GatewayException.kt` (two new leaves)
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/api/CleartextPublicIpInterceptor.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt` (`safeRequest`, `getAIRecommendation`)
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientErrorMappingTest.kt`

Bugs: the interceptor's "blocked — enable it in Settings" `IOException` is mapped to `GatewayUnreachableException("Gateway unreachable at …")`, hiding the reason; every unknown exception (bad URL, client bug) is also "unreachable"; a 201/202 from `/api/ai/recommend` passes `mapErrors`, then `error("unreachable")` turns it into `GatewayUnreachableException`. `safeRequest` keeps its exact signature so its baselined `ThrowsCount` entry still matches.

- [ ] **Step 1: Add the two exception leaves** — append to `GatewayException.kt`:

```kotlin
/**
 * A cleartext (`http://`) request to a public IP literal was refused by the app's own policy before
 * it left the device. Fix: use HTTPS, or enable Settings → Security → "Allow cleartext to public IPs".
 *
 * @param url The URL that was blocked.
 * @param host The public IP literal that triggered the block.
 */
class CleartextBlockedException(
    val url: String,
    val host: String,
    cause: Throwable? = null,
) : GatewayException(
    "Cleartext HTTP to public IP $host blocked by app policy ($url). " +
        "Use HTTPS or enable Settings → Security → Allow cleartext to public IPs.",
    cause,
) {
    override val isRetryable: Boolean = false
}

/**
 * The gateway answered, but not in a way this client understands (undecodable body, refused
 * WebSocket upgrade), or the request could not even be built. Not a connectivity problem.
 *
 * @param url The URL involved.
 * @param detail What went wrong, for display.
 */
class GatewayProtocolException(
    val url: String,
    val detail: String,
    cause: Throwable? = null,
) : GatewayException("Unexpected gateway response at $url: $detail", cause) {
    override val isRetryable: Boolean = false
}
```

- [ ] **Step 2: Give the interceptor a typed IOException**

In `CleartextPublicIpInterceptor.kt` replace the `throw IOException( … )` statement with `throw CleartextBlockedIOException(request.url.host)` and append to the file:
```kotlin
/**
 * Thrown by [CleartextPublicIpInterceptor]. An [IOException] so OkHttp fails the call normally;
 * `safeRequest` converts it into [com.openclaw.ghostcrab.domain.exception.CleartextBlockedException].
 *
 * @param host The public IP literal that was blocked.
 */
internal class CleartextBlockedIOException(val host: String) : IOException(
    "Cleartext HTTP to public IP $host is blocked. Enable it in Settings → Security or use HTTPS.",
)
```
(`CleartextPublicIpInterceptorTest` still passes: it asserts an `IOException`.)

- [ ] **Step 3: Write the failing mapping tests** — create `app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientErrorMappingTest.kt`:

```kotlin
package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.api.CleartextBlockedIOException
import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import com.openclaw.ghostcrab.data.api.dto.AIContextDto
import com.openclaw.ghostcrab.data.api.dto.AIRecommendationRequestDto
import com.openclaw.ghostcrab.domain.exception.CleartextBlockedException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayTlsException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

private const val ERR_BASE = "http://192.168.1.50:18789"

private fun throwingClient(error: Throwable): OpenClawApiClient =
    OpenClawApiClient.forTest(ERR_BASE, HttpClient(MockEngine { throw error }))

private fun jsonResponseClient(status: HttpStatusCode, body: String): OpenClawApiClient {
    val engine = MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }
    return OpenClawApiClient.forTest(ERR_BASE, HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    })
}

class OpenClawApiClientErrorMappingTest {

    @Test fun `socket timeout maps to GatewayTimeoutException`() = runTest {
        assertThrows<GatewayTimeoutException> { throwingClient(SocketTimeoutException("read timed out")).health() }
    }

    @Test fun `TLS failure maps to GatewayTlsException`() = runTest {
        assertThrows<GatewayTlsException> { throwingClient(SSLHandshakeException("bad cert")).health() }
    }

    @Test fun `unknown host maps to GatewayUnreachableException`() = runTest {
        assertThrows<GatewayUnreachableException> { throwingClient(UnknownHostException("nope.local")).health() }
    }

    @Test fun `refused connection maps to GatewayUnreachableException`() = runTest {
        assertThrows<GatewayUnreachableException> { throwingClient(ConnectException("refused")).health() }
    }

    @Test fun `cleartext policy block maps to CleartextBlockedException with the host`() = runTest {
        val ex = assertThrows<CleartextBlockedException> {
            throwingClient(CleartextBlockedIOException("8.8.8.8")).health()
        }
        assertEquals("8.8.8.8", ex.host)
        assertTrue(ex.message.orEmpty().contains("Settings"))
    }

    @Test fun `non-IO failure maps to GatewayProtocolException, not Unreachable`() = runTest {
        val ex = assertThrows<GatewayProtocolException> {
            throwingClient(IllegalArgumentException("bad url")).health()
        }
        assertTrue(ex.detail.contains("IllegalArgumentException"))
    }

    @Test fun `a 202 from ai recommend is decoded instead of reported unreachable`() = runTest {
        val client = jsonResponseClient(HttpStatusCode.Accepted, """{"recommendation":"ok","suggested_changes":[]}""")
        val dto = client.getAIRecommendation(AIRecommendationRequestDto("q", AIContextDto(null, null, null)))
        assertEquals("ok", dto.recommendation)
    }
}
```

- [ ] **Step 4: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.OpenClawApiClientErrorMappingTest"`
Expected: FAIL — `socket timeout…` (got Unreachable), `cleartext policy block…`, `non-IO failure…` and `a 202 from ai recommend…` (all got `GatewayUnreachableException`).

- [ ] **Step 5: Rewrite the mapping in `OpenClawApiClient.kt`**

Imports: remove `java.net.ConnectException` and `java.net.UnknownHostException`; add
```kotlin
import com.openclaw.ghostcrab.domain.exception.CleartextBlockedException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.serialization.ContentConvertException
import java.io.IOException
import java.net.SocketTimeoutException
```
In `getAIRecommendation` replace the `when (response.status) { … }` block with
```kotlin
            when (response.status) {
                HttpStatusCode.NotFound -> throw GatewayApiException(url, 404)
                HttpStatusCode.TooManyRequests -> throw GatewayApiException(url, 429)
                else -> {
                    response.mapErrors(url)
                    response.body()
                }
            }
```
Replace the whole `safeRequest` function (same signature) with:
```kotlin
private suspend fun <T> safeRequest(url: String, block: suspend () -> T): T {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: GatewayException) {
        // Domain exceptions (incl. ConfigValidationException) pass through unchanged.
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

- [ ] **Step 6: Run the mapping tests and the existing client tests**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.OpenClawApiClient*"`
Expected: PASS (31 + 7 tests).

- [ ] **Step 7: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/domain/exception/GatewayException.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/api/CleartextPublicIpInterceptor.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientErrorMappingTest.kt
git commit -m "fix(api): surface cleartext blocks and protocol errors instead of 'unreachable'"
```

---

### Task 11: One brand-compliant error formatter used by every screen

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/ErrorMessages.kt`
- Modify: the eight ViewModels listed in Step 5, `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ConnectionPickerScreen.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/ErrorMessagesTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/ui/ManualEntryViewModelTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/ui/InstalledSkillsViewModelTest.kt`

Brand rule (CLAUDE.md): errors show the exact URL, HTTP status and **exception class**. Today 27 call sites stringify `e.message`, and none shows the class.

- [ ] **Step 1: Write the failing formatter test** — create `app/src/test/kotlin/com/openclaw/ghostcrab/ui/ErrorMessagesTest.kt`:

```kotlin
package com.openclaw.ghostcrab.ui

import com.openclaw.ghostcrab.domain.exception.GatewayApiException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.ConnectException

class ErrorMessagesTest {

    @Test
    fun `api error shows url status and class`() {
        val msg = GatewayApiException("http://10.0.0.5:18789/config/gateway", 412).toUserMessage()
        assertEquals(
            "Gateway API error at http://10.0.0.5:18789/config/gateway: HTTP 412 (GatewayApiException)",
            msg,
        )
    }

    @Test
    fun `wrapped transport failure names the root cause`() {
        val msg = GatewayUnreachableException("http://10.0.0.5:18789", ConnectException("Connection refused"))
            .toUserMessage()
        assertEquals(
            "Gateway unreachable at http://10.0.0.5:18789 " +
                "(GatewayUnreachableException ← ConnectException: Connection refused)",
            msg,
        )
    }

    @Test
    fun `exception without message still names its class`() {
        assertEquals("Unexpected failure (IllegalStateException)", IllegalStateException().toUserMessage())
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.ErrorMessagesTest"`
Expected: FAIL — `Unresolved reference: toUserMessage`.

- [ ] **Step 3: Create `ui/ErrorMessages.kt`**

```kotlin
package com.openclaw.ghostcrab.ui

/**
 * Renders any failure as explicit, brand-compliant text: what happened (domain messages already
 * carry the URL and HTTP status), the exception class, and the root cause. Never "Oops".
 *
 * Examples:
 * - `Gateway API error at http://10.0.0.5:18789/config/gateway: HTTP 412 (GatewayApiException)`
 * - `Gateway unreachable at http://10.0.0.5:18789 (GatewayUnreachableException ← ConnectException: refused)`
 *
 * @receiver The failure to describe.
 * @return A single-line, user-visible message.
 */
fun Throwable.toUserMessage(): String {
    val headline = message?.takeIf { it.isNotBlank() } ?: "Unexpected failure"
    val root = generateSequence(this) { current -> current.cause?.takeIf { it !== current } }.last()
    val rootPart = if (root === this) {
        ""
    } else {
        " ← ${root::class.java.simpleName}" + (root.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
    }
    return "$headline (${this::class.java.simpleName}$rootPart)"
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.ErrorMessagesTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Replace ad-hoc `e.message` strings mechanically**

Run (Git Bash, repo root):
```bash
UI=app/src/main/kotlin/com/openclaw/ghostcrab/ui
FILES="$UI/config/ConfigEditorViewModel.kt $UI/model/ModelManagerViewModel.kt $UI/airecommend/AIRecommendationViewModel.kt $UI/connection/ScanViewModel.kt $UI/connection/ManualEntryViewModel.kt $UI/settings/SettingsViewModel.kt $UI/installedskills/InstalledSkillsViewModel.kt $UI/dashboard/DashboardViewModel.kt $UI/connection/ConnectionPickerScreen.kt"
sed -i -E \
  -e 's/e\.message \?: "[^"]*"/e.toUserMessage()/g' \
  -e 's/\$\{e\.message\}/${e.toUserMessage()}/g' \
  -e 's/= e\.message([,)])/= e.toUserMessage()\1/g' \
  $FILES
for f in $FILES; do sed -i '0,/^import /s//import com.openclaw.ghostcrab.ui.toUserMessage\nimport /' "$f"; done
```
Then in `AIRecommendationViewModel.kt` replace
```kotlin
                    "AI rate limit exceeded at ${e.url}. Please wait before retrying.",
```
with
```kotlin
                    "AI rate limit reached — wait before retrying. ${e.toUserMessage()}",
```

- [ ] **Step 6: Verify nothing user-visible still uses a raw message**

Run: `git grep -n "e\.message" -- app/src/main/kotlin/com/openclaw/ghostcrab/ui`
Expected: no output.

- [ ] **Step 7: Update the three tests that asserted raw messages**

- `ManualEntryViewModelTest.kt`, test `connect failure sets Error uiState with exception message`: `assertEquals("refused", …)` → `assertEquals("refused (RuntimeException)", (vm.uiState.value as ManualEntryUiState.Error).message)`
- `InstalledSkillsViewModelTest.kt`: `assertEquals("offline", s.errorMessage)` → `assertEquals("offline (IllegalStateException)", s.errorMessage)`; `assertEquals("boom", s.errorMessage)` → `assertEquals("boom (RuntimeException)", s.errorMessage)`; `assertEquals("nope", …)` → `assertEquals("nope (RuntimeException)", (vm2.state.value as InstalledSkillsUiState.Ready).errorMessage)`

- [ ] **Step 8: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/ app/src/test/kotlin/com/openclaw/ghostcrab/ui/
git commit -m "feat(ui): show URL, status and exception class in every error message"
```

---

### Task 12: `requireClient()` throws a typed `GatewayNotConnectedException`

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/exception/GatewayException.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/GatewayConnectionManagerImpl.kt`
- Modify (KDoc only): `data/impl/ConfigRepositoryImpl.kt`, `data/impl/ModelRepositoryImpl.kt`, `data/impl/AIRecommendationServiceImpl.kt`, `domain/repository/GatewayHealthChecker.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt`
- Test: `data/ConfigRepositoryImplTest.kt`, `data/ModelRepositoryImplTest.kt`, `data/AIRecommendationServiceImplTest.kt`, `data/GatewayConnectionManagerImplTest.kt`, `ui/ModelManagerViewModelTest.kt`

Bug: `requireClient()` throws `IllegalStateException` (`error(…)`), but every ViewModel catches only `GatewayException`, so a disconnect race crashes the app. Also `activeClient` is a plain `var` written on IO and read from any thread.

- [ ] **Step 1: Write the failing tests**

Append inside `class GatewayConnectionManagerImplTest` (add imports `com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException` and `org.junit.jupiter.api.assertThrows`):
```kotlin
    @Test
    fun `requireClient before connect throws GatewayNotConnectedException`() {
        assertThrows<GatewayNotConnectedException> { manager.requireClient() }
    }
```
Append inside `class ModelManagerViewModelTest` (add import `com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException`):
```kotlin
    @Test
    fun `loading models after the session dropped shows Error instead of crashing`() = runTest {
        val repo = FakeMMRepository().apply { getError = GatewayNotConnectedException() }
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)

        val state = vm.state.value as ModelManagerUiState.Error
        assertTrue(state.message.contains("GatewayNotConnectedException"))
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.GatewayConnectionManagerImplTest" --tests "com.openclaw.ghostcrab.ui.ModelManagerViewModelTest"`
Expected: FAIL — `Unresolved reference: GatewayNotConnectedException`.

- [ ] **Step 3: Add the leaf** — append to `GatewayException.kt`:

```kotlin
/**
 * An operation needed the active gateway session but none exists (never connected, or the
 * connection dropped mid-flight). Reconnect and retry.
 */
class GatewayNotConnectedException :
    GatewayException("No active gateway connection. Reconnect and try again.") {
    override val isRetryable: Boolean = true
}
```

- [ ] **Step 4: Throw it from the manager** — in `GatewayConnectionManagerImpl.kt` add `import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException`, change `private var activeClient: OpenClawApiClient? = null` to
```kotlin
    // Written under [mutex] on the IO dispatcher, read lock-free by repositories on any thread.
    @Volatile private var activeClient: OpenClawApiClient? = null
```
and replace `requireClient()` (KDoc included) with
```kotlin
    /**
     * The live session client, for repository implementations.
     *
     * @return The client of the current session.
     * @throws GatewayNotConnectedException if no session is active.
     */
    fun requireClient(): OpenClawApiClient = activeClient ?: throw GatewayNotConnectedException()
```

- [ ] **Step 5: Replace the WS factories' untyped errors** — in `DataModule.kt` add `import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException` and replace both
`?: error("Cannot install skills while disconnected")` and `?: error("Cannot probe scopes while disconnected")` with `?: throw GatewayNotConnectedException()`.

- [ ] **Step 6: Retarget the three repository tests and KDocs**

Run:
```bash
T=app/src/test/kotlin/com/openclaw/ghostcrab/data
sed -i \
  -e 's/throws IllegalStateException("No active gateway connection")/throws GatewayNotConnectedException()/' \
  -e 's/assertThrows<IllegalStateException>/assertThrows<GatewayNotConnectedException>/' \
  -e 's/throws IllegalStateException when not connected/throws GatewayNotConnectedException when not connected/' \
  -e 's/when not connected throws IllegalStateException/when not connected throws GatewayNotConnectedException/' \
  $T/ConfigRepositoryImplTest.kt $T/ModelRepositoryImplTest.kt $T/AIRecommendationServiceImplTest.kt
for f in $T/ConfigRepositoryImplTest.kt $T/ModelRepositoryImplTest.kt $T/AIRecommendationServiceImplTest.kt; do
  sed -i '0,/^import /s//import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException\nimport /' "$f"
done
M=app/src/main/kotlin/com/openclaw/ghostcrab
sed -i 's/@throws IllegalStateException if no \(active connection exists\|gateway connection is active\)\./@throws com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException if no session is active./' \
  $M/data/impl/ConfigRepositoryImpl.kt $M/data/impl/ModelRepositoryImpl.kt $M/data/impl/AIRecommendationServiceImpl.kt
```
In `ModelRepositoryImpl.kt` class KDoc replace
`* Requires an active connection — [GatewayConnectionManagerImpl.requireClient] throws` / `* [IllegalStateException] if called while disconnected, which propagates to callers.` with
`* Requires an active connection — [GatewayConnectionManagerImpl.requireClient] throws` / `* [com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException] while disconnected.`
In `GatewayHealthChecker.kt` replace the `@throws` paragraph with
`* @throws com.openclaw.ghostcrab.domain.exception.GatewayException on any failure, including` / `*   [com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException] when no session is active.`

- [ ] **Step 7: Verify no `IllegalStateException` contract is left**

Run: `git grep -n "IllegalStateException" -- app/src/main/kotlin/com/openclaw/ghostcrab/data/impl`
Expected: no output.

- [ ] **Step 8: Run the affected tests**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.*" --tests "com.openclaw.ghostcrab.ui.ModelManagerViewModelTest"`
Expected: PASS.

- [ ] **Step 9: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ app/src/test/kotlin/com/openclaw/ghostcrab/
git commit -m "fix(data): throw GatewayNotConnectedException instead of IllegalStateException"
```

---

### Task 13: `suspendRunCatching` — stop swallowing `CancellationException`

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/util/SuspendRunCatching.kt`
- Modify: `ui/settings/SettingsViewModel.kt`, `data/impl/InstalledSkillRepositoryImpl.kt`, `ui/dashboard/DashboardViewModel.kt`, `ui/model/ModelManagerViewModel.kt`, `ui/config/ConfigEditorViewModel.kt`, `ui/airecommend/AIRecommendationViewModel.kt`, `ui/connection/ManualEntryViewModel.kt` (all under `app/src/main/kotlin/com/openclaw/ghostcrab/`)
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/domain/SuspendRunCatchingTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/ui/ManualEntryViewModelTest.kt`

`runCatching` / `catch (_: Exception)` around suspend calls captures `CancellationException`, so a cancelled `viewModelScope` is reported as an error. `ManualEntryViewModel` additionally saves the profile inside `onSuccess {}`, outside any guard: a Keystore/DataStore failure after a successful connect crashes the app. (`ConnectionPickerViewModel.connect` is rewritten in Task 24; `ScanViewModel` already uses a typed catch since Task 2.)

- [ ] **Step 1: Write the failing helper test** — create `app/src/test/kotlin/com/openclaw/ghostcrab/domain/SuspendRunCatchingTest.kt`:

```kotlin
package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.util.suspendRunCatching
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.coroutines.cancellation.CancellationException

class SuspendRunCatchingTest {

    @Test
    fun `returns the value on success`() = runTest {
        assertEquals(42, suspendRunCatching { 42 }.getOrThrow())
    }

    @Test
    fun `captures ordinary failures`() = runTest {
        val result = suspendRunCatching { throw GatewayTimeoutException("http://x") }
        assertInstanceOf(GatewayTimeoutException::class.java, result.exceptionOrNull())
    }

    @Test
    fun `rethrows cancellation instead of capturing it`() = runTest {
        assertThrows<CancellationException> { suspendRunCatching { throw CancellationException("cancelled") } }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.SuspendRunCatchingTest"`
Expected: FAIL — `Unresolved reference: suspendRunCatching`.

- [ ] **Step 3: Create `domain/util/SuspendRunCatching.kt`**

```kotlin
package com.openclaw.ghostcrab.domain.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for coroutine code: captures every failure as [Result.failure] **except**
 * [CancellationException], which is rethrown so structured concurrency keeps working (a cancelled
 * `viewModelScope` must never be shown to the user as an error). Inline, so [block] may suspend.
 *
 * @param block The work to run.
 * @return [Result.success] with the value, or [Result.failure] with the thrown exception.
 * @throws CancellationException if [block] was cancelled.
 */
@Suppress("TooGenericExceptionCaught")
inline fun <T> suspendRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.SuspendRunCatchingTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Write the failing ManualEntry regression test** (append inside `class ManualEntryViewModelTest`; add import `com.openclaw.ghostcrab.domain.exception.ProfileNeedsReauthException`)

```kotlin
    @Test
    fun `profile save failure after a successful connect sets Error instead of crashing`() = runTest {
        coEvery { connectionManager.connect(any(), any()) } returns Unit
        coEvery { profileRepository.saveProfile(any(), any()) } throws ProfileNeedsReauthException("p1")
        vm.onHostChange("192.168.0.23")

        vm.connect()

        val state = vm.uiState.value as ManualEntryUiState.Error
        assertTrue(state.message.contains("ProfileNeedsReauthException"))
    }
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.ManualEntryViewModelTest"`
Expected: FAIL — `ClassCastException: ManualEntryUiState$Connecting cannot be cast to ManualEntryUiState$Error`.

- [ ] **Step 7: Rewrite `ManualEntryViewModel.connect()`** — add imports `com.openclaw.ghostcrab.domain.model.GatewayConnection` and `com.openclaw.ghostcrab.domain.util.suspendRunCatching`; replace the `viewModelScope.launch { … }` block at the end of `connect()` with:
```kotlin
        viewModelScope.launch {
            _uiState.value = ManualEntryUiState.Connecting
            val token = state.token.trim().takeIf { it.isNotEmpty() }
            // Connect AND persist inside one guard: a Keystore/DataStore failure after a successful
            // connect must surface as an error, not crash the app.
            suspendRunCatching {
                connectionManager.connect(url, token)
                saveConnectedProfile(url, token)
                onboardingRepository.markCompleted()
            }.onSuccess {
                _uiState.value = ManualEntryUiState.Idle
                _events.emit(ManualEntryEvent.NavigateToDashboard)
            }.onFailure { e ->
                _uiState.value = ManualEntryUiState.Error(e.toUserMessage())
            }
        }
```
and add this member below `connect()`:
```kotlin
    private suspend fun saveConnectedProfile(url: String, token: String?) {
        val displayName = (connectionManager.connectionState.value as? GatewayConnection.Connected)
            ?.displayName ?: url
        val existingId = profileRepository.getProfiles().first().firstOrNull { it.url == url }?.id
        profileRepository.saveProfile(
            ConnectionProfile(
                id = existingId ?: UUID.randomUUID().toString(),
                displayName = displayName,
                url = url,
                lastConnectedAt = System.currentTimeMillis(),
                hasToken = token != null,
            ),
            token,
        )
    }
```

- [ ] **Step 8: Migrate the remaining sites**

Run:
```bash
M=app/src/main/kotlin/com/openclaw/ghostcrab
sed -i 's/\brunCatching {/suspendRunCatching {/g' $M/ui/settings/SettingsViewModel.kt $M/data/impl/InstalledSkillRepositoryImpl.kt
for f in $M/ui/settings/SettingsViewModel.kt $M/data/impl/InstalledSkillRepositoryImpl.kt $M/ui/dashboard/DashboardViewModel.kt $M/ui/model/ModelManagerViewModel.kt $M/ui/config/ConfigEditorViewModel.kt $M/ui/airecommend/AIRecommendationViewModel.kt; do
  sed -i '0,/^import /s//import com.openclaw.ghostcrab.domain.util.suspendRunCatching\nimport /' "$f"
done
```
Then hand-edit:
- `DashboardViewModel.kt`: `val models = try { modelRepository.getModels() } catch (_: Exception) { emptyList() }` → `val models = suspendRunCatching { modelRepository.getModels() }.getOrDefault(emptyList())`
- `ModelManagerViewModel.kt` (`confirmSwap`): replace
```kotlin
                val recovered = try {
                    modelRepository.getModels()
                } catch (_: Exception) {
                    current.models
                }
```
with `val recovered = suspendRunCatching { modelRepository.getModels() }.getOrDefault(current.models)`
- `ConfigEditorViewModel.kt` (`confirmSave`): `val reloaded = try { configRepository.getConfig() } catch (_: Exception) { null }` → `val reloaded = suspendRunCatching { configRepository.getConfig() }.getOrNull()`
- `AIRecommendationViewModel.kt` (`buildRecommendationContext`): replace the two `runCatching { … }.onFailure { if (it is CancellationException) throw it }` chains with
```kotlin
    val activeConfig = suspendRunCatching { configRepository.getConfig() }.getOrElse { OpenClawConfig(emptyMap()) }
    val activeModelId = suspendRunCatching { modelRepository.getModels().firstOrNull { it.isActive }?.id }.getOrNull()
```
and update its KDoc's last sentence to `Uses [suspendRunCatching], so cancellation is always rethrown.`

- [ ] **Step 9: Verify no bare `runCatching` wraps a suspend call any more**

Run: `git grep -n "runCatching {" -- app/src/main/kotlin | grep -v suspendRunCatching`
Expected: only non-suspending uses remain — `ConnectionPickerViewModel.kt` (rewritten in Task 24), `ConnectionProfileStore.kt`, `CleartextPublicIpInterceptor.kt`, `NsdDiscoveryServiceImpl.kt` (`multicastLock.acquire()`), `OpenClawApiClient.kt` (`Json.parseToJsonElement`), `GatewayWsClient.kt`, `GatewaySection.kt`, `SuggestedChanges.kt`, `RawJsonSectionEditor.kt`, `ManualEntryViewModel.kt` (`URI(...)` in `setPrefillUrl`), `QrScanViewModel.kt`. No hit in `AIRecommendationViewModel.kt`, `ScanViewModel.kt`, `SettingsViewModel.kt` or `InstalledSkillRepositoryImpl.kt`.

- [ ] **Step 10: Run the tests**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 11: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ app/src/test/kotlin/com/openclaw/ghostcrab/domain/SuspendRunCatchingTest.kt app/src/test/kotlin/com/openclaw/ghostcrab/ui/ManualEntryViewModelTest.kt
git commit -m "fix: rethrow CancellationException via suspendRunCatching and guard profile save"
```

---

### Task 14: `GatewayWsClient` — production factory, request timeout, fail pending requests on close

**Files:**
- Modify (rewrite class + companion): `app/src/main/kotlin/com/openclaw/ghostcrab/data/ws/GatewayWsClient.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/ws/GatewayWsClientTest.kt`, `data/impl/InstalledSkillRepositoryImplTest.kt`, `data/impl/ScopeProbeImplTest.kt`

Bugs: `request()` awaits forever (no timeout) and pending requests are never failed when the socket closes — a dropped WS hangs install/uninstall/probe; production builds the client via `forTesting` (reader on `Dispatchers.Unconfined`); `tokenProvider` is stored and never used.

- [ ] **Step 1: Write the failing tests** — append to `GatewayWsClientTest.kt` (inside the class), plus the helper class at file end:

```kotlin
    @Test
    fun `request fails with GatewayTimeoutException when no response arrives`() = runTest {
        val client = GatewayWsClient.forTesting(FakeWsSession(), requestTimeoutMs = 1_000)

        assertThrows<GatewayTimeoutException> { client.request("skills.list", null) }
    }

    @Test
    fun `pending request fails with GatewayUnreachableException when the socket closes`() = runTest {
        val session = ClosingWsSession()
        val client = GatewayWsClient.forTesting(session)
        val call = async { runCatching { client.request("skills.list", null) } }
        session.sent.receive()

        session.closeFromServer()

        assertInstanceOf(GatewayUnreachableException::class.java, call.await().exceptionOrNull())
    }
```
```kotlin
/** Session whose inbound stream can be completed, like a server closing the socket. */
private class ClosingWsSession : WsSession {
    private val inbound = Channel<String>(Channel.UNLIMITED)
    val sent = Channel<String>(Channel.UNLIMITED)
    override suspend fun send(text: String) { sent.send(text) }
    override fun incoming(): Flow<String> = inbound.consumeAsFlow()
    override suspend fun close() = Unit
    fun closeFromServer() { inbound.close() }
}
```
Add imports:
```kotlin
import com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import kotlinx.coroutines.flow.consumeAsFlow
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.assertThrows
```

- [ ] **Step 2: Drop the unused `tokenProvider` from the existing test call sites**

```bash
T=app/src/test/kotlin/com/openclaw/ghostcrab/data
sed -i 's/GatewayWsClient.forTesting(session, tokenProvider = { "tkn" })/GatewayWsClient.forTesting(session)/' \
  $T/impl/InstalledSkillRepositoryImplTest.kt $T/impl/ScopeProbeImplTest.kt
sed -i '/tokenProvider = {/d' $T/ws/GatewayWsClientTest.kt
```

- [ ] **Step 3: Run the WS tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.ws.GatewayWsClientTest"`
Expected: FAIL — compilation error `No value passed for parameter 'tokenProvider'` / `No parameter with name 'requestTimeoutMs'`.

- [ ] **Step 4: Rewrite the client** — in `GatewayWsClient.kt` replace everything from the `GatewayWsClient` KDoc down to (and including) the end of its `companion object` with:

```kotlin
/**
 * Multiplexed JSON-RPC 2.0 client over a single WS session. Request ids come from a monotonic
 * counter; pending responses are parked as [CompletableDeferred] by id. Notifications (no `id`)
 * are fanned out via [notifications].
 *
 * Every [request] is bounded by a timeout, and when the socket closes all pending requests fail
 * with [GatewayUnreachableException] instead of hanging. Construct with [create] or [forTesting].
 */
class GatewayWsClient private constructor(
    private val session: WsSession,
    private val endpoint: String,
    private val requestTimeoutMs: Long,
    dispatcher: CoroutineContext,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>()
    private val _notifications = MutableSharedFlow<JsonRpcNotification>(extraBufferCapacity = 64)

    /** Hot flow of server-pushed notifications (no `id`). */
    val notifications: Flow<JsonRpcNotification> = _notifications

    private val readerJob: Job = scope.launch {
        try {
            session.incoming().collect { raw -> dispatch(raw) }
        } finally {
            // Socket closed (or client closed): nobody will ever answer the parked requests.
            failPending(GatewayUnreachableException(endpoint))
        }
    }

    @Suppress("ReturnCount")
    private fun dispatch(raw: String) {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }
            .onFailure { android.util.Log.d("GatewayWsClient", "unparseable frame: ${it.message}") }
            .getOrNull() ?: return
        if (obj.containsKey("id")) {
            val res = runCatching { json.decodeFromString(JsonRpcResponse.serializer(), raw) }
                .onFailure { android.util.Log.d("GatewayWsClient", "bad response envelope: ${it.message}") }
                .getOrNull() ?: return
            pending.remove(res.id)?.complete(res)
        } else if (obj.containsKey("method")) {
            val n = runCatching { json.decodeFromString(JsonRpcNotification.serializer(), raw) }
                .onFailure { android.util.Log.d("GatewayWsClient", "bad notification: ${it.message}") }
                .getOrNull() ?: return
            scope.launch { _notifications.emit(n) }
        }
    }

    /**
     * Sends a JSON-RPC request and suspends until the matching response arrives.
     *
     * @param method The RPC method name (e.g. `"skills.list"`).
     * @param params Optional JSON parameters.
     * @return The `result` field of the response, or an empty [JsonObject] if absent.
     * @throws WsRpcException if the response carries an `error` object.
     * @throws GatewayTimeoutException if no response arrives within the request timeout.
     * @throws GatewayUnreachableException if the socket closed before the response arrived.
     */
    suspend fun request(method: String, params: JsonElement?): JsonElement {
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pending[id] = deferred
        val req = JsonRpcRequest(id = id, method = method, params = params)
        val res = try {
            session.send(json.encodeToString(JsonRpcRequest.serializer(), req))
            withTimeout(requestTimeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw GatewayTimeoutException("$endpoint#$method", e)
        } finally {
            pending.remove(id)
        }
        res.error?.let { throw WsRpcException(code = it.code, message = it.message) }
        return res.result ?: JsonObject(emptyMap())
    }

    /** Cancels the reader loop and the scope, then closes the underlying session. */
    suspend fun close() {
        readerJob.cancel()
        scope.cancel()
        session.close()
    }

    private fun failPending(cause: GatewayException) {
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
    }

    /** Thrown when the server returns a JSON-RPC error object. */
    class WsRpcException(val code: Int, override val message: String) : RuntimeException(message)

    companion object {
        /** Time to wait for a JSON-RPC response before failing with [GatewayTimeoutException]. */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L

        /**
         * Production factory — reader loop on [Dispatchers.Default].
         *
         * @param session A connected WS session.
         * @param endpoint The `ws(s)://…/ws` URL, used in error messages.
         * @return A client that owns [session]; call [close] when done.
         */
        fun create(session: WsSession, endpoint: String): GatewayWsClient =
            GatewayWsClient(session, endpoint, DEFAULT_REQUEST_TIMEOUT_MS, Dispatchers.Default)

        /**
         * For unit tests only — deterministic frame dispatch on [Dispatchers.Unconfined] by default.
         *
         * @param session Fake session.
         * @param dispatcher Context for the reader loop.
         * @param requestTimeoutMs Per-request timeout (virtual time under `runTest`).
         */
        fun forTesting(
            session: WsSession,
            dispatcher: CoroutineContext = Dispatchers.Unconfined,
            requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
        ): GatewayWsClient = GatewayWsClient(session, "ws://test/ws", requestTimeoutMs, dispatcher)
    }
}
```
Add imports `com.openclaw.ghostcrab.domain.exception.GatewayException`, `com.openclaw.ghostcrab.domain.exception.GatewayTimeoutException`, `com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException`, `kotlinx.coroutines.TimeoutCancellationException`, `kotlinx.coroutines.withTimeout`. In `openGatewayWs` (still present until Task 15) change the last line to `return GatewayWsClient.create(KtorWsSession(session), endpoint = wsUrl)`.

- [ ] **Step 5: Run the WS-related tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.ws.*" --tests "com.openclaw.ghostcrab.data.impl.*"`
Expected: PASS.

- [ ] **Step 6: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/ws/GatewayWsClient.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/
git commit -m "fix(ws): time out JSON-RPC requests and fail them when the socket closes"
```

---

### Task 15: Open WebSockets through `OpenClawApiClient` and share one DI factory

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt` (add `openWebSocket`, make `httpClient` private)
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/ws/GatewayWsClient.kt` (delete `openGatewayWs`)
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientErrorMappingTest.kt`

Problems: the WS upgrade adds `Authorization` by hand (bypassing the origin-scoped plugin's TLS-downgrade check); upgrade failures escape as raw Ktor exceptions instead of `GatewayException`; the factory lambda is copy-pasted for two repositories.

- [ ] **Step 1: Write the failing test** (append inside `class OpenClawApiClientErrorMappingTest`; add imports `com.openclaw.ghostcrab.domain.exception.GatewayException` and `io.ktor.client.plugins.websocket.WebSockets`)

```kotlin
    @Test fun `openWebSocket failures surface as GatewayException`() = runTest {
        val client = OpenClawApiClient.forTest(
            ERR_BASE,
            HttpClient(MockEngine { throw ConnectException("refused") }) { install(WebSockets) },
        )
        assertThrows<GatewayException> { client.openWebSocket() }
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.OpenClawApiClientErrorMappingTest"`
Expected: FAIL — `Unresolved reference: openWebSocket`.

- [ ] **Step 3: Add `openWebSocket` to `OpenClawApiClient`**

Add imports `com.openclaw.ghostcrab.data.ws.GatewayWsClient`, `com.openclaw.ghostcrab.data.ws.KtorWsSession`, `io.ktor.client.plugins.websocket.webSocketSession`. Change the constructor property and its KDoc to
```kotlin
    /** Underlying Ktor client: cleartext interceptor, timeouts, sanitized logging, origin-scoped bearer. */
    private val httpClient: HttpClient,
```
and add before `close()`:
```kotlin
    /**
     * Opens the gateway's JSON-RPC WebSocket (`<baseUrl>/ws`) on this client, so the upgrade request
     * carries the same origin-scoped bearer, cleartext policy, timeouts and sanitized logging as
     * every HTTP call — no hand-added `Authorization` header.
     *
     * @return A connected [GatewayWsClient]; the caller must [GatewayWsClient.close] it.
     * @throws GatewayUnreachableException / [GatewayTimeoutException] / [CleartextBlockedException]
     *   on transport failure.
     * @throws GatewayProtocolException if the server refuses the upgrade.
     */
    suspend fun openWebSocket(): GatewayWsClient = safeRequest(baseUrl) {
        val wsUrl = baseUrl.replaceFirst(Regex("^http"), "ws").trimEnd('/') + "/ws"
        GatewayWsClient.create(KtorWsSession(httpClient.webSocketSession(wsUrl)), wsUrl)
    }
```

- [ ] **Step 4: Delete `openGatewayWs`** — in `GatewayWsClient.kt` remove the whole `openGatewayWs` function with its KDoc and the import `io.ktor.client.plugins.websocket.webSocketSession`.

- [ ] **Step 5: One WS factory in `DataModule.kt`**

Remove the imports `com.openclaw.ghostcrab.data.ws.openGatewayWs` and `com.openclaw.ghostcrab.domain.model.GatewayConnection` (if now unused) and `GatewayNotConnectedException`; add `import com.openclaw.ghostcrab.data.ws.GatewayWsClient` and `import org.koin.core.scope.Scope`. Replace the whole `if (com.openclaw.ghostcrab.BuildConfig.SKILLS_INSTALL_ENABLED) { … }` block with
```kotlin
    if (com.openclaw.ghostcrab.BuildConfig.SKILLS_INSTALL_ENABLED) {
        single<InstalledSkillRepository> { InstalledSkillRepositoryImpl(wsFactory = liveWsFactory()) }
        single<ScopeProbe> { ScopeProbeImpl(wsFactory = liveWsFactory()) }
    }
```
and add at the end of the file:
```kotlin
/**
 * Opens a fresh JSON-RPC WebSocket on the live session client. Shared by every WS-backed
 * repository; throws `GatewayNotConnectedException` while disconnected.
 */
private fun Scope.liveWsFactory(): suspend () -> GatewayWsClient {
    val manager = get<GatewayConnectionManagerImpl>()
    return { manager.requireClient().openWebSocket() }
}
```

- [ ] **Step 6: Verify the manual header is gone**

Run: `git grep -n "Authorization" -- app/src/main/kotlin/com/openclaw/ghostcrab/data/ws`
Expected: no output.

- [ ] **Step 7: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/ws/GatewayWsClient.kt app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientErrorMappingTest.kt
git commit -m "refactor(ws): open sockets via the session client and share one DI factory"
```

---

### Task 16: Never overwrite saved profiles that cannot be decoded; guard `saveToken`

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/exception/GatewayException.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/storage/ConnectionProfileStore.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/ConnectionProfileStoreTest.kt`

Bug: corrupt or newer-schema profile JSON is swallowed to `emptyList()`; the next `saveProfile` writes `[newProfile]` and every saved profile is gone. The default `Json` also rejects unknown keys, so a downgrade is enough to trigger it. `saveToken` lets a Keystore failure escape untyped.

- [ ] **Step 1: Write the failing tests** (append inside `class ConnectionProfileStoreTest`)

```kotlin
    @Test
    fun `decodeProfilesForWrite refuses to overwrite undecodable data`() {
        assertThrows(ProfileStoreCorruptedException::class.java) { decodeProfilesForWrite("""[{"id":1}]""") }
    }

    @Test
    fun `decodeProfilesForRead tolerates unknown fields from a newer schema`() {
        val json = """[{"id":"a","displayName":"A","url":"http://10.0.0.2:18789",""" +
            """"lastConnectedAt":null,"hasToken":false,"pinned":true}]"""
        assertEquals(listOf("a"), decodeProfilesForRead(json).map { it.id })
    }

    @Test
    fun `decodeProfilesForRead shows nothing for corrupt data instead of throwing`() {
        assertEquals(emptyList<StoredProfile>(), decodeProfilesForRead("not json"))
    }

    @Test
    fun `saveToken maps a keystore failure to ProfileNeedsReauthException`() {
        val store = ConnectionProfileStore(mockk(relaxed = true)) { throw GeneralSecurityException("keystore gone") }
        assertThrows(ProfileNeedsReauthException::class.java) { runBlocking { store.saveToken("p1", "t") } }
    }
```
Add imports:
```kotlin
import com.openclaw.ghostcrab.data.storage.StoredProfile
import com.openclaw.ghostcrab.data.storage.decodeProfilesForRead
import com.openclaw.ghostcrab.data.storage.decodeProfilesForWrite
import com.openclaw.ghostcrab.domain.exception.ProfileStoreCorruptedException
import java.security.GeneralSecurityException
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.ConnectionProfileStoreTest"`
Expected: FAIL — `Unresolved reference: decodeProfilesForWrite`.

- [ ] **Step 3: Exceptions** — in `GatewayException.kt` replace `ProfileNeedsReauthException` with a cause-carrying version and append the new leaf:

```kotlin
class ProfileNeedsReauthException(
    val profileId: String,
    cause: Throwable? = null,
) : GatewayException("Stored token for profile $profileId is no longer valid. Please reconnect.", cause) {
    override val isRetryable: Boolean = false
}

/**
 * The saved-profiles store holds data this build cannot decode (corruption or a newer schema).
 * Writes are refused so that data is never overwritten.
 *
 * @param cause The decoding failure.
 */
class ProfileStoreCorruptedException(cause: Throwable) :
    GatewayException("Saved gateway profiles could not be read; refusing to overwrite them.", cause) {
    override val isRetryable: Boolean = false
}
```
(Keep the existing KDoc above `ProfileNeedsReauthException`.)

- [ ] **Step 4: Rewrite the decode paths in `ConnectionProfileStore.kt`**

Add imports `com.openclaw.ghostcrab.domain.exception.ProfileStoreCorruptedException` and `kotlinx.serialization.decodeFromString`; add below `KEY_PROFILES`:
```kotlin
/** Tolerates fields written by newer app versions; never used to swallow errors on write. */
private val profileJson = Json { ignoreUnknownKeys = true }

/** Decodes the stored list for display. Undecodable data reads as empty — and is never written back. */
internal fun decodeProfilesForRead(json: String?): List<StoredProfile> =
    json?.let { runCatching { profileJson.decodeFromString<List<StoredProfile>>(it) }.getOrNull() }.orEmpty()

/**
 * Decodes the stored list before a read-modify-write.
 *
 * @param json The raw stored value, or `null` when nothing was stored yet.
 * @return The stored profiles.
 * @throws ProfileStoreCorruptedException if non-null data cannot be decoded — the write must abort.
 */
internal fun decodeProfilesForWrite(json: String?): List<StoredProfile> = when (json) {
    null -> emptyList()
    else -> try {
        profileJson.decodeFromString<List<StoredProfile>>(json)
    } catch (e: IllegalArgumentException) { // SerializationException is an IllegalArgumentException
        throw ProfileStoreCorruptedException(e)
    }
}
```
Then:
- `getProfilesFlow()` body → `context.profileDataStore.data.map { prefs -> decodeProfilesForRead(prefs[KEY_PROFILES]) }`
- in `saveProfile`, replace the `val current = run { … }` block with `val current = decodeProfilesForWrite(prefs[KEY_PROFILES])` and `Json.encodeToString(updated)` with `profileJson.encodeToString(updated)`
- in `deleteProfile`, the same two replacements (`profileJson.encodeToString(current.filter { it.id != profileId })`)
- in `getToken`, `throw ProfileNeedsReauthException(profileId)` → `throw ProfileNeedsReauthException(profileId, e)`
- replace `saveToken` (KDoc included) with:
```kotlin
    /**
     * Stores [token] encrypted in the Android Keystore for [profileId]. Pass `null` to clear it.
     *
     * @throws ProfileNeedsReauthException if the Keystore / encrypted prefs cannot be opened.
     */
    suspend fun saveToken(profileId: String, token: String?) = withContext(Dispatchers.IO) {
        try {
            val editor = encryptedPrefs.edit()
            if (token == null) editor.remove(tokenKey(profileId)) else editor.putString(tokenKey(profileId), token)
            editor.apply()
        } catch (e: Exception) {
            throw ProfileNeedsReauthException(profileId, e)
        }
    }
```

- [ ] **Step 5: Run the store tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.ConnectionProfileStoreTest"`
Expected: PASS (8 tests).

- [ ] **Step 6: ⚠ manual (device/emulator)** — `./gradlew connectedDebugAndroidTest --tests "*ConnectionProfileStoreInstrumentedTest"` still passes (real DataStore + Keystore).

- [ ] **Step 7: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/domain/exception/GatewayException.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/storage/ConnectionProfileStore.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/ConnectionProfileStoreTest.kt
git commit -m "fix(storage): never overwrite undecodable saved profiles"
```

---

### Task 17: QR scanner — release camera resources, decode once, no unchecked `Activity` cast

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanViewModel.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanScreen.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/QrScanViewModelTest.kt`

Bugs: the analysis executor and ML Kit scanner are `remember`ed but never shut down (thread + native leak per visit); the analyzer fires per frame and every valid frame emits another navigation event; `context as Activity` throws under any `ContextWrapper`.

- [ ] **Step 1: Write the failing test** (append inside `class QrScanViewModelTest`)

```kotlin
    @Test
    fun `second decode after a valid one emits no second navigation`() = runTest {
        val vm = QrScanViewModel()
        vm.onPermissionGranted()

        vm.events.test {
            vm.onQrDecoded("http://192.168.1.50:18789")
            vm.onQrDecoded("http://192.168.1.50:18789")
            awaitItem()
            expectNoEvents()
        }
        assertEquals(QrScanUiState.Decoded, vm.uiState.value)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.QrScanViewModelTest"`
Expected: FAIL — `Unresolved reference: Decoded`.

- [ ] **Step 3: Add the `Decoded` state and the single-decode guard**

In `QrScanViewModel.kt` add to `QrScanUiState`:
```kotlin
    /** A valid code was accepted; further frames are ignored until [QrScanViewModel.onScanAgain]. */
    data object Decoded : QrScanUiState
```
and replace `onQrDecoded` with:
```kotlin
    fun onQrDecoded(raw: String) {
        if (_uiState.value == QrScanUiState.Decoded) return
        val url = raw.trim()
        val valid = (url.startsWith("http://") || url.startsWith("https://")) &&
            runCatching { java.net.URI(url) }.isSuccess
        if (!valid) {
            _uiState.value = QrScanUiState.InvalidQr("QR code doesn't contain a valid gateway URL")
            return
        }
        _uiState.value = QrScanUiState.Decoded
        viewModelScope.launch { _events.emit(QrScanEvent.NavigateToManualEntry(url)) }
    }
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.QrScanViewModelTest"`
Expected: PASS (9 tests).

- [ ] **Step 5: Fix the screen**

In `QrScanScreen.kt`:
1. Add imports `android.content.Context`, `android.content.ContextWrapper`, `androidx.compose.runtime.DisposableEffect`; remove `androidx.compose.runtime.rememberCoroutineScope` and `kotlinx.coroutines.launch`; delete the line `val scope = rememberCoroutineScope()`.
2. In the permission callback replace the `else` branch with
```kotlin
        } else {
            val activity = context.findActivity()
            val isPermanent = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
            viewModel.onPermissionDenied(isPermanent)
        }
```
3. Replace the `LaunchedEffect(uiState) { … }` block with
```kotlin
    LaunchedEffect(uiState) {
        val invalid = uiState as? QrScanUiState.InvalidQr
        if (invalid != null) {
            snackbarHostState.showSnackbar(invalid.message)
            viewModel.onScanAgain()
        }
    }
```
4. In `CameraPreview`, directly after the `val scanner = remember { … }` block, add
```kotlin
    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            scanner.close()
        }
    }
```
5. Append to the file:
```kotlin
/** Walks [ContextWrapper] layers to the hosting [Activity], or `null` (e.g. in previews). */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
```

- [ ] **Step 6: Verify the unchecked cast is gone**

Run: `git grep -n "as Activity" -- app/src/main/kotlin`
Expected: no output.

- [ ] **Step 7: Full gate, ⚠ manual check, commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
⚠ manual (device): open/close the QR screen 10×; LeakCanary (debug build) reports no retained `ImageAnalysis`/executor; scanning one code navigates exactly once.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanViewModel.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanScreen.kt app/src/test/kotlin/com/openclaw/ghostcrab/ui/QrScanViewModelTest.kt
git commit -m "fix(qr): release camera resources and navigate once per scan"
```

---

### Task 18: Inject the IO dispatcher instead of hard-coding `Dispatchers.IO`

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/GatewayConnectionManagerImpl.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/storage/ConnectionProfileStore.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/GatewayConnectionManagerImplTest.kt`

- [ ] **Step 1: Write the failing test** (append inside `class GatewayConnectionManagerImplTest`, plus the helper class at file end)

```kotlin
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `connect runs on the injected IO dispatcher`() = runTest {
        val io = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val mgr = GatewayConnectionManagerImpl(factory, ioDispatcher = io)
        coEvery { probeClient.health() } returns HealthResponse("ok")
        coEvery { probeClient.status() } returns statusOk

        mgr.connect(url)

        assertTrue(io.dispatches > 0)
    }
```
```kotlin
private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    var dispatches = 0
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatches++
        delegate.dispatch(context, block)
    }
}
```
Add imports `kotlinx.coroutines.CoroutineDispatcher`, `kotlinx.coroutines.ExperimentalCoroutinesApi`, `kotlinx.coroutines.test.StandardTestDispatcher`, `kotlin.coroutines.CoroutineContext`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.GatewayConnectionManagerImplTest"`
Expected: FAIL — `No parameter with name 'ioDispatcher'`.

- [ ] **Step 3: Add the parameter**

`GatewayConnectionManagerImpl.kt`: add `import kotlinx.coroutines.CoroutineDispatcher`; add a third constructor parameter
```kotlin
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
```
and replace both `withContext(Dispatchers.IO)` with `withContext(ioDispatcher)`. Add `@param ioDispatcher Dispatcher for blocking client work; injected via the Koin IoDispatcher qualifier.` to the class KDoc.

`ConnectionProfileStore.kt`: add `import kotlinx.coroutines.CoroutineDispatcher`; insert as the **second** constructor parameter (so the trailing-lambda factory stays last)
```kotlin
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
```
and replace both `withContext(Dispatchers.IO)` with `withContext(ioDispatcher)`.

- [ ] **Step 4: Provide it from Koin** — in `DataModule.kt` add imports `kotlinx.coroutines.CoroutineDispatcher`, `kotlinx.coroutines.Dispatchers`, `org.koin.core.qualifier.named`; above `val dataModule` add
```kotlin
/** Koin qualifier for the blocking-IO dispatcher. Inject it; never hard-code `Dispatchers.IO` in data classes. */
val IoDispatcher = named("io")
```
and inside the module, as the first line, `single<CoroutineDispatcher>(IoDispatcher) { Dispatchers.IO }`; change
`single { ConnectionProfileStore(androidContext()) }` → `single { ConnectionProfileStore(androidContext(), ioDispatcher = get(IoDispatcher)) }` and
`single { GatewayConnectionManagerImpl(settingsRepository = get()) }` → `single { GatewayConnectionManagerImpl(settingsRepository = get(), ioDispatcher = get(IoDispatcher)) }`.

- [ ] **Step 5: Verify no hard-coded IO dispatcher remains in data classes**

Run: `git grep -n "Dispatchers.IO" -- app/src/main/kotlin`
Expected: only the default-parameter lines in `GatewayConnectionManagerImpl.kt` and `ConnectionProfileStore.kt`, and the Koin binding in `DataModule.kt`.

- [ ] **Step 6: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/GatewayConnectionManagerImpl.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/storage/ConnectionProfileStore.kt app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/GatewayConnectionManagerImplTest.kt
git commit -m "refactor(di): inject the IO dispatcher through Koin"
```

---

### Task 19: The cleartext opt-in applies to the live session immediately

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt` (factories)
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/GatewayConnectionManagerImpl.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/GatewayConnectionManagerImplTest.kt`

Bug: the opt-in is snapshotted as a `Boolean` when the client is built; turning it **off** in Settings does not affect the running session.

- [ ] **Step 1: Write the failing test** (append inside `class GatewayConnectionManagerImplTest`)

```kotlin
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `turning the cleartext opt-in off applies to the live session`() = runTest {
        val setting = MutableStateFlow(true)
        val settings = object : SettingsRepository {
            override val allowCleartextPublicIPs: Flow<Boolean> = setting
            override suspend fun setAllowCleartextPublicIPs(enabled: Boolean) { setting.value = enabled }
        }
        val policy = slot<() -> Boolean>()
        every { factory.authenticated(any(), any(), capture(policy)) } returns sessionClient
        coEvery { probeClient.health() } returns HealthResponse("ok")
        coEvery { probeClient.status() } throws GatewayAuthException(url, 401)
        coEvery { sessionClient.status() } returns statusOk
        val mgr = GatewayConnectionManagerImpl(factory, settings, UnconfinedTestDispatcher(testScheduler))

        mgr.connect(url, token)
        assertTrue(policy.captured())

        setting.value = false
        assertFalse(policy.captured())
    }
```
Add imports `com.openclaw.ghostcrab.domain.repository.SettingsRepository`, `io.mockk.slot`, `kotlinx.coroutines.flow.Flow`, `kotlinx.coroutines.flow.MutableStateFlow`, `kotlinx.coroutines.test.UnconfinedTestDispatcher`, `org.junit.jupiter.api.Assertions.assertFalse`.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.GatewayConnectionManagerImplTest"`
Expected: FAIL — type mismatch: `capture(policy)` expects `() -> Boolean`, factory takes `Boolean`.

- [ ] **Step 3: Pass a live policy into the clients**

`OpenClawApiClient.kt` companion: change both factory parameters to `allowCleartextPublicIPs: () -> Boolean = { false },` (update the KDoc: "read on every request"), change `buildHttpClient`'s parameter to `allowCleartextPublicIPs: () -> Boolean` and its interceptor line to `addInterceptor(CleartextPublicIpInterceptor(allowCleartextPublicIPs))`.

`GatewayConnectionManagerImpl.kt`: replace the factory interface and default object with
```kotlin
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
Add imports `java.util.concurrent.atomic.AtomicBoolean`, `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.SupervisorJob`, `kotlinx.coroutines.launch`. In the class add below `private val mutex = Mutex()`:
```kotlin
    /** Live copy of the user's cleartext opt-in; every request's interceptor reads it. */
    private val allowCleartext = AtomicBoolean(false)

    init {
        // App-lifetime singleton: keep the opt-in current so a Settings change applies to the live session.
        CoroutineScope(SupervisorJob() + ioDispatcher).launch {
            settingsRepository.allowCleartextPublicIPs.collect { allowCleartext.set(it) }
        }
    }

    /** Reads the current opt-in before building clients, so the first request never races the collector. */
    private suspend fun primeCleartextSetting() {
        allowCleartext.set(settingsRepository.allowCleartextPublicIPs.firstOrNull() ?: false)
    }
```
In `probeAuth` replace the line `val allowCleartext = settingsRepository.allowCleartextPublicIPs.firstOrNull() ?: false` with `primeCleartextSetting()` and the factory call with `clientFactory.unauthenticated(url, allowCleartext::get)`. In `connect` delete the line `val allowCleartext = settingsRepository.allowCleartextPublicIPs.firstOrNull() ?: false` (`probeAuth` primes it) and pass `allowCleartext::get` to both factory calls.

- [ ] **Step 4: Run the manager tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.GatewayConnectionManagerImplTest"`
Expected: PASS (15 tests).

- [ ] **Step 5: Full gate, ⚠ manual check, commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
⚠ manual (device + gateway on a public IP over http): with the opt-in on, connect; turn it off in Settings; the next Dashboard poll fails with the CleartextBlocked message.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/GatewayConnectionManagerImpl.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/GatewayConnectionManagerImplTest.kt
git commit -m "fix(security): apply cleartext opt-in changes to the live session"
```

---

### Task 20: Secret hygiene in the UI — masked token field, redacted diff, no token in saved state

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/components/StringFieldRow.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/sections/GatewaySectionEditor.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/PendingChangesDiffSheet.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/settings/SettingsScreen.kt` (`EditProfileDialog`)
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/config/GatewayAuthSection.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/domain/GatewaySectionTest.kt`

- [ ] **Step 1: Write the failing test** (append inside `class GatewaySectionTest`)

```kotlin
    @Test fun `GatewayAuthSection toString never contains the token`() {
        val text = GatewayAuthSection(mode = AuthMode.bearer, token = "s3cr3t").toString()
        assertTrue(text.contains("[REDACTED]"))
        assertTrue(!text.contains("s3cr3t"))
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.GatewaySectionTest"`
Expected: FAIL — default data-class `toString` contains `s3cr3t`.

- [ ] **Step 3: Redact `GatewayAuthSection.toString`** — add `import com.openclaw.ghostcrab.domain.util.REDACTED_PLACEHOLDER` and give the class a body:

```kotlin
@Serializable
data class GatewayAuthSection(
    val mode: AuthMode = AuthMode.none,
    val token: String? = null,
) {
    /** Redacts [token] so the section can be logged or dumped without leaking the bearer. */
    override fun toString(): String =
        "GatewayAuthSection(mode=$mode, token=${token?.let { REDACTED_PLACEHOLDER }})"
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.GatewaySectionTest"`
Expected: PASS (10 tests).

- [ ] **Step 5: Masked field with a reveal toggle** — add to `strings.xml`:

```xml
    <string name="field_secret_show">Show value</string>
    <string name="field_secret_hide">Hide value</string>
```
In `StringFieldRow.kt` add imports `androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.filled.Visibility`, `androidx.compose.material.icons.filled.VisibilityOff`, `androidx.compose.material3.Icon`, `androidx.compose.material3.IconButton`, `androidx.compose.runtime.getValue`, `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.remember`, `androidx.compose.runtime.setValue`, `androidx.compose.ui.res.stringResource`, `androidx.compose.ui.text.input.PasswordVisualTransformation`, `androidx.compose.ui.text.input.VisualTransformation`, `com.openclaw.ghostcrab.R`. Add `* @param secret When true, masks the value and shows a reveal toggle (tokens, keys).` to the KDoc, add the parameter `secret: Boolean = false,` after `error`, add `var revealed by remember { mutableStateOf(false) }` as the first line of the body; then add these two arguments to `OutlinedTextField` (after `singleLine = singleLine,`):
```kotlin
                visualTransformation = if (secret && !revealed) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = if (secret) {
                    { SecretVisibilityToggle(revealed = revealed, onToggle = { revealed = !revealed }) }
                } else {
                    null
                },
```
and append to the file (a separate composable keeps `StringFieldRow` under detekt's 60-line `LongMethod` limit):
```kotlin
@Composable
private fun SecretVisibilityToggle(revealed: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle) {
        Icon(
            imageVector = if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = stringResource(if (revealed) R.string.field_secret_hide else R.string.field_secret_show),
        )
    }
}
```
In `GatewaySectionEditor.kt` add `secret = true,` to the `Bearer Token` `StringFieldRow` (after `mono = true,`).

- [ ] **Step 6: Redact the diff sheet** — in `PendingChangesDiffSheet.kt` add `import com.openclaw.ghostcrab.domain.util.redactSecrets` and change the two text lines to

```kotlin
    // Secrets never render in clear, even in a confirmation sheet.
    val oldText = prettyJson.encodeToString(JsonElement.serializer(), oldValue.redactSecrets())
    val newText = prettyJson.encodeToString(JsonElement.serializer(), newValue.redactSecrets())
```

- [ ] **Step 7: Keep the new token out of the saved-instance Bundle** — in `SettingsScreen.kt` add `import androidx.compose.runtime.remember` and in `EditProfileDialog` change
`var newToken by rememberSaveable { mutableStateOf("") }` → `var newToken by remember { mutableStateOf("") } // never rememberSaveable: Bundles are persisted`

- [ ] **Step 8: Full gate, ⚠ manual check, commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
⚠ manual (emulator): gateway section with `auth.mode = bearer` shows dots + eye toggle; the diff sheet shows `[REDACTED]` for the token.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ app/src/main/res/values/strings.xml app/src/test/kotlin/com/openclaw/ghostcrab/domain/GatewaySectionTest.kt
git commit -m "fix(security): mask and redact tokens across config and settings UI"
```

---

### Task 21a: `GatewayUrl` — one IPv6-safe parser/formatter for gateway URLs

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/util/GatewayUrl.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/DiscoveredGateway.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/model/config/GatewayHttpSection.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/domain/GatewayUrlTest.kt`

Problems: URL parsing/validation is duplicated in ManualEntry, QrScan and `DiscoveredGateway`; NSD can resolve IPv6 (`fe80::1%wlan0`) and `"http://$hostAddress:$port"` is then an invalid URL; port 18789 lives as a *String* const in the UI package; an `https://` tunnel URL without a port was pre-filled with 18789 instead of 443.

- [ ] **Step 1: Write the failing tests** — create `app/src/test/kotlin/com/openclaw/ghostcrab/domain/GatewayUrlTest.kt`:

```kotlin
package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.model.DiscoveredGateway
import com.openclaw.ghostcrab.domain.util.DEFAULT_GATEWAY_PORT
import com.openclaw.ghostcrab.domain.util.GatewayUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GatewayUrlTest {

    @Test fun `parses an http url with explicit port`() {
        assertEquals(GatewayUrl("http", "192.168.1.50", 9000), GatewayUrl.parse("http://192.168.1.50:9000"))
    }

    @Test fun `missing port defaults to 18789 for http and 443 for https`() {
        assertEquals(GatewayUrl("http", "gw.local", DEFAULT_GATEWAY_PORT), GatewayUrl.parse("http://gw.local"))
        assertEquals(GatewayUrl("https", "gw.example", 443), GatewayUrl.parse(" HTTPS://gw.example/ "))
    }

    @Test fun `rejects non-http schemes, userinfo and garbage`() {
        listOf("ftp://h:1", "http://user:pw@h:1", "just text", "").forEach { assertNull(GatewayUrl.parse(it), it) }
    }

    @Test fun `ipv6 literals are bracketed and zone ids stripped`() {
        assertEquals("http://[fe80::1]:18789", GatewayUrl.of("fe80::1%wlan0", 18789).toString())
        assertEquals(GatewayUrl("http", "fe80::1", 18789), GatewayUrl.parse("http://[fe80::1]:18789"))
    }

    @Test fun `discovered IPv6 gateway produces a valid URL`() {
        val gw = DiscoveredGateway("gw", "fe80::1%wlan0", 18789, "gw", null)
        assertEquals("http://[fe80::1]:18789", gw.url)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.GatewayUrlTest"`
Expected: FAIL — `Unresolved reference: GatewayUrl`.

- [ ] **Step 3: Create `domain/util/GatewayUrl.kt`**

```kotlin
package com.openclaw.ghostcrab.domain.util

import java.net.URI

/** Default OpenClaw gateway port. */
const val DEFAULT_GATEWAY_PORT: Int = 18789

private const val HTTPS_DEFAULT_PORT = 443
private const val MAX_PORT = 65535

/**
 * A validated gateway base URL: `http`/`https`, a host, an explicit port. Path, query and fragment
 * of the input are ignored; userinfo is rejected (it would smuggle credentials into the URL).
 *
 * @property scheme `http` or `https`.
 * @property host Hostname or IP literal; IPv6 literals are stored **without** brackets.
 * @property port TCP port, 1–65535.
 */
data class GatewayUrl(val scheme: String, val host: String, val port: Int) {

    /** Canonical form, bracketing IPv6 literals: `http://[fe80::1]:18789`. */
    override fun toString(): String {
        val hostPart = if (host.contains(':')) "[$host]" else host
        return "$scheme://$hostPart:$port"
    }

    companion object {
        /**
         * Parses [raw]. A missing port defaults to [DEFAULT_GATEWAY_PORT] for `http` and 443 for `https`
         * (tunnels and reverse proxies).
         *
         * @param raw User-, QR- or deep-link-supplied URL text.
         * @return The parsed URL, or `null` if [raw] is not an `http(s)` URL with a host and no userinfo.
         */
        fun parse(raw: String): GatewayUrl? {
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" }
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() }
            return if (scheme != null && host != null && uri.userInfo == null) {
                val defaultPort = if (scheme == "https") HTTPS_DEFAULT_PORT else DEFAULT_GATEWAY_PORT
                GatewayUrl(scheme, host, uri.port.takeIf { it in 1..MAX_PORT } ?: defaultPort)
            } else {
                null
            }
        }

        /**
         * Builds a URL for a resolved `(host, port)` pair, e.g. from mDNS. Strips an IPv6 zone id
         * (`%wlan0`), which is not valid in a URL authority.
         *
         * @param host Resolved host address.
         * @param port Resolved port.
         * @param https Whether to use `https`.
         * @return The URL.
         */
        fun of(host: String, port: Int, https: Boolean = false): GatewayUrl =
            GatewayUrl(if (https) "https" else "http", host.substringBefore('%'), port)
    }
}
```

- [ ] **Step 4: Use it in the domain models**

`DiscoveredGateway.kt`: add `import com.openclaw.ghostcrab.domain.util.GatewayUrl` and change the property to
```kotlin
    /** Constructed base URL (IPv6-safe) for [com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager]. */
    val url: String get() = GatewayUrl.of(hostAddress, port).toString()
```
`GatewayHttpSection.kt`: add `import com.openclaw.ghostcrab.domain.util.DEFAULT_GATEWAY_PORT` and change `val port: Int = 18789,` to `val port: Int = DEFAULT_GATEWAY_PORT,`.

- [ ] **Step 5: Run the domain test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.GatewayUrlTest"`
Expected: PASS (5 tests).


- [ ] **Step 6: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/domain/ app/src/test/kotlin/com/openclaw/ghostcrab/domain/GatewayUrlTest.kt
git commit -m "feat(domain): add IPv6-safe GatewayUrl and use it for discovered gateways"
```

---

### Task 21b: ManualEntry and QR scanning use `GatewayUrl`

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ManualEntryViewModel.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/QrScanViewModel.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/ManualEntryViewModelTest.kt`

- [ ] **Step 1: Write the failing ManualEntry tests**

In `ManualEntryViewModelTest.kt` run:
```bash
F=app/src/test/kotlin/com/openclaw/ghostcrab/ui/ManualEntryViewModelTest.kt
sed -i -e 's/import com.openclaw.ghostcrab.ui.connection.DEFAULT_GATEWAY_PORT/import com.openclaw.ghostcrab.domain.util.DEFAULT_GATEWAY_PORT/' \
       -e 's/assertEquals(DEFAULT_GATEWAY_PORT,/assertEquals(DEFAULT_GATEWAY_PORT.toString(),/' "$F"
```
and append inside the class:
```kotlin
    @Test
    fun `IPv6 prefill keeps the bare host and assembles a bracketed URL`() {
        vm.setPrefillUrl("http://[fe80::1]:18789")
        assertEquals("fe80::1", vm.form.value.host)
        assertEquals("http://[fe80::1]:18789", vm.form.value.assembledUrl)
    }

    @Test
    fun `IPv6 literal host passes validation and connects`() = runTest {
        coEvery { connectionManager.connect(any(), any()) } returns Unit
        vm.setPrefillUrl("http://[fe80::1]:18789")

        vm.connect()

        assertNull(vm.form.value.hostError)
        coVerify { connectionManager.connect("http://[fe80::1]:18789", null) }
    }

    @Test
    fun `https prefill without port defaults to 443`() {
        vm.setPrefillUrl("https://abc-xyz.trycloudflare.com")
        assertEquals("443", vm.form.value.port)
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.ManualEntryViewModelTest"`
Expected: FAIL — compile error (`DEFAULT_GATEWAY_PORT` is a String in the UI package and an Int in the domain) and, once compiling, the three new tests.

- [ ] **Step 3: Rewire `ManualEntryViewModel.kt`**

1. Delete `const val DEFAULT_GATEWAY_PORT = "18789"` and `import java.net.URI`; add `import com.openclaw.ghostcrab.domain.util.DEFAULT_GATEWAY_PORT` and `import com.openclaw.ghostcrab.domain.util.GatewayUrl`.
2. In `ManualEntryFormState`: `val port: String = DEFAULT_GATEWAY_PORT,` → `val port: String = DEFAULT_GATEWAY_PORT.toString(),` and replace `assembledUrl` with
```kotlin
    val assembledUrl: String
        get() {
            val h = host.trim()
            return "$scheme://${if (h.contains(':')) "[$h]" else h}:${port.trim()}"
        }
```
3. Replace `setPrefillUrl` with
```kotlin
    /** Parses a full URL (LAN scan, QR) and populates scheme/host/port individually. */
    fun setPrefillUrl(url: String) {
        val parsed = GatewayUrl.parse(url)
        _form.update {
            it.copy(
                useHttps = parsed?.scheme == "https",
                host = parsed?.host.orEmpty(),
                port = (parsed?.port ?: DEFAULT_GATEWAY_PORT).toString(),
                hostError = null,
                portError = null,
            )
        }
    }
```
4. Move `validateHost` and `validatePort` **out of the class** (keeps the class under the detekt function limit) as private top-level functions at the end of the file, with the IPv6 rule added:
```kotlin
@Suppress("ReturnCount")
private fun validateHost(host: String): String? {
    val trimmed = host.trim()
    if (trimmed.isEmpty()) return "Host is required"
    if (trimmed.contains("://")) return "Remove scheme — use the HTTPS toggle instead"
    if (trimmed.contains('/')) return "Host must not contain '/'"
    // IPv6 literal (from LAN discovery or QR): only hex digits, dots and ≥ 2 colons, no brackets.
    if (trimmed.count { it == ':' } >= 2 && trimmed.all { it.isLetterOrDigit() || it == ':' || it == '.' }) return null
    if (trimmed.contains(':')) return "Port goes in the Port field, not the Host field"
    if (trimmed.any { it.isWhitespace() }) return "Host must not contain whitespace"
    // '@' would smuggle userinfo into the assembled URL; '?' and '#' would truncate the host.
    if (trimmed.any { it in "@?#\\" }) return "Host must not contain '@', '?', '#' or '\\'"
    return null
}

@Suppress("ReturnCount")
private fun validatePort(port: String): String? {
    val trimmed = port.trim()
    if (trimmed.isEmpty()) return "Port is required"
    val n = trimmed.toIntOrNull() ?: return "Port must be a number"
    if (n < 1 || n > 65535) return "Port must be between 1 and 65535"
    return null
}
```

- [ ] **Step 4: Use it in `QrScanViewModel.onQrDecoded`** — add `import com.openclaw.ghostcrab.domain.util.GatewayUrl` and replace the two-line `val valid = …` expression with `val valid = GatewayUrl.parse(url) != null`.

- [ ] **Step 5: Run all unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ app/src/test/kotlin/com/openclaw/ghostcrab/ui/ManualEntryViewModelTest.kt
git commit -m "refactor: centralize gateway URL parsing in an IPv6-safe GatewayUrl"
```

---

### Task 22: `OpenClawApiClient` — one JSON content-type check, named timeouts

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientTest.kt`

Pure refactor (upstream quirk: HTML at `/status` and `/config`). The content-type sniff is copied three times and the timeouts are magic numbers repeated in KDoc.

- [ ] **Step 1: Pin current behaviour with a characterization test** (append inside `class OpenClawApiClientTest`)

```kotlin
    @Test fun `getModels treats an HTML answer as no models`() = runTest {
        val engine = MockEngine {
            respond("<html></html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
        }
        val client = OpenClawApiClient.forTest(BASE, HttpClient(engine))
        assertTrue(client.getModels().isEmpty())
    }
```

- [ ] **Step 2: Run the client tests — they pass before the refactor**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.OpenClawApiClientTest"`
Expected: PASS (32 tests).

- [ ] **Step 3: Refactor**

Add at the bottom of the file, next to `mapErrors`:
```kotlin
/** `true` when the gateway answered with a JSON content type (upstream serves HTML at /status and /config). */
private fun io.ktor.client.statement.HttpResponse.isJson(): Boolean =
    headers[HttpHeaders.ContentType].orEmpty().contains("json", ignoreCase = true)
```
Then:
- `status()`: replace the `val contentType = …` line and the `if/else` with `if (response.isJson()) response.body() else StatusResponse()`
- `getConfig()`: replace the `val contentType = …` line and the `if/else` with
```kotlin
        if (response.isJson()) {
            val body: JsonObject = response.body()
            body to response.headers[HttpHeaders.ETag]
        } else {
            emptyMap<String, JsonElement>() to null
        }
```
- `getModels()`: replace the `val contentType = …` line and the `if/else` with `if (response.isJson()) response.body() else emptyList()`
- In the companion object add
```kotlin
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 30_000L
        private const val SOCKET_TIMEOUT_MS = 30_000L
```
and use them in `install(HttpTimeout) { … }`; change the class KDoc line `* Timeouts: connect = 15s, request = 30s.` to `* Timeouts: [CONNECT_TIMEOUT_MS], [REQUEST_TIMEOUT_MS], [SOCKET_TIMEOUT_MS].`

- [ ] **Step 4: Verify the sniff exists once**

Run: `git grep -c "contains(\"json\"" -- app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt`
Expected: `app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt:1`

- [ ] **Step 5: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/api/OpenClawApiClient.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/OpenClawApiClientTest.kt
git commit -m "refactor(api): single JSON content-type check and named timeouts"
```

---

### Task 23: Config editor keeps other sections' pending edits; no ViewModel passed down

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/ConfigEditorViewModel.kt` (`confirmSave`)
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/ConfigEditorScreen.kt` (`ReadyContent` + call site)
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/ConfigEditorViewModelTest.kt`

Bug: saving section A replaces the state with a fresh `Ready(config, saveSuccess = true)`, silently dropping unsaved edits in every other section. `ReadyContent(state, viewModel)` also hands the VM to a child composable.

- [ ] **Step 1: Write the failing test** (append inside `class ConfigEditorViewModelTest`)

```kotlin
    @Test
    fun `confirmSave keeps pending edits of other sections`() = runTest(testDispatcher) {
        val twoSections = sampleConfig.copy(
            sections = sampleConfig.sections + ("models" to JsonObject(mapOf("timeout_ms" to JsonPrimitive(1)))),
        )
        val repo = FakeConfigRepository(configToReturn = twoSections)
        val (vm, _) = makeVm(initialConnection = connectedState, repo = repo)
        advanceUntilIdle()
        val modelsEdit = JsonObject(mapOf("timeout_ms" to JsonPrimitive(2)))

        vm.editSection("gateway", sampleEdit)
        vm.editSection("models", modelsEdit)
        vm.confirmSave("gateway")
        advanceUntilIdle()

        val state = vm.state.value as ConfigEditorUiState.Ready
        assertEquals(mapOf("models" to modelsEdit), state.pendingChanges)
        assertTrue(state.saveSuccess)
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.ConfigEditorViewModelTest"`
Expected: FAIL — `expected: <{models={"timeout_ms":2}}> but was: <{}>`.

- [ ] **Step 3: Carry the other sections over** — in `confirmSave`, replace
```kotlin
                val updated = configRepository.getConfig()
                _state.value = ConfigEditorUiState.Ready(
                    config = updated,
                    saveSuccess = true,
                )
```
with
```kotlin
                val updated = configRepository.getConfig()
                // Read the latest state: the user may have edited other sections while saving.
                val latest = _state.value as? ConfigEditorUiState.Ready ?: current
                _state.value = ConfigEditorUiState.Ready(
                    config = updated,
                    pendingChanges = latest.pendingChanges - section,
                    fieldErrors = latest.fieldErrors.filterKeys { !it.startsWith("$section.") },
                    saveSuccess = true,
                )
```
Update the KDoc line that begins `On success: transitions to a fresh [ConfigEditorUiState.Ready]` to read `On success: reloads config, keeps pending edits of other sections, sets saveSuccess.`

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.ConfigEditorViewModelTest"`
Expected: PASS (12 tests).

- [ ] **Step 5: Hoist callbacks out of `ReadyContent`** — in `ConfigEditorScreen.kt` change the `ReadyContent` signature to
```kotlin
@Composable
private fun ReadyContent(
    state: ConfigEditorUiState.Ready,
    onEdit: (section: String, value: kotlinx.serialization.json.JsonElement) -> Unit,
    onFieldError: (field: String, error: String?) -> Unit,
    onSave: (section: String) -> Unit,
    onDiscard: (section: String) -> Unit,
    modifier: Modifier = Modifier,
) {
```
inside it replace `viewModel.editSection(sectionKey, newValue)` → `onEdit(sectionKey, newValue)`, `viewModel.setFieldError(field, err)` → `onFieldError(field, err)`, `viewModel.requestSave(sectionKey)` → `onSave(sectionKey)`, `viewModel.discardSection(sectionKey)` → `onDiscard(sectionKey)`; and change the call site to
```kotlin
                    ReadyContent(
                        state = s,
                        onEdit = viewModel::editSection,
                        onFieldError = viewModel::setFieldError,
                        onSave = viewModel::requestSave,
                        onDiscard = viewModel::discardSection,
                        modifier = Modifier.fillMaxSize(),
                    )
```

- [ ] **Step 6: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/ConfigEditorViewModel.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/ConfigEditorScreen.kt app/src/test/kotlin/com/openclaw/ghostcrab/ui/ConfigEditorViewModelTest.kt
git commit -m "fix(config): keep other sections' pending edits after a save"
```

---

### Task 24: One-shot events never get lost; the picker uses state + events instead of a callback

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ManualEntryViewModel.kt`, `ScanViewModel.kt`, `QrScanViewModel.kt`, `ConnectionPickerViewModel.kt`, `ConnectionPickerScreen.kt` (all in `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/`)
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/ScanViewModelTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/ui/ConnectionPickerViewModelTest.kt`

Problems: `MutableSharedFlow()` (replay 0) drops an event emitted while nobody collects (rotation). `ConnectionPickerViewModel.connect(profile, onResult)` fires a callback into a possibly dead composition and keeps `connectingId` in composable-local state.

- [ ] **Step 1: Write the failing event test** (append inside `class ScanViewModelTest`)

```kotlin
    @Test
    fun `navigation event sent while nobody collects is delivered later`() = runTest {
        val vm = makeVm()
        vm.onGatewaySelected(gateway()) // no collector attached, e.g. mid-rotation

        vm.events.test {
            assertInstanceOf(ScanEvent.NavigateToManualEntry::class.java, awaitItem())
        }
    }
```

- [ ] **Step 2: Rewrite the picker tests** — in `ConnectionPickerViewModelTest.kt` add imports `app.cash.turbine.test`, `com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException`, `com.openclaw.ghostcrab.ui.connection.ConnectionPickerEvent`, `org.junit.jupiter.api.Assertions.assertNull`, and replace the three `connect …` tests with:

```kotlin
    @Test
    fun `connect without token calls connectionManager with null token and navigates`() = runTest {
        val p = profile(hasToken = false)
        val (vm, _, manager) = makeVm(profiles = listOf(p))

        vm.events.test {
            vm.connect(p)
            assertEquals(ConnectionPickerEvent.NavigateToDashboard, awaitItem())
        }
        assertEquals(listOf(Pair(p.url, null as String?)), manager.connectCalls)
        assertNull(vm.uiState.value.connectingProfileId)
    }

    @Test
    fun `connect with token retrieves token then passes it to connectionManager`() = runTest {
        val p = profile(id = "tok-id", hasToken = true)
        val (vm, repo, manager) = makeVm(profiles = listOf(p))
        repo.tokens["tok-id"] = "secret-token"

        vm.connect(p)

        assertEquals(listOf(Pair(p.url, "secret-token")), manager.connectCalls)
    }

    @Test
    fun `connect failure surfaces errorMessage and clears connecting state`() = runTest {
        val p = profile()
        val manager = object : FakeCpConnectionManager() {
            override suspend fun connect(url: String, token: String?) {
                throw GatewayUnreachableException(url)
            }
        }
        val vm = ConnectionPickerViewModel(FakeCpProfileRepo(listOf(p)), manager, FakeCpOnboardingRepo(true))

        vm.connect(p)

        val state = vm.uiState.value
        assertNull(state.connectingProfileId)
        assertTrue(state.errorMessage.orEmpty().contains("GatewayUnreachableException"))
        vm.clearError()
        assertNull(vm.uiState.value.errorMessage)
    }
```
(Remove the now-unused `assertNotNull` import.)

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.ScanViewModelTest" --tests "com.openclaw.ghostcrab.ui.ConnectionPickerViewModelTest"`
Expected: FAIL — picker: `Unresolved reference: ConnectionPickerEvent`; scan: `No value produced in 3s`.

- [ ] **Step 4: Channel-backed events in the three ViewModels**

```bash
C=app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection
for f in $C/ManualEntryViewModel.kt $C/ScanViewModel.kt $C/QrScanViewModel.kt; do
  sed -i \
    -e 's/private val _events = MutableSharedFlow<\(.*\)>()/private val _events = Channel<\1>(Channel.BUFFERED)/' \
    -e 's/val events: SharedFlow<\(.*\)> = _events.asSharedFlow()/val events: Flow<\1> = _events.receiveAsFlow()/' \
    -e 's/_events\.emit(/_events.send(/g' \
    -e '/^import kotlinx.coroutines.flow.MutableSharedFlow$/d' \
    -e '/^import kotlinx.coroutines.flow.SharedFlow$/d' \
    -e '/^import kotlinx.coroutines.flow.asSharedFlow$/d' \
    "$f"
  sed -i '0,/^import /s//import kotlinx.coroutines.channels.Channel\nimport kotlinx.coroutines.flow.Flow\nimport kotlinx.coroutines.flow.receiveAsFlow\nimport /' "$f"
done
```
Add a one-line KDoc above each `val events`: `/** One-shot navigation events, buffered so an event sent during rotation is delivered once a collector returns. */`

- [ ] **Step 5: Picker state + events** — in `ConnectionPickerViewModel.kt` add imports `com.openclaw.ghostcrab.domain.util.suspendRunCatching`, `com.openclaw.ghostcrab.ui.toUserMessage`, `kotlinx.coroutines.channels.Channel`, `kotlinx.coroutines.flow.Flow`, `kotlinx.coroutines.flow.receiveAsFlow`, `kotlinx.coroutines.flow.update`; add above the class:
```kotlin
/**
 * Transient picker state.
 *
 * @param connectingProfileId Profile whose connect is in flight, or `null`.
 * @param errorMessage Last connect failure, formatted for display; cleared by
 *   [ConnectionPickerViewModel.clearError].
 */
data class ConnectionPickerUiState(
    val connectingProfileId: String? = null,
    val errorMessage: String? = null,
)

/** One-shot navigation events for [ConnectionPickerScreen]. */
sealed interface ConnectionPickerEvent {
    /** Connected — show the dashboard. */
    data object NavigateToDashboard : ConnectionPickerEvent
}
```
and replace `connect(profile, onResult)` (KDoc included) with:
```kotlin
    private val _uiState = MutableStateFlow(ConnectionPickerUiState())

    /** Connect progress and errors; survives rotation. */
    public val uiState: StateFlow<ConnectionPickerUiState> = _uiState.asStateFlow()

    private val _events = Channel<ConnectionPickerEvent>(Channel.BUFFERED)

    /** One-shot navigation events, buffered across rotation. */
    public val events: Flow<ConnectionPickerEvent> = _events.receiveAsFlow()

    /**
     * Connects to [profile] with its stored token. Ignored while another connect is in flight.
     * Sends [ConnectionPickerEvent.NavigateToDashboard] on success; sets
     * [ConnectionPickerUiState.errorMessage] on failure.
     *
     * @param profile The profile to connect to.
     */
    public fun connect(profile: ConnectionProfile) {
        if (_uiState.value.connectingProfileId != null) return
        _uiState.value = ConnectionPickerUiState(connectingProfileId = profile.id)
        viewModelScope.launch {
            suspendRunCatching {
                val token = if (profile.hasToken) profileRepository.getToken(profile.id) else null
                connectionManager.connect(profile.url, token)
            }.onSuccess {
                _uiState.value = ConnectionPickerUiState()
                _events.send(ConnectionPickerEvent.NavigateToDashboard)
            }.onFailure { e ->
                _uiState.value = ConnectionPickerUiState(errorMessage = e.toUserMessage())
            }
        }
    }

    /** Clears [ConnectionPickerUiState.errorMessage] after the snackbar was shown. */
    public fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
```

- [ ] **Step 6: Screen** — in `ConnectionPickerScreen.kt` replace
```kotlin
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var connectingId by remember { mutableStateOf<String?>(null) }
```
with
```kotlin
    val pickerState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ConnectionPickerEvent.NavigateToDashboard -> onNavigateToDashboard()
            }
        }
    }
    LaunchedEffect(pickerState.errorMessage) {
        val message = pickerState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearError()
    }
```
and replace the whole `ProfileCard( … )` call with
```kotlin
                    ProfileCard(
                        profile = profile,
                        isConnecting = pickerState.connectingProfileId == profile.id,
                        onConnect = { viewModel.connect(profile) },
                        onDelete = { viewModel.delete(profile.id) },
                    )
```
Remove imports that are now unused (`rememberCoroutineScope`, `kotlinx.coroutines.launch`, and `mutableStateOf`/`setValue` if nothing else uses them — the compiler warns).

- [ ] **Step 7: Run all unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Full gate, ⚠ manual check, commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
⚠ manual (emulator): tap a saved profile, rotate while "connecting"; the spinner survives and the app lands on the Dashboard exactly once. `./gradlew connectedDebugAndroidTest --tests "*ConnectionPickerScreenTest"` passes.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/ app/src/test/kotlin/com/openclaw/ghostcrab/ui/ScanViewModelTest.kt app/src/test/kotlin/com/openclaw/ghostcrab/ui/ConnectionPickerViewModelTest.kt
git commit -m "refactor(ui): buffered one-shot events and state-driven connection picker"
```

---

### Task 25: Lifecycle-aware collection, injectable screen ViewModels, no `!!`

**Files:**
- Modify: every screen using `collectAsState()` under `app/src/main/kotlin/com/openclaw/ghostcrab/ui/` (10 files)
- Modify: `ui/airecommend/AIRecommendationScreen.kt`, `ui/config/ConfigEditorScreen.kt`, `ui/installedskills/InstalledSkillsScreen.kt`, `ui/model/ModelManagerScreen.kt`, `ui/settings/SettingsScreen.kt` (VM as default param)
- Modify: `ui/components/IntFieldRow.kt`, `ui/config/sections/RawJsonSectionEditor.kt`, `ui/connection/ManualEntryScreen.kt` (`!!`)
- Modify: `config/detekt-baseline.xml` (refresh)

`collectAsState()` keeps `WhileSubscribed` upstreams (DataStore) hot in the background; five screens resolve their VM inside the body so UI tests cannot inject a fake; six `!!` remain after null checks.

- [ ] **Step 1: Swap to `collectAsStateWithLifecycle`**

```bash
UI=app/src/main/kotlin/com/openclaw/ghostcrab/ui
FILES=$(git grep -l "\.collectAsState()" -- $UI)
sed -i -e 's/\.collectAsState()/.collectAsStateWithLifecycle()/g' \
       -e 's/^import androidx.compose.runtime.collectAsState$/import androidx.lifecycle.compose.collectAsStateWithLifecycle/' $FILES
git grep -n "collectAsState(" -- $UI | grep -v WithLifecycle
```
Expected output of the last command: nothing.

- [ ] **Step 2: ViewModels as default parameters** — apply the same shape to the five screens. Example for `AIRecommendationScreen.kt`; replace
```kotlin
fun AIRecommendationScreen(onNavigateBack: () -> Unit) {
    val viewModel: AIRecommendationViewModel = koinViewModel()
```
with
```kotlin
fun AIRecommendationScreen(
    onNavigateBack: () -> Unit,
    viewModel: AIRecommendationViewModel = koinViewModel(),
) {
```
and likewise:
- `ConfigEditorScreen(onNavigateBack: () -> Unit,)` + `val viewModel: ConfigEditorViewModel = koinViewModel()` → add `viewModel: ConfigEditorViewModel = koinViewModel(),` as the last parameter, delete the body line
- `InstalledSkillsScreen(onNavigateBack: () -> Unit)` + `val viewModel: InstalledSkillsViewModel = koinViewModel()` → parameter `viewModel: InstalledSkillsViewModel = koinViewModel(),`
- `public fun ModelManagerScreen(onNavigateBack: () -> Unit)` + `val viewModel: ModelManagerViewModel = koinViewModel()` → parameter `viewModel: ModelManagerViewModel = koinViewModel(),`
- `SettingsScreen(onNavigateBack: () -> Unit, onNavigateToInstalledSkills: () -> Unit = {},)` + `val viewModel: SettingsViewModel = koinViewModel()` → parameter `viewModel: SettingsViewModel = koinViewModel(),` after `onNavigateToInstalledSkills`

Verify: `git grep -n "val viewModel.*= koinViewModel()" -- app/src/main/kotlin` → no output.

- [ ] **Step 3: Remove the `!!`s**

`IntFieldRow.kt`: replace
```kotlin
            if (errorText != null) {
                Text(
                    text = errorText!!,
```
with
```kotlin
            errorText?.let { message ->
                Text(
                    text = message,
```
`RawJsonSectionEditor.kt`: same change with `parseError` (`if (parseError != null) {` / `text = parseError!!,` → `parseError?.let { message ->` / `text = message,`).
`ManualEntryScreen.kt`: replace
```kotlin
                        if (form.hostError != null) {
                            Text(form.hostError!!, color = BrandTokens.colorCrimsonError)
                        }
```
with `form.hostError?.let { Text(it, color = BrandTokens.colorCrimsonError) }`, and the `portError` block the same way.
Verify: `git grep -n '!!' -- app/src/main/kotlin` → no output.

- [ ] **Step 4: Refresh the detekt baseline for the re-signed screens**

Run: `./gradlew detekt`
Expected: FAIL with `LongMethod` for `AIRecommendationScreen`, `ConfigEditorScreen`, `ModelManagerScreen` and/or `SettingsScreen` (their signatures changed; bodies did not).
Run: `./gradlew detektBaseline` then `git diff config/detekt-baseline.xml`
Expected: only those `LongMethod` IDs replaced by the new-signature IDs.

- [ ] **Step 5: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/ config/detekt-baseline.xml
git commit -m "refactor(ui): lifecycle-aware state collection and injectable screen view models"
```

---

### Task 26: Split `AIRecommendationScreen.kt` (692 lines) into focused files

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/SkillUnavailableContent.kt`
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/RecommendationReadyContent.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/AIRecommendationScreen.kt`
- Modify: `config/detekt-baseline.xml`

Pure move — no behaviour change. Line numbers are as of 2026-10-13 and will have shifted; identify blocks by declaration.

- [ ] **Step 1: Create the two files with the header**

Each new file starts with
```kotlin
package com.openclaw.ghostcrab.ui.airecommend

```
followed by a verbatim copy of the entire `import` block of `AIRecommendationScreen.kt` (do not copy its `@file:Suppress` line).

- [ ] **Step 2: Move the skill-install UI** — cut from `AIRecommendationScreen.kt` everything from the comment `// ── Skill unavailable ──` (≈ line 322) through the closing brace of `private fun InstallErrorRow(` (just before the `ErrorContent` composable), i.e. `SkillUnavailableCopy`, `rememberSkillUnavailableCopy`, `SkillUnavailableContent`, `InstallButton`, `OpenBrowserButton`, `InstallProgressRow`, `InstallErrorRow`, and paste it into `SkillUnavailableContent.kt`. Change `private fun SkillUnavailableContent(` to `internal fun SkillUnavailableContent(` (it is called from the screen); everything else stays `private`.

- [ ] **Step 3: Move the result UI** — cut `ReadyContent` (its `@OptIn(ExperimentalLayoutApi::class)` and `@Composable` annotations through its closing brace, ≈ lines 586–692) into `RecommendationReadyContent.kt`, rename it `internal fun RecommendationReadyContent(` and update the single call site in `AIRecommendationScreen` (`ReadyContent(` → `RecommendationReadyContent(`).

- [ ] **Step 4: Compile**

Run: `./gradlew compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (unused-import warnings are fine; remove them with Android Studio → Code → Optimize Imports on the three files).

- [ ] **Step 5: Refresh the baseline for the moved functions**

Run: `./gradlew detekt` (expected: `LongMethod` for `RecommendationReadyContent`, previously baselined as `AIRecommendationScreen.kt$…ReadyContent`), then `./gradlew detektBaseline` and `git diff config/detekt-baseline.xml`.
Expected diff: the `ReadyContent` ID moves to `RecommendationReadyContent.kt$…RecommendationReadyContent(…)`; nothing else.

- [ ] **Step 6: Verify size**

Run: `wc -l app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/*.kt`
Expected: `AIRecommendationScreen.kt` < 400 lines.

- [ ] **Step 7: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/airecommend/ config/detekt-baseline.xml
git commit -m "refactor(ai): split AIRecommendationScreen into focused files"
```

---

### Task 27: Split `SettingsScreen.kt` (632 lines) and `ModelManagerScreen.kt` (460 lines)

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/settings/SettingsDialogs.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/ui/settings/SettingsSections.kt`
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/model/ModelManagerContent.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/settings/SettingsScreen.kt`, `app/src/main/kotlin/com/openclaw/ghostcrab/ui/model/ModelManagerScreen.kt`
- Modify: `config/detekt-baseline.xml`

Pure move. Call graph (verified 2026-10-13): `SettingsBody → ReadyContent → {ConnectionsSection, OnboardingSection, SkillsSection, SecuritySection, AboutSection}`; `ConnectionsSection → {CleartextRow, ProfileRow, SectionHeader}`; every section → `SectionHeader`; `AboutSection → AboutRow`; `SettingsBody → SettingsDialogs → {DeleteProfileDialog, ClearAllDialog, EditProfileDialog}`; `EditProfileDialog → outlinedTextFieldColors`.

- [ ] **Step 1: Create the three files** — `package com.openclaw.ghostcrab.ui.settings` (two files) / `package com.openclaw.ghostcrab.ui.model` (one file), each followed by a verbatim copy of its source screen's full `import` block.

- [ ] **Step 2: `SettingsDialogs.kt`** — move `SettingsDialogs`, `DeleteProfileDialog`, `ClearAllDialog`, `EditProfileDialog` and `outlinedTextFieldColors` (each with its annotations and KDoc). Make `SettingsDialogs` `internal`; the rest stay `private`.

- [ ] **Step 3: `SettingsSections.kt`** — move `ConnectionsSection`, `CleartextRow`, `OnboardingSection`, `SkillsSection`, `SecuritySection`, `AboutSection`, `ProfileRow`, `SectionHeader`, `AboutRow`. Make `ConnectionsSection`, `OnboardingSection`, `SkillsSection`, `SecuritySection`, `AboutSection` `internal`; the rest stay `private`. In `SettingsScreen.kt` change `private data class SettingsCallbacks(` to `internal data class SettingsCallbacks(` (used by `ConnectionsSection`).

- [ ] **Step 4: `ModelManagerContent.kt`** — move `ReadyContent`, `ModelCard`, `StatusPill` from `ModelManagerScreen.kt`; rename `ReadyContent` → `internal fun ModelManagerReadyContent(` and update its call site in `ModelManagerScreen`.

- [ ] **Step 5: Compile**

Run: `./gradlew compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. Then Optimize Imports on the five files.

- [ ] **Step 6: Refresh the baseline**

Run: `./gradlew detekt`, `./gradlew detektBaseline`, `git diff config/detekt-baseline.xml`.
Expected diff: `LongMethod` IDs for `EditProfileDialog` (→ `SettingsDialogs.kt`), `ModelCard` and `ReadyContent` (→ `ModelManagerContent.kt$…ModelManagerReadyContent`) move; `TooManyFunctions` for `SettingsScreen.kt` may disappear; nothing new.

- [ ] **Step 7: Verify sizes**

Run: `wc -l app/src/main/kotlin/com/openclaw/ghostcrab/ui/settings/*.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/model/*.kt`
Expected: `SettingsScreen.kt` < 300, `ModelManagerScreen.kt` < 220.

- [ ] **Step 8: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ui/settings/ app/src/main/kotlin/com/openclaw/ghostcrab/ui/model/ config/detekt-baseline.xml
git commit -m "refactor(ui): split SettingsScreen and ModelManagerScreen into focused files"
```

---

### Task 28: Settings and onboarding repositories take a `DataStore`, with JVM tests

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/SettingsRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/OnboardingRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/SettingsRepositoryImplTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/data/OnboardingRepositoryImplTest.kt`

Both repositories have no tests because they reach DataStore through a `Context` extension. Injecting the `DataStore<Preferences>` (same files, same names — no data migration) makes them testable on the JVM with a real store in a temp dir.

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/com/openclaw/ghostcrab/data/SettingsRepositoryImplTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.openclaw.ghostcrab.data.impl.SettingsRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SettingsRepositoryImplTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `cleartext opt-in defaults to false and persists when set`() = runTest {
        val store = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tempDir, "app_settings.preferences_pb") },
        )
        val repo = SettingsRepositoryImpl(store)

        assertFalse(repo.allowCleartextPublicIPs.first())
        repo.setAllowCleartextPublicIPs(true)
        assertTrue(repo.allowCleartextPublicIPs.first())
    }
}
```
`app/src/test/kotlin/com/openclaw/ghostcrab/data/OnboardingRepositoryImplTest.kt`:
```kotlin
package com.openclaw.ghostcrab.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.openclaw.ghostcrab.data.impl.OnboardingRepositoryImpl
import com.openclaw.ghostcrab.domain.model.OnboardingStep
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OnboardingRepositoryImplTest {

    @TempDir
    lateinit var tempDir: File

    private fun TestScope.newStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = backgroundScope,
        produceFile = { File(tempDir, "onboarding.preferences_pb") },
    )

    @Test
    fun `fresh install starts at Welcome and is not completed`() = runTest {
        val repo = OnboardingRepositoryImpl(newStore())
        assertFalse(repo.isCompleted())
        assertEquals(OnboardingStep.Welcome, repo.getSavedStep())
    }

    @Test
    fun `saved step round-trips`() = runTest {
        val repo = OnboardingRepositoryImpl(newStore())
        repo.saveStep(OnboardingStep.VerifyRunning)
        assertEquals(OnboardingStep.VerifyRunning, repo.getSavedStep())
    }

    @Test
    fun `markCompleted then reset`() = runTest {
        val repo = OnboardingRepositoryImpl(newStore())

        repo.markCompleted()
        assertTrue(repo.isCompleted())
        assertEquals(OnboardingStep.Completed, repo.getSavedStep())

        repo.reset()
        assertFalse(repo.isCompleted())
        assertEquals(OnboardingStep.Welcome, repo.getSavedStep())
    }

    @Test
    fun `unknown stored step falls back to Welcome`() = runTest {
        val store = newStore()
        store.edit { it[stringPreferencesKey("current_step")] = "StepFromTheFuture" }
        assertEquals(OnboardingStep.Welcome, OnboardingRepositoryImpl(store).getSavedStep())
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.SettingsRepositoryImplTest" --tests "com.openclaw.ghostcrab.data.OnboardingRepositoryImplTest"`
Expected: FAIL — type mismatch: constructors take `Context`, not `DataStore<Preferences>`.

- [ ] **Step 3: Inject the store**

`SettingsRepositoryImpl.kt` — replace the file body below the imports with:
```kotlin
/** App-settings store (file `app_settings`). Exposed to DI; the repository receives the store, not a Context. */
internal val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "app_settings",
)

private val KEY_ALLOW_CLEARTEXT_PUBLIC = booleanPreferencesKey("allow_cleartext_public_ips")

/**
 * DataStore-backed [SettingsRepository]. All preferences default to `false` unless explicitly set.
 *
 * @param dataStore The `app_settings` preferences store.
 */
class SettingsRepositoryImpl(private val dataStore: DataStore<Preferences>) : SettingsRepository {

    override val allowCleartextPublicIPs: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_ALLOW_CLEARTEXT_PUBLIC] ?: false }

    override suspend fun setAllowCleartextPublicIPs(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_ALLOW_CLEARTEXT_PUBLIC] = enabled }
    }
}
```
`OnboardingRepositoryImpl.kt` — change `private val Context.onboardingDataStore` to `internal val Context.onboardingDataStore`, change the constructor to `public class OnboardingRepositoryImpl(private val dataStore: DataStore<Preferences>) : OnboardingRepository {`, update the KDoc `@param` to `@param dataStore The `onboarding` preferences store.`, and replace every `context.onboardingDataStore` in the class with `dataStore`.

`DataModule.kt` — add imports `com.openclaw.ghostcrab.data.impl.appSettingsDataStore` and `com.openclaw.ghostcrab.data.impl.onboardingDataStore`, then:
```kotlin
    single<OnboardingRepository> { OnboardingRepositoryImpl(androidContext().onboardingDataStore) }
    single<SettingsRepository> { SettingsRepositoryImpl(androidContext().appSettingsDataStore) }
```

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.SettingsRepositoryImplTest" --tests "com.openclaw.ghostcrab.data.OnboardingRepositoryImplTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/SettingsRepositoryImpl.kt app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/OnboardingRepositoryImpl.kt app/src/main/kotlin/com/openclaw/ghostcrab/di/DataModule.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/SettingsRepositoryImplTest.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/OnboardingRepositoryImplTest.kt
git commit -m "test(data): inject DataStore into settings/onboarding repos and cover them"
```

---

### Task 29: Verify the Koin graph in a unit test

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create: `app/src/test/kotlin/com/openclaw/ghostcrab/di/KoinModulesTest.kt`

`UiModule` wires ViewModels positionally (`get(), get(), get(), get(), getOrNull(), getOrNull()`); nothing checks the graph until the app crashes at runtime.

- [ ] **Step 1: Add `koin-test`**

`gradle/libs.versions.toml`, `[libraries]`, after `koin-androidx-compose`:
```toml
koin-test = { group = "io.insert-koin", name = "koin-test", version.ref = "koin" }
```
`app/build.gradle.kts`, after `testImplementation(libs.ktor.client.mock)`:
```kotlin
    testImplementation(libs.koin.test)
```

- [ ] **Step 2: Write the test** — create `app/src/test/kotlin/com/openclaw/ghostcrab/di/KoinModulesTest.kt`:

```kotlin
package com.openclaw.ghostcrab.di

import android.content.Context
import androidx.datastore.core.DataStore
import com.openclaw.ghostcrab.data.impl.OpenClawApiClientFactory
import org.junit.jupiter.api.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.test.verify.verify

/**
 * Static check of the Koin graph: every constructor dependency of every definition must itself be
 * declared. `extraTypes` lists what definitions pass explicitly instead of resolving with `get()`
 * (the Android Context, DataStores, lambdas, defaulted constructor parameters).
 */
@OptIn(KoinExperimentalAPI::class)
class KoinModulesTest {

    @Test
    fun `app module graph resolves`() {
        appModule.verify(
            extraTypes = listOf(
                Context::class,
                DataStore::class,
                Function0::class,
                Function1::class,
                OpenClawApiClientFactory::class,
            ),
        )
    }
}
```

- [ ] **Step 3: Prove the test can fail** — temporarily delete the line `single<GatewayHealthChecker> { get<GatewayConnectionManagerImpl>() }` from `DataModule.kt`.

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.di.KoinModulesTest"`
Expected: FAIL with `MissingKoinDefinitionException` naming `GatewayHealthChecker` (required by `DashboardViewModel`). Restore the line.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.di.KoinModulesTest"`
Expected: PASS. If it fails naming a framework or lambda type that a definition passes explicitly (not via `get()`), add that `KClass` to `extraTypes`; if it names a repository, domain or ViewModel type, that is a real wiring bug — fix `DataModule`/`UiModule`, not the test.

- [ ] **Step 5: Name the positional AI ViewModel arguments** — in `UiModule.kt` replace
`viewModel { AIRecommendationViewModel(get(), get(), get(), get(), getOrNull(), getOrNull()) }` with
```kotlin
    viewModel {
        AIRecommendationViewModel(
            aiService = get(),
            connectionManager = get(),
            configRepository = get(),
            modelRepository = get(),
            scopeProbe = getOrNull(),
            installRepo = getOrNull(),
        )
    }
```

- [ ] **Step 6: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/kotlin/com/openclaw/ghostcrab/di/UiModule.kt app/src/test/kotlin/com/openclaw/ghostcrab/di/KoinModulesTest.kt
git commit -m "test(di): verify the Koin module graph"
```

---

### Task 30: Installed-skills repository — no `!!`, typed protocol errors, refresh/uninstall tests

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImpl.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImplTest.kt`

Bugs: malformed gateway JSON hits `this["slug"]!!` → NPE → reported as `Unknown("null")`; `catch (e: Exception)` in `install` also catches `CancellationException`; transport failures (timeout, socket closed) are reported as `Unknown` instead of retryable `Network`. Refresh and uninstall have no tests.

- [ ] **Step 1: Write the failing tests** (append inside `class InstalledSkillRepositoryImplTest`; add imports `com.openclaw.ghostcrab.domain.exception.GatewayProtocolException`, `kotlinx.coroutines.flow.first`, `org.junit.jupiter.api.Assertions.assertInstanceOf`)

```kotlin
    @Test
    fun `refreshFromGateway publishes the gateway list`() = runTest {
        val session = FakeWsSession()
        val repo = InstalledSkillRepositoryImpl { GatewayWsClient.forTesting(session) }

        val call = async { repo.refreshFromGateway() }
        val id = session.awaitSent()["id"].toString().toLong()
        session.pushInbound(
            """{"jsonrpc":"2.0","id":$id,"result":{"skills":[{"slug":"a/b","installed_version":"1.0"}]}}""",
        )

        assertEquals(listOf("a/b"), call.await().getOrThrow().map { it.slug })
        assertEquals(listOf("a/b"), repo.observeInstalled().first().map { it.slug })
    }

    @Test
    fun `malformed skill entry fails the refresh with GatewayProtocolException`() = runTest {
        val session = FakeWsSession()
        val repo = InstalledSkillRepositoryImpl { GatewayWsClient.forTesting(session) }

        val call = async { repo.refreshFromGateway() }
        val id = session.awaitSent()["id"].toString().toLong()
        session.pushInbound("""{"jsonrpc":"2.0","id":$id,"result":{"skills":[{"installed_version":"1.0"}]}}""")

        assertInstanceOf(GatewayProtocolException::class.java, call.await().exceptionOrNull())
    }

    @Test
    fun `uninstall error is returned as a failure`() = runTest {
        val session = FakeWsSession()
        val repo = InstalledSkillRepositoryImpl { GatewayWsClient.forTesting(session) }

        val call = async { repo.uninstall("a/b") }
        val id = session.awaitSent()["id"].toString().toLong()
        session.pushInbound("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32004,"message":"a/b"}}""")

        assertInstanceOf(GatewayWsClient.WsRpcException::class.java, call.await().exceptionOrNull())
    }

    @Test
    fun `install with a malformed result reports a Protocol error`() = runTest {
        val session = FakeWsSession()
        val repo = InstalledSkillRepositoryImpl { GatewayWsClient.forTesting(session) }

        val emissions = async { repo.install("foo/bar").toList() }
        val id = session.awaitSent()["id"].toString().toLong()
        session.pushInbound("""{"jsonrpc":"2.0","id":$id,"result":{"installed_version":"1.0.0"}}""")

        val last = emissions.await().last() as SkillInstallProgress.Failed
        assertInstanceOf(SkillInstallError.Protocol::class.java, last.error)
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.impl.InstalledSkillRepositoryImplTest"`
Expected: FAIL — the malformed-entry tests (`NullPointerException` instead of `GatewayProtocolException`; `Unknown` instead of `Protocol`).

- [ ] **Step 3: Fix the mapping** — in `InstalledSkillRepositoryImpl.kt` add imports `com.openclaw.ghostcrab.domain.exception.GatewayException`, `com.openclaw.ghostcrab.domain.exception.GatewayProtocolException`, `kotlinx.serialization.json.longOrNull` (remove `kotlinx.serialization.json.long`). Replace `toInstalledSkill` with:
```kotlin
    private fun JsonObject.toInstalledSkill(): InstalledSkill {
        val slug = this["slug"]?.jsonPrimitive?.contentOrNull
        val version = this["installed_version"]?.jsonPrimitive?.contentOrNull
        if (slug == null || version == null) {
            throw GatewayProtocolException("ws:skills", "skill entry without slug/installed_version: $this")
        }
        return InstalledSkill(
            slug = slug,
            installedVersion = version,
            source = when (this["source"]?.jsonPrimitive?.contentOrNull) {
                "ClawHub", "clawhub" -> SkillSource.ClawHub
                "Local", "local" -> SkillSource.Local
                else -> SkillSource.Unknown
            },
            installedAt = this["installed_at"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }
```
and in `install` replace the catch chain of the request `try` with:
```kotlin
        } catch (e: CancellationException) {
            throw e
        } catch (e: GatewayWsClient.WsRpcException) {
            send(SkillInstallProgress.Failed(e.toInstallError()))
        } catch (e: GatewayProtocolException) {
            send(SkillInstallProgress.Failed(SkillInstallError.Protocol(rpcCode = 0, message = e.detail)))
        } catch (e: GatewayException) {
            // Timeout or socket closed mid-install: retryable transport failure.
            send(SkillInstallProgress.Failed(SkillInstallError.Network(e.message ?: e::class.java.simpleName)))
        } catch (e: Exception) {
            send(SkillInstallProgress.Failed(SkillInstallError.Unknown(e.message ?: "unknown")))
        } finally {
```

- [ ] **Step 4: Run them to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.data.impl.InstalledSkillRepositoryImplTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImpl.kt app/src/test/kotlin/com/openclaw/ghostcrab/data/impl/InstalledSkillRepositoryImplTest.kt
git commit -m "fix(skills): typed protocol errors instead of NPE on malformed gateway JSON"
```

---

### Task 31: Remove dead code; make `AI_PRO_ENABLED` actually gate the AI entry point

**Files:**
- Delete: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/components/ConnectionStatusBar.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/theme/Color.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/onboarding/OnboardingViewModel.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/dashboard/DashboardScreen.kt` (`QuickActionsRow`)
- Modify: `app/build.gradle.kts`, `gradle/libs.versions.toml`
- Modify (KDoc): `data/impl/AIRecommendationServiceImpl.kt`, `domain/model/GatewayConnection.kt`, `domain/repository/AIRecommendationService.kt`, `ui/airecommend/AIRecommendationViewModel.kt`

`ConnectionStatusBar` (142 lines), `GhostCrabLightColorScheme` and `markOnboardingConnected()` are never called; `ktor-client-auth` is unused since the scoped bearer plugin; `AI_PRO_ENABLED` is documented as gating AI recommendations but is never read; four KDocs name the capability `skill-ai-recommend` while the code checks `ai.recommend`.

- [ ] **Step 1: Prove they are unused**

Run: `git grep -n -e "ConnectionStatusBar(" -e "GhostCrabLightColorScheme" -e "markOnboardingConnected" -e "ktor.client.auth" -e "AI_PRO_ENABLED" -- app/src`
Expected: only their own declarations (and nothing reading `AI_PRO_ENABLED`).

- [ ] **Step 2: Delete**

```bash
git rm app/src/main/kotlin/com/openclaw/ghostcrab/ui/components/ConnectionStatusBar.kt
```
- `Color.kt`: delete the `GhostCrabLightColorScheme` declaration with its KDoc and the import `androidx.compose.material3.lightColorScheme`.
- `OnboardingViewModel.kt`: delete `markOnboardingConnected()` with its KDoc (`complete()` stays — `skip()` uses it).
- `app/build.gradle.kts`: delete `implementation(libs.ktor.client.auth)`; `gradle/libs.versions.toml`: delete the `ktor-client-auth = …` line.

- [ ] **Step 3: Wire `AI_PRO_ENABLED`** — in `DashboardScreen.kt` add `import com.openclaw.ghostcrab.BuildConfig` and in `QuickActionsRow` wrap the AI button:

```kotlin
        if (BuildConfig.AI_PRO_ENABLED) {
            OutlinedButton(
                onClick = onNavigateToAiRecommend,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.dashboard_action_ai_recommend)) }
        }
```

- [ ] **Step 4: Fix the capability-name KDoc drift**

```bash
M=app/src/main/kotlin/com/openclaw/ghostcrab
sed -i 's/skill-ai-recommend/ai.recommend/g' $M/data/impl/AIRecommendationServiceImpl.kt $M/domain/model/GatewayConnection.kt $M/domain/repository/AIRecommendationService.kt $M/ui/airecommend/AIRecommendationViewModel.kt
git grep -n "skill-ai-recommend" -- app/src/main
```
Expected output of the grep: nothing (KDoc-only change; the frozen interface's signature is untouched).

- [ ] **Step 5: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add -A app/src/main/kotlin/com/openclaw/ghostcrab/ app/build.gradle.kts gradle/libs.versions.toml
git commit -m "chore: remove dead code and gate the AI entry point on AI_PRO_ENABLED"
```

---

### Task 32: Lint — wire the orphaned strings, suppress verified false positives, forbid new unused resources

**Files:**
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/ConfigEditorScreen.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/PendingChangesDiffSheet.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/components/SecurityBanner.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/lint.xml`

Baseline: 18 `UnusedResources` (16 strings exist for text that is hard-coded in the Config editor and security banners; 3 are genuinely dead), 10 `IconDuplicates` (legacy round/monochrome launcher PNGs are identical by design), 8 `Typos` (all inside base64 certificate data in `font_certs.xml`).

- [ ] **Step 1: Use the Config editor strings** — in `ConfigEditorScreen.kt` add imports `androidx.compose.ui.res.stringResource` and `com.openclaw.ghostcrab.R`, then:
- after `val readyState = state as? ConfigEditorUiState.Ready` add `val savedMessage = stringResource(R.string.config_editor_saved_snackbar)` and change `snackbarHostState.showSnackbar("Configuration saved")` → `snackbarHostState.showSnackbar(savedMessage)`
- `title = { Text("Configure Gateway") },` → `title = { Text(stringResource(R.string.config_editor_title)) },`
- `Text("Retry")` → `Text(stringResource(R.string.config_editor_retry))`
- `title = { Text("Configuration Changed") },` → `title = { Text(stringResource(R.string.config_editor_concurrent_title)) },`
- the concurrent-edit body `Text( "Server values changed since you opened section " + "\"$concurrentSection\". The latest values have been loaded.", )` → `Text(stringResource(R.string.config_editor_concurrent_body, concurrentSection))`
- `"Reload",` → `stringResource(R.string.config_editor_concurrent_reload),`
- `Text("Dismiss")` → `Text(stringResource(R.string.config_editor_concurrent_dismiss))`
- `label = { Text("Modified", style = MaterialTheme.typography.labelSmall) },` → (wrapped for the 120-column limit)
```kotlin
                            label = {
                                Text(
                                    stringResource(R.string.config_editor_section_modified),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
```
- in `SectionCard`, `"Discard",` → `stringResource(R.string.config_editor_discard),` and `Text("Save")` → `Text(stringResource(R.string.config_editor_save))`

- [ ] **Step 2: Use the diff and banner strings**

`PendingChangesDiffSheet.kt` (add the same two imports): `text = "Review changes: $sectionKey",` → `text = stringResource(R.string.config_editor_diff_title, sectionKey),`; `text = "Before",` → `text = stringResource(R.string.config_editor_diff_before),`; `text = "After",` → `text = stringResource(R.string.config_editor_diff_after),`; the confirm button's `Text("Save")` → `Text(stringResource(R.string.config_editor_save))`.
`SecurityBanner.kt` (same two imports): the HTTP banner text literal → `stringResource(R.string.banner_http_message)`; the no-auth banner text literal → `stringResource(R.string.banner_no_auth_message)`.

- [ ] **Step 3: Delete the three dead strings** from `strings.xml`: `config_editor_not_connected`, `installed_skills_refresh_failed`, `installed_skills_uninstall_failed` (their messages are produced by `toUserMessage()` in the ViewModels).

- [ ] **Step 4: Create `app/lint.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<lint>
    <!-- Base64 certificate data for the Google Fonts provider, not prose. -->
    <issue id="Typos">
        <ignore path="**/values/font_certs.xml" />
    </issue>
    <!-- Legacy round/monochrome launcher PNGs are intentionally identical to square/foreground. -->
    <issue id="IconDuplicates">
        <ignore regexp="ic_launcher" />
    </issue>
    <!-- Zero unused resources as of 2026-10-13: keep it that way. -->
    <issue id="UnusedResources" severity="error" />
</lint>
```

- [ ] **Step 5: Run lint**

Run: `./gradlew lintDebug`
Expected: `BUILD SUCCESSFUL`; `app/build/reports/lint-results-debug.txt` reports `0 errors` and at most 27 warnings (63 − 18 − 10 − 8), with no `[UnusedResources]`, `[IconDuplicates]` or `[Typos]` line. If it reports `UnusedResources` as an error, a string listed in Steps 1–2 was missed — wire it.

- [ ] **Step 6: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/lint.xml app/src/main/res/values/strings.xml app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/ConfigEditorScreen.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/config/PendingChangesDiffSheet.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/components/SecurityBanner.kt
git commit -m "chore(lint): wire orphaned strings and suppress verified false positives"
```

---

### Task 33a: Pair-code decoder (`ghostcrab://pair?p=…` and plain URLs)

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/domain/util/PairCode.kt`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/domain/PairCodeTest.kt`

Spec: `docs/superpowers/specs/2026-04-17-qr-pairing-repurpose-design.md` — payload `ghostcrab://pair?p=<base64url(json)>`, JSON v1 `{v:1, url, token?, name?}`. Decoding never throws; it returns a sealed result with a user-facing reason.

- [ ] **Step 1: Write the failing decoder tests** — create `app/src/test/kotlin/com/openclaw/ghostcrab/domain/PairCodeTest.kt`:

```kotlin
package com.openclaw.ghostcrab.domain

import com.openclaw.ghostcrab.domain.util.PairCodeResult
import com.openclaw.ghostcrab.domain.util.decodePairCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class PairCodeTest {

    private fun pairUri(json: String) =
        "ghostcrab://pair?p=" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test fun `v1 bundle decodes url token and name`() {
        val result = decodePairCode(pairUri("""{"v":1,"url":"http://192.168.0.23:18789","token":"tkn","name":"Den"}"""))
        val bundle = (result as PairCodeResult.Bundle).bundle
        assertEquals("http://192.168.0.23:18789", bundle.url.toString())
        assertEquals("tkn", bundle.token)
        assertEquals("Den", bundle.name)
        assertFalse(bundle.toString().contains("tkn"))
    }

    @Test fun `bundle without token is accepted`() {
        val result = decodePairCode(pairUri("""{"v":1,"url":"http://192.168.0.23:18789"}"""))
        assertNull((result as PairCodeResult.Bundle).bundle.token)
    }

    @Test fun `unknown version is rejected explicitly`() {
        val result = decodePairCode(pairUri("""{"v":2,"url":"http://192.168.0.23:18789"}"""))
        assertTrue((result as PairCodeResult.Invalid).reason.contains("version 2"))
    }

    @Test fun `malformed base64 is rejected`() {
        assertInstanceOf(PairCodeResult.Invalid::class.java, decodePairCode("ghostcrab://pair?p=%%%"))
    }

    @Test fun `non-http url inside the bundle is rejected`() {
        assertInstanceOf(PairCodeResult.Invalid::class.java, decodePairCode(pairUri("""{"v":1,"url":"ftp://x"}""")))
    }

    @Test fun `plain https url is still accepted`() {
        assertInstanceOf(PairCodeResult.PlainUrl::class.java, decodePairCode("https://abc-xyz.trycloudflare.com"))
    }

    @Test fun `random text is invalid`() {
        assertInstanceOf(PairCodeResult.Invalid::class.java, decodePairCode("just some random text"))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.PairCodeTest"`
Expected: FAIL — `Unresolved reference: decodePairCode`.

- [ ] **Step 3: Create `domain/util/PairCode.kt`**

```kotlin
package com.openclaw.ghostcrab.domain.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

private const val PAIR_PREFIX = "ghostcrab://pair?"
private val pairJson = Json { ignoreUnknownKeys = true }

/**
 * Connection bundle carried by a `ghostcrab://pair?p=<base64url(json)>` code (schema v1, see
 * `docs/superpowers/specs/2026-04-17-qr-pairing-repurpose-design.md`).
 *
 * @property url Validated gateway base URL.
 * @property token Bearer token, or `null` for unauthenticated gateways. Sensitive — never log.
 * @property name Optional display name.
 */
data class PairBundle(val url: GatewayUrl, val token: String?, val name: String?) {
    /** Redacts [token]. */
    override fun toString(): String =
        "PairBundle(url=$url, token=${token?.let { REDACTED_PLACEHOLDER }}, name=$name)"
}

/** Result of decoding a scanned or deep-linked payload. Decoding never throws. */
sealed interface PairCodeResult {
    /** A full `ghostcrab://pair` bundle. */
    data class Bundle(val bundle: PairBundle) : PairCodeResult

    /** A plain `http(s)` gateway URL (legacy / tunnel QR codes). */
    data class PlainUrl(val url: GatewayUrl) : PairCodeResult

    /** Unusable payload; [reason] is user-facing. */
    data class Invalid(val reason: String) : PairCodeResult
}

@Serializable
private data class PairPayloadV1(val v: Int, val url: String, val token: String? = null, val name: String? = null)

/**
 * Decodes [raw] from a QR code or a `VIEW` intent: a `ghostcrab://pair?p=…` bundle or a plain
 * `http(s)` gateway URL.
 *
 * @param raw Scanned text or deep-link URI string.
 * @return [PairCodeResult.Bundle], [PairCodeResult.PlainUrl], or [PairCodeResult.Invalid].
 */
fun decodePairCode(raw: String): PairCodeResult {
    val trimmed = raw.trim()
    return if (trimmed.startsWith(PAIR_PREFIX, ignoreCase = true)) {
        decodeBundle(trimmed)
    } else {
        GatewayUrl.parse(trimmed)?.let { PairCodeResult.PlainUrl(it) }
            ?: PairCodeResult.Invalid("QR code doesn't contain a valid gateway URL")
    }
}

private fun decodeBundle(uri: String): PairCodeResult {
    val encoded = uri.substringAfter('?').split('&').firstOrNull { it.startsWith("p=") }?.removePrefix("p=")
    val payload = encoded
        ?.let { runCatching { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }
        ?.let { runCatching { pairJson.decodeFromString<PairPayloadV1>(it) }.getOrNull() }
    val url = payload?.let { GatewayUrl.parse(it.url) }
    return when {
        payload == null -> PairCodeResult.Invalid("Pair code could not be decoded")
        payload.v != 1 -> PairCodeResult.Invalid("Unsupported pair code version ${payload.v}")
        url == null -> PairCodeResult.Invalid("Pair code URL is not a valid http(s) gateway URL")
        else -> PairCodeResult.Bundle(PairBundle(url, payload.token?.takeIf { it.isNotBlank() }, payload.name))
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.domain.PairCodeTest"`
Expected: PASS (7 tests).


- [ ] **Step 5: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/domain/util/PairCode.kt app/src/test/kotlin/com/openclaw/ghostcrab/domain/PairCodeTest.kt
git commit -m "feat(domain): decode ghostcrab://pair codes"
```

---

### Task 33b: Scanned pair codes prefill ManualEntry through an in-memory handoff

**Files:**
- Create: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/connection/PairingHandoff.kt`
- Modify: `ui/connection/QrScanViewModel.kt`, `ui/connection/QrScanScreen.kt`, `ui/connection/ManualEntryViewModel.kt`, `ui/connection/ManualEntryScreen.kt`, `ui/navigation/NavGraph.kt`, `di/UiModule.kt` (under `app/src/main/kotlin/com/openclaw/ghostcrab/`)
- Modify: `config/detekt-baseline.xml`
- Test: `app/src/test/kotlin/com/openclaw/ghostcrab/ui/QrScanViewModelTest.kt`, `app/src/test/kotlin/com/openclaw/ghostcrab/ui/ManualEntryViewModelTest.kt`

Deviation from the spec (recorded in Task 35): **no auto-connect** — the form is prefilled and the user taps Connect, because the same decoder serves deep links that any app can fire. The bearer never travels in a navigation route (routes are persisted in saved state); it goes through an in-memory one-shot `PairingHandoff`.

- [ ] **Step 1: Create `ui/connection/PairingHandoff.kt`**

```kotlin
package com.openclaw.ghostcrab.ui.connection

import com.openclaw.ghostcrab.domain.util.PairBundle
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot, in-memory handoff of a scanned or deep-linked [PairBundle] to the ManualEntry screen.
 * Keeps the bearer token out of navigation routes, which are persisted in saved state.
 * Registered as a Koin `single`.
 */
class PairingHandoff {

    private val pending = AtomicReference<PairBundle?>(null)

    /**
     * Stores [bundle], replacing any unconsumed one.
     *
     * @param bundle The decoded pair bundle.
     */
    fun offer(bundle: PairBundle) {
        pending.set(bundle)
    }

    /**
     * Returns and clears the pending bundle.
     *
     * @return The bundle, or `null` when none is pending.
     */
    fun take(): PairBundle? = pending.getAndSet(null)
}
```

- [ ] **Step 2: Write the failing ViewModel tests**

```bash
T=app/src/test/kotlin/com/openclaw/ghostcrab/ui
sed -i 's/QrScanViewModel()/QrScanViewModel(PairingHandoff())/g' $T/QrScanViewModelTest.kt
sed -i 's/ManualEntryViewModel(connectionManager, profileRepository, onboardingRepository)/ManualEntryViewModel(connectionManager, profileRepository, onboardingRepository, PairingHandoff())/' $T/ManualEntryViewModelTest.kt
for f in $T/QrScanViewModelTest.kt $T/ManualEntryViewModelTest.kt; do
  sed -i '0,/^import /s//import com.openclaw.ghostcrab.ui.connection.PairingHandoff\nimport /' "$f"
done
```
Append inside `class QrScanViewModelTest` (add import `java.util.Base64`):
```kotlin
    @Test
    fun `pair code hands the bundle off and navigates without the token in the event`() = runTest {
        val handoff = PairingHandoff()
        val vm = QrScanViewModel(handoff)
        val code = "ghostcrab://pair?p=" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"v":1,"url":"http://192.168.0.23:18789","token":"tkn"}""".toByteArray())

        vm.events.test {
            vm.onQrDecoded(code)
            assertEquals(QrScanEvent.NavigateToPairedEntry, awaitItem())
        }
        assertEquals("tkn", handoff.take()?.token)
    }
```
Append inside `class ManualEntryViewModelTest` (add imports `com.openclaw.ghostcrab.domain.util.GatewayUrl` and `com.openclaw.ghostcrab.domain.util.PairBundle`):
```kotlin
    @Test
    fun `consumePendingPairing prefills host, port and token without connecting`() {
        val handoff = PairingHandoff().apply {
            offer(PairBundle(GatewayUrl("https", "gw.example", 8443), token = "tkn", name = "Den"))
        }
        val pairedVm = ManualEntryViewModel(connectionManager, profileRepository, onboardingRepository, handoff)

        assertTrue(pairedVm.consumePendingPairing())

        val form = pairedVm.form.value
        assertTrue(form.useHttps)
        assertEquals("gw.example", form.host)
        assertEquals("8443", form.port)
        assertEquals("tkn", form.token)
        assertFalse(pairedVm.consumePendingPairing())
        coVerify(exactly = 0) { connectionManager.connect(any(), any()) }
    }
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.QrScanViewModelTest" --tests "com.openclaw.ghostcrab.ui.ManualEntryViewModelTest"`
Expected: FAIL — `Too many arguments for constructor` / `Unresolved reference: consumePendingPairing`.

- [ ] **Step 4: Implement in the ViewModels**

`QrScanViewModel.kt` — add imports `com.openclaw.ghostcrab.domain.util.PairCodeResult`, `com.openclaw.ghostcrab.domain.util.decodePairCode` (remove `GatewayUrl`); add to `QrScanEvent`:
```kotlin
    /** A pair bundle was handed to [PairingHandoff]; ManualEntry picks it up (no token in the route). */
    data object NavigateToPairedEntry : QrScanEvent
```
change the class header to `class QrScanViewModel(private val pairingHandoff: PairingHandoff) : ViewModel() {` and replace `onQrDecoded` with:
```kotlin
    /**
     * Call when ML Kit decodes a barcode. Accepts a plain `http(s)` gateway URL or a
     * `ghostcrab://pair` bundle; anything else sets [QrScanUiState.InvalidQr]. After one accepted
     * code, further frames are ignored until [onScanAgain].
     *
     * @param raw Raw string decoded from the QR code.
     */
    fun onQrDecoded(raw: String) {
        if (_uiState.value == QrScanUiState.Decoded) return
        when (val result = decodePairCode(raw)) {
            is PairCodeResult.Invalid -> _uiState.value = QrScanUiState.InvalidQr(result.reason)
            is PairCodeResult.PlainUrl -> accept(QrScanEvent.NavigateToManualEntry(raw.trim()))
            is PairCodeResult.Bundle -> {
                pairingHandoff.offer(result.bundle)
                accept(QrScanEvent.NavigateToPairedEntry)
            }
        }
    }

    private fun accept(event: QrScanEvent) {
        _uiState.value = QrScanUiState.Decoded
        viewModelScope.launch { _events.send(event) }
    }
```
`ManualEntryViewModel.kt` — add the constructor parameter `private val pairingHandoff: PairingHandoff,` (last), a field `private var pairedName: String? = null`, in `saveConnectedProfile` change the `displayName` expression to
```kotlin
        val displayName = pairedName
            ?: (connectionManager.connectionState.value as? GatewayConnection.Connected)?.displayName
            ?: url
```
and add:
```kotlin
    /**
     * Consumes a pending [com.openclaw.ghostcrab.domain.util.PairBundle] (QR or `ghostcrab://pair`
     * deep link) and prefills the form. Never connects by itself: the user confirms with Connect.
     *
     * @return `true` if a bundle was consumed.
     */
    fun consumePendingPairing(): Boolean {
        val bundle = pairingHandoff.take() ?: return false
        pairedName = bundle.name
        _form.update {
            it.copy(
                useHttps = bundle.url.scheme == "https",
                host = bundle.url.host,
                port = bundle.url.port.toString(),
                token = bundle.token.orEmpty(),
                hostError = null,
                portError = null,
            )
        }
        return true
    }
```

- [ ] **Step 5: Run them to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.openclaw.ghostcrab.ui.QrScanViewModelTest" --tests "com.openclaw.ghostcrab.ui.ManualEntryViewModelTest"`
Expected: PASS.

- [ ] **Step 6: Wire DI, screens and navigation**

`UiModule.kt`: add `import com.openclaw.ghostcrab.ui.connection.PairingHandoff`, add `single { PairingHandoff() }` as the first line of the module, and change `viewModel { ManualEntryViewModel(get(), get(), get()) }` → `viewModel { ManualEntryViewModel(get(), get(), get(), get()) }`, `viewModel { QrScanViewModel() }` → `viewModel { QrScanViewModel(get()) }`.
`QrScanScreen.kt`: add the parameter `onNavigateToPairedEntry: () -> Unit,` after `onNavigateToManualEntry`, and in the events `when` add `QrScanEvent.NavigateToPairedEntry -> onNavigateToPairedEntry()`.
`ManualEntryScreen.kt`: directly after the `LaunchedEffect(prefillUrl) { … }` block add
```kotlin
    // A QR / deep-link pair bundle waiting in PairingHandoff (no-op otherwise).
    LaunchedEffect(Unit) { viewModel.consumePendingPairing() }
```
`NavGraph.kt`, `composable("qr_scan")`: add after `onNavigateToManualEntry = { … },`
```kotlin
                onNavigateToPairedEntry = {
                    navController.navigate("manual_entry") {
                        popUpTo("qr_scan") { inclusive = true }
                    }
                },
```

- [ ] **Step 7: Refresh the baseline for `QrScanScreen`'s new signature**

Run: `./gradlew detekt` (expected: `LongMethod` for `QrScanScreen`), `./gradlew detektBaseline`, `git diff config/detekt-baseline.xml` (expected: only the `QrScanScreen` ID changes).

- [ ] **Step 8: Full gate, ⚠ manual check, commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
⚠ manual (device): `python -c "import base64,json;print('ghostcrab://pair?p='+base64.urlsafe_b64encode(json.dumps({'v':1,'url':'http://<LAN-IP>:18789','token':'<token>'}).encode()).decode().rstrip('='))"` → render it as a QR (any generator) → scan in-app → ManualEntry shows host/port/token prefilled; tapping Connect connects.
```bash
git add app/src/main/kotlin/com/openclaw/ghostcrab/ app/src/test/kotlin/com/openclaw/ghostcrab/ config/detekt-baseline.xml
git commit -m "feat(qr): decode ghostcrab://pair codes and prefill the connect form"
```

---

### Task 34: `ghostcrab://pair` deep link

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/MainActivity.kt`
- Modify: `app/src/main/kotlin/com/openclaw/ghostcrab/ui/navigation/NavGraph.kt`
- Modify: `app/src/main/res/values/strings.xml`, `config/detekt-baseline.xml`

- [ ] **Step 1: Register the scheme** — add the label string `<string name="pair_deeplink_label">Pair with GhostCrab</string>` to `strings.xml`. In `AndroidManifest.xml` add `android:launchMode="singleTop"` to the `.MainActivity` element and, after its launcher `<intent-filter>`, add

```xml
            <intent-filter android:label="@string/pair_deeplink_label">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="ghostcrab" android:host="pair" />
            </intent-filter>
```

- [ ] **Step 2: Accept the intent in `MainActivity.kt`** (whole file)

```kotlin
package com.openclaw.ghostcrab

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openclaw.ghostcrab.domain.util.PairCodeResult
import com.openclaw.ghostcrab.domain.util.decodePairCode
import com.openclaw.ghostcrab.ui.connection.PairingHandoff
import com.openclaw.ghostcrab.ui.navigation.NavGraph
import com.openclaw.ghostcrab.ui.theme.BrandTokens
import com.openclaw.ghostcrab.ui.theme.GhostCrabTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val pairingHandoff: PairingHandoff by inject()

    /** Incremented for every accepted `ghostcrab://pair` link; the nav graph navigates on change. */
    private val pairRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a fresh launch: after recreation the link was already handled.
        if (savedInstanceState == null) acceptPairIntent(intent)
        setContent {
            GhostCrabTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BrandTokens.colorAbyss,
                ) {
                    val pairRequest by pairRequests.collectAsStateWithLifecycle()
                    NavGraph(pairRequest = pairRequest)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptPairIntent(intent)
    }

    /**
     * Hands a `ghostcrab://pair` bundle to [PairingHandoff] (the token never enters a navigation
     * route) and asks the nav graph to open ManualEntry, which prefills but does not auto-connect.
     */
    private fun acceptPairIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        when (val result = decodePairCode(data)) {
            is PairCodeResult.Bundle -> {
                pairingHandoff.offer(result.bundle)
                pairRequests.update { it + 1 }
            }
            is PairCodeResult.Invalid -> Toast.makeText(this, result.reason, Toast.LENGTH_LONG).show()
            is PairCodeResult.PlainUrl -> Unit // the intent filter only matches ghostcrab://pair
        }
    }
}
```

- [ ] **Step 3: Navigate on request** — in `NavGraph.kt` add `import androidx.compose.runtime.LaunchedEffect`, change `fun NavGraph() {` to `fun NavGraph(pairRequest: Int = 0) {` and add after `val navController = rememberNavController()`:

```kotlin
    // A `ghostcrab://pair` deep link was accepted (bundle waiting in PairingHandoff): open ManualEntry.
    LaunchedEffect(pairRequest) {
        if (pairRequest > 0) navController.navigate("manual_entry")
    }
```

- [ ] **Step 4: Compile and refresh the baseline** (`NavGraph` is baselined as `fun NavGraph()`)

Run: `./gradlew assembleDebug` → `BUILD SUCCESSFUL`; `./gradlew detektBaseline`; `git diff config/detekt-baseline.xml` → only the `NavGraph` ID changes.

- [ ] **Step 5: ⚠ manual device check** (emulator or phone, debug build installed)

```bash
adb shell am start -W -a android.intent.action.VIEW \
  -d "ghostcrab://pair?p=eyJ2IjoxLCJ1cmwiOiJodHRwOi8vMTkyLjE2OC4xLjUwOjE4Nzg5IiwibmFtZSI6IlFBIEdhdGV3YXkifQ" \
  com.openclaw.ghostcrab.debug
```
Expected: the app opens (or comes to front) on ManualEntry with host `192.168.1.50`, port `18789`, empty token, and does **not** connect until Connect is tapped. `adb shell am start -W -a android.intent.action.VIEW -d "ghostcrab://pair?p=xx" com.openclaw.ghostcrab.debug` shows a toast "Pair code could not be decoded".

- [ ] **Step 6: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/src/main/AndroidManifest.xml app/src/main/kotlin/com/openclaw/ghostcrab/MainActivity.kt app/src/main/kotlin/com/openclaw/ghostcrab/ui/navigation/NavGraph.kt app/src/main/res/values/strings.xml config/detekt-baseline.xml
git commit -m "feat: handle ghostcrab://pair deep links"
```

---

### Task 35: Refresh stale docs

**Files:**
- Modify: `CLAUDE.md`, `README.md`, `site/README.md`, `docs/HANDOFF.md`, `docs/IMPLEMENTATION_PLAN.md`, `docs/superpowers/specs/2026-04-17-qr-pairing-repurpose-design.md`

- [ ] **Step 1: `CLAUDE.md`** — make these replacements (old → new):

```text
OLD: | `ui/navigation/` | `Routes` sealed class, `NavGraph` composable (type-safe Compose Navigation) |
NEW: | `ui/navigation/` | `NavGraph` composable (string routes, e.g. `manual_entry?prefillUrl={prefillUrl}`) |

OLD: | `domain/util/` | Pure helpers (IP literal checks, etc.) |
NEW: | `domain/util/` | Pure helpers: `GatewayUrl`, `decodePairCode`, config redaction, `withLeaf`, `suspendRunCatching`, `generateToken` |

OLD: | `ui/{connection,dashboard,config,models,model,ai,airecommend,onboarding,settings}/` | Per-feature Compose screens + ViewModels |
NEW: | `ui/{connection,dashboard,config,model,airecommend,installedskills,onboarding,settings}/` | Per-feature Compose screens + ViewModels |
     | `ui/ErrorMessages.kt` | `Throwable.toUserMessage()` — the only way failures are rendered |

OLD: - **BuildConfig fields**: `AI_PRO_ENABLED` (Boolean — gates AI recommendations), `GIT_SHA` (baked in at build, shown on About screen).
NEW: - **BuildConfig fields**: `AI_PRO_ENABLED` (hides the Dashboard AI entry when false), `GIT_SHA` (About screen), `SKILLS_INSTALL_ENABLED` (true debug / false release).

OLD: Current status: **v0.1.0 shipped** — Phases 0–11 complete. New work is additive (bug fixes, tests, features beyond the original plan). Check `docs/IMPLEMENTATION_PLAN.md` Progress Ledger for the authoritative state.
NEW: Current status: **v0.2.0** — Phases 0–11 and 13–14 complete; Phase 12 (WS skill install) awaits on-device QA; iOS app in `ios/`, marketing site in `site/`. What is left: `docs/superpowers/plans/2026-10-13-finish-development-roadmap.md`. The Progress Ledger in `docs/IMPLEMENTATION_PLAN.md` stays authoritative.

OLD: WebSocket streaming · offline config cache · iOS · agent runtime on-device · provider-API keys on-device · multi-active-connection · Play Billing.
NEW: WebSocket event streaming (the JSON-RPC skills channel is in scope) · offline config cache · agent runtime on-device · provider-API keys on-device · multi-active-connection · Play Billing.
```
Insert directly after the `data/impl/` table row:
```text
| `data/ws/` | `GatewayWsClient` — JSON-RPC 2.0 over a WebSocket opened by the session's `OpenClawApiClient` |
```
Append to the **Key Constraints** list:
```text
- **Coroutines**: wrap suspend calls in `suspendRunCatching` (domain/util), never bare `runCatching`; inject dispatchers (Koin `IoDispatcher`).
- **detekt baseline** is keyed by file + signature: after moving or re-signing a baselined function run `./gradlew detektBaseline` and review the diff.
```
Insert before `## Docs`:
```markdown
## Other platforms in this repo

- `ios/` — SwiftUI app (xcodegen `project.yml`, iOS 18+, bundle `com.qavren.ghostcrab`). Builds and tests only on the Mac Mini; see `ios/README.md`.
- `site/` — getghostcrab.com (Astro), deployed by `.github/workflows/site-deploy.yml` (wrangler → Workers Static Assets).
```

- [ ] **Step 2: `README.md`** — make these replacements (old → new):

```text
OLD: - **Model manager** — view all configured models, swap the active one *(Phase 7, in progress)*
NEW: - **Model manager** — view all configured models, swap the active one

OLD: - **AI recommendations** — ask the gateway's built-in AI CLI for configuration suggestions *(Phase 8)*
NEW: - **AI recommendations** — ask the gateway's AI skill for configuration suggestions; nothing is pre-selected and security-sensitive changes need explicit confirmation

OLD: | **ViewModel** | One VM per screen, `StateFlow<UiState>`, `SharedFlow` for one-shot events |
NEW: | **ViewModel** | One VM per screen, `StateFlow<UiState>`, buffered `Channel` flows for one-shot events |

OLD: | **DI** | Koin — `AppModule`, `DataModule`, `DomainModule`, `UiModule` |
NEW: | **DI** | Koin — `AppModule`, `DataModule`, `UiModule` |
```
Insert after the `- **Onboarding walkthrough** …` bullet:
```text
- **QR pairing** — scan a plain gateway URL or a `ghostcrab://pair` code; pair deep links prefill the connect form
- **Skills (debug builds)** — list, install and uninstall gateway skills over the JSON-RPC WebSocket
```
Insert after the `| Android | 8.0+ (API 26) |` row:
```text
| iOS | 18+ — SwiftUI app in [`ios/`](ios/README.md), TestFlight pending |
```
Append to the Implementation Status table, then add the sentence below it:
```markdown
| 11 | Cloudflare tunnel + QR connect, logo, splash | ✅ Done |
| 12 | Skills install over WebSocket (debug builds) | 🟡 On-device QA pending |
| 13 | R8 release build verification | ✅ Done |
| 14 | Release signing | ✅ Done |

Phases 6/7/8/12 still need verification against a real OpenClaw gateway — see milestone M5 in [the roadmap](docs/superpowers/plans/2026-10-13-finish-development-roadmap.md).
```

- [ ] **Step 3: `site/README.md`** — line 3: `Deployed to **Cloudflare Pages**.` → `Deployed to **Cloudflare Workers Static Assets** by GitHub Actions.` Replace everything from `## Deploy to Cloudflare Pages` to the end of the file with:

```markdown
## Deploy

`.github/workflows/site-deploy.yml` owns deployment:

- `pull_request` touching `site/**`, `wrangler.jsonc` or the workflow → `npm ci` + `npm run build` only (no deploy).
- `push` to `main` with the same paths, or `workflow_dispatch` → build, then `npx wrangler@4 deploy` using the root `wrangler.jsonc`.
- Requires the repo secret `CLOUDFLARE_API_TOKEN` ("Edit Cloudflare Workers" template).

The old Cloudflare dashboard Git integration never produced a green build and should be disconnected so the two paths do not race. Changing `docs/PRIVACY_POLICY_IOS.md` alone does **not** trigger a deploy — run the workflow manually.
```

- [ ] **Step 4: `docs/HANDOFF.md`** — replace the two header lines `**Date:** 2026-04-16  ` and `**Branch:** main  ` with
```markdown
**Date:** 2026-04-16 — **superseded 2026-10-13** by `docs/superpowers/plans/2026-10-13-finish-development-roadmap.md`; kept for history.  
**Branch:** main (at the time of writing)  
```

- [ ] **Step 5: `docs/IMPLEMENTATION_PLAN.md`** — directly below the ledger row that starts with `| 12 | Skills-Install (WS-backed) |` insert:

```markdown
| 13 | R8 Release Build Verification | 🟢 Done | 2026-04-16 | 2026-04-16 | — | Sonnet 4.6 | `assembleRelease` green, no `missing_rules.txt`, 81 `$$serializer` classes kept (HANDOFF). Runtime smoke install still open (roadmap M6). |
| 14 | Release Signing | 🟢 Done | 2026-04-16 | 2026-04-16 | — | Sonnet 4.6 | PKCS12 RSA-4096 keystore; signing from `local.properties`/env; `docs/RELEASE_SIGNING.md`. Keystore backup + Play App Signing open (roadmap M6). |
| 15 | Audit fixes & refactor | 🟡 In Progress | 2026-10-13 | — | — | — | `docs/superpowers/plans/2026-10-13-android-fixes-and-refactor.md` |
| iOS | SwiftUI app (`ios/`) | 🟡 In Progress | 2026-05-28 | — | — | — | All 11 screens exist; finish work in `docs/superpowers/plans/2026-10-13-ios-finish.md`. |
| Site | getghostcrab.com (`site/`) | 🟢 Done | — | 2026-08-05 | — | — | Astro; deployed by `.github/workflows/site-deploy.yml`. |
```

- [ ] **Step 6: Spec status** — in `docs/superpowers/specs/2026-04-17-qr-pairing-repurpose-design.md` add directly under the title:
```markdown
> **Status (2026-10-13):** mobile decoder + deep link implemented (Android Tasks 33a–34 of the 2026-10-13 plan). Deviation: pair bundles prefill the connect form but never auto-connect (deep links can be fired by any app). Gateway `/pair` route is still upstream work.
```

- [ ] **Step 7: Verify and commit**

Run: `git grep -n -e "DomainModule" -e "Phase 7, in progress" -e "Routes. sealed" -- README.md CLAUDE.md`
Expected: no output.
```bash
git add CLAUDE.md README.md site/README.md docs/HANDOFF.md docs/IMPLEMENTATION_PLAN.md docs/superpowers/specs/2026-04-17-qr-pairing-repurpose-design.md
git commit -m "docs: refresh CLAUDE.md, README, site README, handoff and ledger"
```

---

### Task 36: Cut v0.2.0

**Files:**
- Modify: `app/build.gradle.kts` (`defaultConfig`)
- Modify: `CHANGELOG.md`
- Modify: `docs/IMPLEMENTATION_PLAN.md` (ledger row 15)

- [ ] **Step 1: Bump the version** — in `app/build.gradle.kts` change `versionCode = 1` → `versionCode = 2` and `versionName = "0.1.0"` → `versionName = "0.2.0"`. (README's badge already says 0.2.0; the iOS plan aligns `MARKETING_VERSION`.)

- [ ] **Step 2: Cut the changelog** — in `CHANGELOG.md` replace the line `## [Unreleased]` with:

```markdown
## [Unreleased]

---

## [0.2.0] — 2026-10-13

### Security (audit 2026-10-13)

- Gateway config sent as AI context is redacted (`token`, `secret`, `password`, API keys).
- AI-suggested changes start unselected; auth/credential/TLS/exposure changes need an explicit confirmation.
- The cleartext-to-public-IP opt-in applies to the live session immediately (no longer snapshotted at connect).
- Gateway token field is masked with a reveal toggle; the config diff sheet redacts secrets; the profile-edit token is no longer kept in `rememberSaveable`.
- WebSocket upgrades go through the origin-scoped bearer plugin (no hand-added `Authorization` header).

### Fixed (audit 2026-10-13)

- Adding/clearing a token in Settings now updates `hasToken`, so the new token is actually used.
- No crash when reconnecting a known gateway whose token cannot be decrypted, when installing a skill while disconnected, or when saving a profile fails after a successful connect.
- Consecutive config writes refresh the ETag (no spurious 412); the ETag cache never survives a reconnect; AI changes are applied as one nested merge-patch per section.
- The gateway section editor never shows defaults for undecodable JSON and writes exactly the edited leaf.
- Dashboard health polls use the live session client (honours the cleartext opt-in).
- A corrupt or newer-schema profile store is never overwritten.
- QR scanner releases its executor/ML Kit client and navigates once per scan.
- Saving one config section keeps unsaved edits in the others.
- JSON-RPC requests time out and fail when the socket closes instead of hanging.
- Cancellation is no longer reported as an error (`suspendRunCatching`).

### Changed

- Every error message shows the URL, HTTP status and exception class; a cleartext block is reported as such, not as "unreachable".
- Disconnected-state failures are a typed `GatewayNotConnectedException`.
- Lifecycle-aware state collection; buffered one-shot events; the connection picker is state-driven.
- IPv6-safe gateway URLs; `https://` URLs without a port default to 443.
- `AI_PRO_ENABLED` now hides the Dashboard AI entry when false.

### Added (pairing, tests)

- `ghostcrab://pair?p=…` QR codes and deep links prefill the connect form (no auto-connect).
- Tests: error-mapping matrix, Koin graph verification, settings/onboarding repositories, skills repository refresh/uninstall, regression tests for every fix above.
```
The previous `[Unreleased]` content (the April hardening) now sits directly below this block and becomes part of 0.2.0. Rename its three headings so they do not collide — between the new block and `## [0.1.0]` only: `### Security` → `### Security (hardening, PR #100)`, `### Fixed` → `### Fixed (hardening)`, `### Added` → `### Added (release signing)`.

- [ ] **Step 3: Close ledger row 15** — in `docs/IMPLEMENTATION_PLAN.md` change row 15's status to `🟢 Done`, `Completed` to `2026-10-13`, and `Commit SHA` to the output of `git rev-parse --short HEAD`.

- [ ] **Step 4: Full gate and commit**

Run: `./gradlew testDebugUnitTest detekt lintDebug` → `BUILD SUCCESSFUL`; `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.
```bash
git add app/build.gradle.kts CHANGELOG.md docs/IMPLEMENTATION_PLAN.md
git commit -m "chore(release): v0.2.0"
```

---

## Self-review (done while writing; re-run after edits)

- **Spec coverage:** every P1 of the Android audit → Tasks 1–11 (5a–5d, 7a–7b); P2s → 12–20, 23–24, 30; top-15 refactors S/M → 21–27; untested classes → 10, 28–30; lint → 32; docs/version → 35–36; QR pairing → 33a–34. Deferred (ADR needed or L-size) items are listed in the roadmap, M2 rows 2.18–2.19.
- **Placeholders:** none — every code step carries code; every move step names the declarations moved.
- **Names used across tasks:** `REDACTED_PLACEHOLDER`, `isSecretKey`, `redactSecrets`, `isSecurityAffectingConfigPath` (4, 5a, 5d, 20, 33a); `isSecurityAffecting`, `parseSuggestedValue`, `sectionPatches` (5a, 5c, 5d, 7b); `withLeaf` (7a, 7b, 8); `GatewayHealthChecker.check()` (9, 12, 29); `CleartextBlockedException`, `CleartextBlockedIOException`, `GatewayProtocolException(url, detail, cause)` (10, 15, 30); `toUserMessage()` (11, 13, 24); `GatewayNotConnectedException()` (12, 15); `suspendRunCatching` (13, 24); `GatewayWsClient.create` / `forTesting(session, dispatcher, requestTimeoutMs)` (14, 15, 30); `openWebSocket()` (15); `ProfileStoreCorruptedException`, `decodeProfilesForRead/Write` (16); `QrScanUiState.Decoded` (17, 33b); `IoDispatcher`, `ioDispatcher` (18, 19); `GatewayUrl`, `DEFAULT_GATEWAY_PORT: Int` (21a, 21b, 33a, 33b); `ConnectionPickerUiState`, `ConnectionPickerEvent` (24); `PairBundle`, `PairCodeResult`, `decodePairCode` (33a, 33b, 34); `PairingHandoff.offer/take`, `consumePendingPairing` (33b, 34).
