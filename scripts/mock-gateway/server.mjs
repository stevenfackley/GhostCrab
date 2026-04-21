#!/usr/bin/env node
// Mock OpenClaw Gateway — implements the API surface GhostCrab was built against.
//
// Endpoints (HTTP):
//   GET    /health
//   GET    /status
//   GET    /config                        → JSON body + ETag header
//   PATCH  /config/{section}              → If-Match supported; 412/422 on mismatch/invalid
//   GET    /api/models/status             → ModelDto[]
//   POST   /api/models/active             → {id}
//   POST   /api/ai/recommend              → {query, context}
//
// WebSocket JSON-RPC 2.0 at /ws:
//   auth.whoami               → {scopes}
//   skills.list               → {skills}
//   skills.install            → InstalledSkill + 'skills.install.progress' notifications
//   skills.uninstall          → null
//
// Env:
//   PORT       default 18790 (avoids conflict with real gateway on 18789)
//   HOST       default 0.0.0.0 (reachable from LAN/device)
//   TOKEN      if set, requires `Authorization: Bearer <TOKEN>` for /api/*, /config*, /ws
//   SCOPES     comma-separated; default "operator.read,operator.admin"
//   FAIL_MODE  comma-separated: "models-404" "ai-404" "ai-429" "install-unauth" "install-notfound"

import http from "node:http";
import { randomUUID, createHash } from "node:crypto";
import { WebSocketServer } from "ws";

const PORT = Number(process.env.PORT ?? 18790);
const HOST = process.env.HOST ?? "0.0.0.0";
const TOKEN = process.env.TOKEN ?? null;
const SCOPES = (process.env.SCOPES ?? "operator.read,operator.admin").split(",").map(s => s.trim()).filter(Boolean);
const FAIL = new Set((process.env.FAIL_MODE ?? "").split(",").map(s => s.trim()).filter(Boolean));

// ── In-memory state ────────────────────────────────────────────────────────
const state = {
  config: {
    gateway: { port: 18790, cleartext: true, bind: "0.0.0.0" },
    models: { active: "claude-sonnet-4-6", timeout_ms: 30000 },
    skills: { autoUpdate: false, watch: true },
  },
  configEtag: null,
  models: [
    { id: "claude-sonnet-4-6", provider: "anthropic", displayName: "Claude Sonnet 4.6",
      isActive: true, status: "ready", capabilities: ["chat", "tools", "vision"] },
    { id: "claude-opus-4-7", provider: "anthropic", displayName: "Claude Opus 4.7",
      isActive: false, status: "ready", capabilities: ["chat", "tools", "vision", "reasoning"] },
    { id: "gpt-4o", provider: "openai", displayName: "GPT-4o",
      isActive: false, status: "auth-error", capabilities: ["chat", "tools"] },
    { id: "llama-3.3-70b", provider: "ollama", displayName: "Llama 3.3 70B",
      isActive: false, status: "loading", capabilities: ["chat"] },
  ],
  skills: [
    { slug: "openclaw/ai-recommender", installed_version: "1.2.0", source: "clawhub", installed_at: Date.now() - 86_400_000 },
    { slug: "wanng-ide/auto-skill-hunter", installed_version: "0.4.1", source: "clawhub", installed_at: Date.now() - 3600_000 },
  ],
};
refreshEtag();

function refreshEtag() {
  const hash = createHash("sha1").update(JSON.stringify(state.config)).digest("hex");
  state.configEtag = `W/"${hash.slice(0, 12)}"`;
}

// ── HTTP helpers ───────────────────────────────────────────────────────────
function json(res, status, body, extra = {}) {
  const payload = Buffer.from(JSON.stringify(body));
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": payload.length,
    "cache-control": "no-store",
    ...extra,
  });
  res.end(payload);
}
function text(res, status, body) {
  res.writeHead(status, { "content-type": "text/plain; charset=utf-8" });
  res.end(body);
}
async function readBody(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const raw = Buffer.concat(chunks).toString("utf8");
  if (!raw) return null;
  try { return JSON.parse(raw); } catch { return { __raw: raw, __parseError: true }; }
}
function requiresAuth(req) {
  if (!TOKEN) return null;
  const h = req.headers.authorization;
  if (h === `Bearer ${TOKEN}`) return null;
  return { status: 401, error: "missing or invalid bearer token" };
}

