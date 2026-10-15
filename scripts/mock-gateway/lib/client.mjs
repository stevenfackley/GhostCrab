// Minimal protocol-4 client: challenge -> signed connect -> req/res/events. Used by the tests and probe-real.mjs.
import { newIdentity, payloadV3, signPayload } from "./device.mjs";

export const OPERATOR_SCOPES = ["operator.read", "operator.write", "operator.admin"];
export const DEFAULT_CLIENT = { id: "openclaw-android", version: "0.3.0-test", platform: "android", mode: "ui", displayName: "GhostCrab test" };

/**
 * @returns {Promise<{hello, error, closed, request, nextEvent, close, identity}>} hello is the hello-ok payload, or
 *   null with error set; closed resolves to the socket's {code, reason}.
 */
export async function connect(url, { identity = newIdentity(), auth = {}, scopes = OPERATOR_SCOPES, withDevice = true, tamperSignature = false, client = DEFAULT_CLIENT } = {}) {
  const ws = new WebSocket(url);
  const inbox = [];
  const waiters = [];
  let ended = null;
  const wake = () => waiters.splice(0).forEach((resolve) => resolve());
  const closed = new Promise((resolve) => ws.addEventListener("close", (ev) => resolve({ code: ev.code, reason: ev.reason })));
  ws.addEventListener("message", (ev) => { inbox.push(JSON.parse(String(ev.data))); wake(); });
  ws.addEventListener("close", (ev) => { ended = { code: ev.code, reason: ev.reason }; wake(); });
  await new Promise((resolve, reject) => {
    ws.addEventListener("open", resolve, { once: true });
    ws.addEventListener("error", reject, { once: true });
  });
  const next = async (predicate, timeoutMs = 5000) => {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const index = inbox.findIndex(predicate);
      if (index >= 0) return inbox.splice(index, 1)[0];
      if (ended || Date.now() > deadline) return null;
      await new Promise((resolve) => { waiters.push(resolve); setTimeout(resolve, Math.max(0, deadline - Date.now())); });
    }
  };
  let seq = 0;
  const request = async (method, params = {}) => {
    const id = `t-${++seq}`;
    ws.send(JSON.stringify({ type: "req", id, method, params }));
    return next((frame) => frame.type === "res" && frame.id === id);
  };
  const nextEvent = (name, timeoutMs = 2000) => next((frame) => frame.type === "event" && frame.event === name, timeoutMs);
  const challenge = await next((frame) => frame.type === "event" && frame.event === "connect.challenge");
  const { nonce, ts } = challenge.payload;
  const params = { minProtocol: 4, maxProtocol: 4, client, role: "operator", scopes, caps: [], commands: [], permissions: {}, locale: "en-US", userAgent: "ghostcrab-test/0" };
  if (Object.keys(auth).length) params.auth = auth;
  if (withDevice) {
    const payload = payloadV3({
      deviceId: identity.deviceId, clientId: client.id, clientMode: client.mode, role: "operator", scopes,
      signedAtMs: ts, token: auth.token ?? auth.deviceToken ?? auth.bootstrapToken, nonce,
      platform: client.platform, deviceFamily: client.deviceFamily,
    });
    const signature = signPayload(identity, tamperSignature ? `${payload}x` : payload);
    params.device = { id: identity.deviceId, publicKey: identity.publicKey, signature, signedAt: ts, nonce };
  }
  const res = await request("connect", params);
  return {
    identity,
    hello: res?.ok ? res.payload : null,
    error: res?.ok ? null : res?.error ?? null,
    closed,
    request,
    nextEvent,
    close: () => ws.close(),
  };
}
