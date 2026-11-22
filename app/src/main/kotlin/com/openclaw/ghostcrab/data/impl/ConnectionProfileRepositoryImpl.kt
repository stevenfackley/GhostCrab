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
        // A failed write must not leave hasToken claiming a token that is not stored: a new token goes in before the
        // profile that points at it, a cleared one comes out only after the profile stops pointing at it.
        if (token != null) store.saveToken(profile.id, token)
        store.saveProfile(profile.toStored())
        if (token == null) store.saveToken(profile.id, null)
    }

    override suspend fun getToken(profileId: String): String? =
        store.getToken(profileId)

    override suspend fun deleteProfile(profileId: String) =
        store.deleteProfile(profileId)

    override suspend fun clearAll() = store.clearAll()
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
