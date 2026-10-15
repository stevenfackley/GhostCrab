import CryptoKit
import Foundation
import Testing
@testable import GhostCrab

/// Frames captured from `ghcr.io/openclaw/openclaw` 2026.10.3 during roadmap M5 (`docs/QA_REAL_GATEWAY_2026-10.md`) and
/// the 2026-10-15 verification run, trimmed, issued device token replaced by `dt-test-0001`. Bundled from
/// `fixtures/gateway-protocol/2026.10.3/` (a folder reference in `ios/project.yml`).
enum GatewayFixtures {

    static let version = "2026.10.3"

    static let all = [
        "challenge", "hello-ok-paired", "hello-ok-noscope",
        "err-pairing-required", "err-token-mismatch", "err-token-missing", "err-device-identity-required",
        "err-missing-scope", "err-unknown-method", "err-skill-not-found",
        "res-health", "res-skills-status", "res-models-list", "res-config-get",
        "err-client-id-invalid", "err-pairing-metadata-upgrade",
        "err-config-stale-hash", "err-config-base-hash-required", "err-config-invalid", "res-config-patch",
        "res-skills-update", "evt-config-changed", "res-skills-install",
    ]

    struct Missing: Error, CustomStringConvertible {
        let name: String
        var description: String { "missing test fixture \(name).json (fixtures folder not bundled? run xcodegen)" }
    }

    private final class BundleToken {}

    /// - Parameter name: Fixture name without `.json`.
    /// - Returns: The raw frame text.
    /// - Throws: ``Missing`` when the fixture is not in the test bundle.
    static func text(_ name: String) throws -> String {
        guard let url = Bundle(for: BundleToken.self).url(forResource: name, withExtension: "json", subdirectory: version) else {
            throw Missing(name: name)
        }
        return try String(contentsOf: url, encoding: .utf8)
    }
}

/// RFC 8032 §7.1 TEST 1 and values derived from it.
enum TestKeys {
    static let rfcSeed = Data(hex: "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    static let rfcPublicKey = Data(hex: "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    static let rfcDeviceId = "21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9"
    static let operatorScopes = ["operator.read", "operator.write", "operator.admin"]
}

extension Data {
    /// - Parameter hex: An even-length hex string.
    init(hex: String) {
        var bytes: [UInt8] = []
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            bytes.append(UInt8(hex[index..<next], radix: 16) ?? 0)
            index = next
        }
        self.init(bytes)
    }
}

/// Parses a JSON object for assertions.
func jsonObject(_ text: String) throws -> [String: Any] {
    try #require(try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
}

/// Runs `body` and returns the `GatewayError` it threw, or `nil`.
func gatewayError(_ body: () async throws -> Void) async -> GatewayError? {
    do {
        try await body()
        return nil
    } catch let error as GatewayError {
        return error
    } catch {
        return nil
    }
}
