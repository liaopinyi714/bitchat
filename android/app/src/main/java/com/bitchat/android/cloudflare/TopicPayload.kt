package com.bitchat.android.cloudflare

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned topic wrapper shared by offline packets and the online transport. */
object TopicPayload {
    private val magic = "BCTOPIC1".toByteArray(Charsets.US_ASCII)
    private const val MAX_NAME_BYTES = 255
    private const val ITERATIONS = 600_000
    private val keys = ConcurrentHashMap<String, SecretKeySpec>()
    private val commitments = ConcurrentHashMap<String, String>()
    private val protectedTopics = ConcurrentHashMap.newKeySet<String>()
    val joinedTopics = ConcurrentHashMap.newKeySet<String>()
    data class Decoded(val channel: String, val content: ByteArray)
    @Volatile var currentChannel: String? = null

    /** Routing hint only; decode() authenticates encrypted content before presentation. */
    fun peekChannel(bytes: ByteArray): String? = try {
        require(isTopic(bytes))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.position(magic.size)
        val length = buffer.short.toInt() and 0xffff
        require(length in 2..MAX_NAME_BYTES && buffer.remaining() > length)
        val name = ByteArray(length).also(buffer::get)
        normalize(String(name, Charsets.UTF_8)).also { require(name.contentEquals(it.toByteArray(Charsets.UTF_8))) }
    } catch (_: Exception) { null }

    fun normalize(name: String): String {
        val value = Normalizer.normalize(name.trim().let { if (it.startsWith("#")) it else "#$it" }, Normalizer.Form.NFC)
        require(value.length > 1 && value.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES)
        require(value.none { it.isISOControl() || it.isWhitespace() })
        return value
    }

    fun isTopic(bytes: ByteArray): Boolean = bytes.size >= magic.size && bytes.copyOfRange(0, magic.size).contentEquals(magic)

    fun derive(password: String, channel: String): SecretKeySpec {
        require(password.isNotEmpty() && password.length <= 1024)
        val salt = "bitchat/topic-key/v1\n${normalize(channel)}".toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    fun commitment(key: SecretKeySpec): String = hex(MessageDigest.getInstance("SHA-256").digest(key.encoded))
    fun expectedCommitment(channel: String): String? = commitments[normalize(channel)]
    fun hasKey(channel: String): Boolean = keys.containsKey(normalize(channel))
    fun requirePassword(channel: String) { protectedTopics.add(normalize(channel)) }

    fun setKey(channel: String, key: SecretKeySpec): Boolean {
        val name = normalize(channel)
        val value = commitment(key)
        if (commitments[name]?.let { it != value } == true) return false
        keys[name] = key
        protectedTopics.add(name)
        commitments[name] = value
        return true
    }

    fun replaceKey(channel: String, key: SecretKeySpec) {
        val name = normalize(channel)
        keys[name] = key
        protectedTopics.add(name)
        commitments[name] = commitment(key)
    }

    fun requireCommitment(channel: String, value: String) {
        require(value.matches(Regex("[a-f0-9]{64}")))
        val name = normalize(channel)
        protectedTopics.add(name)
        commitments[name] = value
        if (keys[name]?.let { commitment(it) != value } == true) keys.remove(name)
    }

    fun roomId(channel: String): String = hex(MessageDigest.getInstance("SHA-256")
        .digest("bitchat/room/v1\n${normalize(channel)}".toByteArray(Charsets.UTF_8)))

    fun encode(channel: String, bytes: ByteArray): ByteArray {
        val name = normalize(channel)
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val key = keys[name]
        require(!protectedTopics.contains(name) || key != null) { "Channel password required" }
        val header = ByteBuffer.allocate(magic.size + 2 + nameBytes.size + 1 + if (key != null) 32 else 0)
            .order(ByteOrder.BIG_ENDIAN).put(magic).putShort(nameBytes.size.toShort()).put(nameBytes).put((if (key != null) 1 else 0).toByte())
        if (key != null) header.put(unhex(commitment(key)))
        val aad = header.array()
        if (key == null) return aad + bytes
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        require(cipher.iv.size == 12)
        cipher.updateAAD(aad)
        return aad + cipher.iv + cipher.doFinal(bytes)
    }

    fun decode(bytes: ByteArray): Decoded? {
        if (!isTopic(bytes)) return null
        return try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            buffer.position(magic.size)
            val length = buffer.short.toInt() and 0xffff
            require(length in 2..MAX_NAME_BYTES && buffer.remaining() > length)
            val nameBytes = ByteArray(length).also(buffer::get)
            val channel = normalize(String(nameBytes, Charsets.UTF_8))
            require(nameBytes.contentEquals(channel.toByteArray(Charsets.UTF_8)))
            val mode = buffer.get().toInt()
            if (mode == 0) {
                require(!protectedTopics.contains(channel)) { "Protected channel received plaintext" }
                Decoded(channel, ByteArray(buffer.remaining()).also(buffer::get))
            } else {
                require(mode == 1 && buffer.remaining() >= 32 + 12 + 16)
                val declared = hex(ByteArray(32).also(buffer::get))
                val key = keys[channel] ?: return null
                require(commitment(key) == declared && commitments[channel] == declared)
                val aad = bytes.copyOfRange(0, buffer.position())
                val iv = ByteArray(12).also(buffer::get)
                val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                cipher.updateAAD(aad)
                Decoded(channel, cipher.doFinal(encrypted))
            }
        } catch (_: Exception) { null }
    }

    fun forget(channel: String) {
        val name = normalize(channel)
        keys.remove(name); commitments.remove(name); protectedTopics.remove(name); joinedTopics.remove(name)
    }
    fun clear() { keys.clear(); commitments.clear(); protectedTopics.clear(); joinedTopics.clear(); currentChannel = null }
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    fun unhex(value: String): ByteArray {
        require(value.length % 2 == 0 && value.matches(Regex("[a-f0-9]+")))
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
