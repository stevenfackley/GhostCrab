package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.storage.ConnectionProfileStore
import com.openclaw.ghostcrab.data.storage.StoredProfile
import com.openclaw.ghostcrab.domain.exception.ProfileNeedsReauthException
import com.openclaw.ghostcrab.domain.model.ConnectionProfile
import com.openclaw.ghostcrab.domain.repository.ConnectionProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ConnectionProfileRepositoryImpl(
    private val store: ConnectionProfileStore,
) : ConnectionProfileRepository {

    override fun getProfiles(): Flow<List<ConnectionProfile>> =
        store.getProfilesFlow().map { list -> list.map { it.toDomain() } }

    override suspend fun saveProfile(profile: ConnectionProfile, token: String?) {
        store.saveProfile(profile.toStored())
        store.saveToken(profile.id, token)
    }

    override suspend fun getToken(profileId: String): String? =
        store.getToken(profileId)

    override suspend fun deleteProfile(profileId: String) =
        store.deleteProfile(profileId)
}

private fun StoredProfile.toDomain() = ConnectionProfile(
    id = id,
    displayName = displayName,
    url = url,
    lastConnectedAt = lastConnectedAt,
    hasToken = hasToken,
)

private fun ConnectionProfile.toStored() = StoredProfile(
    id = id,
    displayName = displayName,
    url = url,
    lastConnectedAt = lastConnectedAt,
    hasToken = hasToken,
)
