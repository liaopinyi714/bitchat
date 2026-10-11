package com.bitchat.android.cloudflare

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.mesh.MeshCore
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.mesh.PrivateMediaPreparation
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.noise.AuthenticatedNoiseSession
import com.bitchat.android.noise.NoiseDecryptionResult
import com.bitchat.android.noise.NoisePeerIdentity
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.testing.InMemoryKeyStoreApplication
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = InMemoryKeyStoreApplication::class)
class MeshCoreLifecycleTest {
    private val scope = TestScope()
    private val crypto = mock<EncryptionService>()
    private val self = "0011223344556677"
    private val peerKey = ByteArray(32) { 1 }
    private val peer = requireNotNull(NoisePeerIdentity.derivePeerID(peerKey))
    private val sent = mutableListOf<BitchatPacket>()
    private val received = mutableListOf<String>()
    private var starts = 0
    private lateinit var core: MeshCore

    private fun createCore(
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler),
        directedAnnouncements: Boolean = false
    ) {
        whenever(crypto.signData(any())).thenReturn(ByteArray(64))
        whenever(crypto.verifyEd25519Signature(any(), any(), any())).thenReturn(true)
        whenever(crypto.getEstablishedPeers()).thenReturn(emptyList())
        whenever(crypto.hasEstablishedSession(peer)).thenReturn(true)
        whenever(crypto.encrypt(any(), eq(peer))).thenAnswer { it.getArgument<ByteArray>(0) }
        core = MeshCore(ApplicationProvider.getApplicationContext<Application>(), scope,
            object : MeshTransport {
                override val id = "synthetic-lifecycle"
                override fun broadcastPacket(routed: RoutedPacket): Boolean { sent += routed.packet; return true }
                override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean { sent += packet; return true }
            }, crypto, self, 7u, null,
            object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity() = 100
                override fun gcsMaxBytes() = 400
                override fun gcsTargetFpr() = 0.01
            }, hooks = MeshCore.Hooks(onMessageReceived = { received += it.id; true }, onStarted = { starts++ }),
            bridgeToOtherTransports = false, enablePeriodicGossip = false,
            directedAnnouncements = directedAnnouncements, packetDispatcher = dispatcher)
        core.startCore()
    }

    @After fun cleanup() {
        if (::core.isInitialized) core.shutdown()
        scope.cancel()
        AppStateStore.clear()
    }

    private fun announce() = BitchatPacket(version = 1u, type = MessageType.ANNOUNCE.value,
        senderID = TopicPayload.unhex(peer), recipientID = null, timestamp = System.currentTimeMillis().toULong(),
        payload = requireNotNull(IdentityAnnouncement("synthetic recipient", peerKey, ByteArray(32) { 2 }).encode()),
        signature = ByteArray(64), ttl = 7u)

    private fun privatePacket() = BitchatPacket(version = 1u, type = MessageType.NOISE_ENCRYPTED.value,
        senderID = TopicPayload.unhex(peer), recipientID = TopicPayload.unhex(self),
        timestamp = System.currentTimeMillis().toULong(), payload = byteArrayOf(1), ttl = 7u)

    @Test fun `stop and restart discard old queued announcements and accept fresh ones`() {
        createCore()
        assertTrue(core.processIncoming(announce(), peer, "synthetic-link"))
        core.stopCore()
        core.clearAllInternalData()
        core.startCore()
        scope.runCurrent()
        assertNull(core.getPeerInfo(peer))

        assertTrue(core.processIncoming(announce(), peer, "synthetic-link"))
        scope.runCurrent()
        assertNotNull(core.getPeerInfo(peer))
    }

    @Test fun `stop and restart discard queued private sends but allow new sends`() {
        createCore()
        core.sendPrivateMessage("old synthetic text", peer, "recipient", "old-id")
        core.stopCore()
        core.startCore()
        scope.runCurrent()
        assertTrue(sent.isEmpty())

        core.sendPrivateMessage("new synthetic text", peer, "recipient", "new-id")
        scope.runCurrent()
        val payloads = sent.mapNotNull { NoisePayload.decode(it.payload) }
            .filter { it.type == NoisePayloadType.PRIVATE_MESSAGE }
            .mapNotNull { PrivateMessagePacket.decode(it.data)?.messageID }
        assertEquals(listOf("new-id"), payloads)
    }

    @Test fun `replacement lifecycle announces after it becomes ready to dispatch`() {
        createCore()
        scope.runCurrent()
        whenever(crypto.getStaticPublicKey()).thenReturn(ByteArray(32))
        whenever(crypto.getSigningPublicKey()).thenReturn(ByteArray(32))
        core.stopCore()
        core.startCore()
        scope.runCurrent()
        assertEquals(2, starts)
        assertEquals(1, sent.count { it.type == MessageType.ANNOUNCE.value })
    }

    @Test fun `prepared private transfer from an old lifecycle cannot commit after restart`() {
        createCore()
        val session = AuthenticatedNoiseSession(peerKey, ByteArray(32) { 2 })
        whenever(crypto.getAuthenticatedSession(peer)).thenReturn(session)
        whenever(crypto.withAuthenticatedSession(eq(peer), eq(session), any())).thenAnswer {
            it.getArgument<() -> Boolean>(2).invoke()
        }
        val file = BitchatFilePacket("synthetic.bin", 3, "application/octet-stream", byteArrayOf(1, 2, 3))
        fun prepare(id: String) = core.prepareFilePrivate(peer, file, id, allowLegacyFallback = true)
        assertEquals(PrivateMediaPreparation.AwaitingPeerState, prepare("awaiting"))
        scope.runCurrent()
        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        val old = prepare("old-transfer") as PrivateMediaPreparation.Ready

        core.stopCore()
        core.startCore()
        scope.runCurrent()
        sent.clear()
        assertFalse(old.transfer.commit())
        assertTrue(sent.isEmpty())

        val fresh = prepare("new-transfer") as PrivateMediaPreparation.Ready
        assertTrue(fresh.transfer.commit())
        assertEquals(1, sent.size)
    }

    @Test fun `stopped core rejects bridge and directed announcements without marking identity as sent`() {
        createCore(directedAnnouncements = true)
        scope.runCurrent()
        whenever(crypto.getStaticPublicKey()).thenReturn(ByteArray(32))
        whenever(crypto.getSigningPublicKey()).thenReturn(ByteArray(32))
        core.stopCore()
        core.sendAnnouncementToPeer(peer)
        core.sendFromBridge(RoutedPacket(announce()))
        assertFalse(core.sendFromBridgeAndReport(RoutedPacket(announce())))
        assertTrue(sent.isEmpty())

        core.startCore()
        scope.runCurrent()
        sent.clear()
        core.sendAnnouncementToPeer(peer)
        assertEquals(1, sent.size)
        assertEquals(MessageType.ANNOUNCE.value, sent.single().type)
    }

    @Test fun `in flight old announcement cannot restore a peer after stop and restart`() {
        assertBlockedPacketDiscarded(announcement = true)
    }

    @Test fun `in flight old private decrypt cannot enter the replacement lifecycle`() {
        assertBlockedPacketDiscarded(announcement = false)
    }

    private fun assertBlockedPacketDiscarded(announcement: Boolean) {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            createCore(dispatcher)
            if (announcement) {
                whenever(crypto.verifyEd25519Signature(any(), any(), any())).thenAnswer {
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); true
                }
            } else {
                whenever(crypto.decryptWithSession(any(), eq(peer))).thenAnswer {
                    entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                    NoiseDecryptionResult(NoisePayload(NoisePayloadType.PRIVATE_MESSAGE,
                        requireNotNull(PrivateMessagePacket("old-id", "synthetic text").encode())).encode(),
                        AuthenticatedNoiseSession(peerKey, ByteArray(32) { 2 }))
                }
            }
            val packet = if (announcement) announce() else privatePacket()
            assertTrue(core.processIncoming(packet, peer, "synthetic-link"))
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            core.stopCore()
            core.clearAllInternalData()
            core.startCore()
            assertEquals(1, starts)
            assertFalse(core.processIncoming(packet, peer, "replacement-link"))

            release.countDown()
            executor.submit {}.get(10, TimeUnit.SECONDS)
            scope.runCurrent()
            assertEquals(2, starts)
            assertNull(core.getPeerInfo(peer))
            assertTrue(received.isEmpty())
            assertTrue(sent.isEmpty())
            assertTrue(core.processIncoming(announce(), peer, "replacement-link"))
        } finally {
            release.countDown()
            if (::core.isInitialized) core.shutdown()
            dispatcher.close()
        }
    }
}
