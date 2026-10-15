// Ed25519 device identity helpers shared by the mock gateway, its tests and probe-real.mjs (ADR-0007).
import { createHash, createPrivateKey, createPublicKey, generateKeyPairSync, sign, verify } from "node:crypto";

/** Upstream normalizeDeviceMetadataForAuth: trim, then lowercase ASCII A-Z only. */
export const normalizeMeta = (value) =>
  typeof value === "string" ? value.trim().replace(/[A-Z]/g, (c) => c.toLowerCase()) : "";

/** Upstream buildDeviceAuthPayloadV3. */
export function payloadV3({ deviceId, clientId, clientMode, role, scopes, signedAtMs, token, nonce, platform, deviceFamily }) {
  return [
    "v3", deviceId, clientId, clientMode, role, scopes.join(","), String(signedAtMs), token ?? "", nonce,
    normalizeMeta(platform), normalizeMeta(deviceFamily),
  ].join("|");
}

/** hex(sha256(raw public key)). */
export const deviceIdFor = (publicKeyB64Url) =>
  createHash("sha256").update(Buffer.from(publicKeyB64Url, "base64url")).digest("hex");

/** A fresh identity: { jwk (private, sensitive), publicKey (raw, base64url), deviceId }. */
export function newIdentity() {
  const { privateKey } = generateKeyPairSync("ed25519");
  const jwk = privateKey.export({ format: "jwk" });
  return { jwk, publicKey: jwk.x, deviceId: deviceIdFor(jwk.x) };
}

export function signPayload(identity, payload) {
  return sign(null, Buffer.from(payload, "utf8"), createPrivateKey({ key: identity.jwk, format: "jwk" })).toString("base64url");
}

export function verifyPayload(publicKeyB64Url, payload, signatureB64Url) {
  try {
    const key = createPublicKey({ key: { kty: "OKP", crv: "Ed25519", x: publicKeyB64Url }, format: "jwk" });
    return verify(null, Buffer.from(payload, "utf8"), key, Buffer.from(signatureB64Url ?? "", "base64url"));
  } catch {
    return false; // malformed key or signature: simply not valid
  }
}
