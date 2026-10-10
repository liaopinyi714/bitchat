package com.bitchat.android.testing

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Test-only AES key store for JVM tests that load packaged Android resources.
 * Uses ephemeral synthetic keys, never starts application transports, and makes
 * no claim to emulate hardware-backed key storage or validate Android Keystore.
 */
class InMemoryKeyStoreApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Robolectric uses separate class loaders for SDKs; never reuse their providers.
        Security.removeProvider("AndroidKeyStore")
        InMemoryAndroidKeyStore.keys.clear()
        Security.addProvider(InMemoryAndroidKeyStoreProvider())
    }
}

class InMemoryAndroidKeyStoreProvider : Provider(
    "AndroidKeyStore", 1.0, "Ephemeral JVM test key store"
) {
    init {
        put("KeyStore.AndroidKeyStore", InMemoryAndroidKeyStore::class.java.name)
        put("KeyGenerator.AES", InMemoryAndroidKeyGenerator::class.java.name)
    }
}

class InMemoryAndroidKeyStore : KeyStoreSpi() {
    companion object {
        val keys = ConcurrentHashMap<String, Key>()
    }

    override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
    override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)
    override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)
    override fun engineSize(): Int = keys.size
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)
    override fun engineDeleteEntry(alias: String) { keys.remove(alias) }
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
        keys[alias] = key
    }
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) {
        throw UnsupportedOperationException("Encoded entries are outside this test store")
    }
    override fun engineGetCreationDate(alias: String): Date? = if (keys.containsKey(alias)) Date(0) else null
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCertificateAlias(certificate: Certificate): String? = null
    override fun engineIsCertificateEntry(alias: String): Boolean = false
    override fun engineSetCertificateEntry(alias: String, certificate: Certificate) {
        throw UnsupportedOperationException("Certificates are outside this test store")
    }
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
}

class InMemoryAndroidKeyGenerator : KeyGeneratorSpi() {
    private var parameters: KeyGenParameterSpec? = null
    private var random = SecureRandom()

    override fun engineInit(parameters: AlgorithmParameterSpec, random: SecureRandom?) {
        require(parameters is KeyGenParameterSpec) { "Expected Android key generation parameters" }
        this.parameters = parameters
        if (random != null) this.random = random
    }

    override fun engineInit(random: SecureRandom?) {
        throw UnsupportedOperationException("An alias is required by this test store")
    }

    override fun engineInit(keySize: Int, random: SecureRandom?) {
        throw UnsupportedOperationException("An alias is required by this test store")
    }

    override fun engineGenerateKey(): SecretKey {
        val spec = requireNotNull(parameters)
        val bytes = ByteArray(spec.keySize / 8)
        random.nextBytes(bytes)
        val key = SecretKeySpec(bytes, "AES")
        InMemoryAndroidKeyStore.keys[spec.keystoreAlias] = key
        return key
    }
}
