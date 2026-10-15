# ADR-005: Additive Methods on Frozen Repository Interfaces

**Status:** Accepted  
**Date:** 2026-10-14

## Context

Interfaces in `domain/repository/` are the v1.0 API surface and are frozen. v0.2.0 needed two capabilities the v1.0 interfaces lack: wiping all profile data (including undecodable data) and a single `/health` call on the live session client.

## Decision

Frozen interfaces may gain ADDITIVE members without a new ADR each. Existing signatures never change. Each addition is marked `Additive (v1.1)` in its KDoc (Android) or doc comment (iOS).

The rule covers both platforms: the iOS protocols in `ios/GhostCrab/Domain/Repository/` are frozen the same way.

Instances on `refactor/finish-dev-plan` (PR #125, Android):

- `ConnectionProfileRepository.clearAll()`
- `GatewayHealthChecker` (new interface, implemented by `GatewayConnectionManagerImpl`)

Instances on `feat/protocol-alignment-android` (ADR-0007, Android):

- `InstalledSkillRepository.setSkillEnabled()`
- `InstalledSkill.disabled` (additive model field with a default)
- `GatewayConnection.Connected.reconnecting` and `GatewayConnection.Connected.sessionGeneration` (additive model fields with defaults: `false` while the manager's one silent reconnect runs; a counter that makes each live session a new value)
- `GatewayConnection.Connected.warnings` and `GatewayConnection.Error.warnings` (additive model fields, default `emptyList()`: problems that did not fail the connect, or context for the error)
- `ConnectionWarning` (new sealed interface in `domain/model/` with `DeviceIdentityRegenerated` and `DeviceTokenNotSaved(cause)`; marked `Additive (v1.1)` in its KDoc)
- `GatewayNotConnectedException.reconnectingTo` (additive constructor parameter, default `null`: the URL of the manager's silent reconnect; when set the exception is retryable)

Instances on `feat/ios-finish` (PR #127, iOS):

- `ConnectionProfileRepository.clearAll()` (Swift protocol; implemented by `ConnectionProfileRepositoryImpl` over `ProfileStore.clearAll()`, used by Settings → "Clear all saved gateways", mirroring Android's `SettingsViewModel.confirmClearAllProfiles`)

Instances on `feat/protocol-alignment-ios` (ADR-0007, PR #134, iOS):

- `InstalledSkillRepository.setSkillEnabled(skillKey:enabled:)` (`skills.update`; marked `Additive (v1.1)`)
- `InstalledSkill.disabled` (additive model field, default `false`; a stored value without it decodes as enabled)
- `GatewayConnectionManager.connectionWarnings()` (protocol requirement with a default implementation returning `[]`)
- `ConnectionWarning` (new enum in `Domain/Model/` with `deviceIdentityRegenerated` and `deviceTokenNotSaved`; mirrors Android's)
- `ModelRepository.getModelCatalogue()` and `ModelCatalogue` (`models` + `unlistedPrimary`; default implementation wraps `getModels()` with no unlisted primary)
- `DeviceIdentityProviding.regenerated` (`Data/Storage` protocol, not a frozen `Domain/Repository` one; default `false`; true once an unreadable seed was replaced)
- `GatewayConnection.connected(…, reconnecting:sessionGeneration:)` (additive associated values with defaults `false` / `0`, mirroring Android's fields: `true` while the manager's one silent reconnect runs; a counter that makes each live session a new value) and the helpers `isReconnecting`, `sessionGeneration` and `sessionCapabilities`. Constructing the case is source compatible; a pattern that binds every associated value needs two more `_`

Contract relaxation, same PR: `ModelRepository.getModels()` documented "exactly one entry has `isActive == true`"; it now reads "at most one". The real gateway can name a primary model the catalogue lacks (or a bare id several providers list), so nothing is active then. No signature changed; callers that assumed exactly one must handle none.

Other doc-only changes to frozen iOS interfaces in that PR describe the real transport (`GatewayConnectionManager.probeAuth`, `ScopeProbe`, `AIRecommendationService`); `ScopeProbeResult.unknownOldGateway` is no longer produced but stays for source compatibility. Non-repository changes the ADR-0007 consequences cover: new `GatewayError` cases; `SkillInstallError.protocol(code:message:)`, which replaced `protocol(rpcCode: Int, message:)` (a change, not an addition: protocol-4 error codes are strings, and ADR-0007 sanctions it); and the `PairingLink` setup-code and `ws(s)` helpers.

## Reasons

- Additive members do not break existing callers or v1.0 contracts.
- An ADR per method is overhead; this one records the rule and the current instances.

## Consequences

- Changing or removing an existing member still requires a new ADR.
- Every additive member carries the `Additive (v1.1)` marker in its KDoc or doc comment so it can be audited with a grep.
