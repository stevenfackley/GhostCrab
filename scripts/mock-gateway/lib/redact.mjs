// Credential-bearing field names: their values never reach the probe's output (or a fixture made from it).
export const SECRET_KEYS = new Set(["token", "deviceToken", "bootstrapToken", "password", "privateKey"]);

/**
 * JSON.stringify with the value of every SECRET_KEYS field, at any depth, replaced by "<redacted>".
 * @param {unknown} value A frame, payload or error to print.
 * @returns {string | undefined} The redacted JSON (undefined for undefined, as JSON.stringify).
 */
export const redactedJson = (value) =>
  JSON.stringify(value, (key, v) => (SECRET_KEYS.has(key) && v != null ? "<redacted>" : v));
