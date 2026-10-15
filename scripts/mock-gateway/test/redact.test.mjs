import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { redactedJson } from "../lib/redact.mjs";

const FIXTURES = new URL("../../../fixtures/gateway-protocol/2026.10.3/", import.meta.url);
const fixture = (name) => JSON.parse(fs.readFileSync(new URL(`${name}.json`, FIXTURES), "utf8"));

test("an auto-approving gateway's hello-ok.auth prints without its device token", () => {
  const auth = { ...fixture("hello-ok-paired").payload.auth, deviceToken: "dt-live-secret" };
  const printed = redactedJson(auth);
  assert.ok(!printed.includes("dt-live-secret"));
  assert.equal(JSON.parse(printed).deviceToken, "<redacted>");
  assert.equal(JSON.parse(printed).role, auth.role);
});

test("shared tokens, bootstrap tokens and passwords are redacted at any depth", () => {
  const printed = redactedJson({ params: { auth: { token: "s1", bootstrapToken: "s2", password: "s3" } }, method: "token" });
  for (const secret of ["s1", "s2", "s3"]) assert.ok(!printed.includes(`"${secret}"`), printed);
  assert.equal(JSON.parse(printed).method, "token");
});

test("a frame without credentials prints unchanged", () => {
  const error = fixture("err-pairing-required");
  assert.equal(redactedJson(error), JSON.stringify(error));
});
