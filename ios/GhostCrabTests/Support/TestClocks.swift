import Foundation

/// Instant of ``ImmediateClock`` and ``TestClock``: an offset from the clock's start.
struct TestInstant: InstantProtocol {
    var offset: Swift.Duration

    func advanced(by duration: Swift.Duration) -> TestInstant {
        TestInstant(offset: offset + duration)
    }

    func duration(to other: TestInstant) -> Swift.Duration {
        other.offset - offset
    }

    static func < (lhs: TestInstant, rhs: TestInstant) -> Bool {
        lhs.offset < rhs.offset
    }
}

/// Never waits: every sleep returns at once with ``now`` moved to its deadline, so code that waits on a clock runs
/// to completion without real time passing.
final class ImmediateClock: Clock, @unchecked Sendable {
    typealias Duration = Swift.Duration
    typealias Instant = TestInstant

    private let lock = NSLock()
    private var current = TestInstant(offset: .zero)

    var now: TestInstant { lock.withLock { current } }
    var minimumResolution: Swift.Duration { .zero }

    func sleep(until deadline: TestInstant, tolerance: Swift.Duration?) async throws {
        try Task.checkCancellation()
        lock.withLock { current = max(current, deadline) }
        await Task.yield()
    }
}

/// Moves only when a test calls ``advance(by:)``. A sleep suspends until its deadline is reached or its task is
/// cancelled; ``waitForSleepers(_:)`` tells a test that the code under test is waiting.
final class TestClock: Clock, @unchecked Sendable {
    typealias Duration = Swift.Duration
    typealias Instant = TestInstant

    private struct Sleeper {
        let id: UInt64
        let deadline: TestInstant
        let continuation: CheckedContinuation<Void, any Error>
    }

    private let lock = NSLock()
    private var current = TestInstant(offset: .zero)
    private var sleepers: [Sleeper] = []
    /// Sleeps whose task was cancelled before they suspended.
    private var cancelledIDs: Set<UInt64> = []
    private var lastID: UInt64 = 0

    var now: TestInstant { lock.withLock { current } }
    var minimumResolution: Swift.Duration { .zero }

    /// Sleeps suspended on this clock.
    var sleeperCount: Int { lock.withLock { sleepers.count } }

    func sleep(until deadline: TestInstant, tolerance: Swift.Duration?) async throws {
        let id: UInt64 = lock.withLock {
            lastID += 1
            return lastID
        }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                let outcome: Result<Void, any Error>? = lock.withLock {
                    if cancelledIDs.remove(id) != nil { return .failure(CancellationError()) }
                    if deadline <= current { return .success(()) }
                    sleepers.append(Sleeper(id: id, deadline: deadline, continuation: continuation))
                    return nil
                }
                if let outcome { continuation.resume(with: outcome) }
            }
        } onCancel: {
            let sleeper: Sleeper? = lock.withLock {
                if let index = sleepers.firstIndex(where: { $0.id == id }) { return sleepers.remove(at: index) }
                cancelledIDs.insert(id)
                return nil
            }
            sleeper?.continuation.resume(throwing: CancellationError())
        }
    }

    /// Moves time forward by `duration` and wakes every sleep whose deadline it reached.
    func advance(by duration: Swift.Duration) {
        let due: [Sleeper] = lock.withLock {
            current = current.advanced(by: duration)
            let reached = current
            let due = sleepers.filter { $0.deadline <= reached }
            sleepers.removeAll { $0.deadline <= reached }
            return due
        }
        for sleeper in due { sleeper.continuation.resume() }
    }

    /// Returns once at least `count` sleeps are suspended on this clock, yielding (never sleeping) meanwhile.
    func waitForSleepers(_ count: Int = 1) async {
        while sleeperCount < count { await Task.yield() }
    }
}
