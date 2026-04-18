package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.ws.GatewayWsClient
import com.openclaw.ghostcrab.domain.repository.ScopeProbe
import com.openclaw.ghostcrab.domain.repository.ScopeProbeResult
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ScopeProbeImpl(
    private val wsFactory: suspend () -> GatewayWsClient,
) : ScopeProbe {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun probe(): ScopeProbeResult = try {
        val ws = wsFactory()
        val res = ws.request("auth.whoami", params = null).jsonObject
        val scopes = res["scopes"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content }
            ?.toSet()
            ?: emptySet()
        ScopeProbeResult.Known(scopes)
    } catch (e: GatewayWsClient.WsRpcException) {
        if (e.code == -32601) ScopeProbeResult.UnknownOldGateway
        else ScopeProbeResult.Failed(cause = "${e.code}: ${e.message}")
    } catch (e: Exception) {
        ScopeProbeResult.Failed(cause = e.message ?: "unknown")
    }
}
