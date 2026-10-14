package com.openclaw.ghostcrab.domain.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for coroutine code: captures every failure as [Result.failure] **except**
 * [CancellationException], which is rethrown so structured concurrency keeps working (a cancelled
 * `viewModelScope` must never be shown to the user as an error). Inline, so [block] may suspend.
 *
 * @param block The work to run.
 * @return [Result.success] with the value, or [Result.failure] with the thrown exception.
 * @throws CancellationException if [block] was cancelled.
 */
@Suppress("TooGenericExceptionCaught")
inline fun <T> suspendRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