// ── HTTP routes ────────────────────────────────────────────────────────────
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const path = url.pathname;
  const method = req.method ?? "GET";
  const log = (...a) => console.log(`[${new Date().toISOString()}]`, method, path, ...a);

  // CORS (allow anything — this is a dev mock)
  res.setHeader("access-control-allow-origin", "*");
  res.setHeader("access-control-allow-methods", "GET, POST, PATCH, DELETE, OPTIONS");
  res.setHeader("access-control-allow-headers", "authorization, content-type, if-match");
  if (method === "OPTIONS") { res.writeHead(204); res.end(); return; }

  try {
    // Public endpoints
    if (method === "GET" && path === "/health") {
      log("→ 200");
      return json(res, 200, { status: "ok" });
    }
    if (method === "GET" && path === "/ready") {
      log("→ 200");
      return json(res, 200, { ok: true, status: "live" });
    }
    if (method === "GET" && path === "/status") {
      log("→ 200");
      return json(res, 200, {
        displayName: "Mock OpenClaw Gateway",
        version: "mock-0.1.0",
        capabilities: ["models", "config", "ai.recommend", "skills.install"],
        hardware: "x86_64 · 32GB · Windows 11 (mock)",
      });
    }

    // Protected endpoints
    const authErr = requiresAuth(req);
    if (authErr) { log("→ 401"); return json(res, 401, { error: authErr.error }); }

    if (method === "GET" && path === "/config") {
      log("→ 200 etag=" + state.configEtag);
      return json(res, 200, state.config, { etag: state.configEtag });
    }

    const cfgMatch = path.match(/^\/config\/([a-zA-Z0-9_-]+)$/);
    if (method === "PATCH" && cfgMatch) {
      const section = cfgMatch[1];
      const ifMatch = req.headers["if-match"];
      if (ifMatch && ifMatch !== state.configEtag) {
        log("→ 412 ifMatch=" + ifMatch + " current=" + state.configEtag);
        return json(res, 412, { error: "etag mismatch", current: state.configEtag });
      }
      const body = await readBody(req);
      if (!body || body.__parseError) {
        log("→ 422 malformed");
        return json(res, 422, { field: section, reason: "malformed JSON body" });
      }
      // Demo validation: reject negative timeouts
      if (section === "models" && typeof body.timeout_ms === "number" && body.timeout_ms < 0) {
        log("→ 422 negative timeout");
        return json(res, 422, { field: "timeout_ms", reason: "must be non-negative" });
      }
      state.config[section] = { ...(state.config[section] ?? {}), ...body };
      refreshEtag();
      log("→ 204 newEtag=" + state.configEtag);
      res.writeHead(204, { etag: state.configEtag });
      return res.end();
    }

    if (method === "GET" && path === "/api/models/status") {
      if (FAIL.has("models-404")) { log("→ 404 (FAIL_MODE)"); return text(res, 404, "not found"); }
      log("→ 200 models=" + state.models.length);
      return json(res, 200, state.models);
    }
    if (method === "POST" && path === "/api/models/active") {
      const body = await readBody(req) ?? {};
      const target = state.models.find(m => m.id === body.id);
      if (!target) { log("→ 404 id=" + body.id); return json(res, 404, { error: "unknown model id" }); }
      state.models = state.models.map(m => ({ ...m, isActive: m.id === target.id }));
      state.config.models = { ...state.config.models, active: target.id };
      refreshEtag();
      log("→ 204 active=" + target.id);
      res.writeHead(204);
      return res.end();
    }

    if (method === "POST" && path === "/api/ai/recommend") {
      if (FAIL.has("ai-404")) { log("→ 404 (FAIL_MODE)"); return json(res, 404, { error: "ai skill not installed" }); }
      if (FAIL.has("ai-429")) { log("→ 429 (FAIL_MODE)"); return json(res, 429, { error: "quota exceeded" }); }
      const body = await readBody(req) ?? {};
      const query = (body.query ?? "").slice(0, 200);
      log("→ 200 query=\"" + query + "\"");
      return json(res, 200, {
        recommendation:
          `Mock recommendation for "${query}". Consider reducing timeout_ms and enabling skills watch for ` +
          `faster iteration. This is placeholder content from mock-gateway.`,
        suggested_changes: [
          { section: "models", key: "timeout_ms", current_value: "30000", suggested_value: "15000",
            rationale: "lower latency for interactive queries" },
          { section: "skills", key: "watch", current_value: "true", suggested_value: "true",
            rationale: "already optimal" },
        ],
      });
    }

    log("→ 404 (no route)");
    text(res, 404, "not found");
  } catch (err) {
    console.error("ERROR", err);
    json(res, 500, { error: String(err?.message ?? err) });
  }
});

