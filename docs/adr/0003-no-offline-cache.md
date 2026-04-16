# ADR-003: Stateless Client — No Offline Config Cache

**Status:** Accepted  
**Date:** 2026-04-14

## Context

Should GhostCrab cache the gateway's `openclaw.json` locally so users can view (or edit) config while the gateway is unreachable?

## Decision

No. GhostCrab is always stateless with respect to gateway configuration.

## Reasons

- Target users are technically literate admins who connect to their own infrastructure. Stale cached config is actively harmful (user edits a cached value that was already changed on the gateway).
- Synchronisation logic for detecting and merging conflicts is non-trivial and out of scope for v1.0.
- Config reads are fast (LAN latency); the use case for offline editing is weak.

## Consequences

- The app is unusable without an active gateway connection — intentional.
- If demand for offline read-only view emerges post-v1.0, a read-only snapshot with a prominent staleness warning is the correct approach.
