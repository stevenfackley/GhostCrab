package com.openclaw.ghostcrab.ui.installedskills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.exception.GatewayConnectionClosedException
import com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException
import com.openclaw.ghostcrab.domain.exception.GatewayUnreachableException
import com.openclaw.ghostcrab.domain.model.GatewayConnection
import com.openclaw.ghostcrab.domain.model.InstalledSkill
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import com.openclaw.ghostcrab.domain.repository.InstalledSkillRepository
import com.openclaw.ghostcrab.ui.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long a read or a disable that failed with an end-of-socket error waits for the connection state to leave its
 * session before the failure is shown. The socket's end fails pending requests first; the manager publishes the
 * silent reconnect (or Error) right after, so this only bounds a socket that failed a request without ending.
 */
internal const val SOCKET_END_GRACE_MS: Long = 5_000L

/**
 * ViewModel for [InstalledSkillsScreen].
 *
 * The connection state drives the screen; the iOS `InstalledSkillsViewModel` follows the same rules:
 * - A live session (Connected, not reconnecting) is read with `skills.status`
 *   ([InstalledSkillRepository.refreshFromGateway]): [InstalledSkillsUiState.Loading] until it answers, then
 *   [InstalledSkillsUiState.Ready] (an empty list is "No skills installed") or [InstalledSkillsUiState.LoadFailed].
 *   Every new session (the silent reconnect's included) is read again.
 * - The silent reconnect keeps the rows on screen; with nothing loaded yet it shows Loading, which ends when the
 *   reconnect does.
 * - Error (the connect or the silent reconnect failed) and Disconnected show [InstalledSkillsUiState.Disconnected],
 *   without rows; Connecting shows Loading.
 * - Lists from [InstalledSkillRepository.observeInstalled] and read results count only while their session is live.
 * - A read or a disable that fails because the socket ended (the failure arrives before the state that follows) shows
 *   no failure: the next connection state decides. Only if the state stays on that session for
 *   [SOCKET_END_GRACE_MS] is the failure shown.
 *
 * @param repo Frozen v1.1 skill repository — only registered in Koin when
 *   `BuildConfig.SKILLS_INSTALL_ENABLED` is true.
 * @param connectionManager Source of the connection state.
 */
