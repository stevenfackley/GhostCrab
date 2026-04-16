# ADR-002: Auth-Mode Detection via /health + /status Asymmetry

**Status:** Accepted  
**Date:** 2026-04-14

## Context

GhostCrab must determine whether a gateway requires authentication before displaying a token field to the user. The gateway does not expose a dedicated `/auth-mode` endpoint in the v1.0 API.

## Decision

Probe auth mode by:
1. GET `/health` — unauthenticated; success confirms reachability.
2. GET `/status` (no token) — if 401/403 → `AuthRequirement.Token`; if 200 → `AuthRequirement.None`.

Implemented in `GatewayConnectionManagerImpl.probeAuth()`.

## Reasons

- Avoids adding a new Gateway endpoint to detect auth mode (Gateway team was not available for coordination).
- `/health` is intentionally unauthenticated; `/status` requires auth when auth is enabled.

## Consequences

- Heuristic: if a gateway returns 200 on `/status` without a token, we infer no auth. A future gateway version could add a dedicated `/auth-mode` endpoint (ADR-002b) to make this explicit.
- Password/OAuth modes unsupported at v1.0 — both map to `AuthRequirement.Token` and are documented.
