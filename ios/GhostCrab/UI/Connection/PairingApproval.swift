import Foundation

/// What the gateway owner must do to approve this device (ADR-0007 decision 7). Mirrors Android's `PairingPrompt`.
public struct PairingPrompt: Equatable, Sendable {
    /// The gateway endpoint that asked for the approval, exactly as the error reported it.
    public let url: String
    /// The pending request, when the gateway sent one with a shell-safe spelling (see ``init(url:requestId:reason:requestedScopes:warnings:)``).
    /// Stable across reconnects.
    public let requestId: String?
    /// `not-paired`, `scope-upgrade`, `role-upgrade` or `metadata-upgrade`.
    public let reason: String
    /// The scopes this device asked for.
    public let requestedScopes: [String]
    /// What explains the request, e.g. ``ConnectionWarning/deviceIdentityRegenerated`` when an already-paired device
    /// has to be approved again. Empty when there is nothing to explain.
    public let warnings: [ConnectionWarning]

    /// Longest request id kept; real ids are UUIDs.
    private static let maxRequestIdLength = 128

    /// - Parameters:
    ///   - url: The gateway endpoint that asked for the approval.
    ///   - requestId: The pending request. Dropped (`nil`, so the prompt falls back to `openclaw devices list`)
    ///     unless it is 1–128 ASCII letters, digits, `-`, `_`, `.` or `:`: the approval command is copied into a
    ///     shell on the gateway host, so a spoofed id must not smuggle shell syntax into it.
    ///   - reason: Why the gateway asks.
    ///   - requestedScopes: The scopes this device asked for.
    ///   - warnings: What explains the request.
    public init(
        url: String,
        requestId: String?,
        reason: String,
        requestedScopes: [String],
        warnings: [ConnectionWarning] = []
    ) {
        self.url = url
        self.requestId = requestId.flatMap { Self.isShellSafe($0) ? $0 : nil }
        self.reason = reason
        self.requestedScopes = requestedScopes
        self.warnings = warnings
    }

    /// - Returns: The prompt for a ``GatewayError/pairingRequired(url:requestId:reason:requestedScopes:)``, `nil`
    ///   for any other error.
    public init?(error: any Error) {
        guard case let .pairingRequired(url, requestId, reason, scopes)? = error as? GatewayError else { return nil }
        self.init(url: url, requestId: requestId, reason: reason, requestedScopes: scopes)
    }

    /// The exact command for the gateway host: `openclaw devices approve <requestId>`, or `openclaw devices list`
    /// when the gateway sent no usable request id.
    public var approveCommand: String {
        requestId.map { "openclaw devices approve \($0)" } ?? "openclaw devices list"
    }

    /// - Parameter warnings: What explains the request.
    /// - Returns: This prompt with `warnings` instead of its own.
    public func with(warnings: [ConnectionWarning]) -> PairingPrompt {
        PairingPrompt(url: url, requestId: requestId, reason: reason, requestedScopes: requestedScopes, warnings: warnings)
    }

    private static func isShellSafe(_ id: String) -> Bool {
        !id.isEmpty && id.count <= maxRequestIdLength
            && id.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || "-_.:".contains($0)) }
    }
}

/// Approval-loop timing and copy.
public enum PairingApproval {
    /// How often a connect is re-attempted while the device awaits approval.
    public static let retryInterval: Duration = .seconds(5)
    /// Upstream expires a pending device-pairing request after 5 minutes.
    public static let timeout: Duration = .seconds(300)
    /// Shown when no approval arrived before the pending request expired. Plain text: the command stands on its own
    /// line.
    public static let timeoutMessage = "Device approval not received within 5 minutes; the pending request expired. "
        + "On the gateway host run:\nopenclaw devices list\nthen approve this device and connect again (PAIRING_REQUIRED)."
}

