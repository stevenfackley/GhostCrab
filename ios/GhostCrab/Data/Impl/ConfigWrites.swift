import Foundation

/// `config.patch` params: `raw` is a JSON merge patch as text (JSON is valid JSON5); `baseHash` is the
/// `config.get` hash the patch was made against (required once a config file exists).
struct ConfigPatchParams: Encodable, Sendable {
    let raw: String
    let baseHash: String?
}

/// `config.patch` helpers shared by ``ConfigRepositoryImpl`` and ``ModelRepositoryImpl`` (ADR-0007).
enum ConfigWrites {

    /// `config.patch` answers once the gateway applied the write (hot reload), which can take a while.
    static let patchTimeout: Duration = .seconds(60)

    private static let invalidRequest = "INVALID_REQUEST"

    /// Messages of the two `baseHash` rejections (verified against 2026.10.3; neither carries `details`):
    /// `config changed since last load; re-run config.get and retry` and
    /// `config base hash required for <path>; re-run config.get and retry with baseHash`.
    private static let staleHashPrefixes = ["config changed since last load", "config base hash required"]

    /// - Parameters:
    ///   - patch: JSON merge patch (objects merge, `null` deletes), e.g. `{"gateway":{"port":18790}}`.
    ///   - baseHash: The `config.get` hash the patch was made against.
    /// - Returns: `config.patch` params with `patch` as compact JSON (sorted keys, so it is deterministic).
    /// - Throws: `EncodingError` for a value that is not JSON.
    static func patchParams(_ patch: [String: AnyCodable], baseHash: String?) throws -> ConfigPatchParams {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return ConfigPatchParams(raw: String(decoding: try encoder.encode(patch), as: UTF8.self), baseHash: baseHash)
    }

    /// Maps a rejected `config.patch` of one top-level `section` onto the frozen `ConfigRepository` contract:
    /// - a stale, unknown or missing `baseHash` → ``GatewayError/api(url:statusCode:body:)`` 412 with the server
    ///   text (the editor reloads and tells the user the config changed);
    /// - schema validation with exactly one issue, inside `section` (`details.issues[{path, message}]` with a path
    ///   `<section>.…`) → ``GatewayError/configValidation(field:reason:)`` for that path, so the editor shows it on
    ///   the field;
    /// - any other `INVALID_REQUEST` → ``GatewayError/api(url:statusCode:body:)`` 422 whose body is the server text
    ///   plus every issue it does not already spell out. That covers issues outside `section` (`config.patch`
    ///   validates the whole merged config, so a key the user never touched can fail the write), several issues, a
    ///   JSON5 syntax error and array removal without `replacePaths`;
    /// - anything else passes through.
    ///
    /// - Parameters:
    ///   - error: What the `config.patch` request threw.
    ///   - section: The top-level key the write patched (e.g. `gateway`).
    /// - Returns: The error to throw.
    static func editorRejection(_ error: GatewayError, section: String) -> GatewayError {
        map(error, inlineScope: "\(section).")
    }

    /// Maps a rejected `config.patch` of the active model (`agents.defaults.model.primary`). No form field shows the
    /// value, so a validation failure is always a 422 listing every issue; a stale or missing `baseHash` is the same
    /// 412 the config editor reloads on.
    ///
    /// - Parameter error: What the `config.patch` request threw.
    /// - Returns: The error to throw.
    static func modelRejection(_ error: GatewayError) -> GatewayError {
        map(error, inlineScope: nil)
    }

    /// - Parameter inlineScope: Path prefix whose single issue becomes a field error; `nil` for none.
    private static func map(_ error: GatewayError, inlineScope: String?) -> GatewayError {
        guard case let .rpc(url, _, code, _, message, _, details, _) = error, code == invalidRequest else {
            return error
        }
        if staleHashPrefixes.contains(where: { message.hasPrefix($0) }) {
            return .api(url: url, statusCode: 412, body: message)
        }
        let found = issues(in: details)
        if let inlineScope, found.count == 1, let path = found[0].path, path.hasPrefix(inlineScope) {
            return .configValidation(field: path, reason: found[0].message)
        }
        return .api(url: url, statusCode: 422, body: withIssues(message, found))
    }

    /// One `details.issues[]` entry (e.g. `gateway.port` → `Too big: …`); the path is optional upstream.
    private struct ConfigIssue {
        let path: String?
        let message: String

        var text: String { path.map { "\($0): \(message)" } ?? message }
    }

    /// `details.issues[]` entries that carry a message, in server order.
    private static func issues(in details: [String: AnyCodable]?) -> [ConfigIssue] {
        guard let entries = details?["issues"]?.value as? [AnyCodable] else { return [] }
        return entries.compactMap { entry in
            guard let object = entry.objectValue,
                  let message = object["message"]?.value as? String,
                  !isBlank(message) else { return nil }
            let path = (object["path"]?.value as? String).flatMap { isBlank($0) ? nil : $0 }
            return ConfigIssue(path: path, message: message)
        }
    }

    /// The server message plus every issue it does not already spell out, `; `-separated.
    private static func withIssues(_ message: String, _ issues: [ConfigIssue]) -> String {
        ([message] + issues.map(\.text).filter { !message.contains($0) }).joined(separator: "; ")
    }

    private static func isBlank(_ text: String) -> Bool {
        text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}
