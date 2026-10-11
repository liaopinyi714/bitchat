package com.bitchat.android.cloudflare

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.FragmentPayload
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
import java.util.Base64
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = InMemoryKeyStoreApplication::class)
class CloudflareFragmentLifecycleTest {
    private data class Session(val socket: WebSocket, val listener: WebSocketListener)
    private val scope = TestScope()
    private val crypto = mock<EncryptionService>()
    private val client = mock<OkHttpClient>()
    private val sessions = mutableMapOf<String, Session>()
    private val writes = mutableListOf<JSONObject>()
    private val self = "0011223344556677"
    private val peer = "1122334455667788"
    private val content = ByteArray(4096).also { Random(42).nextBytes(it) }
    private lateinit var service: CloudflareMeshService

    @Before fun setup() {
        whenever(crypto.getStaticPublicKey()).thenReturn(ByteArray(32))
        whenever(crypto.getSigningPublicKey()).thenReturn(ByteArray(32))
        whenever(crypto.signData(any())).thenReturn(ByteArray(64))
        whenever(crypto.createRelayIdentityProof(any(), any())).thenReturn(ByteArray(32))
        whenever(crypto.getEstablishedPeers()).thenReturn(emptyList())
        whenever(crypto.hasEstablishedSession(peer)).thenReturn(true)
        // Synthetic, incompressible ciphertext keeps the fragmentation scheduler under test.
        whenever(crypto.encrypt(any(), eq(peer))).thenReturn(content)
        whenever(client.newWebSocket(any(), any())).thenAnswer { call ->
            val socket = mock<WebSocket>()
            whenever(socket.send(any<String>())).thenAnswer { write ->
                writes += JSONObject(write.getArgument<String>(0))
                true
            }
            val path = call.getArgument<Request>(0).url.encodedPath.removePrefix("/v1/ws/")
            sessions[path] = Session(socket, call.getArgument(1))
            socket
        }
        service = CloudflareMeshService(ApplicationProvider.getApplicationContext<Application>(),
            self, scope, client, crypto) { false }
        service.startServices()
        ready("mail", self)
        scope.runCurrent()
        writes.clear()
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
            .put("key", "0".repeat(64)).toString())
        session.listener.onMessage(session.socket, JSONObject().put("type", "ready").put("protocol", 1)
            .put("owner", "0".repeat(64)).toString())
    }

    private fun fragments(): List<FragmentPayload> = writes.mapNotNull { frame ->
        if (frame.optString("type") != "packet") return@mapNotNull null
        val packet = BitchatPacket.fromBinaryData(Base64.getDecoder().decode(frame.getString("packet")))
        if (packet?.type == MessageType.FRAGMENT.value) FragmentPayload.decode(packet.payload) else null
    }

    @Test fun `private fragments stop at restart and a new transfer completes`() {
        assertRestartDropsOldFragments(null)
    }

    @Test fun `channel fragments stop at restart and a new transfer completes`() {
        assertRestartDropsOldFragments("#synthetic")
    }

    private fun assertRestartDropsOldFragments(channel: String?) {
        if (channel != null) {
            service.join(channel)
            ready("room", TopicPayload.roomId(channel))
        }
        fun send(id: String) {
            if (channel == null) service.sendPrivateMessage("synthetic text", peer, "recipient", id)
            else service.sendFileToTopic(BitchatFilePacket("synthetic.bin", content.size.toLong(),
                "application/octet-stream", content), channel)
        }
        send("old-transfer")
        scope.runCurrent()
        val oldFragment = fragments().single()
        assertTrue(oldFragment.total > 1)

        service.stopServices()
        service.startServices()
        ready("mail", self)
        if (channel != null) ready("room", TopicPayload.roomId(channel))
        scope.runCurrent()
        scope.advanceTimeBy(200)
        scope.runCurrent()
        assertEquals(1, fragments().size)

        send("new-transfer")
        scope.runCurrent()
        scope.advanceTimeBy(200)
        scope.runCurrent()
        val newFragments = fragments().drop(1)
        assertTrue(newFragments.size > 1)
        assertFalse(oldFragment.fragmentID.contentEquals(newFragments.first().fragmentID))
        assertEquals(newFragments.first().total, newFragments.size)
        assertEquals((newFragments.indices).toList(), newFragments.map { it.index })
        assertTrue(newFragments.all { it.fragmentID.contentEquals(newFragments.first().fragmentID) })
    }
}
