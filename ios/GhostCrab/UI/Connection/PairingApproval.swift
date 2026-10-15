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

    /// - Parameters:
    ///   - url: The gateway endpoint that asked for the approval.
    ///   - requestId: The pending request. Dropped (`nil`, so the prompt falls back to `openclaw devices list`)
    ///     unless it is 1–128 ASCII letters, digits, `-`, `_`, `.` or `:` starting with a letter or digit: the
    ///     approval command is copied into a shell on the gateway host, so a spoofed id must not smuggle shell syntax
    ///     into it, nor turn into an option (`--help`).
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
        self.requestId = PairingCommand.shellSafeRequestId(requestId)
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
        PairingCommand.approve(requestId: requestId)
    }

    /// - Parameter warnings: What explains the request.
    /// - Returns: This prompt with `warnings` instead of its own.
    public func with(warnings: [ConnectionWarning]) -> PairingPrompt {
        PairingPrompt(url: url, requestId: requestId, reason: reason, requestedScopes: requestedScopes, warnings: warnings)
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

/// Result of ``awaitPairingApproval(wakeUp:onPrompt:interval:timeout:attempt:)``.
public enum PairingOutcome: Equatable, Sendable {
    case approved
    case timedOut
}

/// "Check now" for the approval loop, and the clock the loop measures time on: ends the current wait early.
///
/// Nothing polls: a wait is one continuation that the interval's timer, ``fireNow()`` or the caller's cancellation
/// resumes, whichever comes first.
@MainActor
public final class PairingWakeUp {

    /// Measures the intervals and the approval window.
    let clock: any Clock<Duration>
    private var wakeRequested = false
    /// The suspended ``wait(_:)``, if any.
    private var waiter: CheckedContinuation<Void, any Error>?
    /// Ends ``waiter`` once its interval has passed on ``clock``.
    private var timer: Task<Void, Never>?
    /// Identifies the latest wait, so a late timer or cancellation from an earlier one never ends it.
    private var waitID: UInt64 = 0

    /// - Parameter clock: Measures the intervals and the approval window; tests pass a clock they advance themselves.
    public init(clock: any Clock<Duration> = ContinuousClock()) {
        self.clock = clock
    }

    /// Ends the current (or the next) ``wait(_:)`` at once.
    public func fireNow() {
        wakeRequested = true
        end(waitID)
    }

    /// Forgets a ``fireNow()`` nobody waited for, so a "Check now" left over from an earlier run cannot cut the
    /// first interval of the next one.
    func reset() {
        wakeRequested = false
    }

    /// Sleeps on ``clock`` for `interval`, or until ``fireNow()``. A wait still suspended when the next one starts
    /// (its run was cancelled and a new run started before the cancellation handler's main-actor hop) is resumed as
    /// cancelled first, so it is never left suspended.
    ///
    /// - Throws: `CancellationError` when the calling task is cancelled, or when a newer wait replaced this one.
    func wait(_ interval: Duration) async throws {
        defer { wakeRequested = false }
        if !wakeRequested {
            waitID &+= 1
            let id = waitID
            let clock = self.clock
            try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                    replaceWaiter(with: continuation)
                    timer = Task { @MainActor in
                        // Cancelled only by end(_:) or replaceWaiter(with:), which have already resumed the wait.
                        do { try await clock.sleep(for: interval) } catch { return }
                        self.end(id)
                    }
                }
            } onCancel: {
                Task { @MainActor in self.end(id) }
            }
        }
        try Task.checkCancellation()
    }

    /// Makes `continuation` the suspended wait; an earlier one still suspended ends with `CancellationError`.
    private func replaceWaiter(with continuation: CheckedContinuation<Void, any Error>) {
        if let stale = waiter {
            waiter = nil
            timer?.cancel()
            timer = nil
            stale.resume(throwing: CancellationError())
        }
        waiter = continuation
    }

    /// Resumes wait `id` if it is still the one suspended, and stops its timer.
    private func end(_ id: UInt64) {
        guard id == waitID, let continuation = waiter else { return }
        waiter = nil
        timer?.cancel()
        timer = nil
        continuation.resume()
    }
}

/// Re-runs `attempt` every `interval` (or at once on ``PairingWakeUp/fireNow()``) while it fails with
/// `PAIRING_REQUIRED`, passing each prompt to `onPrompt`. Nothing is approved from the phone. Any other failure ends
/// the wait at once, ``GatewayError/deviceCredentialStorage(operation:underlying:)`` included: only the user retries
/// that one.
///
/// - Parameters:
///   - wakeUp: "Check now", and the clock that measures `interval` and `timeout`.
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
    try await approvalLoop(
        clock: wakeUp.clock, wakeUp: wakeUp, onPrompt: onPrompt, interval: interval, timeout: timeout, attempt: attempt
    )
}

/// ``awaitPairingApproval(wakeUp:onPrompt:interval:timeout:attempt:)`` on the wake-up's clock, opened so its
/// instants can be compared.
@MainActor
private func approvalLoop<C: Clock<Duration>>(
    clock: C,
    wakeUp: PairingWakeUp,
    onPrompt: @MainActor (PairingPrompt) async -> Void,
    interval: Duration,
    timeout: Duration,
    attempt: @MainActor () async throws -> Void
) async throws -> PairingOutcome {
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
