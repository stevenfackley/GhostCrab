package com.openclaw.ghostcrab.ui

private val WHITESPACE_RUN = Regex("\\s+")

/**
 * Renders any failure as explicit, brand-compliant text: what happened (domain messages already
 * carry the URL and HTTP status), the exception class, and the root cause. Never "Oops".
 *
 * The root cause's message is appended only when it adds something: a blank message, or one the
 * headline already contains, is left out (its class is still named). Runs of whitespace, newlines
 * included, collapse to one space so the result is always a single line.
 *
 * Examples:
 * - `Gateway API error at http://10.0.0.5:18789/config/gateway: HTTP 412 (GatewayApiException)`
 * - `Gateway unreachable at http://10.0.0.5:18789 (GatewayUnreachableException ← ConnectException: refused)`
 *
 * @receiver The failure to describe.
 * @return A single-line, user-visible message.
 */
fun Throwable.toUserMessage(): String {
    val headline = message?.singleLine()?.takeIf { it.isNotBlank() } ?: "Unexpected failure"
    val root = generateSequence(this) { current -> current.cause?.takeIf { it !== current } }.last()
    val rootPart = if (root === this) {
        ""
    } else {
        val rootMessage = root.message?.singleLine()?.takeIf { it.isNotBlank() && it !in headline }
        " ← ${root::class.java.simpleName}" + (rootMessage?.let { ": $it" } ?: "")
    }
    return "$headline (${this::class.java.simpleName}$rootPart)"
}

private fun String.singleLine(): String = replace(WHITESPACE_RUN, " ").trim()
