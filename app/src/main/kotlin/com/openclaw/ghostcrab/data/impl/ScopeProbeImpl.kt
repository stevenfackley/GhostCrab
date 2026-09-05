package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.ws.GatewayWsClient
import com.openclaw.ghostcrab.domain.repository.ScopeProbe
import com.openclaw.ghostcrab.domain.repository.ScopeProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * WS-backed [ScopeProbe]. Each probe opens its own session via [wsFactory] and closes it
 * before returning, so repeated probes never accumulate open sockets.
 *
 * @param wsFactory Suspending factory that opens a fresh [GatewayWsClient].
 */
class ScopeProbeImpl(
    private val wsFactory: suspend () -> GatewayWsClient,
) : ScopeProbe {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun probe(): ScopeProbeResult = try {
        val ws = wsFactory()
        try {
            val res = ws.request("auth.whoami", params = null).jsonObject
            val scopes = res["scopes"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content }
                ?.toSet()
                ?: emptySet()
            ScopeProbeResult.Known(scopes)
        } finally {
            withContext(NonCancellable) { ws.close() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: GatewayWsClient.WsRpcException) {
        if (e.code == -32601) ScopeProbeResult.UnknownOldGateway
        else ScopeProbeResult.Failed(cause = "${e.code}: ${e.message}")
    } catch (e: Exception) {
        ScopeProbeResult.Failed(cause = e.message ?: "unknown")
    }
}
