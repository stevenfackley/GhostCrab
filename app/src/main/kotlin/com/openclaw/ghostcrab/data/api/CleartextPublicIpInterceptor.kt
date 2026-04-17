package com.openclaw.ghostcrab.data.api

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * OkHttp interceptor that blocks cleartext HTTP requests to public (non-LAN) IP addresses
 * unless the user has explicitly opted in via [isAllowed].
 *
 * Only numeric IPv4/IPv6 literals are evaluated — hostname-based URLs are always allowed
 * through (DNS-resolved addresses aren't checked here to avoid double-resolution latency).
 *
 * Private ranges allowed by default: RFC-1918 (10/8, 172.16/12, 192.168/16),
 * link-local (169.254/16), and loopback (127/8).
 */
internal class CleartextPublicIpInterceptor(
    private val isAllowed: () -> Boolean,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.scheme == "http" && !isAllowed() && isPublicIpLiteral(request.url.host)) {
            throw IOException(
                "Cleartext HTTP to public IP ${request.url.host} is blocked. " +
                    "Enable it in Settings → Security or use HTTPS."
            )
        }
        return chain.proceed(request)
    }

    companion object {
        /**
         * Returns `true` if [host] is a numeric IPv4/IPv6 literal pointing to a public address.
         * Hostnames and private/loopback IPs return `false`.
         */
        internal fun isPublicIpLiteral(host: String): Boolean {
            if (!host.matches(IPV4_REGEX) && !host.contains(":")) return false
            val rawIp = if (host.startsWith("[") && host.endsWith("]")) {
                host.drop(1).dropLast(1)
            } else {
                host
            }
            val addr = parseIpLiteral(rawIp)
            return addr != null &&
                !addr.isLoopbackAddress &&
                !addr.isSiteLocalAddress &&
                !addr.isLinkLocalAddress
        }

        // For IPv4 literals: parse the 4 octets directly into a byte array — no DNS possible.
        // For IPv6 literals: getByName does not resolve DNS for numeric inputs, but
        // IPv6 public-IP blocking is best-effort (ULA/link-local heuristics are imprecise).
        private fun parseIpLiteral(rawIp: String): java.net.InetAddress? = runCatching {
            if (rawIp.matches(IPV4_REGEX)) {
                val bytes = ByteArray(4)
                rawIp.split(".").forEachIndexed { i, part ->
                    val v = part.toInt()
                    require(v in 0..255)
                    bytes[i] = v.toByte()
                }
                java.net.InetAddress.getByAddress(bytes)
            } else {
                java.net.InetAddress.getByName(rawIp)
            }
        }.getOrNull()

        private val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}
