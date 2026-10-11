package com.bitchat.android.cloudflare

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.noise.AuthenticatedNoiseSession
import com.bitchat.android.noise.NoiseDecryptionResult
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.bitchat.android.testing.InMemoryKeyStoreApplication
import kotlinx.coroutines.Dispatchers
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
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = InMemoryKeyStoreApplication::class)
class CloudflarePrivateDeliveryTest {
    private val scope = TestScope()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val crypto = mock<EncryptionService>()
    private val client = mock<OkHttpClient>()
    private val peer = "1122334455667788"
    private val self = "0011223344556677"
    private val receipt = CountDownLatch(1)
    private lateinit var repository: ConversationRepository
    private lateinit var service: CloudflareMeshService
    private lateinit var socket: WebSocket
    private lateinit var listener: WebSocketListener
    @Volatile private var savedBeforeReceipt = false
    @Volatile private var directedReceipt = false

    @Before fun setup() {
        context.deleteDatabase("relay-private-test.db")
        AppStateStore.clear()
        AppStateStore.resumePrivateConversationsAfterPanic()
        repository = ConversationRepository(context, Dispatchers.Unconfined,
            "relay-private-test.db", InMemoryConversationStorageCipher())
        AppStateStore.setConversationRepositoryForTest(repository)
        whenever(crypto.getStaticPublicKey()).thenReturn(ByteArray(32))
        whenever(crypto.getSigningPublicKey()).thenReturn(ByteArray(32))
        whenever(crypto.signData(any())).thenReturn(ByteArray(64))
        whenever(crypto.createRelayIdentityProof(any(), any())).thenReturn(ByteArray(32))
        // Test-only Noise boundary: existing Noise vectors cover cryptography separately.
        whenever(crypto.decryptWithSession(any(), eq(peer))).thenAnswer { call ->
            NoiseDecryptionResult(call.getArgument(0),
                AuthenticatedNoiseSession(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
        }
        whenever(crypto.encrypt(any(), eq(peer))).thenAnswer { it.getArgument<ByteArray>(0) }
        whenever(client.newWebSocket(any(), any())).thenAnswer { call ->
            listener = call.getArgument(1)
            socket = mock()
            whenever(socket.send(any<String>())).thenAnswer { write ->
                val frame = JSONObject(write.getArgument<String>(0))
                if (frame.optString("type") == "packet") {
                    val packet = requireNotNull(BitchatPacket.fromBinaryData(
                        Base64.getDecoder().decode(frame.getString("packet"))))
                    if (packet.type == MessageType.NOISE_ENCRYPTED.value &&
                        NoisePayload.decode(packet.payload)?.type == NoisePayloadType.DELIVERED) {
                        savedBeforeReceipt = AppStateStore.privateMessages.value.values.flatten()
                            .any { it.id == "synthetic-private" && it.content == "hello via relay" }
                        directedReceipt = frame.optString("target") == peer
                        receipt.countDown()
                    }
                }
                true
            }
            socket
        }
        // No room, Bluetooth, Wi-Fi, or local mirror is involved in this path.
        service = CloudflareMeshService(context, self, scope, client, crypto) { false }
        ContactDirectory.initialize(context) { service }
        service.startServices()
        listener.onMessage(socket, JSONObject().put("type", "challenge").put("protocol", 1)
            .put("kind", "mail").put("scope", self).put("nonce", "synthetic-nonce")
            .put("key", "0".repeat(64)).toString())
        listener.onMessage(socket, "{\"type\":\"ready\",\"protocol\":1}")
        scope.runCurrent()
    }

    @After fun cleanup() {
        service.stopServices(); scope.cancel()
        ContactDirectory.initialize(context) { null }
        AppStateStore.setConversationRepositoryForTest(null)
        AppStateStore.clear()
        repository.closeForTest()
        context.deleteDatabase("relay-private-test.db")
    }

    private fun receiveText() {
        val payload = NoisePayload(NoisePayloadType.PRIVATE_MESSAGE,
            requireNotNull(PrivateMessagePacket("synthetic-private", "hello via relay").encode())).encode()
        val packet = BitchatPacket(version = 1u, type = MessageType.NOISE_ENCRYPTED.value,
            senderID = TopicPayload.unhex(peer), recipientID = TopicPayload.unhex(self),
            timestamp = System.currentTimeMillis().toULong(), payload = payload, ttl = 7u)
        val bytes = requireNotNull(packet.toBinaryData())
        listener.onMessage(socket, JSONObject().put("type", "packet").put("peer", peer)
            .put("id", TopicPayload.hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .put("packet", Base64.getEncoder().encodeToString(bytes)).toString())
        assertTrue(
            "Await the actual receipt; store state=${AppStateStore.conversationStoreState.value::class.simpleName}",
            receipt.await(30, TimeUnit.SECONDS)
        )
        assertTrue(savedBeforeReceipt)
        assertTrue(directedReceipt)
    }

    @Test fun `mailbox private text reaches durable state and the attached UI before receipt`() {
        val delegate = mock<MeshDelegate>()
        service.delegate = delegate
        receiveText()
        verify(delegate).didReceiveMessage(argThat { content == "hello via relay" && isPrivate })
    }

    @Test fun `mailbox private text persists when the UI delegate is detached`() {
        service.delegate = null
        receiveText()
        assertEquals(1, AppStateStore.privateMessages.value.values.flatten().size)
    }
}
