package com.openclaw.ghostcrab.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.openclaw.ghostcrab.domain.model.DiscoveredGateway
import com.openclaw.ghostcrab.domain.repository.DiscoveryService
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val SERVICE_TYPE = "_openclaw-gw._tcp."
private const val RESOLVE_TIMEOUT_MS = 5_000L

/**
 * Production [DiscoveryService] backed by Android [NsdManager].
 *
 * - Acquires a [WifiManager.MulticastLock] for the duration of each scan.
 * - Per-service [NsdManager.resolveService] is serialised via [resolveMutex] (Android limitation:
 *   only one resolution at a time on API < 34) and wrapped in a [RESOLVE_TIMEOUT_MS] timeout with
 *   a single retry.
 * - Deduplicates by [NsdServiceInfo.getServiceName] (mDNS instance name).
 * - [stopDiscovery] closes the active [callbackFlow] channel, completing any ongoing collection.
 *
 * @param context Application context.
 */
class NsdDiscoveryServiceImpl(private val context: Context) : DiscoveryService {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** Serialises NsdManager.resolveService calls; Android only allows one in-flight at a time. */
    private val resolveMutex = Mutex()

    /** Non-null while a [startDiscovery] flow is being collected. */
    @Volatile private var activeChannel: SendChannel<DiscoveredGateway>? = null

    override fun startDiscovery(): Flow<DiscoveredGateway> = callbackFlow {
        val seen = mutableSetOf<String>()

        val multicastLock = wifiManager.createMulticastLock("ghostcrab_mdns").apply {
            setReferenceCounted(true)
        }
        runCatching { multicastLock.acquire() }

        activeChannel = channel

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                launch {
                    val resolved = resolveWithRetry(serviceInfo) ?: return@launch
                    val instanceName = resolved.serviceName ?: return@launch
                    if (!seen.add(instanceName)) return@launch

                    val host = resolved.host?.hostAddress ?: return@launch
                    val port = resolved.port.takeIf { it > 0 } ?: return@launch

                    val attrs = resolved.attributes
                    val displayName = attrs["displayName"]
                        ?.let { String(it) }
                        ?.takeIf { it.isNotBlank() }
                        ?: instanceName
                    val version = attrs["version"]?.let { String(it) }

                    trySend(
                        DiscoveredGateway(
                            instanceName = instanceName,
                            hostAddress = host,
                            port = port,
                            displayName = displayName,
                            version = version,
                        )
                    )
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("NSD discovery start failed: errorCode=$errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        awaitClose {
            activeChannel = null
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (_: Exception) {
                // Safe to ignore: already stopped or never started
            }
            if (multicastLock.isHeld) multicastLock.release()
        }
    }

    override suspend fun stopDiscovery() {
        activeChannel?.close()
        activeChannel = null
    }

    /**
     * Attempts to resolve [serviceInfo] twice before giving up.
     *
     * @return Resolved [NsdServiceInfo], or `null` if both attempts time out or fail.
     */
    private suspend fun resolveWithRetry(serviceInfo: NsdServiceInfo): NsdServiceInfo? =
        resolveOnce(serviceInfo) ?: resolveOnce(serviceInfo)

    @Suppress("DEPRECATION") // resolveService deprecated API 34; required for minSdk 26
    private suspend fun resolveOnce(serviceInfo: NsdServiceInfo): NsdServiceInfo? =
        resolveMutex.withLock {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                    nsdManager.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }

                            override fun onServiceResolved(si: NsdServiceInfo) {
                                if (cont.isActive) cont.resume(si)
                            }
                        }
                    )
                }
            }
        }
}
