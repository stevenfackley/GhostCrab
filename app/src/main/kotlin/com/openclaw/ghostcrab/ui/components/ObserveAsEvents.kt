package com.openclaw.ghostcrab.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Collects one-shot events from [flow] while the host lifecycle is at least STARTED and passes
 * each one to [onEvent].
 *
 * Collection stops when the screen goes to the background, so a buffered event (a Channel-backed
 * `receiveAsFlow()`) waits in the channel instead of firing navigation from a stopped screen; it is
 * delivered once the screen is started again. Collecting on [Dispatchers.Main.immediate] hands the
 * event to [onEvent] in the same dispatch it is received, so it cannot be dropped between receive
 * and handling when the lifecycle stops. [onEvent] is read through [rememberUpdatedState]: a
 * recomposed handler takes effect without restarting collection.
 *
 * @param T Event type.
 * @param flow One-shot event stream. Must not be shared by two collectors, or each event reaches only one.
 * @param onEvent Called on the main thread for every event.
 */
@Composable
fun <T> ObserveAsEvents(flow: Flow<T>, onEvent: (T) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(flow, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Main.immediate) {
                flow.collect { currentOnEvent(it) }
            }
        }
    }
}
