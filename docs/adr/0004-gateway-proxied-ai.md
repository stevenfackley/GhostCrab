# ADR-004: AI Recommendations Proxied Through the Gateway

**Status:** Accepted  
**Date:** 2026-04-14

## Context

The AI Recommendations feature (Phase 8) lets users ask for configuration advice. The AI call could be made directly from the device (e.g., Gemini API key on-device) or routed through the gateway.

## Decision

Route all AI inference through the gateway via `POST /api/ai/recommend`. The gateway invokes its installed AI CLI (e.g., Gemini CLI) and streams or returns the result.

## Reasons

- Provider API keys never leave the user's server. The device only holds a gateway bearer token.
- The gateway already has the hardware context, active config, and model state needed to produce useful recommendations — sending all of this from the device would require an extra round-trip.
- Consistent with GhostCrab's role as a thin client; no provider-specific logic on-device.

## Consequences

- AI recommendations are unavailable if the `skill-ai-recommend` skill is not installed on the gateway. The app surfaces a graceful unavailable state (see `AIServiceUnavailableException`).
- Gateway operator controls which AI provider is used; the app has no opinion on this.
