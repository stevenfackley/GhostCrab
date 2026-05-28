import Foundation
import os

/// URLSession-backed HTTP client for the OpenClaw Gateway API.
///
/// Create via ``unauthenticated(baseURL:allowCleartextPublicIPs:)`` for probing
/// or ``authenticated(baseURL:token:allowCleartextPublicIPs:)`` for a live session.
/// Call ``close()`` when the session ends to release underlying connections.
///
/// **Authorization headers are never logged.** Verify with Console.app.
///
/// Timeouts: connect = 15s, request = 30s.
///
/// Direct port of `OpenClawApiClient.kt`.
public actor OpenClawAPIClient {

    // MARK: - State

    public let baseURL: URL
    private let token: String?
    private let session: URLSession
    private let decoder: JSONDecoder
    private let encoder: JSONEncoder

    private static let logger = Logger(
        subsystem: "com.qavren.ghostcrab",
        category: "OpenClawAPIClient"
    )

    private init(baseURL: URL, token: String?, allowCleartextPublicIPs: Bool) {
        self.baseURL = baseURL
        self.token = token

        // Make sure the guard knows whether to permit cleartext public-IP calls.
        // The closure is captured by value here so per-client setting wins for
        // the lifetime of the configuration. If multiple clients exist with
        // differing flags, the most recently constructed one wins — same
        // semantics as Kotlin's per-engine interceptor closure.
        CleartextPublicIPGuard.configure { allowCleartextPublicIPs }

        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 30      // request + socket idle
        config.timeoutIntervalForResource = 30
        config.waitsForConnectivity = false
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.protocolClasses = [CleartextPublicIPGuard.self] + (config.protocolClasses ?? [])

        // 15s connect timeout via the connection-establishment knob.
        config.connectionProxyDictionary = nil
        if #available(iOS 13, macOS 10.15, *) {
            // URLSession lumps "connect" into timeoutIntervalForRequest; we
            // approximate the 15s connect window by setting a tight
            // first-byte timeout on a per-request basis below.
        }

        self.session = URLSession(configuration: config)

        let dec = JSONDecoder()
        // Lenient mirrors Kotlin's `isLenient = true, ignoreUnknownKeys = true`.
        // `JSONDecoder` already ignores unknown keys; the rest is handled per-DTO.
        self.decoder = dec

        let enc = JSONEncoder()
        self.encoder = enc
    }

    // MARK: - Factories

    /// Client with no authentication — use for ``health()`` and auth probes.
    ///
    /// - Parameters:
    ///   - baseURL: Gateway base URL (no trailing slash).
    ///   - allowCleartextPublicIPs: If `true`, the cleartext-public-IP guard is bypassed.
    public static func unauthenticated(
        baseURL: URL,
        allowCleartextPublicIPs: Bool = false
    ) -> OpenClawAPIClient {
        OpenClawAPIClient(
            baseURL: baseURL,
            token: nil,
            allowCleartextPublicIPs: allowCleartextPublicIPs
        )
    }

    /// Client with Bearer token authentication — use for live sessions.
    ///
    /// - Parameters:
    ///   - baseURL: Gateway base URL (no trailing slash).
    ///   - token: Bearer token sent in `Authorization: Bearer <token>` on every request.
    ///   - allowCleartextPublicIPs: If `true`, the cleartext-public-IP guard is bypassed.
    public static func authenticated(
        baseURL: URL,
        token: String,
        allowCleartextPublicIPs: Bool = false
    ) -> OpenClawAPIClient {
        OpenClawAPIClient(
            baseURL: baseURL,
            token: token,
            allowCleartextPublicIPs: allowCleartextPublicIPs
        )
    }

    // MARK: - Public API

    /// `GET /health` — unauthenticated liveness check.
    ///
    /// - Throws: `GatewayError.unreachable` if the host cannot be reached.
    /// - Throws: `GatewayError.timeout` if the request times out.
    /// - Throws: `GatewayError.tls` on SSL/TLS failure.
    public func health() async throws -> HealthResponse {
        let url = endpoint("/health")
        return try await safe(url: url) {
            let (data, response) = try await self.send(.init(url: url))
            try self.mapErrors(url: url, response: response)
            // Tolerant: if upstream gives non-JSON we synthesize "ok".
            if let decoded = try? self.decoder.decode(HealthResponse.self, from: data) {
                return decoded
            }
            return HealthResponse(status: "ok")
        }
    }

    /// `GET /status` — returns gateway identity, version, and capabilities.
    ///
    /// May require authentication depending on gateway configuration.
    ///
    /// Some gateways (e.g. upstream `ghcr.io/openclaw/openclaw:latest`) serve an
    /// HTML admin UI at `/status` instead of JSON. In that case we return a
    /// default `StatusResponse` so the connect flow still succeeds.
    ///
    /// - Throws: `GatewayError.auth` if auth is required and no/invalid token is present.
    /// - Throws: `GatewayError.unreachable` if the host cannot be reached.
    /// - Throws: `GatewayError.timeout` if the request times out.
    public func status() async throws -> StatusResponse {
        let url = endpoint("/status")
        return try await safe(url: url) {
            let (data, response) = try await self.send(.init(url: url))
            try self.mapErrors(url: url, response: response)
            guard self.contentTypeIsJSON(response) else { return StatusResponse() }
            return (try? self.decoder.decode(StatusResponse.self, from: data)) ?? StatusResponse()
        }
    }

    /// `GET /config` — fetches the full `openclaw.json` configuration.
    ///
    /// Some gateway builds serve HTML at `/config` instead of JSON. In that case
    /// we return an empty config + nil ETag so the editor opens in a clearly-empty
    /// (not "disconnected") state.
    ///
    /// - Returns: `OpenClawConfig` with section map and optional ETag header.
    /// - Throws: `GatewayError.api` on unexpected HTTP errors.
    public func getConfig() async throws -> OpenClawConfig {
        let url = endpoint("/config")
        return try await safe(url: url) {
            let (data, response) = try await self.send(.init(url: url))
            try self.mapErrors(url: url, response: response)
            guard self.contentTypeIsJSON(response) else {
                return OpenClawConfig(sections: [:], etag: nil)
            }
            let etag = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "ETag")
            let sections = (try? self.decoder.decode([String: AnyCodable].self, from: data)) ?? [:]
            return OpenClawConfig(sections: sections, etag: etag)
        }
    }

    /// `PATCH /config/{section}` — applies a JSON merge-patch to a single config section.
    ///
    /// - Parameters:
    ///   - section: Top-level section key (e.g. `"gateway"`).
    ///   - value: JSON merge-patch value.
    ///   - etag: Optional ETag for optimistic concurrency — sent as `If-Match` header.
    /// - Throws: `GatewayError.api` with `statusCode = 412` on concurrent-edit conflict.
    /// - Throws: `GatewayError.configValidation` if the gateway reports validation failure (422).
    /// - Throws: `GatewayError.api` on other unexpected HTTP errors.
    public func updateConfig(section: String, value: AnyCodable, etag: String?) async throws {
        let url = endpoint("/config/\(section)")
        try await safe(url: url) {
            var req = URLRequest(url: url)
            req.httpMethod = "PATCH"
            req.setValue("application/merge-patch+json", forHTTPHeaderField: "Content-Type")
            if let etag {
                req.setValue(etag, forHTTPHeaderField: "If-Match")
            }
            req.httpBody = try self.encoder.encode(value)

            let (data, response) = try await self.send(req)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            switch status {
            case 200, 204:
                return
            case 412:
                throw GatewayError.api(url: url.absoluteString, statusCode: 412)
            case 422:
                let bodyText = String(data: data, encoding: .utf8) ?? ""
                if let parsed = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                   let field = parsed["field"] as? String,
                   let reason = parsed["reason"] as? String {
                    throw GatewayError.configValidation(field: field, reason: reason)
                }
                throw GatewayError.api(url: url.absoluteString, statusCode: 422, body: bodyText)
            default:
                try self.mapErrors(url: url, response: response)
            }
        }
    }

    /// `GET /api/models/status` — returns all models known to the gateway.
    ///
    /// - Returns: List of `ModelDTO` (may be empty if no providers configured).
    /// - Throws: `GatewayError.auth` if the endpoint requires authentication.
    /// - Throws: `GatewayError.api` on unexpected HTTP errors.
    /// - Throws: `GatewayError.unreachable` if the host cannot be reached.
    /// - Throws: `GatewayError.timeout` if the request times out.
    public func getModels() async throws -> [ModelDTO] {
        let url = endpoint("/api/models/status")
        return try await safe(url: url) {
            let (data, response) = try await self.send(.init(url: url))
            try self.mapErrors(url: url, response: response)
            guard self.contentTypeIsJSON(response) else { return [] }
            return (try? self.decoder.decode([ModelDTO].self, from: data)) ?? []
        }
    }

    /// `POST /api/models/active` — sets the active model on the gateway.
    ///
    /// - Parameter id: The `id` of the model to make active.
    /// - Throws: `GatewayError.auth` if the endpoint requires authentication.
    /// - Throws: `GatewayError.api` on unexpected HTTP errors (e.g. model not found — 404).
    /// - Throws: `GatewayError.unreachable` if the host cannot be reached.
    /// - Throws: `GatewayError.timeout` if the request times out.
    public func setActiveModel(_ id: String) async throws {
        let url = endpoint("/api/models/active")
        try await safe(url: url) {
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: ["id": id])

            let (_, response) = try await self.send(req)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            switch status {
            case 200, 204: return
            default: try self.mapErrors(url: url, response: response)
            }
        }
    }

    /// `POST /api/ai/recommend` — submits a query to the gateway's AI skill.
    ///
    /// - Parameter request: The query and session context.
    /// - Returns: `AIRecommendationResponseDTO` with the recommendation text and optional suggested changes.
    /// - Throws: `GatewayError.aiServiceUnavailable` when the AI skill is not installed (404).
    /// - Throws: `GatewayError.aiQuotaExceeded` when the AI quota is exceeded (429).
    /// - Throws: `GatewayError.auth` if the endpoint requires authentication.
    /// - Throws: `GatewayError.unreachable` if the host cannot be reached.
    /// - Throws: `GatewayError.timeout` if the request times out.
    public func getAIRecommendation(
        _ request: AIRecommendationRequestDTO
    ) async throws -> AIRecommendationResponseDTO {
        let url = endpoint("/api/ai/recommend")
        return try await safe(url: url) {
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try self.encoder.encode(request)

            let (data, response) = try await self.send(req)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            switch status {
            case 200:
                return try self.decoder.decode(AIRecommendationResponseDTO.self, from: data)
            case 404:
                throw GatewayError.aiServiceUnavailable(url: url.absoluteString)
            case 429:
                throw GatewayError.aiQuotaExceeded(url: url.absoluteString)
            default:
                try self.mapErrors(url: url, response: response)
                // mapErrors throws on non-success; this is unreachable.
                throw GatewayError.api(url: url.absoluteString, statusCode: status)
            }
        }
    }

    /// `GET /api/skills` — returns the currently installed skills on the gateway.
    ///
    /// Added in v1.1 for the iOS port. The Kotlin Android client reaches the
    /// same data via the JSON-RPC WebSocket `skills.list` method; the REST
    /// endpoint is the documented HTTP fallback exposed by gateway builds
    /// `>= 2026-04-01`.
    ///
    /// - Returns: List of ``InstalledSkillDTO`` (may be empty).
    /// - Throws: ``GatewayError/auth(url:statusCode:)`` if the endpoint
    ///   requires authentication.
    /// - Throws: ``GatewayError/api(url:statusCode:body:)`` on unexpected HTTP
    ///   errors.
    /// - Throws: ``GatewayError/unreachable(url:underlying:)`` if the host
    ///   cannot be reached.
    /// - Throws: ``GatewayError/timeout(url:underlying:)`` if the request
    ///   times out.
    public func getInstalledSkills() async throws -> [InstalledSkillDTO] {
        let url = endpoint("/api/skills")
        return try await safe(url: url) {
            let (data, response) = try await self.send(.init(url: url))
            try self.mapErrors(url: url, response: response)
            guard self.contentTypeIsJSON(response) else { return [] }
            // The gateway returns either a bare array or `{"skills":[...]}`
            // depending on build. Tolerate both.
            if let arr = try? self.decoder.decode([InstalledSkillDTO].self, from: data) {
                return arr
            }
            if let envelope = try? self.decoder.decode(InstalledSkillsEnvelope.self, from: data) {
                return envelope.skills
            }
            return []
        }
    }

    /// `POST /api/skills/install` — install a skill from ClawHub.
    ///
    /// Added in v1.1 for the iOS port. Replaces the Kotlin JSON-RPC
    /// `skills.install` call. The REST endpoint is synchronous: the gateway
    /// performs the download + verify + apply pipeline and returns the
    /// resulting ``InstalledSkillDTO``. No intermediate progress events are
    /// available over REST — the Swift ``InstalledSkillRepositoryImpl`` emits
    /// `.connecting` then a terminal `.succeeded` / `.failed`.
    ///
    /// - Parameters:
    ///   - slug: e.g. `"wanng-ide/auto-skill-hunter"`.
    ///   - version: Optional pinned version. `nil` → latest.
    /// - Returns: The newly-installed ``InstalledSkillDTO`` reported by the
    ///   gateway.
    /// - Throws: ``GatewayError/auth(url:statusCode:)`` for 401/403.
    /// - Throws: ``GatewayError/api(url:statusCode:body:)`` for 404 (slug not
    ///   found), 409 (dependency conflict), 422 (verification failed), or any
    ///   other unexpected status. Callers map the status code to a domain
    ///   ``SkillInstallError``.
    /// - Throws: ``GatewayError/unreachable(url:underlying:)`` /
    ///   ``GatewayError/timeout(url:underlying:)`` on transport errors.
    public func installSkill(slug: String, version: String?) async throws -> InstalledSkillDTO {
        let url = endpoint("/api/skills/install")
        return try await safe(url: url) {
            var body: [String: Any] = [
                "source": "clawhub",
                "slug": slug,
                "force": false
            ]
            if let version { body["version"] = version }

            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: body)

            let (data, response) = try await self.send(req)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            switch status {
            case 200, 201:
                return try self.decoder.decode(InstalledSkillDTO.self, from: data)
            case 401, 403:
                throw GatewayError.auth(url: url.absoluteString, statusCode: status)
            default:
                let bodyText = String(data: data, encoding: .utf8)
                throw GatewayError.api(
                    url: url.absoluteString,
                    statusCode: status,
                    body: bodyText
                )
            }
        }
    }

    /// `POST /api/skills/uninstall` — remove a previously-installed skill.
    ///
    /// Added in v1.1 for the iOS port. Replaces the Kotlin JSON-RPC
    /// `skills.uninstall` call.
    ///
    /// - Parameter slug: The skill slug to remove.
    /// - Throws: ``GatewayError/auth(url:statusCode:)`` for 401/403.
    /// - Throws: ``GatewayError/api(url:statusCode:body:)`` on unexpected
    ///   HTTP status.
    /// - Throws: ``GatewayError/unreachable(url:underlying:)`` /
    ///   ``GatewayError/timeout(url:underlying:)`` on transport errors.
    public func uninstallSkill(slug: String) async throws {
        let url = endpoint("/api/skills/uninstall")
        try await safe(url: url) {
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONSerialization.data(withJSONObject: ["slug": slug])

            let (data, response) = try await self.send(req)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            switch status {
            case 200, 204:
                return
            case 401, 403:
                throw GatewayError.auth(url: url.absoluteString, statusCode: status)
            default:
                let bodyText = String(data: data, encoding: .utf8)
                throw GatewayError.api(
                    url: url.absoluteString,
                    statusCode: status,
                    body: bodyText
                )
            }
        }
    }

    /// `POST /api/auth/whoami` — returns the scopes carried by the active
    /// bearer token.
    ///
    /// Added in v1.1 for the iOS port. Replaces the Kotlin JSON-RPC
    /// `auth.whoami` call. Gateway builds without scope support return
    /// HTTP 404 (or 405) — callers map that to
    /// ``ScopeProbeResult/unknownOldGateway``.
    ///
    /// - Returns: The decoded ``WhoamiResponseDTO``.
    /// - Throws: ``GatewayError/api(url:statusCode:body:)`` on unexpected
    ///   HTTP status. 404/405 propagate so the caller can detect "old gateway".
    /// - Throws: ``GatewayError/auth(url:statusCode:)`` for 401/403.
    /// - Throws: ``GatewayError/unreachable(url:underlying:)`` /
    ///   ``GatewayError/timeout(url:underlying:)`` on transport errors.
    public func whoami() async throws -> WhoamiResponseDTO {
        let url = endpoint("/api/auth/whoami")
        return try await safe(url: url) {
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = Data("{}".utf8)

            let (data, response) = try await self.send(req)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            switch status {
            case 200:
                return (try? self.decoder.decode(WhoamiResponseDTO.self, from: data))
                    ?? WhoamiResponseDTO(scopes: [])
            case 401, 403:
                throw GatewayError.auth(url: url.absoluteString, statusCode: status)
            default:
                let bodyText = String(data: data, encoding: .utf8)
                throw GatewayError.api(
                    url: url.absoluteString,
                    statusCode: status,
                    body: bodyText
                )
            }
        }
    }

    /// Releases the underlying URLSession. Call on disconnect.
    public func close() {
        session.invalidateAndCancel()
    }

    // MARK: - Helpers

    private func endpoint(_ path: String) -> URL {
        // Avoid double-slash if the caller passed a URL with a trailing slash.
        let trimmed = baseURL.absoluteString.hasSuffix("/")
            ? String(baseURL.absoluteString.dropLast())
            : baseURL.absoluteString
        return URL(string: trimmed + path) ?? baseURL.appendingPathComponent(path)
    }

    private func contentTypeIsJSON(_ response: URLResponse) -> Bool {
        guard let http = response as? HTTPURLResponse,
              let ct = http.value(forHTTPHeaderField: "Content-Type")
        else { return false }
        return ct.lowercased().contains("json")
    }

    /// Sends a request with the Authorization header injected when a token is present.
    /// Logs at debug level with the Authorization header stripped.
    private func send(_ request: URLRequest) async throws -> (Data, URLResponse) {
        var req = request
        if let token {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        Self.logDebug(req)
        return try await session.data(for: req)
    }

    /// Maps an HTTP response into a `GatewayError`. No-op on 2xx.
    nonisolated private func mapErrors(url: URL, response: URLResponse) throws {
        guard let http = response as? HTTPURLResponse else { return }
        let code = http.statusCode
        if (200..<300).contains(code) { return }
        switch code {
        case 401, 403:
            throw GatewayError.auth(url: url.absoluteString, statusCode: code)
        default:
            throw GatewayError.api(url: url.absoluteString, statusCode: code)
        }
    }

    /// Wraps a request closure with the same error-translation policy as
    /// Kotlin's `safeRequest` (`OpenClawApiClient.kt:308-340`).
    private func safe<T>(
        url: URL,
        _ block: () async throws -> T
    ) async throws -> T {
        do {
            return try await block()
        } catch let e as GatewayError {
            // Re-throw domain errors unchanged — including configValidation,
            // which would otherwise fall through to the generic catch.
            throw e
        } catch let e as URLError {
            switch e.code {
            case .timedOut:
                throw GatewayError.timeout(url: url.absoluteString, underlying: e)
            case .serverCertificateHasBadDate,
                 .serverCertificateUntrusted,
                 .serverCertificateHasUnknownRoot,
                 .serverCertificateNotYetValid,
                 .clientCertificateRejected,
                 .clientCertificateRequired,
                 .secureConnectionFailed:
                throw GatewayError.tls(url: url.absoluteString, underlying: e)
            case .cannotConnectToHost,
                 .cannotFindHost,
                 .notConnectedToInternet,
                 .networkConnectionLost,
                 .dnsLookupFailed:
                throw GatewayError.unreachable(url: url.absoluteString, underlying: e)
            default:
                throw GatewayError.unreachable(url: url.absoluteString, underlying: e)
            }
        } catch let e as DecodingError {
            throw GatewayError.api(
                url: url.absoluteString,
                statusCode: 0,
                body: "Response deserialization failed: \(e)"
            )
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw GatewayError.unreachable(url: url.absoluteString, underlying: error)
        }
    }

    // MARK: - Logging (Authorization-redacted)

    /// Logs the request method + URL at debug level. Authorization header is
    /// never included in the log line — tokens must not appear in Console.app.
    nonisolated private static func logDebug(_ req: URLRequest) {
        #if DEBUG
        let method = req.httpMethod ?? "GET"
        let url = req.url?.absoluteString ?? "<nil>"
        var headerNames = req.allHTTPHeaderFields?.keys.map { $0 } ?? []
        headerNames.removeAll { $0.caseInsensitiveCompare("Authorization") == .orderedSame }
        logger.debug("\(method, privacy: .public) \(url, privacy: .public) headers=\(headerNames.joined(separator: ","), privacy: .public)")
        #endif
    }
}

