package com.openclaw.ghostcrab.domain.util

/**
 * Generates a cryptographically random 32-byte bearer token encoded as URL-safe Base64.
 *
 * @return A 43-character base64url string (no padding) suitable for use as a bearer token.
 */
fun generateToken(): String {
    val bytes = ByteArray(32)
    java.security.SecureRandom().nextBytes(bytes)
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