class InstalledSkillsViewModel(
    private val repo: InstalledSkillRepository,
    private val connectionManager: GatewayConnectionManager,
) : ViewModel() {

    private val _state = MutableStateFlow<InstalledSkillsUiState>(InstalledSkillsUiState.Loading)

    /** Observable UI state. */
    val state: StateFlow<InstalledSkillsUiState> = _state.asStateFlow()

    /** The latest connection state. */
    private var connection: GatewayConnection = connectionManager.connectionState.value

    /** `sessionGeneration` of the session last read (or being read); `null` while no read belongs to one. */
    private var loadedFor: Long? = null

    /** The read in flight, if any. */
    private var loadJob: Job? = null

    /** The live session's `sessionGeneration`, or `null` while there is none (reconnecting included). */
    private val liveGeneration: Long?
        get() = connection.liveGeneration()

    /** Whether a refresh would read anything (a session is live); the screen offers Refresh only then. */
    val canRefresh: StateFlow<Boolean> = connectionManager.connectionState
        .map { it.liveGeneration() != null }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = connectionManager.connectionState.value.liveGeneration() != null,
        )

    init {
        viewModelScope.launch { connectionManager.connectionState.collect(::onConnection) }
        viewModelScope.launch {
            repo.observeInstalled().collect { list ->
                // The repository emits an empty list for a socket that is no longer live: only a live session's
                // list counts.
                if (liveGeneration == null) return@collect
                _state.update { current ->
                    when (current) {
                        is InstalledSkillsUiState.Ready -> current.copy(skills = list)
                        // A cached list shows at once; an empty one may only mean "not read on this socket yet", so
                        // the read's answer decides.
                        is InstalledSkillsUiState.Loading, is InstalledSkillsUiState.LoadFailed ->
                            if (list.isEmpty()) {
                                current
                            } else {
                                InstalledSkillsUiState.Ready(skills = list, isRefreshing = loadJob?.isActive == true)
                            }
                        is InstalledSkillsUiState.Disconnected -> current
                    }
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Reads the list again with `skills.status`. On failure sets [InstalledSkillsUiState.Ready.errorMessage] while
     * rows are shown, else shows [InstalledSkillsUiState.LoadFailed]. Does nothing while no session is live: a new
     * session reads by itself.
     */
    fun refresh() {
        liveGeneration?.let(::load)
    }

    /** Opens the uninstall confirmation dialog for [slug]. */
    fun requestUninstall(slug: String) {
        withReady { it.copy(pendingUninstallSlug = slug) }
    }

    /** Dismisses the uninstall confirmation dialog without acting. */
    fun cancelUninstall() {
        withReady { it.copy(pendingUninstallSlug = null) }
    }

    /**
     * Confirms the pending skill's removal, which on OpenClaw means disabling it
     * ([InstalledSkillRepository.setSkillEnabled] with `enabled = false`; there is no removal RPC). Closes the
     * dialog, emits an in-flight indicator, and sets [InstalledSkillsUiState.Ready.uninstallSuccessSlug]
     * on success or [InstalledSkillsUiState.Ready.errorMessage] on failure. A failure from the end of the session it
     * started on is not reported: the connection state that follows says it, and the next session's list shows
     * whether the skill was disabled.
     */
    fun confirmUninstall() {
        val slug = (_state.value as? InstalledSkillsUiState.Ready)?.pendingUninstallSlug ?: return
        val startedOn = liveGeneration
        withReady { it.copy(pendingUninstallSlug = null, uninstallingSlug = slug) }
        viewModelScope.launch {
            // OpenClaw has no skill removal (ADR-0007): the confirm action disables the skill.
            // A newer disable, or a new session's list, owns the row spinner otherwise.
            fun InstalledSkillsUiState.Ready.done() =
                copy(uninstallingSlug = uninstallingSlug.takeUnless { it == slug })
            repo.setSkillEnabled(slug, enabled = false)
                .onSuccess {
                    withReady { s -> s.done().copy(uninstallSuccessSlug = slug) }
                }
                .onFailure { e ->
                    val cutOff = startedOn != null && e.endsTheSocket() &&
                        connectionManager.connectionState.leavesSession(startedOn)
                    withReady { s -> if (cutOff) s.done() else s.done().copy(errorMessage = e.toUserMessage()) }
                }
        }
    }

    /** Clears the uninstall-success flag after the snackbar has been shown. */
    fun clearUninstallSuccess() {
        withReady { it.copy(uninstallSuccessSlug = null) }
    }

    /** Clears the transient error message after the snackbar has been shown. */
    fun clearError() {
        withReady { it.copy(errorMessage = null) }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun onConnection(conn: GatewayConnection) {
        connection = conn
        when (conn) {
            is GatewayConnection.Connected -> when {
                // The silent reconnect ends in a new session (read below) or in Error: rows on screen stay meanwhile.
                conn.reconnecting -> if (_state.value !is InstalledSkillsUiState.Ready) {
                    loadedFor = null
                    _state.value = InstalledSkillsUiState.Loading
                }
                conn.sessionGeneration != loadedFor -> load(conn.sessionGeneration)
            }
            is GatewayConnection.Connecting -> {
                endSession()
                _state.value = InstalledSkillsUiState.Loading
            }
            is GatewayConnection.Error -> {
                endSession()
                _state.value = InstalledSkillsUiState.Disconnected(reason = conn.cause.toUserMessage())
            }
            is GatewayConnection.Disconnected -> {
                endSession()
                _state.value = InstalledSkillsUiState.Disconnected(reason = null)
            }
        }
    }

    /** Reads the list of the session [generation], replacing a read still in flight. */
    private fun load(generation: Long) {
        loadedFor = generation
        loadJob?.cancel()
        _state.update {
            (it as? InstalledSkillsUiState.Ready)?.copy(isRefreshing = true) ?: InstalledSkillsUiState.Loading
        }
        loadJob = viewModelScope.launch {
            val result = repo.refreshFromGateway()
            if (!isActive) return@launch
            if (liveGeneration != generation) {
                // Its session has ended: the connection state decides what shows.
                withReady { it.copy(isRefreshing = false) }
                return@launch
            }
            result.onSuccess { list ->
                _state.update {
                    (it as? InstalledSkillsUiState.Ready)?.copy(skills = list, isRefreshing = false)
                        ?: InstalledSkillsUiState.Ready(skills = list)
                }
            }.onFailure { e ->
                // The socket's end fails this read before the manager publishes what follows (the silent reconnect,
                // Error): that state, not this failure, is what the screen shows.
                if (e.endsTheSocket() && connectionManager.connectionState.leavesSession(generation)) {
                    withReady { it.copy(isRefreshing = false) }
                    return@launch
                }
                _state.update {
                    (it as? InstalledSkillsUiState.Ready)?.copy(isRefreshing = false, errorMessage = e.toUserMessage())
                        ?: InstalledSkillsUiState.LoadFailed(e.toUserMessage())
                }
            }
        }
    }

    /** No session is live any more: a read in flight is dropped and the next session is read. */
    private fun endSession() {
        loadJob?.cancel()
        loadJob = null
        loadedFor = null
    }

    private fun withReady(block: (InstalledSkillsUiState.Ready) -> InstalledSkillsUiState) {
        _state.update { if (it is InstalledSkillsUiState.Ready) block(it) else it }
    }
}

/** The live session's `sessionGeneration`, or `null` unless Connected and not reconnecting. */
private fun GatewayConnection.liveGeneration(): Long? =
    (this as? GatewayConnection.Connected)?.takeUnless { it.reconnecting }?.sessionGeneration

/**
 * Whether this failure is the session socket ending: a drop or failed send ([GatewayUnreachableException]), the
 * gateway's 1008 close ([GatewayConnectionClosedException]), or a closed or missing session
 * ([GatewayNotConnectedException]). A timeout or an error answer comes from a live socket and is not one.
 */
private fun Throwable.endsTheSocket(): Boolean =
    this is GatewayUnreachableException || this is GatewayConnectionClosedException ||
        this is GatewayNotConnectedException

/**
 * @receiver The connection state.
 * @param generation The session a request ran on.
 * @return `true` once the state is no longer that live session (reconnecting, Error, Disconnected, Connecting or a
 *   newer session), `false` if it still is after [SOCKET_END_GRACE_MS].
 */
private suspend fun StateFlow<GatewayConnection>.leavesSession(generation: Long): Boolean =
    withTimeoutOrNull(SOCKET_END_GRACE_MS) { first { it.liveGeneration() != generation } } != null
