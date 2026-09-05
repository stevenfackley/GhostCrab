package com.openclaw.ghostcrab.data

import com.openclaw.ghostcrab.data.api.OpenClawApiClient
import io.ktor.http.Url
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The bearer token must only ever be attached to requests bound for the configured gateway origin. */
class OpenClawApiClientOriginTest {

    private val http = Url("http://192.168.1.50:18789")
    private val https = Url("https://gw.example.com")

    @Test
    fun `same host and port is same origin`() {
        assertTrue(OpenClawApiClient.isSameOrigin(http, Url("http://192.168.1.50:18789/api/models/status")))
    }

    @Test
    fun `host comparison is case-insensitive`() {
        assertTrue(OpenClawApiClient.isSameOrigin(https, Url("https://GW.Example.com/config")))
    }

    @Test
    fun `ws and wss count as http and https`() {
        assertTrue(OpenClawApiClient.isSameOrigin(http, Url("ws://192.168.1.50:18789/ws")))
        assertTrue(OpenClawApiClient.isSameOrigin(https, Url("wss://gw.example.com/ws")))
    }

    @Test
    fun `different host is not same origin`() {
        assertFalse(OpenClawApiClient.isSameOrigin(http, Url("http://198.51.100.9:18789/health")))
        assertFalse(OpenClawApiClient.isSameOrigin(https, Url("https://evil.example.com/config")))
    }

    @Test
    fun `different port is not same origin`() {
        assertFalse(OpenClawApiClient.isSameOrigin(http, Url("http://192.168.1.50:18790/health")))
        assertFalse(OpenClawApiClient.isSameOrigin(https, Url("https://gw.example.com:8443/health")))
    }

    @Test
    fun `https origin never sends to http or ws downgrade`() {
        assertFalse(OpenClawApiClient.isSameOrigin(https, Url("http://gw.example.com:443/health")))
        assertFalse(OpenClawApiClient.isSameOrigin(https, Url("ws://gw.example.com:443/ws")))
    }

    @Test
    fun `http origin may upgrade to https on the same host and port`() {
        assertTrue(OpenClawApiClient.isSameOrigin(http, Url("https://192.168.1.50:18789/health")))
    }
}
