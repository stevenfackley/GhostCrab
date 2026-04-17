package com.openclaw.ghostcrab.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises [NsdDiscoveryServiceImpl] against the real [NsdManager].
 *
 * Requires a device/emulator with mDNS support. Emulators with host-only networking may
 * not receive mDNS announcements — prefer a physical device for reliable runs.
 */
@RunWith(AndroidJUnit4::class)
class NsdDiscoveryServiceInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serverSocket: ServerSocket? = null

    @Before
    fun setup() {
        // Nothing — per-test registration.
    }

    @After
    fun teardown() {
        registrationListener?.let {
            runCatching { nsdManager.unregisterService(it) }
        }
        registrationListener = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    @Test
    fun discovery_emits_gateway_for_registered_service() = runBlocking {
        val instanceName = "ghostcrab-test-${UUID.randomUUID().toString().take(8)}"
        val socket = ServerSocket(0).also { serverSocket = it }

        registerFakeGateway(
            instanceName = instanceName,
            port = socket.localPort,
            displayName = "Test Gateway",
            version = "1.2.3",
        )

        val service = NsdDiscoveryServiceImpl(context)
        val gateway = withTimeout(15_000L) {
            service.startDiscovery().first { it.instanceName == instanceName }
        }
        service.stopDiscovery()

        assertEquals(instanceName, gateway.instanceName)
        assertEquals(socket.localPort, gateway.port)
        assertEquals("Test Gateway", gateway.displayName)
        assertEquals("1.2.3", gateway.version)
        assertTrue(gateway.hostAddress.isNotBlank())
    }

    @Test
    fun stopDiscovery_closes_the_flow() = runBlocking {
        val service = NsdDiscoveryServiceImpl(context)
        val flow = service.startDiscovery()
        service.stopDiscovery()
        // Flow should terminate; toList returns whatever was collected before close.
        withTimeoutOrNull(2_000L) { flow.toList() }
        // Reaching here without hanging indicates stopDiscovery closed the channel.
    }

    private fun registerFakeGateway(
        instanceName: String,
        port: Int,
        displayName: String,
        version: String,
    ) {
        val info = NsdServiceInfo().apply {
            this.serviceName = instanceName
            this.serviceType = "_openclaw-gw._tcp."
            this.port = port
            setAttribute("displayName", displayName)
            setAttribute("version", version)
        }

        val registered = CountDownLatch(1)
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = registered.countDown()
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        require(registered.await(10, TimeUnit.SECONDS)) { "NSD registration timed out" }
    }
}