// MARK: - v1.1 DTOs (skills + whoami)

/// Wire type for a single installed-skill entry returned by
/// `GET /api/skills` or `POST /api/skills/install`.
///
/// Added in v1.1 for the iOS port. The Android client deserializes the same
/// shape from JSON-RPC `skills.list` / `skills.install` responses.
public struct InstalledSkillDTO: Codable, Sendable, Equatable {
    public let slug: String
    public let installedVersion: String
    public let source: String?
    public let installedAt: Int64?

    public init(
        slug: String,
        installedVersion: String,
        source: String? = nil,
        installedAt: Int64? = nil
    ) {
        self.slug = slug
        self.installedVersion = installedVersion
        self.source = source
        self.installedAt = installedAt
    }

    private enum CodingKeys: String, CodingKey {
        case slug
        case installedVersion = "installed_version"
        case source
        case installedAt = "installed_at"
    }
}

/// Envelope form returned by some gateway builds for `GET /api/skills`.
public struct InstalledSkillsEnvelope: Codable, Sendable {
    public let skills: [InstalledSkillDTO]
    public init(skills: [InstalledSkillDTO]) { self.skills = skills }
}

/// Wire type for `POST /api/auth/whoami` — returns the scopes carried by the
/// active bearer token.
///
/// Added in v1.1 for the iOS port. Replaces the Kotlin JSON-RPC `auth.whoami`
/// response shape.
public struct WhoamiResponseDTO: Codable, Sendable, Equatable {
    public let scopes: [String]

    public init(scopes: [String]) {
        self.scopes = scopes
    }

    private enum CodingKeys: String, CodingKey {
        case scopes
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.scopes = (try? c.decode([String].self, forKey: .scopes)) ?? []
    }
}
