package com.bitchat.android.crypto

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A challenge-bound possession proof; the static private key never leaves the device. */
internal object RelayIdentityProof {
    fun create(privateKey: ByteArray, serverPublicKey: ByteArray, challenge: ByteArray): ByteArray {
        require(privateKey.size == 32 && serverPublicKey.size == 32)
        require(challenge.size in 1..4096)
        val secret = ByteArray(32)
        try {
            X25519PrivateKeyParameters(privateKey, 0).generateSecret(X25519PublicKeyParameters(serverPublicKey, 0), secret, 0)
            require(secret.any { it != 0.toByte() })
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret, "HmacSHA256"))
            return mac.doFinal(challenge)
        } finally { secret.fill(0) }
    }
}
