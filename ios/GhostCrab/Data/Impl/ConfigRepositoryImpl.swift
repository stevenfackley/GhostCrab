import Foundation

/// Production ``ConfigRepository`` backed by ``GatewayConnectionManagerImpl``.
///
/// Caches the most recently seen ETag so ``updateConfig(section:value:)`` can
/// send an `If-Match` header, enabling optimistic-concurrency detection
/// (HTTP 412) on the gateway side.
///
/// The cache is a lock-protected cell rather than an actor — the operations
/// themselves serialize through the underlying ``OpenClawAPIClient`` actor, so
/// the only shared state here is the single optional ETag string. A simple
/// `NSLock` matches the semantics of Kotlin's `AtomicReference<String?>`
/// exactly.
///
/// Direct port of `ConfigRepositoryImpl.kt`.
public final class ConfigRepositoryImpl: ConfigRepository, @unchecked Sendable {

    private let connectionManager: GatewayConnectionManagerImpl
    private let etagBox = ETagBox()

    /// - Parameter connectionManager: Concrete manager used to obtain the
    ///   active ``OpenClawAPIClient``.
    public init(connectionManager: GatewayConnectionManagerImpl) {
        self.connectionManager = connectionManager
    }

    /// Fetches the full config from the gateway and caches the returned ETag.
    ///
    /// - Returns: ``OpenClawConfig`` with all sections and the latest ETag.
    /// - Throws: ``GatewayError/api(url:statusCode:body:)`` on HTTP error.
    /// - Throws: ``GatewayError/unreachable(url:underlying:)`` if no active
    ///   connection exists.
    public func getConfig() async throws -> OpenClawConfig {
        let client = try await connectionManager.requireClient()
        let config = try await client.getConfig()
        etagBox.set(config.etag)
        return config
    }

    /// Sends a JSON merge-patch for `section` using the cached ETag for
    /// conflict detection.
    ///
    /// - Parameters:
    ///   - section: Top-level config section key.
    ///   - value: JSON merge-patch value.
    /// - Throws: ``GatewayError/api(url:statusCode:body:)`` on HTTP error,
    ///   including 412 (concurrent edit).
    /// - Throws: ``GatewayError/configValidation(field:reason:)`` on
    ///   server-side validation failure.
    /// - Throws: ``GatewayError/unreachable(url:underlying:)`` if no active
    ///   connection exists.
    public func updateConfig(section: String, value: AnyCodable) async throws {
        let client = try await connectionManager.requireClient()
        try await client.updateConfig(section: section, value: value, etag: etagBox.get())
    }
}

// ── ETag cache ───────────────────────────────────────────────────────────────

/// Lock-protected mutable optional string, mirroring Kotlin's
/// `AtomicReference<String?>`.
private final class ETagBox: @unchecked Sendable {
    private var stored: String?
    private let lock = NSLock()

    func get() -> String? {
        lock.lock(); defer { lock.unlock() }
        return stored
    }

    func set(_ value: String?) {
        lock.lock(); defer { lock.unlock() }
        stored = value
    }
}
