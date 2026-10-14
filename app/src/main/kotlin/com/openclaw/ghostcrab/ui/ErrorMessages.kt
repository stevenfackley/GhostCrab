package com.openclaw.ghostcrab.ui

/**
 * Renders any failure as explicit, brand-compliant text: what happened (domain messages already
 * carry the URL and HTTP status), the exception class, and the root cause. Never "Oops".
 *
 * Examples:
 * - `Gateway API error at http://10.0.0.5:18789/config/gateway: HTTP 412 (GatewayApiException)`
 * - `Gateway unreachable at http://10.0.0.5:18789 (GatewayUnreachableException ← ConnectException: refused)`
 *
 * @receiver The failure to describe.
 * @return A single-line, user-visible message.
 */
fun Throwable.toUserMessage(): String {
    val headline = message?.takeIf { it.isNotBlank() } ?: "Unexpected failure"
    val root = generateSequence(this) { current -> current.cause?.takeIf { it !== current } }.last()
    val rootPart = if (root === this) {
        ""
    } else {
        " ← ${root::class.java.simpleName}" + (root.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
    }
    return "$headline (${this::class.java.simpleName}$rootPart)"
}
