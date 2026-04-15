package com.openclaw.ghostcrab.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import com.openclaw.ghostcrab.domain.repository.GatewayConnectionManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConnectionPickerViewModel(
    private val profileRepository: ConnectionProfileRepository,
    private val connectionManager: GatewayConnectionManager,
) : ViewModel() {

    val profiles: StateFlow<List<ConnectionProfile>> = profileRepository
        .getProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(profileId: String) {
        viewModelScope.launch {
            profileRepository.deleteProfile(profileId)
        }
    }

    fun connect(profile: ConnectionProfile, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                val token = if (profile.hasToken) profileRepository.getToken(profile.id) else null
                connectionManager.connect(profile.url, token)
            }
            onResult(result)
        }
    }
}
