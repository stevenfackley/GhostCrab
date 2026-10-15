package com.openclaw.ghostcrab.data.impl

import com.openclaw.ghostcrab.data.gateway.GatewayRpcClient
import com.openclaw.ghostcrab.data.gateway.objectAt
import com.openclaw.ghostcrab.data.gateway.stringListOrEmpty
import com.openclaw.ghostcrab.data.gateway.stringOrNull
import com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException
import com.openclaw.ghostcrab.domain.exception.GatewayProtocolException
import com.openclaw.ghostcrab.domain.exception.GatewayRpcException
import com.openclaw.ghostcrab.domain.model.ModelInfo
import com.openclaw.ghostcrab.domain.repository.ModelRepository
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val METHOD_MODELS_LIST = "models.list"
private const val METHOD_CONFIG_GET = "config.get"
private const val METHOD_CONFIG_PATCH = "config.patch"
private const val STATUS_READY = "ready"
private const val STATUS_UNAVAILABLE = "unavailable"
private const val CAPABILITY_REASONING = "reasoning"

/**
 * [ModelRepository] over the live session socket (ADR-0007). The catalogue is `models.list`; "active" is the config
 * key `agents.defaults.model.primary` (`provider/model`), read with `config.get` and written with `config.patch`
 * (there is no `models.set` RPC). [ModelInfo.id] is that `provider/model` ref.
 *
 * @param session Returns the live session's socket; throws `GatewayNotConnectedException` while disconnected.
 */
class ModelRepositoryImpl(
    private val session: () -> GatewayRpcClient,
) : ModelRepository {

    /**
     * @return The catalogue in server order; the entry matching `agents.defaults.model.primary` is active. Without
     *   `operator.admin` (`config.get` refused) the list still loads with no entry active.
     * @throws GatewayProtocolException if `models.list` answers a non-object payload.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException if no session is active.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayException on any other gateway failure.
     */
    override suspend fun getModels(): List<ModelInfo> {
        val rpc = session()
        val payload = rpc.request(METHOD_MODELS_LIST) as? JsonObject
            ?: throw GatewayProtocolException(rpc.endpoint, "models.list returned a non-object payload")
        val primary = primaryModel(rpc)
        return (payload["models"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toModelInfo(primary) }
    }

    /**
     * Writes `agents.defaults.model.primary` with `config.patch`, against the `hash` of a fresh `config.get`.
     *
     * @param modelId A [ModelInfo.id] (`provider/model`).
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayApiException 412 when the config changed between the
     *   read and the write (or no hash was available): retry; 422 when the gateway rejects the model ref (the body
     *   is the server text plus every validation issue).
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayMissingScopeException without `operator.admin`.
     * @throws com.openclaw.ghostcrab.domain.exception.GatewayNotConnectedException if no session is active.
     */
    override suspend fun setActiveModel(modelId: String) {
        val rpc = session()
        val hash = (rpc.request(METHOD_CONFIG_GET) as? JsonObject)?.stringOrNull("hash")
        val patch = buildJsonObject {
            putJsonObject("agents") { putJsonObject("defaults") { putJsonObject("model") { put("primary", modelId) } } }
        }
        try {
            rpc.request(METHOD_CONFIG_PATCH, configPatchParams(patch, hash), CONFIG_PATCH_TIMEOUT_MS)
        } catch (e: GatewayRpcException) {
            throw e.toModelWriteException()
        }
    }

    /** `agents.defaults.model.primary` (object or string form); `null` when unset or without `operator.admin`. */
    private suspend fun primaryModel(rpc: GatewayRpcClient): String? {
        val config = try {
            rpc.request(METHOD_CONFIG_GET) as? JsonObject
        } catch (ignored: GatewayMissingScopeException) {
            // config.* is admin-only; the catalogue stays useful without the active marker.
            return null
        }
        return when (val model = config?.objectAt("parsed", "agents", "defaults")?.get("model")) {
            is JsonObject -> model.stringOrNull("primary")
            is JsonPrimitive -> model.takeIf { it.isString }?.content
            else -> null
        }
    }
}

/** One `models.list` entry as a [ModelInfo]; `null` without `id` or `provider`. */
@Suppress("ReturnCount")
private fun JsonObject.toModelInfo(primary: String?): ModelInfo? {
    val id = stringOrNull("id") ?: return null
    val provider = stringOrNull("provider") ?: return null
    val ref = modelRef(provider, id)
    val available = (this["available"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    val reasoning = (this["reasoning"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
    return ModelInfo(
        id = ref,
        provider = provider,
        displayName = stringOrNull("name") ?: id,
        isActive = primary != null && (primary == ref || primary == id),
        status = if (available == false) stringOrNull("unavailableReason") ?: STATUS_UNAVAILABLE else STATUS_READY,
        capabilities = stringListOrEmpty("input") + listOfNotNull(CAPABILITY_REASONING.takeIf { reasoning }),
    )
}

/**
 * @param provider `models.list` `provider`.
 * @param id `models.list` `id`.
 * @return `provider/id`, unless [id] already carries the provider prefix.
 */
internal fun modelRef(provider: String, id: String): String = if (id.startsWith("$provider/")) id else "$provider/$id"
