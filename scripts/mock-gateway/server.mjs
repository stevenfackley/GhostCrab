#!/usr/bin/env node
// Mock OpenClaw gateway speaking the real control plane (WS protocol 4), as captured from
// ghcr.io/openclaw/openclaw 2026.10.3 (docs/QA_REAL_GATEWAY_2026-10.md, docs/adr/0007-gateway-protocol-alignment.md).
//   HTTP: /health, /ready (JSON); Control-UI-style HTML for other GETs; 404 text/plain for /api/* and other methods.
//   WS (any path): connect.challenge -> signed connect -> hello-ok | PAIRING_REQUIRED, then req/res RPCs, and
//   config.changed events ~80 ms after writes (or before the write's response with CONFIG_CHANGED_EARLY=1).
//   Mock-only routes (never on a real gateway): POST /__mock/devices/approve, POST /__mock/setup-code.
// Env: PORT (18790), HOST (0.0.0.0), TOKEN (shared token; unset = auth mode none), SCOPES (granted subset),
//      AUTO_APPROVE=1 (approve new devices at once), FAIL_MODE (comma list: install-unavailable),
//      MOCK_DISPLAY_NAME (presence host = the app's gateway name), MOCK_VERSION (server.version),
//      CONFIG_CHANGED_EARLY=1 (config.changed before the write's response, an ordering the real gateway also shows),
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
  configChangedEarly = false,
} = {}) {
  const fail = new Set(failModes);
  const state = {
    paired: new Map(), // deviceId -> { deviceToken, scopes, platform }
    pending: new Map(), // deviceId -> { requestId, scopes, platform }
    config: {
      gateway: { mode: "local", port: 18789, bind: "lan", auth: token ? { mode: "token", token } : { mode: "none" } },
      // A real gateway's config names a primary model; the ready one below, so apps show it active.
      agents: { defaults: { model: { primary: "anthropic/claude-sonnet-4-6" } }, entries: { main: {} } },
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
  /**
   * After a write: applies [catchUp] (what the next read reflects) and emits config.changed, ~80 ms later by
   * default, or at once with configChangedEarly, i.e. before the caller sends the write's response.
   */
  const afterWrite = (catchUp = () => {}) => {
    const changed = () => {
      catchUp();
      broadcastConfigChanged();
    };
    if (configChangedEarly) changed();
    else later(changed);
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
          return ws.close(1008, closeReason(result.error));
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

  /** Verified: the close reason is the error message, plus ` (requestId: …)` for a pairing request. */
  const closeReason = (error) => {
    const requestId = error.details?.code === "PAIRING_REQUIRED" ? error.details.requestId : null;
    return `${error.message}${requestId ? ` (requestId: ${requestId})` : ""}`.slice(0, 123);
  };
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
    afterWrite(() => {
      const skill = state.skills.find((entry) => entry.skillKey === skillKey);
      if (skill) skill.disabled = !enabled;
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
    afterWrite();
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
      configChangedEarly: process.env.CONFIG_CHANGED_EARLY === "1",
    });
    const address = await gateway.listen(Number(process.env.PORT || 18790), process.env.HOST || "0.0.0.0");
    console.log(`mock OpenClaw gateway (protocol ${PROTOCOL}) on ${address.address}:${address.port}`);
    console.log(`approve pending devices: curl -X POST http://127.0.0.1:${address.port}/__mock/devices/approve`);
  }
}
