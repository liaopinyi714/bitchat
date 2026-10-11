package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class PacketProcessorQueueTest {
    @Test fun `concurrent producers share one serialized peer consumer`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var maximumActive = 0
        val handled = mutableListOf<Int>()
        val delegate = RecordingDelegate {
            active++
            maximumActive = maxOf(maximumActive, active)
            gate.await()
            handled += it.packet.payload.single().toInt() and 0xff
            active--
            true
        }
        val processor = PacketProcessor(SELF, StandardTestDispatcher(testScheduler)).also { it.delegate = delegate }
        val producers = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 200).map { index -> producers.submit<Boolean> {
                check(start.await(5, TimeUnit.SECONDS))
                processor.processPacket(packet(payload = byteArrayOf(index.toByte())))
            } }
            start.countDown()
            futures.forEach { assertTrue(it.get(10, TimeUnit.SECONDS)) }
            runCurrent()
            assertEquals(1, maximumActive)
            gate.complete(Unit)
            runCurrent()
            assertEquals((0 until 200).toSet(), handled.toSet())
            assertEquals(200, handled.size)
            assertEquals(1, maximumActive)
        } finally { processor.shutdown(); producers.shutdownNow() }
    }

    @Test fun `full peer queue rejects promptly and accepts again after draining`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var handled = 0
        val processor = PacketProcessor(SELF, StandardTestDispatcher(testScheduler)).also {
            it.delegate = RecordingDelegate { gate.await(); handled++; true }
        }
        try {
            assertTrue(processor.processPacket(packet()))
            runCurrent()
            repeat(256) { assertTrue(processor.processPacket(packet())) }
            assertFalse(processor.processPacket(packet()))
            gate.complete(Unit)
            runCurrent()
            assertEquals(257, handled)
            assertTrue(processor.processPacket(packet()))
            runCurrent()
            assertEquals(258, handled)
        } finally { processor.shutdown() }
    }

    @Test fun `aggregate byte budget bounds bursts from multiple peers and is reclaimed`() = runTest {
        val processor = PacketProcessor(SELF, StandardTestDispatcher(testScheduler)).also {
            it.delegate = RecordingDelegate { true }
        }
        try {
            val payload = ByteArray(65_536)
            val accepted = (1..256).count { processor.processPacket(packet(peer(it), payload)) }
            assertTrue(accepted in 1 until 256)
            runCurrent()
            assertTrue(processor.processPacket(packet(peer(300), payload)))
        } finally { processor.shutdown() }
    }

    @Test fun `idle consumers are reclaimed on demand and expire when unused`() = runTest {
        val processor = PacketProcessor(SELF, StandardTestDispatcher(testScheduler)).also {
            it.delegate = RecordingDelegate { true }
        }
        try {
            repeat(256) { assertTrue(processor.processPacket(packet(peer(it + 1)))) }
            assertFalse(processor.processPacket(packet(peer(300))))
            runCurrent()
            assertTrue(processor.processPacket(packet(peer(300))))
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(processor.getDebugInfo().contains("Active Peer Actors: 0"))
            assertTrue(processor.processPacket(packet(peer(301))))
        } finally { processor.shutdown() }
    }

    @Test fun `one handler failure does not lose later packets or their order`() = runTest {
        val received = mutableListOf<Int>()
        val processor = PacketProcessor(SELF, StandardTestDispatcher(testScheduler)).also {
            it.delegate = RecordingDelegate { routed ->
                val id = routed.packet.payload.single().toInt()
                received += id
                if (id == 1) throw IllegalArgumentException("synthetic malformed payload")
                true
            }
        }
        try {
            assertTrue(processor.processPacket(packet(payload = byteArrayOf(1))))
            assertTrue(processor.processPacket(packet(payload = byteArrayOf(2))))
            runCurrent()
            assertEquals(listOf(1, 2), received)
        } finally { processor.shutdown() }
    }

    @Test fun `shutdown discards queued packets and rejects every later producer`() = runTest {
        var handled = 0
        val processor = PacketProcessor(SELF, StandardTestDispatcher(testScheduler)).also {
            it.delegate = RecordingDelegate { handled++; true }
        }
        repeat(10) { assertTrue(processor.processPacket(packet())) }
        processor.shutdown()
        assertFalse(processor.processPacket(packet()))
        runCurrent()
        assertEquals(0, handled)
    }

    private fun packet(peer: String = PEER, payload: ByteArray = byteArrayOf(1)) = RoutedPacket(
        BitchatPacket(version = 1u, type = MessageType.ANNOUNCE.value,
            senderID = peer.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            recipientID = SpecialRecipients.BROADCAST, timestamp = 1u, payload = payload, ttl = 7u),
        peerID = peer, relayAddress = "synthetic-link"
    )

    private fun peer(index: Int) = index.toString(16).padStart(16, '0')

    private class RecordingDelegate(private val announce: suspend (RoutedPacket) -> Boolean) : PacketProcessorDelegate {
        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) = true
        override fun updatePeerLastSeen(peerID: String) = Unit
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize() = 1
        override fun getBroadcastRecipient() = SpecialRecipients.BROADCAST
        override fun handleNoiseHandshake(routed: RoutedPacket) = true
        override fun handleNoiseEncrypted(routed: RoutedPacket) = true
        override suspend fun handleAnnounce(routed: RoutedPacket) = announce(routed)
        override fun handleMessage(routed: RoutedPacket) = Unit
        override fun handleLeave(routed: RoutedPacket) = Unit
        override fun handleFragment(packet: BitchatPacket): BitchatPacket? = null
        override fun handleRequestSync(routed: RoutedPacket) = Unit
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendCachedMessages(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) = Unit
        override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
    }

    private companion object {
        const val SELF = "1111222233334444"
        const val PEER = "aaaabbbbccccdddd"
    }
}
