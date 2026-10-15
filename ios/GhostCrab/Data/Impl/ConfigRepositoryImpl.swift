import Foundation

/// ``ConfigRepository`` over the live session socket (ADR-0007). ``getConfig()`` reads `config.get` (`parsed`
/// sections, `hash` as the ETag); ``updateConfig(section:value:)`` sends `config.patch {raw: {"<section>": value},
/// baseHash}`.
///
/// The cached hash is bound to the socket that produced it, so a reconnect never sends a stale one; without a cached
/// hash a write reads `config.get` first. The hash a write returns is the next `baseHash`; a write answer without one
/// clears it (the pre-write hash is stale by definition). Requests are numbered as they are issued and an answer
/// older than the cached one never replaces it, so a slow read overlapping a write cannot put the pre-write hash
/// back.
public actor ConfigRepositoryImpl: ConfigRepository {

    /// The newest known hash, the socket it belongs to and the number of the request that produced it.
    private struct CachedHash {
        let socket: GatewayRPCClient
        let hash: String?
        let ticket: Int
    }

    private let session: @Sendable () async throws -> GatewayRPCClient
    private var cachedHash: CachedHash?
    private var lastTicket = 0

    /// - Parameter session: The live session socket; throws while disconnected.
    public init(session: @escaping @Sendable () async throws -> GatewayRPCClient) {
        self.session = session
    }

    /// - Parameter connectionManager: Source of the live session socket.
    public init(connectionManager: GatewayConnectionManagerImpl) {
        self.init(session: { try await connectionManager.requireSession() })
    }

    /// - Returns: The authored config (`parsed`, secrets redacted by the gateway as `__OPENCLAW_REDACTED__`) and its
    ///   `hash`.
    /// - Throws: ``GatewayError/protocolViolation(url:detail:)`` for a non-object payload;
    ///   ``GatewayError/missingScope(url:method:scope:)`` without `operator.admin`;
    ///   ``GatewayError/unreachable(url:underlying:)`` while disconnected.
    public func getConfig() async throws -> OpenClawConfig {
        try await fetch(try await session())
    }

    /// - Parameters:
    ///   - section: Top-level key, e.g. `gateway`.
    ///   - value: Merge-patch value for that section.
    /// - Throws: ``GatewayError/configValidation(field:reason:)`` when the gateway's schema check reports exactly
    ///   one issue and it lies inside `section` (its `path`, e.g. `gateway.port`, and `message`);
    ///   ``GatewayError/api(url:statusCode:body:)`` 412 on a stale or missing hash (reload), 422 on any other
    ///   rejected patch (issues outside `section` or several issues, listed in the body; a JSON5 syntax error);
    ///   ``GatewayError/missingScope(url:method:scope:)`` without `operator.admin`;
    ///   ``GatewayError/unreachable(url:underlying:)`` while disconnected.
    public func updateConfig(section: String, value: AnyCodable) async throws {
        let rpc = try await session()
        let baseHash: String?
        if let cachedHash, cachedHash.socket === rpc, let hash = cachedHash.hash {
            baseHash = hash
        } else {
            baseHash = try await fetch(rpc).etag
        }
        let params = try ConfigWrites.patchParams([section: value], baseHash: baseHash)
        let ticket = issueTicket()
        let result: AnyCodable?
        do {
            result = try await rpc.request("config.patch", params: params, timeout: ConfigWrites.patchTimeout)
        } catch let error as GatewayError {
            throw ConfigWrites.editorRejection(error, section: section)
        }
        // 2026.10.3 answers {ok, path, hash, config, changedPaths, sentinel}; without a hash the next write re-reads.
        remember(result?.objectValue?["hash"]?.value as? String, from: rpc, ticket: ticket)
    }

    private func fetch(_ rpc: GatewayRPCClient) async throws -> OpenClawConfig {
        let ticket = issueTicket()
        guard let payload = try await rpc.request("config.get")?.objectValue else {
            throw GatewayError.protocolViolation(url: rpc.endpoint, detail: "config.get returned a non-object payload")
        }
        let sections = payload["parsed"]?.objectValue ?? payload["config"]?.objectValue ?? [:]
        let hash = payload["hash"]?.value as? String
        remember(hash, from: rpc, ticket: ticket)
        return OpenClawConfig(sections: sections, etag: hash)
    }

    private func issueTicket() -> Int {
        lastTicket += 1
        return lastTicket
    }

    /// Caches `hash` unless an answer to a later request of the same socket is already cached.
    private func remember(_ hash: String?, from rpc: GatewayRPCClient, ticket: Int) {
        if let cachedHash, cachedHash.socket === rpc, cachedHash.ticket > ticket { return }
        cachedHash = CachedHash(socket: rpc, hash: hash, ticket: ticket)
    }
}
