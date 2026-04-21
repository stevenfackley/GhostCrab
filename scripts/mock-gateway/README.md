# mock-gateway

Stand-in OpenClaw Gateway for manual QA of GhostCrab.

The real upstream gateway (`ghcr.io/openclaw/openclaw`) does **not** implement the
JSON API GhostCrab was built against — `/api/models/*`, `/api/ai/recommend`, raw
JSON-RPC over `/ws` with `skills.list` / `skills.uninstall` — those endpoints
were speculative spec that never shipped. See
[`IMPLEMENTATION_PLAN.md`](../../IMPLEMENTATION_PLAN.md) (phase 7 ledger).

This mock implements the spec GhostCrab expects, so Models, Config editor, AI
Recommend, and Skills-Install can be exercised end-to-end on a real device.

## Start

```bash
cd scripts/mock-gateway
npm install      # once
npm start        # listens on :18790 by default
```

`HOST=0.0.0.0` by default, so the device on your LAN can reach it.

## Point the app at it

1. Find your workstation's LAN IP (Windows: `ipconfig` → IPv4 of Wi-Fi/Ethernet).
2. In GhostCrab's Manual Entry: `http://<LAN-IP>:18790`, auth: `none` (unless you
   set `TOKEN`).
3. Connect. Models, Config, AI Recommend, Installed Skills should all populate.

## Env vars

| Var        | Default                         | Purpose |
|------------|---------------------------------|---------|
| `PORT`     | `18790`                         | avoids conflict with real gateway on 18789 |
| `HOST`     | `0.0.0.0`                       | `127.0.0.1` to lock to workstation |
| `TOKEN`    | *(none — unauth)*               | require `Authorization: Bearer <TOKEN>` |
| `SCOPES`   | `operator.read,operator.admin`  | controls `auth.whoami` response |
| `FAIL_MODE`| *(none)*                        | comma list — force failure paths |

`FAIL_MODE` values:
- `models-404` — `GET /api/models/status` → 404 (exercise empty-state UI)
- `ai-404` — `POST /api/ai/recommend` → 404 (simulate missing AI skill)
- `ai-429` — `POST /api/ai/recommend` → 429 (quota exhausted)
- `install-unauth` — `skills.install` → RPC error −32003 (missing `operator.admin`)
- `install-notfound` — `skills.install` → RPC error −32004 (unknown slug)

Example:

```bash
TOKEN=abc123 SCOPES=operator.read FAIL_MODE=install-unauth npm start
```

## What it covers

HTTP:
- `GET /health`, `GET /ready`, `GET /status`
- `GET /config` (returns body + `ETag`)
- `PATCH /config/{section}` (honors `If-Match`, 412 on mismatch, 422 on invalid)
- `GET /api/models/status`, `POST /api/models/active`
- `POST /api/ai/recommend`

WebSocket JSON-RPC at `/ws`:
- `auth.whoami` → `{scopes, nodeId, version}`
- `skills.list` → `{skills: InstalledSkill[]}`
- `skills.install` → `InstalledSkill`, streams 5 `skills.install.progress` notifications
- `skills.uninstall` → `null`

## Smoke-test

```bash
npm start            # in one terminal
node smoke-ws.mjs    # in another — exercises every WS method
```

## Out of scope

- No persistence across restarts. State is in-memory.
- No TLS. Add a reverse proxy if you need HTTPS/WSS.
- Does **not** mimic the real gateway's event-envelope + pairing handshake
  (`connect.challenge`, `device.pair.*`). GhostCrab doesn't implement that side
  either, so both stay consistent.
