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
| `CONFIG_CHANGED_EARLY` | *(off)* | `1` sends `config.changed` *before* the `skills.update` / `config.patch` response, an ordering the real gateway also produces; clients must subscribe to events before sending the write. `createMockGateway({ configChangedEarly: true })` in tests |

Methods: `health`, `status`, `models.list`, `skills.status`, `skills.install`, `skills.update`, `config.get`,
`config.patch` (`baseHash` required; `gateway.port` validated; `__OPENCLAW_REDACTED__` restores the secret).
Anything else answers `INVALID_REQUEST unknown method: …`, as upstream. Writes (`config.patch`, `skills.update`)
emit `config.changed` ~80 ms later (before the response with `CONFIG_CHANGED_EARLY=1`), and `skills.status`
reflects a `skills.update` only from then on, as upstream. The seeded config sets `agents.defaults.model.primary`
to `anthropic/claude-sonnet-4-6`, so the apps show that model active.
`client.id` must be `openclaw-android` or `openclaw-ios` and `client.mode` `ui` or `cli`; a paired device that
changes `platform` must be approved again. A refused `connect` is followed by close `1008` with the error message as
the reason; for `PAIRING_REQUIRED` the reason ends in ` (requestId: <id>)`, as upstream.

## Tests

```bash
npm test
```

CI runs them in the `mock-gateway` job of `.github/workflows/ci.yml`.

## Probing a real gateway

`node probe-real.mjs ws://<host>:18789/ws <token> [pair|no-token|config|skills]` connects with a persistent device
identity (`.probe-identity.json`, gitignored) and prints frames with every token and password redacted
(`lib/redact.mjs`). Run it when a new gateway release ships, to re-check what the fixtures pin (`baseHash` errors,
`skills.update` and the `config.changed` timing, ClawHub skill entries).

## Not covered

TLS (`wss://`), JSON5 in `config.patch` (`raw` must be JSON), events beyond the challenge and `config.changed`,
scope/role upgrade requests, and `role: "node"` clients (the only ones setup codes serve).
