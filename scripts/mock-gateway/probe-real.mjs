#!/usr/bin/env node
// Re-checks a real OpenClaw gateway against the behaviour pinned in fixtures/gateway-protocol/2026.10.3 (run it when a
// new gateway release ships). Uses the conformance tests' client; prints frames, never tokens.
// usage: node probe-real.mjs <ws-url> <shared-token> [pair|no-token|config|skills]
//   pair      connect with a persistent identity (.probe-identity.json, gitignored) and print hello-ok.auth
//   no-token  credential-less connect from a never-seen device: does the gateway answer AUTH_TOKEN_MISSING (what
//             probeAuth assumes) or PAIRING_REQUIRED first? Not captured so far.
//   config  config.patch with a wrong and with no baseHash (both rejected; nothing changes)
//   skills  skills.update disable+enable of SKILL_KEY (default browser-automation) with the config.changed timing;
//           optional INSTALL_SLUG install
import fs from "node:fs";
import { connect } from "./lib/client.mjs";
import { newIdentity } from "./lib/device.mjs";
import { redactedJson } from "./lib/redact.mjs";
// Every value printed goes through here: an auto-approving gateway puts a device token in hello-ok.auth.
const print = (label, value) => console.log(label, redactedJson(value));
const [url, token, scenario = "pair"] = process.argv.slice(2);
if (!url || !token) {
  console.error("usage: node probe-real.mjs <ws-url> <shared-token> [pair|no-token|config|skills]");
  process.exit(2);
}
if (scenario === "no-token") {
  const probe = await connect(url, { identity: newIdentity() });
  print("credential-less connect, new device:", probe.hello ? probe.hello.auth : probe.error);
  probe.close();
  process.exit(0);
}
const identityFile = new URL("./.probe-identity.json", import.meta.url); // holds a private key: gitignored
const identity = fs.existsSync(identityFile) ? JSON.parse(fs.readFileSync(identityFile, "utf8")) : newIdentity();
fs.writeFileSync(identityFile, JSON.stringify(identity), { mode: 0o600 });
const session = await connect(url, { identity, auth: { token } });
if (!session.hello) {
  print("connect error:", session.error);
  const requestId = session.error?.details?.requestId;
  if (requestId) console.log(`approve on the gateway host: openclaw devices approve ${requestId}`);
  process.exit(1);
}
print("hello-ok.auth:", session.hello.auth);
if (scenario === "config") {
  print("config.patch, stale baseHash:", await session.request("config.patch", { raw: "{}", baseHash: "stale" }));
  print("config.patch, no baseHash:", await session.request("config.patch", { raw: JSON.stringify({ gateway: { port: 18789 } }) }));
} else if (scenario === "skills") {
  const skillKey = process.env.SKILL_KEY ?? "browser-automation";
  for (const enabled of [false, true]) {
    const started = Date.now();
    print(`skills.update enabled=${enabled}:`, await session.request("skills.update", { skillKey, enabled }));
    const changed = await session.nextEvent("config.changed");
    console.log(`config.changed ${changed ? `after ${Date.now() - started} ms` : "not seen within 2 s"}`);
  }
  if (process.env.INSTALL_SLUG) {
    print("skills.install:", await session.request("skills.install", { source: "clawhub", slug: process.env.INSTALL_SLUG, force: false, timeoutMs: 120000 }));
  }
  const status = await session.request("skills.status");
  print("skills.status sources:", [...new Set(status.payload.skills.map((skill) => skill.source))]);
}
session.close();
