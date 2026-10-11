package com.bitchat.android.cloudflare

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.testing.InMemoryKeyStoreApplication
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = InMemoryKeyStoreApplication::class)
class CloudflarePeerPresenceTest {
    private data class Session(val socket: WebSocket, val listener: WebSocketListener)
    private val scope = TestScope()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val crypto = mock<EncryptionService>()
    private val client = mock<OkHttpClient>()
    private val sessions = mutableMapOf<String, Session>()
    private var clock = 0L
    private lateinit var service: CloudflareMeshService
    private val peer = "1122334455667788"
    private val self = "0011223344556677"

    @Before fun setup() {
        whenever(crypto.getStaticPublicKey()).thenReturn(ByteArray(32))
        whenever(crypto.getSigningPublicKey()).thenReturn(ByteArray(32))
        whenever(crypto.signData(any())).thenReturn(ByteArray(64))
        whenever(crypto.createRelayIdentityProof(any(), any())).thenReturn(ByteArray(32))
        whenever(client.newWebSocket(any(), any())).thenAnswer { call ->
            val socket = mock<WebSocket>()
            whenever(socket.send(any<String>())).thenReturn(true)
            val path = call.getArgument<Request>(0).url.encodedPath.removePrefix("/v1/ws/")
            sessions[path] = Session(socket, call.getArgument(1))
            socket
        }
        service = CloudflareMeshService(context, self, scope, client, crypto, presenceClock = { clock }) { false }
        service.startServices()
        ready("mail", self)
        service.updatePeerInfo(peer, "synthetic recipient", ByteArray(32) { 1 }, ByteArray(32) { 2 }, true)
    }

    @After fun cleanup() {
        service.stopServices()
        scope.cancel()
        TopicPayload.clear()
        TopicRelayState.clear()
        AppStateStore.clear()
    }

    private fun ready(kind: String, id: String) {
        val session = requireNotNull(sessions["$kind/$id"])
        session.listener.onMessage(session.socket, JSONObject().put("type", "challenge").put("protocol", 1)
            .put("kind", kind).put("scope", id).put("nonce", "synthetic-nonce")
            .put("key", "0".repeat(64)).put("owner", "0".repeat(64)).toString())
        session.listener.onMessage(session.socket, JSONObject().put("type", "ready").put("protocol", 1)
            .put("owner", "0".repeat(64)).toString())
    }

    private fun join(channel: String) { service.join(channel); ready("room", TopicPayload.roomId(channel)) }

    private fun observe(channel: String?) {
        val session = requireNotNull(sessions[if (channel == null) "mail/$self" else "room/${TopicPayload.roomId(channel)}"])
        // The peer identity is already cached. These synthetic relay-authenticated envelopes
        // exercise transport presence; signature and Noise validation have their own vectors.
        val payload = requireNotNull(IdentityAnnouncement("synthetic recipient", ByteArray(32) { 1 }, ByteArray(32) { 2 }).encode())
        val packet = BitchatPacket(version = 1u, type = MessageType.ANNOUNCE.value,
            senderID = TopicPayload.unhex(peer), recipientID = null, timestamp = 1uL,
            payload = payload, signature = ByteArray(64), ttl = 7u)
        val bytes = requireNotNull(packet.toBinaryData(padding = false))
        session.listener.onMessage(session.socket, JSONObject().put("type", "packet").put("peer", peer)
            .put("id", TopicPayload.hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .put("packet", Base64.getEncoder().encodeToString(bytes)).toString())
    }

    @Test fun `cached identity alone is not online presence`() {
        assertNotNull(service.getPeerInfo(peer))
        assertTrue(service.canQueuePrivateMessage(peer))
        assertFalse(service.isOnline(peer))
    }

    @Test fun `private mailbox peer survives leaving its final shared channel`() {
        join("#first")
        observe("#first")
        observe(null)
        service.leave("#first")

        assertTrue(service.isOnline(peer))
        assertNotNull(service.getPeerInfo(peer))
        verify(crypto, never()).removePeer(peer)
        val mailbox = requireNotNull(sessions["mail/$self"])
        mailbox.listener.onFailure(mailbox.socket, java.io.IOException("synthetic disconnect"), null)
        assertFalse(service.isOnline(peer))
        assertNotNull(service.getPeerInfo(peer))
    }

    @Test fun `leaving one channel preserves a peer still present in another`() {
        join("#first")
        join("#second")
        observe("#first")
        observe("#second")
        service.leave("#first")
        assertTrue(service.isOnline(peer))
        service.leave("#second")
        assertFalse(service.isOnline(peer))
        assertNull(service.getPeerInfo(peer))
    }

    @Test fun `expired private-only peer remains routable for a fresh handshake without fake presence`() {
        observe(null)
        clock = 180_000L
        scope.runCurrent()
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertNull(service.getPeerInfo(peer))
        assertFalse(service.isOnline(peer))
        assertTrue(service.canQueuePrivateMessage(peer))
        whenever(crypto.initiateHandshake(peer)).thenReturn(byteArrayOf(1))

        service.sendPrivateMessage("synthetic reconnect", peer, "recipient", "retry-after-expiry")
        scope.runCurrent()

        verify(crypto).initiateHandshake(peer)
        val writes = argumentCaptor<String>()
        verify(requireNotNull(sessions["mail/$self"]).socket, atLeastOnce()).send(writes.capture())
        assertTrue(writes.allValues.map(::JSONObject).any { frame ->
            frame.optString("type") == "packet" && frame.optString("target") == peer &&
                BitchatPacket.fromBinaryData(Base64.getDecoder().decode(frame.getString("packet")))?.type == MessageType.NOISE_HANDSHAKE.value
        })
    }

    @Test fun `panic clearing discards private routing targets before restart`() {
        observe(null)
        service.clearAllInternalData()
        service.startServices()
        scope.runCurrent()
        assertFalse(service.canQueuePrivateMessage(peer))
        assertFalse(service.isOnline(peer))
    }
}
