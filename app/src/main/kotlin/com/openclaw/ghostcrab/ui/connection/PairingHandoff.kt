package com.openclaw.ghostcrab.ui.connection

import com.openclaw.ghostcrab.domain.util.PairBundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * One-shot, in-memory handoff of a scanned or deep-linked [PairBundle] to the ManualEntry screen.
 * Keeps the bearer token out of navigation routes and saved state (both end up in the saved-state
 * `Bundle`); the bundle never leaves process memory, is cleared on read, and expires after [ttl] so an
 * unconsumed one never prefills a later, unrelated visit. Registered as a Koin `single`.
 *
 * @param timeSource Clock for expiry; injectable for tests.
 * @param ttl How long an offered bundle stays takeable.
 */
class PairingHandoff(
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val ttl: Duration = DEFAULT_TTL,
) {

    private class Pending(val bundle: PairBundle, val offeredAt: TimeMark)

    private val pending = AtomicReference<Pending?>(null)

    private val _offers = MutableStateFlow(0)

    /** Number of bundles offered so far; a visible consumer re-runs [take] when it changes. */
    val offers: StateFlow<Int> = _offers.asStateFlow()

    /**
     * Stores [bundle], replacing any unconsumed one, and bumps [offers].
     *
     * @param bundle The decoded pair bundle.
     */
    fun offer(bundle: PairBundle) {
        pending.set(Pending(bundle, timeSource.markNow()))
        _offers.update { it + 1 }
    }

    /**
     * Returns and clears the pending bundle, so a bundle is consumed at most once.
     *
     * @return The bundle, or `null` when none is pending or it is older than [ttl] (it is dropped either way).
     */
    fun take(): PairBundle? = pending.getAndSet(null)?.takeIf { it.offeredAt.elapsedNow() < ttl }?.bundle

    private companion object {
        val DEFAULT_TTL: Duration = 2.minutes
    }
}