extension ConnectionWarning {
    /// Plain-text explanation for the approval card and the dashboard (shown amber, as every warning). Names the
    /// case, never a secret.
    public var userMessage: String {
        switch self {
        case .deviceIdentityRegenerated:
            return "This device has a new identity; approve it again. Its previous key could not be read from this "
                + "device's Keychain, so every gateway sees a new device (deviceIdentityRegenerated)."
        case .deviceTokenNotSaved:
            return "The gateway's device token could not be saved in this device's Keychain. This session works; "
                + "later connects need the gateway token until a device token is saved (deviceTokenNotSaved)."
        }
    }
}

/// Result of ``awaitPairingApproval(wakeUp:onPrompt:interval:timeout:attempt:)``.
public enum PairingOutcome: Equatable, Sendable {
    case approved
    case timedOut
}

/// "Check now" for the approval loop: ends the current wait early.
@MainActor
public final class PairingWakeUp {

    private static let tick: Duration = .milliseconds(100)
    private var wakeRequested = false

    public init() {}

    /// Ends the current (or the next) ``wait(_:)`` at once.
    public func fireNow() {
        wakeRequested = true
    }

    /// Forgets a ``fireNow()`` nobody waited for, so a "Check now" left over from an earlier run cannot cut the
    /// first interval of the next one.
    func reset() {
        wakeRequested = false
    }

    /// Sleeps for `interval`, or until ``fireNow()``.
    ///
    /// - Throws: `CancellationError` when the calling task is cancelled.
    func wait(_ interval: Duration) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: interval)
        while !wakeRequested, clock.now < deadline {
            try await Task.sleep(for: min(Self.tick, clock.now.duration(to: deadline)))
        }
        wakeRequested = false
    }
}

/// Re-runs `attempt` every `interval` (or at once on ``PairingWakeUp/fireNow()``) while it fails with
/// `PAIRING_REQUIRED`, passing each prompt to `onPrompt`. Nothing is approved from the phone. Any other failure ends
/// the wait at once, ``GatewayError/deviceCredentialStorage(operation:underlying:)`` included: only the user retries
/// that one.
///
/// - Parameters:
///   - wakeUp: "Check now".
///   - onPrompt: Receives every prompt the gateway reports while waiting.
///   - interval: Delay between attempts.
///   - timeout: Overall budget (the pending request's lifetime).
///   - attempt: The connect to retry.
/// - Returns: `.approved` once `attempt` succeeds, `.timedOut` after `timeout`.
/// - Throws: Whatever `attempt` throws other than `PAIRING_REQUIRED` (e.g. a revoked token); `CancellationError`.
@MainActor
public func awaitPairingApproval(
    wakeUp: PairingWakeUp,
    onPrompt: @MainActor (PairingPrompt) async -> Void,
    interval: Duration = PairingApproval.retryInterval,
    timeout: Duration = PairingApproval.timeout,
    attempt: @MainActor () async throws -> Void
) async throws -> PairingOutcome {
    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: timeout)
    while clock.now < deadline {
        try await wakeUp.wait(min(interval, clock.now.duration(to: deadline)))
        do {
            try await attempt()
            return .approved
        } catch {
            guard let prompt = PairingPrompt(error: error) else { throw error }
            await onPrompt(prompt)
        }
    }
    return .timedOut
}

/// Connects once; on `PAIRING_REQUIRED` reports the prompt and waits for approval with
/// ``awaitPairingApproval(wakeUp:onPrompt:interval:timeout:attempt:)``.
///
/// - Returns: `true` once connected, `false` when the approval window expired.
/// - Throws: Any other connect failure; `CancellationError`.
@MainActor
public func connectAwaitingApproval(
    wakeUp: PairingWakeUp,
    onPrompt: @MainActor (PairingPrompt) async -> Void,
    interval: Duration = PairingApproval.retryInterval,
    timeout: Duration = PairingApproval.timeout,
    connect: @MainActor () async throws -> Void
) async throws -> Bool {
    do {
        try await connect()
        return true
    } catch {
        guard let prompt = PairingPrompt(error: error) else { throw error }
        await onPrompt(prompt)
        let outcome = try await awaitPairingApproval(
            wakeUp: wakeUp, onPrompt: onPrompt, interval: interval, timeout: timeout, attempt: connect
        )
        return outcome == .approved
    }
}
