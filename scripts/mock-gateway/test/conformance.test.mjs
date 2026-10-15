import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { createMockGateway } from "../server.mjs";
import { connect, DEFAULT_CLIENT, OPERATOR_SCOPES } from "../lib/client.mjs";
import { newIdentity } from "../lib/device.mjs";

const FIXTURES = new URL("../../../fixtures/gateway-protocol/2026.10.3/", import.meta.url);
const fixture = (name) => JSON.parse(fs.readFileSync(new URL(`${name}.json`, FIXTURES), "utf8"));
const keys = (object) => Object.keys(object).sort();
const TOKEN = "shared-token";
let gateway;
let url;
let base;

before(async () => {
  gateway = createMockGateway({ token: TOKEN });
  const { port } = await gateway.listen(0, "127.0.0.1");
  url = `ws://127.0.0.1:${port}/ws`;
  base = `http://127.0.0.1:${port}`;
});
after(() => gateway.close());

const approve = () => fetch(`${base}/__mock/devices/approve`, { method: "POST" });

async function pairedSession(identity = newIdentity()) {
  const first = await connect(url, { identity, auth: { token: TOKEN } });
  assert.equal(first.error?.details?.code, "PAIRING_REQUIRED");
  first.close();
  await approve();
  const second = await connect(url, { identity, auth: { token: TOKEN } });
  assert.ok(second.hello, JSON.stringify(second.error));
  return second;
}

const skillNamed = async (session, key) =>
  (await session.request("skills.status")).payload.skills.find((skill) => skill.skillKey === key);

test("HTTP surface: JSON probes, HTML elsewhere, 404 for /api", async () => {
  const health = await fetch(`${base}/health`);
  assert.equal(health.headers.get("content-type"), "application/json");
  assert.deepEqual(await health.json(), { ok: true, status: "live" });
  assert.match((await fetch(`${base}/status`)).headers.get("content-type"), /text\/html/);
  assert.equal((await fetch(`${base}/api/models/status`)).status, 404);
});

test("a non-connect first frame closes 1008", async () => {
  const ws = new WebSocket(url);
  const closed = new Promise((resolve) => ws.addEventListener("close", resolve));
  await new Promise((resolve) => ws.addEventListener("message", resolve, { once: true }));
  ws.send(JSON.stringify({ jsonrpc: "2.0", id: 1, method: "auth.whoami", params: null }));
  const ev = await closed;
  assert.equal(ev.code, 1008);
  assert.equal(ev.reason, "invalid request frame");
});

test("credential and device failures carry the captured error shapes", async () => {
  assert.deepEqual((await connect(url, { withDevice: false })).error, fixture("err-device-identity-required").error);
  assert.deepEqual((await connect(url, {})).error, fixture("err-token-missing").error);
  assert.deepEqual((await connect(url, { auth: { token: "wrong" } })).error, fixture("err-token-mismatch").error);
  assert.equal((await connect(url, { auth: { token: TOKEN }, tamperSignature: true })).error.details.code, "DEVICE_AUTH_SIGNATURE_INVALID");
});

test("a token without device identity gets no scopes, like the real gateway", async () => {
  const s = await connect(url, { withDevice: false, auth: { token: TOKEN } });
  assert.deepEqual(s.hello.auth.scopes, []);
  assert.deepEqual((await s.request("status")).error, fixture("err-missing-scope").error);
  assert.equal((await s.request("health")).ok, true);
  s.close();
});

test("client id and mode are allow-lists checked before auth", async () => {
  const badId = await connect(url, { client: { ...DEFAULT_CLIENT, id: "ghostcrab-android" }, auth: { token: TOKEN } });
  assert.deepEqual(badId.error, fixture("err-client-id-invalid").error);
  assert.equal((await badId.closed).code, 1008);
  const badMode = await connect(url, { client: { ...DEFAULT_CLIENT, mode: "operator" }, auth: { token: TOKEN } });
  assert.match(badMode.error.message, /at \/client\/mode: must be equal to one of the allowed values/);
});

test("pairing: one stable requestId until approved, then scopes and a reusable device token", async () => {
  const identity = newIdentity();
  const first = await connect(url, { identity, auth: { token: TOKEN } });
  const expected = fixture("err-pairing-required").error;
  assert.equal(first.error.code, expected.code);
  assert.deepEqual(keys(first.error.details), keys(expected.details));
  assert.equal((await first.closed).code, 1008);
  const retry = await connect(url, { identity, auth: { token: TOKEN } });
  assert.equal(retry.error.details.requestId, first.error.details.requestId);
  await approve();
  const second = await connect(url, { identity, auth: { token: TOKEN } });
  assert.deepEqual(second.hello.auth.scopes, OPERATOR_SCOPES);
  assert.deepEqual(keys(second.hello), keys(fixture("hello-ok-paired").payload));
  second.close();
  const reuse = await connect(url, { identity, auth: { deviceToken: second.hello.auth.deviceToken }, scopes: ["operator.read"] });
  assert.equal(reuse.hello.auth.method, "device-token");
  assert.deepEqual(reuse.hello.auth.scopes, ["operator.read"]);
  reuse.close();
});

