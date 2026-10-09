package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random

@RunWith(RobolectricTestRunner::class)
class FragmentingPacketSenderTest {

    private val senderID = "1122334455667788"

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling a mirrored transfer stops remaining fragments by its original id`() = runTest {
        val sender = FragmentingPacketSender(backgroundScope, null, "test", interFragmentDelayMs = 20L)
        val packet = packetWithPayload(32).copy(timestamp = 1_000uL)
        val writes = mutableListOf<RoutedPacket>()
        val routed = RoutedPacket(packet, transferId = "synthetic-mirror-transfer",
            preparedPackets = List(3) { packet }, reportTransferProgress = false)
        assertTrue(sender.send(routed, "mirrored send") { writes.add(it); true })
        runCurrent()
        assertEquals(1, writes.size)
        assertEquals("synthetic-mirror-transfer", writes.single().transferId)
        assertTrue(sender.cancelTransfer("synthetic-mirror-transfer"))
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(1, writes.size)
        assertFalse(sender.cancelTransfer("synthetic-mirror-transfer"))
    }

    private fun packetWithPayload(bytes: Int): BitchatPacket {
        val payload = ByteArray(bytes)
        Random(42).nextBytes(payload)
        return BitchatPacket(
            version = 2u,
            type = MessageType.FILE_TRANSFER.value,
            senderID = MeshPacketUtils.hexStringToByteArray(senderID),
            recipientID = null,
            timestamp = System.currentTimeMillis().toULong(),
            payload = payload,
            signature = null,
            ttl = 7u
        )
    }

    @Test
    fun `oversized packet exceeding receiver fragment cap is rejected with fail event`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val sender = FragmentingPacketSender(scope, FragmentManager(), "test")
        // ~256 * 469 bytes fit; 1 MiB clearly exceeds MAX_FRAGMENTS_PER_ID
        val packet = packetWithPayload(1024 * 1024)
        var sent = false

        val failed = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val collectJob = launch(Dispatchers.Default) {
            TransferProgressManager.events.collect { event ->
                if (event.failed) failed.add(event.transferId)
            }
        }
        kotlinx.coroutines.delay(100) // activate subscription before emitting

        val accepted = sender.send(RoutedPacket(packet, transferId = "oversize-test"), "test") { sent = true; true }
        assertFalse(accepted)
        assertFalse(sent)
        withTimeout(5_000) {
            while (!failed.contains("oversize-test")) {
                kotlinx.coroutines.delay(10)
            }
        }
        collectJob.cancel()
        Unit
    }

    @Test
    fun `packet within fragment cap is accepted`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val sender = FragmentingPacketSender(scope, FragmentManager(), "test", interFragmentDelayMs = 0L)
        val packet = packetWithPayload(10_000)
        var writes = 0

        val accepted = sender.send(RoutedPacket(packet, transferId = "fits-test"), "test") { writes += 1; true }
        assertTrue(accepted)
        withTimeout(5_000) {
            while (writes == 0) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertTrue(writes > 0)
    }

    @Test
    fun `fragment count at cap boundary is not rejected`() {
        val manager = FragmentManager()
        val packet = packetWithPayload(AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID * 400)
        val fragments = manager.createFragments(packet, AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID)
        assertTrue(fragments.isNotEmpty())
        assertTrue(fragments.size <= AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID)
    }
}
