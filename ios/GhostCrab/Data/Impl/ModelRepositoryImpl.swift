import Foundation

/// ``ModelRepository`` over the live session socket (ADR-0007). The catalogue is `models.list`; the active model is
/// the config key `agents.defaults.model.primary`, read with `config.get` and written with `config.patch` (there is
/// no `models.set` RPC). ``ModelInfo/id`` is the `provider/model` ref.
public final class ModelRepositoryImpl: ModelRepository, Sendable {

    /// ``ModelInfo/status`` of a catalogue entry that is not unavailable.
    static let statusReady = "ready"
    /// ``ModelInfo/status`` of the configured primary when no catalogue entry has that ref.
    static let statusNotListed = "not-listed"
    /// ``ModelInfo/status`` of a bare-id primary that several providers list.
    static let statusAmbiguous = "ambiguous"

    private let session: @Sendable () async throws -> GatewayRPCClient

    /// - Parameter session: The live session socket; throws while disconnected.
    public init(session: @escaping @Sendable () async throws -> GatewayRPCClient) {
        self.session = session
    }

    /// - Parameter connectionManager: Source of the live session socket.
    public convenience init(connectionManager: GatewayConnectionManagerImpl) {
        self.init(session: { try await connectionManager.requireSession() })
    }

    /// - Returns: The catalogue in server order. The entry `agents.defaults.model.primary` names is active: a
    ///   `provider/model` value matches that ref; a bare model id matches only when exactly one provider lists it.
    ///   When the primary matches no entry, or a bare id matches several providers, no catalogue entry is active and
    ///   the raw primary is appended as the active entry (status `not-listed` or `ambiguous`), so it is never hidden
    ///   and never two providers are active. Without `operator.admin` (`config.get` refused) the list still loads
    ///   with nothing active.
    /// - Throws: ``GatewayError/methodUnsupported(url:method:)`` when the gateway has no `models.list`;
    ///   ``GatewayError/protocolViolation(url:detail:)`` for a non-object payload;
    ///   ``GatewayError/unreachable(url:underlying:)`` while disconnected; any other typed RPC error.
    public func getModels() async throws -> [ModelInfo] {
        let rpc = try await session()
        guard let payload = try await rpc.request("models.list")?.objectValue else {
            throw GatewayError.protocolViolation(url: rpc.endpoint, detail: "models.list returned a non-object payload")
        }
        let entries = ((payload["models"]?.value as? [AnyCodable]) ?? []).compactMap(\.objectValue)
        let catalogue = entries.compactMap(CatalogueEntry.init)
        let primary = try await primaryModel(rpc)
        return Self.models(catalogue, primary: primary)
    }

    /// Writes `agents.defaults.model.primary` with `config.patch`, against the `hash` of a fresh `config.get`.
    ///
    /// - Parameter modelId: A ``ModelInfo/id`` (`provider/model`).
    /// - Throws: ``GatewayError/api(url:statusCode:body:)`` 412 when the config changed between the read and the
    ///   write (or no hash was available): retry; 422 when the gateway rejects the model ref (the body is the server
    ///   text plus every validation issue). ``GatewayError/missingScope(url:method:scope:)`` without
    ///   `operator.admin`; ``GatewayError/unreachable(url:underlying:)`` while disconnected.
    public func setActiveModel(modelId: String) async throws {
        let rpc = try await session()
        let hash = try await rpc.request("config.get")?.objectValue?["hash"]?.value as? String
        let patch: [String: AnyCodable] = [
            "agents": AnyCodable(["defaults": AnyCodable(["model": AnyCodable(["primary": AnyCodable(modelId)])])]),
        ]
        let params = try ConfigWrites.patchParams(patch, baseHash: hash)
        do {
            _ = try await rpc.request("config.patch", params: params, timeout: ConfigWrites.patchTimeout)
        } catch let error as GatewayError {
            throw ConfigWrites.modelRejection(error)
        }
    }

    /// `agents.defaults.model.primary` (object or string form); `nil` when unset, empty or without `operator.admin`.
    private func primaryModel(_ rpc: GatewayRPCClient) async throws -> String? {
        let payload: AnyCodable?
        do {
            payload = try await rpc.request("config.get")
        } catch GatewayError.missingScope {
            return nil // config.* is admin-only; the catalogue stays useful without the active marker
        }
        let model = payload?.object(at: "parsed", "agents", "defaults")?["model"]?.value
        let primary = ((model as? [String: AnyCodable])?["primary"]?.value as? String) ?? (model as? String)
        return primary.flatMap { $0.isEmpty ? nil : $0 }
    }

    /// - Returns: `catalogue` as ``ModelInfo`` with the entry `primary` names active, plus the raw primary as an
    ///   active entry when it names no single catalogue entry.
    static func models(_ catalogue: [CatalogueEntry], primary: String?) -> [ModelInfo] {
        guard let primary else { return catalogue.map { $0.modelInfo(active: false) } }
        let matches = Set(catalogue.filter { primary.contains("/") ? $0.ref == primary : $0.id == primary }.map(\.ref))
        let activeRef = matches.count == 1 ? matches.first : nil
        var list = catalogue.map { $0.modelInfo(active: $0.ref == activeRef) }
        if activeRef == nil {
            let provider = primary.firstIndex(of: "/").map { String(primary[..<$0]) } ?? ""
            list.append(ModelInfo(
                id: primary,
                provider: provider,
                displayName: primary,
                isActive: true,
                status: matches.isEmpty ? statusNotListed : statusAmbiguous,
                capabilities: []
            ))
        }
        return list
    }
}

/// - Parameters:
///   - provider: `models.list` `provider`.
///   - id: `models.list` `id`.
/// - Returns: `provider/id`, unless `id` already carries the provider prefix.
func modelRef(provider: String, id: String) -> String {
    id.hasPrefix("\(provider)/") ? id : "\(provider)/\(id)"
}

/// The parts of one `models.list` entry GhostCrab shows (`ModelsListResult` in the 2026.10.3 schema).
struct CatalogueEntry: Sendable {
    let id: String
    let provider: String
    let name: String?
    /// `nil` when absent or not a JSON boolean.
    let available: Bool?
    let unavailableReason: String?
    let input: [String]
    let reasoning: Bool

    /// `provider/id`.
    var ref: String { modelRef(provider: provider, id: id) }

    /// `nil` without a string `id` and `provider`.
    init?(_ object: [String: AnyCodable]) {
        guard let id = object["id"]?.value as? String, let provider = object["provider"]?.value as? String else {
            return nil
        }
        self.id = id
        self.provider = provider
        self.name = object["name"]?.value as? String
        self.available = object["available"]?.value as? Bool
        self.unavailableReason = object["unavailableReason"]?.value as? String
        self.input = ((object["input"]?.value as? [AnyCodable]) ?? []).compactMap { $0.value as? String }
        self.reasoning = object["reasoning"]?.value as? Bool ?? false
    }

    func modelInfo(active: Bool) -> ModelInfo {
        ModelInfo(
            id: ref,
            provider: provider,
            displayName: name ?? id,
            isActive: active,
            status: available == false ? (unavailableReason ?? "unavailable") : ModelRepositoryImpl.statusReady,
            capabilities: input + (reasoning ? ["reasoning"] : [])
        )
    }
}
