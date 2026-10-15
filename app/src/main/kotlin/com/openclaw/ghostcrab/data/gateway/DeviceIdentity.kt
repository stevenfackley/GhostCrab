package com.openclaw.ghostcrab.data.gateway

import com.google.crypto.tink.subtle.Ed25519Sign
import java.security.MessageDigest
import java.util.Base64

/**
 * This install's Ed25519 device identity (ADR-0007). [deviceId] is `hex(sha256(raw public key))`, the
 * fingerprint the gateway checks against `device.publicKey`. The private seed leaves this object only through
 * [seedForStorage]; [toString] prints the device id and nothing else.
 */
class DeviceIdentity private constructor(seed: ByteArray, publicKey: ByteArray) {

    private val seed: ByteArray = seed.copyOf()

    /** `hex(sha256(raw public key))`. */
    val deviceId: String = sha256Hex(publicKey)

    /** Raw 32-byte public key, base64url without padding (`connect.params.device.publicKey`). */
    val publicKeyBase64Url: String = base64Url(publicKey)

    /**
     * @param payload The text to sign (the v3 device-auth payload).
     * @return The Ed25519 signature, base64url without padding.
     * @throws java.security.GeneralSecurityException if Tink refuses the key (never for a valid seed).
     */
    fun sign(payload: String): String = base64Url(Ed25519Sign(seed).sign(payload.toByteArray(Charsets.UTF_8)))

    /** @return A copy of the 32-byte seed, for the encrypted store only. Sensitive — never log. */
    internal fun seedForStorage(): ByteArray = seed.copyOf()

    override fun toString(): String = "DeviceIdentity(deviceId=$deviceId)"

    companion object {
        /** Ed25519 seed length. */
        const val SEED_BYTES: Int = 32

        /** @return A fresh random identity. */
        fun generate(): DeviceIdentity {
            val pair = Ed25519Sign.KeyPair.newKeyPair()
            return DeviceIdentity(pair.privateKey, pair.publicKey)
        }

        /**
         * @param seed The 32-byte seed from [seedForStorage].
         * @return The identity for that seed.
         * @throws IllegalArgumentException if [seed] is not [SEED_BYTES] long.
         */
        fun fromSeed(seed: ByteArray): DeviceIdentity {
            require(seed.size == SEED_BYTES) { "Ed25519 seed must be $SEED_BYTES bytes, was ${seed.size}" }
            val pair = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed)
            return DeviceIdentity(pair.privateKey, pair.publicKey)
        }

        /**
         * @param publicKeyBase64Url A raw public key, base64url.
         * @return The gateway device id for it.
         * @throws IllegalArgumentException if [publicKeyBase64Url] is not valid base64url.
         */
        fun deviceIdForPublicKey(publicKeyBase64Url: String): String =
            sha256Hex(Base64.getUrlDecoder().decode(publicKeyBase64Url))
    }
}

/** Supplies this install's device identity (persisted by `DeviceIdentityStore`). */
interface DeviceIdentityProvider {
    /**
     * @return The identity, created and persisted on first use.
     * @throws Exception if the encrypted store cannot be written (the connect then fails with that cause).
     */
    suspend fun identity(): DeviceIdentity
}

/** base64url without padding, as the gateway expects for keys and signatures. */
internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
