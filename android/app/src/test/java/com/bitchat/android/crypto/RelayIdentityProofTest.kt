package com.bitchat.android.crypto

import org.junit.Assert.*
import org.junit.Test

class RelayIdentityProofTest {
    private fun unhex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val privateKey = unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    private val publicKey = unhex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")

    @Test fun proofMatchesRfc7748DhAndIndependentHmacVector() {
        val proof = RelayIdentityProof.create(privateKey, publicKey, "synthetic-relay-challenge".toByteArray())
        assertArrayEquals(unhex("1e89938adc960c8d3318394ce337ba3c9373fadcfa6d9aa46cc238c3b02471ba"), proof)
        assertFalse(proof.contentEquals(RelayIdentityProof.create(privateKey, publicKey, "another-challenge".toByteArray())))
    }

    @Test fun invalidAndLowOrderKeysFailClosed() {
        assertThrows(Exception::class.java) { RelayIdentityProof.create(privateKey, ByteArray(32), byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { RelayIdentityProof.create(privateKey, publicKey.copyOf(31), byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { RelayIdentityProof.create(privateKey, publicKey, ByteArray(5000)) }
    }
}
