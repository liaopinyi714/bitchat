package com.bitchat.android.cloudflare

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.testing.InMemoryKeyStoreApplication
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import okhttp3.OkHttpClient
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
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = InMemoryKeyStoreApplication::class)
class CloudflareAnnouncementsTest {
    private val scope = TestScope()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val crypto = mock<EncryptionService>()
    private val client = mock<OkHttpClient>()
    private val peer = "1122334455667788"
    private val self = "0011223344556677"
    private val writes = mutableListOf<JSONObject>()
    private lateinit var socket: WebSocket
    private lateinit var listener: WebSocketListener
    private lateinit var service: CloudflareMeshService

    @Before fun setup() {
        whenever(crypto.getStaticPublicKey()).thenReturn(ByteArray(32))
        whenever(crypto.getSigningPublicKey()).thenReturn(ByteArray(32))
        whenever(crypto.signData(any())).thenReturn(ByteArray(64))
        whenever(crypto.createRelayIdentityProof(any(), any())).thenReturn(ByteArray(32))
        whenever(client.newWebSocket(any(), any())).thenAnswer { call ->
            listener = call.getArgument(1)
            socket = mock()
            whenever(socket.send(any<String>())).thenAnswer { write ->
                writes.add(JSONObject(write.getArgument<String>(0)))
                true
            }
            socket
        }
        service = CloudflareMeshService(context, self, scope, client, crypto) { false }
        service.startServices()
        listener.onMessage(socket, JSONObject().put("type", "challenge").put("protocol", 1)
            .put("kind", "mail").put("scope", self).put("nonce", "synthetic-nonce")
            .put("key", "0".repeat(64)).toString())
        listener.onMessage(socket, "{\"type\":\"ready\",\"protocol\":1}")
        scope.runCurrent()
        writes.clear()
        service.updatePeerInfo(peer, "recipient", ByteArray(32) { 1 }, ByteArray(32) { 2 }, true)
    }

    @After fun cleanup() {
        if (::service.isInitialized) service.stopServices()
        scope.cancel()
        TopicPayload.clear()
        TopicRelayState.clear()
        AppStateStore.clear()
    }

    private fun announceNickname(): String {
        val frame = writes.single { it.optString("type") == "packet" }
        assertEquals(peer, frame.getString("target"))
        val packet = requireNotNull(BitchatPacket.fromBinaryData(Base64.getDecoder().decode(frame.getString("packet"))))
        assertEquals(MessageType.ANNOUNCE.value, packet.type)
        assertEquals(self, TopicPayload.hex(packet.senderID))
        assertNotNull(packet.signature)
        return requireNotNull(IdentityAnnouncement.decode(packet.payload)).nickname
    }

    @Test fun `renaming announces to a known private peer without a shared room`() {
        DataManager(context).saveNickname("before")
        service.sendBroadcastAnnounce()
        scope.runCurrent()
        assertEquals("before", announceNickname())
        writes.clear()

        DataManager(context).saveNickname("after")
        service.sendBroadcastAnnounce()
        scope.runCurrent()
        assertEquals("after", announceNickname())
    }

    @Test fun `stopped service does not emit private announcements`() {
        service.stopServices()
        service.sendBroadcastAnnounce()
        scope.runCurrent()
        assertTrue(writes.isEmpty())
    }
}
