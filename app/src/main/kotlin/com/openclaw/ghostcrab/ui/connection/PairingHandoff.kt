package com.openclaw.ghostcrab.ui.connection

import com.openclaw.ghostcrab.domain.util.PairBundle
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot, in-memory handoff of a scanned or deep-linked [PairBundle] to the ManualEntry screen.
 * Keeps the bearer token out of navigation routes and saved state (both end up in the saved-state
 * `Bundle`); the bundle never leaves process memory and is cleared on read. Registered as a Koin `single`.
 */
class PairingHandoff {

    private val pending = AtomicReference<PairBundle?>(null)

    /**
     * Stores [bundle], replacing any unconsumed one.
     *
     * @param bundle The decoded pair bundle.
     */
    fun offer(bundle: PairBundle) {
        pending.set(bundle)
    }

    /**
     * Returns and clears the pending bundle, so a bundle is consumed at most once.
     *
     * @return The bundle, or `null` when none is pending.
     */
    fun take(): PairBundle? = pending.getAndSet(null)
}
