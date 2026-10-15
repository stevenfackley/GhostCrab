package com.openclaw.ghostcrab.testutil

/** RFC 8032 §7.1 TEST 1 secret key (Ed25519 seed). Public key `d75a9801…511a`. */
val RFC8032_SEED: ByteArray = hexToBytes("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")

/** hex(sha256(RFC 8032 TEST 1 public key)): the gateway device id of [RFC8032_SEED]. */
const val RFC8032_DEVICE_ID = "21fe31dfa154a261626bf854046fd2271b7bed4b6abe45aa58877ef47f9721b9"

/** The three operator scopes GhostCrab requests. */
val OPERATOR_SCOPES = listOf("operator.read", "operator.write", "operator.admin")

/**
 * @param hex An even-length hex string.
 * @return Its bytes.
 */
fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