// ── WebSocket JSON-RPC ─────────────────────────────────────────────────────
const wss = new WebSocketServer({ noServer: true });

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  if (url.pathname !== "/ws") { socket.destroy(); return; }
  if (TOKEN) {
    const h = req.headers.authorization;
    if (h !== `Bearer ${TOKEN}`) {
      socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n");
      socket.destroy();
      return;
    }
  }
  wss.handleUpgrade(req, socket, head, ws => wss.emit("connection", ws, req));
});

wss.on("connection", ws => {
  const peer = randomUUID().slice(0, 8);
  console.log(`[ws ${peer}] connected`);

  ws.on("close", () => console.log(`[ws ${peer}] closed`));
  ws.on("error", e => console.log(`[ws ${peer}] error`, e.message));

  ws.on("message", raw => {
    let msg;
    try { msg = JSON.parse(raw.toString()); }
    catch { return ws.send(JSON.stringify({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "parse error" } })); }

    const { id, method, params } = msg;
    console.log(`[ws ${peer}] →`, method, id ?? "(notif)");

    const reply = (result) => ws.send(JSON.stringify({ jsonrpc: "2.0", id, result }));
    const rpcError = (code, message) => ws.send(JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } }));

    switch (method) {
      case "auth.whoami":
        reply({ scopes: SCOPES, nodeId: "mock-node", version: "mock-0.1.0" });
        break;

      case "skills.list":
        reply({ skills: state.skills });
        break;

      case "skills.install": {
        if (FAIL.has("install-unauth")) return rpcError(-32003, "operator.admin required");
        if (FAIL.has("install-notfound")) return rpcError(-32004, params?.slug ?? "unknown");
        const { slug, version } = params ?? {};
        if (!slug) return rpcError(-32602, "slug required");
        // Stream progress notifications
        const notif = (evt) => ws.send(JSON.stringify({ jsonrpc: "2.0", method: "skills.install.progress", params: evt }));
        const sha = createHash("sha256").update(slug).digest("hex");
        setTimeout(() => notif({ phase: "downloading", pct: 10 }), 150);
        setTimeout(() => notif({ phase: "downloading", pct: 55 }), 450);
        setTimeout(() => notif({ phase: "downloading", pct: 100 }), 750);
        setTimeout(() => notif({ phase: "verifying", sha256: sha }), 900);
        setTimeout(() => notif({ phase: "applying", step: "linking skill binaries" }), 1100);
        setTimeout(() => {
          const existing = state.skills.find(s => s.slug === slug);
          if (existing) { existing.installed_version = version ?? "latest"; existing.installed_at = Date.now(); }
          else {
            state.skills.push({
              slug, installed_version: version ?? "latest", source: "clawhub", installed_at: Date.now(),
            });
          }
          const skill = state.skills.find(s => s.slug === slug);
          reply(skill);
        }, 1400);
        break;
      }

      case "skills.uninstall": {
        const slug = params?.slug;
        if (!slug) return rpcError(-32602, "slug required");
        const before = state.skills.length;
        state.skills = state.skills.filter(s => s.slug !== slug);
        if (state.skills.length === before) return rpcError(-32004, slug);
        reply(null);
        break;
      }

      default:
        rpcError(-32601, `method not found: ${method}`);
    }
  });
});

// ── Bootstrap ──────────────────────────────────────────────────────────────
server.listen(PORT, HOST, () => {
  const bind = HOST === "0.0.0.0" ? "(all interfaces)" : HOST;
  console.log(`mock-gateway listening on http://${HOST}:${PORT} ${bind}`);
  console.log(`  token       : ${TOKEN ? "required" : "disabled"}`);
  console.log(`  scopes      : ${SCOPES.join(", ") || "(none)"}`);
  console.log(`  fail modes  : ${[...FAIL].join(", ") || "(none)"}`);
  console.log(`  models      : ${state.models.length}, skills : ${state.skills.length}`);
  console.log(`  ws endpoint : ws://${HOST}:${PORT}/ws`);
});
