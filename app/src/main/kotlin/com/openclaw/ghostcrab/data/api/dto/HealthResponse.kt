package com.openclaw.ghostcrab.data.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
)
