package com.openclaw.ghostcrab.domain.util

import java.net.URI
import java.net.URISyntaxException

/** Default OpenClaw gateway port. */
const val DEFAULT_GATEWAY_PORT: Int = 18789

private const val HTTPS_DEFAULT_PORT = 443
private const val MAX_PORT = 65535

/**
 * A validated gateway base URL: `http`/`https`, a host, an explicit port. Path, query and fragment
 * of the input are ignored; userinfo is rejected (it would smuggle credentials into the URL).
 *
 * @property scheme `http` or `https`.
 * @property host Hostname or IP literal; IPv6 literals are stored **without** brackets.
 * @property port TCP port, 1–65535.
 */
data class GatewayUrl(val scheme: String, val host: String, val port: Int) {

    init {
        require(scheme == "http" || scheme == "https") { "scheme must be http or https" }
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..MAX_PORT) { "port must be in 1..$MAX_PORT" }
    }

    /** Canonical form, bracketing IPv6 literals: `http://[fe80::1]:18789`. */
    override fun toString(): String {
        val hostPart = if (host.contains(':')) "[$host]" else host
        return "$scheme://$hostPart:$port"
    }

    companion object {
        /**
         * Parses [raw]. A missing port defaults to [DEFAULT_GATEWAY_PORT] for `http` and 443 for `https`
         * (tunnels and reverse proxies).
         *
         * @param raw User-, QR- or deep-link-supplied URL text.
         * @return The parsed URL (IPv6 zone ids stripped), or `null` if [raw] is not an `http(s)` URL with a
         *   host, no userinfo and a port in 1–65535.
         */
        fun parse(raw: String): GatewayUrl? {
            val uri = try {
                URI(raw.trim())
            } catch (_: URISyntaxException) {
                return null
            }
            val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" }
            val host = uri.host?.takeIf { uri.userInfo == null }
                ?.removePrefix("[")?.removeSuffix("]")?.substringBefore('%')
                ?.takeIf { it.isNotEmpty() }
            val port = if (uri.port == -1) {
                if (scheme == "https") HTTPS_DEFAULT_PORT else DEFAULT_GATEWAY_PORT
            } else {
                uri.port
            }
            return if (scheme != null && host != null && port in 1..MAX_PORT) {
                GatewayUrl(scheme, host, port)
            } else {
                null
            }
        }

        /**
         * Builds a URL for a resolved `(host, port)` pair, e.g. from mDNS. Strips an IPv6 zone id
         * (`%wlan0`), which is not valid in a URL authority.
         *
         * @param host Resolved host address.
         * @param port Resolved port.
         * @param https Whether to use `https`.
         * @return The URL.
         */
        fun of(host: String, port: Int, https: Boolean = false): GatewayUrl =
            GatewayUrl(if (https) "https" else "http", host.substringBefore('%'), port)
    }
}
