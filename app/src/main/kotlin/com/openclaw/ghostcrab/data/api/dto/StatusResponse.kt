package com.openclaw.ghostcrab.data.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class StatusResponse(
    val displayName: String = "OpenClaw Gateway",
    val version: String = "unknown",
    val capabilities: List<String> = emptyList(),
    val hardware: String? = null,
)
