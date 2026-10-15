import Foundation

/// How a ``PairingWait`` run ended. Not reported for a run that was cancelled (``PairingWait/cancel()``, or its
/// owner going away): the owner already reset its screen.
public enum PairingWaitResult {
    /// The gateway accepted the connect, at once or after the device was approved.
    case connected
    /// No approval arrived before the pending request expired; the wait disconnected.
    case expired
    /// Another screen acted on the connection (a newer connect or a disconnect), or the manager superseded this
    /// connect (`CancellationError`). The wait ended without touching the connection. Show nothing.
    case superseded
    /// The connect failed for another reason, or the token could not be read. `PAIRING_REQUIRED` never ends up here;
    /// ``GatewayError/deviceCredentialStorage(operation:underlying:)`` does, at once: only the user retries it.
    case failed(any Error)
}

/// One connect at a time for a screen, including the device-approval wait it may turn into (ADR-0007 decision 7).
/// Shared by every ViewModel that connects (Manual Entry, the saved-gateway list); owns the running task and the
/// approval card's "Check now" and "Cancel". Mirrors Android's `PairingWait`.
///
/// - The gateway token is read once, before the first connect, never per retry.
/// - On `PAIRING_REQUIRED` the connect is retried every `retryInterval` (or at once on ``checkNow()``) until the
///   pending request expires (`timeout`). Every prompt carries the manager's ``ConnectionWarning``s, which explain it.
/// - A wait never fights another screen: before each retry it checks that the manager still holds this wait's own
///   `PAIRING_REQUIRED` error for its URL. Anything else (a newer connect, a disconnect) ends the run as
///   ``PairingWaitResult/superseded`` without touching the connection.
/// - Expiry, ``cancel()`` and the owner going away (this object's `deinit`) disconnect when the manager still holds
///   that error, so no stale `PAIRING_REQUIRED` outlives the wait. A cancelled run whose connect had already
///   succeeded disconnects too: no screen will show that session.
///
/// The running task holds neither this object nor its owner (callers pass `[weak self]` closures), so releasing the
/// owner releases the wait and cancels its run.
@MainActor
public final class PairingWait {

    private let connectionManager: any GatewayConnectionManager
    private let retryInterval: Duration
    private let timeout: Duration
    private let wakeUp = PairingWakeUp()
    /// Bumped by every ``start(url:token:onPrompt:onFinished:)`` and ``cancel()``; a run whose id differs is stale.
    private var runID: UInt64 = 0

    /// The running connect and its approval wait, if any. Tests await it.
    public private(set) var task: Task<Void, Never>?

    /// `true` while a connect, or the approval wait it turned into, is running.
    public var isRunning: Bool { task != nil }

    /// - Parameters:
    ///   - connectionManager: Connects, and supplies the state each retry checks and the warnings that explain a
    ///     prompt.
    ///   - retryInterval: Delay between connects while the device awaits approval.
    ///   - timeout: How long to wait for approval (upstream expires a pending request after 5 minutes).
    public init(
        connectionManager: any GatewayConnectionManager,
        retryInterval: Duration = PairingApproval.retryInterval,
        timeout: Duration = PairingApproval.timeout
    ) {
        self.connectionManager = connectionManager
        self.retryInterval = retryInterval
        self.timeout = timeout
    }

    deinit {
        // The run holds no reference to this object; once cancelled it disconnects if it still waits.
        task?.cancel()
    }

