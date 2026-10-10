package com.bitchat.android.cloudflare

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class TopicPayloadTest {
    @Before fun prepare() = TopicPayload.clear()
    @After fun cleanup() = TopicPayload.clear()

    @Test fun topicNamesAreCanonicalAndBounded() {
        assertEquals("#café", TopicPayload.normalize(" cafe\u0301 "))
        assertEquals("#Test", TopicPayload.normalize("#Test"))
        assertThrows(IllegalArgumentException::class.java) { TopicPayload.normalize("two words") }
        assertThrows(IllegalArgumentException::class.java) { TopicPayload.normalize("#") }
        assertThrows(IllegalArgumentException::class.java) { TopicPayload.normalize("界".repeat(100)) }
    }

    @Test fun roomAndPasswordDerivationMatchIndependentVectors() {
        assertEquals("3acb1ac4cc9a14d1e7aa4838ba4e6a042ee3c068b65b6dcfe21fb023e0b0de49", TopicPayload.roomId("#test"))
        val key = TopicPayload.derive("synthetic-password", "#test")
        assertEquals("5b801ece9eac7446d1cf0c8e2cfd20b9fea250479208f2fa3f1daf964008c0b1", TopicPayload.hex(key.encoded))
        assertEquals("577b6d3ebf1ddce17d59f08e51b41f7982c37a0d6dd8b251516fd44c7cbf25d6", TopicPayload.commitment(key))
    }

    @Test fun publicEnvelopeMatchesGoldenWireBytes() {
        val bytes = TopicPayload.encode("#test", "hello".toByteArray())
        assertEquals("4243544f50494331000523746573740068656c6c6f", TopicPayload.hex(bytes))
        assertEquals("#test", TopicPayload.decode(bytes)?.channel)
        assertEquals("hello", TopicPayload.decode(bytes)?.content?.toString(Charsets.UTF_8))
        assertNull(TopicPayload.decode(bytes.copyOf(9)))
    }

    @Test fun protectedEnvelopeRejectsTamperingAndPlaintextDowngrade() {
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val public = TopicPayload.encode("#test", "hello".toByteArray())
        assertTrue(TopicPayload.setKey("#test", key))
        val encrypted = TopicPayload.encode("#test", "hello".toByteArray())
        assertEquals("hello", TopicPayload.decode(encrypted)?.content?.toString(Charsets.UTF_8))
        val changed = encrypted.copyOf(); changed[changed.lastIndex] = (changed.last().toInt() xor 1).toByte()
        assertNull(TopicPayload.decode(changed))
        assertNull(TopicPayload.decode(public))
        val changedName = encrypted.copyOf(); changedName[11] = 'x'.code.toByte()
        assertNull(TopicPayload.decode(changedName))
    }

    @Test fun rememberedProtectionBlocksSendingBeforeAKeyIsAvailable() {
        TopicPayload.requirePassword("#test")
        assertThrows(IllegalArgumentException::class.java) { TopicPayload.encode("#test", byteArrayOf(1)) }
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        TopicPayload.requireCommitment("#test", TopicPayload.commitment(key))
        assertFalse(TopicPayload.setKey("#test", SecretKeySpec(ByteArray(32) { 8 }, "AES")))
        assertTrue(TopicPayload.setKey("#test", key))
        val encrypted = TopicPayload.encode("#test", byteArrayOf(1))
        TopicPayload.requireCommitment("#test", "a".repeat(64))
        assertFalse(TopicPayload.hasKey("#test"))
        assertNull(TopicPayload.decode(encrypted))
    }

    @Test fun pendingProtectionOnlyReplacesTheKeyAfterAnExactAcknowledgement() {
        val oldKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val newKey = SecretKeySpec(ByteArray(32) { 8 }, "AES")
        TopicPayload.setKey("#test", oldKey)
        val oldPacket = TopicPayload.encode("#test", byteArrayOf(1))
        TopicPayload.stageProtection("#test", newKey)
        assertEquals(TopicPayload.commitment(oldKey), TopicPayload.expectedCommitment("#test"))
        assertNotNull(TopicPayload.decode(oldPacket))
        assertFalse(TopicPayload.confirmProtection("#test", "a".repeat(64)))
        assertTrue(TopicPayload.confirmProtection("#test", TopicPayload.commitment(newKey)))
        assertNull(TopicPayload.pendingCommitment("#test"))
        assertNull(TopicPayload.decode(oldPacket))
        assertNotNull(TopicPayload.decode(TopicPayload.encode("#test", byteArrayOf(2))))
    }

    @Test fun panicAndLeaveForgetPendingProtection() {
        val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        TopicPayload.stageProtection("#test", key)
        TopicPayload.forget("#test")
        assertFalse(TopicPayload.confirmProtection("#test", TopicPayload.commitment(key)))
        TopicPayload.stageProtection("#test", key)
        TopicPayload.clear()
        assertFalse(TopicPayload.confirmProtection("#test", TopicPayload.commitment(key)))
    }
}