test("a changed platform needs re-approval; a changed client id or letter case does not", async () => {
  const identity = newIdentity();
  (await pairedSession(identity)).close();
  const otherId = await connect(url, { identity, client: { ...DEFAULT_CLIENT, id: "openclaw-ios" }, auth: { token: TOKEN } });
  assert.ok(otherId.hello, JSON.stringify(otherId.error));
  otherId.close();
  const upperCase = await connect(url, { identity, client: { ...DEFAULT_CLIENT, platform: "Android" }, auth: { token: TOKEN } });
  assert.ok(upperCase.hello, JSON.stringify(upperCase.error));
  upperCase.close();
  const moved = await connect(url, { identity, client: { ...DEFAULT_CLIENT, platform: "ios" }, auth: { token: TOKEN } });
  assert.equal(moved.error.details.reason, "metadata-upgrade");
  assert.deepEqual(keys(moved.error.details), keys(fixture("err-pairing-metadata-upgrade").error.details));
});

test("skills: verified shapes, and skills.status catches up on config.changed", async () => {
  const s = await pairedSession();
  const status = await s.request("skills.status");
  for (const key of ["name", "skillKey", "source", "bundled", "disabled"]) assert.ok(key in status.payload.skills[0], key);
  assert.deepEqual((await s.request("skills.list")).error, fixture("err-unknown-method").error);
  const notFound = await s.request("skills.install", { source: "clawhub", slug: "missing-skill", force: false });
  assert.equal(notFound.error.code, "UNAVAILABLE");
  assert.match(notFound.error.message, /not found on ClawHub/);
  const installed = await s.request("skills.install", { source: "clawhub", slug: "@someone/demo-skill", force: false });
  assert.deepEqual(keys(installed.payload), keys(fixture("res-skills-install").payload));
  assert.equal(installed.payload.slug, "demo-skill");
  const entry = await skillNamed(s, "demo-skill");
  assert.equal(entry.source, "openclaw-workspace");
  assert.equal(entry.clawhub.installedVersion, "1.0.0");
  const update = await s.request("skills.update", { skillKey: "demo-skill", enabled: false });
  assert.deepEqual(keys(update.payload), keys(fixture("res-skills-update").payload));
  assert.equal((await skillNamed(s, "demo-skill")).disabled, false); // stale until config.changed
  assert.ok(await s.nextEvent("config.changed"));
  assert.equal((await skillNamed(s, "demo-skill")).disabled, true);
  assert.equal((await s.request("skills.update", { skillKey: "no-such-skill", enabled: false })).ok, true);
  assert.ok(Array.isArray((await s.request("models.list")).payload.models));
  s.close();
});

test("config.patch: verified error and success shapes, redacted secrets restored", async () => {
  const s = await pairedSession();
  const first = (await s.request("config.get")).payload;
  assert.equal(first.parsed.gateway.auth.token, "__OPENCLAW_REDACTED__");
  const raw = JSON.stringify({ gateway: { port: 18790 } });
  assert.deepEqual((await s.request("config.patch", { raw, baseHash: "stale" })).error, fixture("err-config-stale-hash").error);
  assert.match((await s.request("config.patch", { raw })).error.message, /^config base hash required for gateway\.port; /);
  const tooBig = await s.request("config.patch", { raw: JSON.stringify({ gateway: { port: 99999 } }), baseHash: first.hash });
  assert.deepEqual(tooBig.error, fixture("err-config-invalid").error);
  const patch = { gateway: { port: 18790, auth: { token: "__OPENCLAW_REDACTED__" } } };
  const done = await s.request("config.patch", { raw: JSON.stringify(patch), baseHash: first.hash });
  assert.deepEqual(keys(done.payload), keys(fixture("res-config-patch").payload));
  assert.ok(await s.nextEvent("config.changed"));
  const next = (await s.request("config.get")).payload;
  assert.equal(next.parsed.gateway.port, 18790);
  assert.equal(next.hash, done.payload.hash);
  assert.notEqual(next.hash, first.hash);
  assert.equal(gateway.state.config.gateway.auth.token, TOKEN);
  s.close();
});

test("setup codes never authenticate an operator client", async () => {
  const { bootstrapToken, setupCode } = await (await fetch(`${base}/__mock/setup-code`, { method: "POST" })).json();
  assert.equal(JSON.parse(Buffer.from(setupCode, "base64url").toString()).bootstrapToken, bootstrapToken);
  const attempt = await connect(url, { auth: { bootstrapToken } });
  assert.equal(attempt.error.details.code, "AUTH_BOOTSTRAP_TOKEN_INVALID");
  assert.equal(attempt.error.details.recommendedNextStep, "review_auth_configuration");
});