    /// Reads the token, connects; on `PAIRING_REQUIRED` retries until the device is approved or the pending request
    /// expires. Does nothing while a run is in progress: one loop at a time.
    ///
    /// - Parameters:
    ///   - url: The gateway base URL (the manager reports its errors under it).
    ///   - token: Reads the gateway token, once, before the first connect. A failure ends the run as
    ///     ``PairingWaitResult/failed(_:)`` before anything connects.
    ///   - onPrompt: Receives every approval prompt, with the warnings that explain it.
    ///   - onFinished: Receives how the run ended. Runs inside the run's task (``task``), so a ``cancel()`` during it
    ///     is visible to it as task cancellation. Not called for a cancelled run.
    /// - Returns: `false` when a run was already in progress and nothing was started.
    @discardableResult
    public func start(
        url: String,
        token: @escaping @MainActor @Sendable () async throws -> String?,
        onPrompt: @escaping @MainActor @Sendable (PairingPrompt) -> Void,
        onFinished: @escaping @MainActor @Sendable (PairingWaitResult) async -> Void
    ) -> Bool {
        guard task == nil else { return false }
        runID &+= 1
        let id = runID
        // A "Check now" left over from an earlier run must not cut this run's first interval.
        wakeUp.reset()
        let manager = connectionManager
        let wakeUp = self.wakeUp
        let interval = retryInterval
        let timeout = self.timeout
        task = Task { [weak self] in
            var accepted = false
            let result: PairingWaitResult
            do {
                let credential = try await token()
                accepted = try await Self.connectOrWait(
                    url: url, credential: credential, manager: manager, wakeUp: wakeUp,
                    interval: interval, timeout: timeout, onPrompt: onPrompt
                )
                result = accepted ? .connected : .expired
            } catch is PairingWaitSuperseded {
                result = .superseded
            } catch is CancellationError {
                // This run was cancelled, or the manager superseded the connect with a newer one.
                result = .superseded
            } catch {
                result = .failed(error)
            }
            if Task.isCancelled {
                // cancel(), or the owner went away: the owner already reset its screen.
                if accepted || Self.awaitingApproval(manager, url: url) {
                    await manager.disconnect()
                }
                return
            }
            if case .expired = result, Self.awaitingApproval(manager, url: url) {
                await manager.disconnect()
            }
            await onFinished(result)
            self?.ended(id)
        }
        return true
    }

    /// "Check now" on the approval card: retry the connect at once instead of at the next interval.
    public func checkNow() {
        wakeUp.fireNow()
    }

    /// "Cancel" on the approval card, or the screen being left: stops the run without reporting it. Once the run has
    /// ended, it disconnects if the manager still holds this run's `PAIRING_REQUIRED` error (the pending request
    /// expires on the gateway), or if its connect had already succeeded. No-op while nothing runs.
    public func cancel() {
        guard let running = task else { return }
        task = nil
        runID &+= 1
        running.cancel()
    }

    private func ended(_ id: UInt64) {
        if runID == id { task = nil }
    }

    /// Connects; on `PAIRING_REQUIRED` waits for approval. Every retry first checks ``awaitingApproval(_:url:)``.
    ///
    /// - Returns: `true` once connected, `false` when the approval window expired.
    /// - Throws: ``PairingWaitSuperseded`` when another screen acted on the connection; any other connect failure;
    ///   `CancellationError`.
    private static func connectOrWait(
        url: String,
        credential: String?,
        manager: any GatewayConnectionManager,
        wakeUp: PairingWakeUp,
        interval: Duration,
        timeout: Duration,
        onPrompt: @escaping @MainActor @Sendable (PairingPrompt) -> Void
    ) async throws -> Bool {
        let attempts = AttemptCounter()
        return try await connectAwaitingApproval(
            wakeUp: wakeUp,
            onPrompt: { prompt in
                let warnings = await manager.connectionWarnings()
                guard !Task.isCancelled else { return }
                onPrompt(prompt.with(warnings: warnings))
            },
            interval: interval,
            timeout: timeout
        ) {
            attempts.count += 1
            if attempts.count > 1, !awaitingApproval(manager, url: url) { throw PairingWaitSuperseded() }
            try await manager.connect(url: url, token: credential)
        }
    }

    /// `true` while the manager's state is still the `PAIRING_REQUIRED` error for `url`.
    private static func awaitingApproval(_ manager: any GatewayConnectionManager, url: String) -> Bool {
        guard case let .error(errorURL, cause) = manager.currentState, errorURL == url,
              case .pairingRequired = cause
        else {
            return false
        }
        return true
    }
}

/// Counts the attempts of one run, so only retries check the manager's state.
@MainActor
private final class AttemptCounter {
    var count = 0
}

/// Another screen acted on the connection since the last attempt; the wait ends without retrying.
private struct PairingWaitSuperseded: Error {}
