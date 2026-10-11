package com.bitchat.android.cloudflare

import android.app.Application
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
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
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RelayConnectionTest {
    private val scope = TestScope()
    private val crypto = mock<EncryptionService>()
    private val client = mock<OkHttpClient>()
    private val sessions = mutableListOf<Pair<WebSocket, WebSocketListener>>()
    private val statuses = mutableListOf<String>()
    private var readyCount = 0
    private var readyAction: () -> Unit = {}
    private val connection: RelayConnection

    init {
        whenever(crypto.getStaticPublicKey()).thenReturn(ByteArray(32))
        whenever(crypto.getSigningPublicKey()).thenReturn(ByteArray(32))
        whenever(crypto.signData(any())).thenReturn(ByteArray(64))
        whenever(crypto.createRelayIdentityProof(any(), any())).thenReturn(ByteArray(32))
        whenever(client.newWebSocket(any(), any())).thenAnswer { call ->
            val socket = mock<WebSocket>()
            whenever(socket.send(any<String>())).thenReturn(true)
            sessions.add(socket to call.getArgument<WebSocketListener>(1))
            socket
        }
        connection = RelayConnection("wss://relay.test", "room", "a".repeat(64), scope, client,
            crypto, "0".repeat(16), { _, _, _ -> true }, { readyCount++; readyAction() }, statuses::add)
    }

    @After fun cleanup() = connection.stop()

    private fun message(type: String, protocol: Int = 1): String = JSONObject()
        .put("type", type).put("protocol", protocol).put("kind", "room")
        .put("scope", "a".repeat(64)).put("nonce", "synthetic-nonce")
        .put("key", "0".repeat(64)).put("owner", "0".repeat(64)).toString()

    private fun receive(text: String, index: Int = sessions.lastIndex) {
        val (socket, listener) = sessions[index]
        listener.onMessage(socket, text)
    }

    @Test fun `authentication timeout cancels the socket and schedules reconnect`() {
        connection.start()
        scope.runCurrent()
        scope.advanceTimeBy(30001)
        scope.runCurrent()
        verify(sessions.first().first).cancel()
        assertFalse(connection.ready)
        scope.advanceTimeBy(2000)
        scope.runCurrent()
        assertEquals(2, sessions.size)
    }

    @Test fun `ready before a valid challenge is rejected`() {
        connection.start()
        receive(message("ready"))
        assertFalse(connection.ready)
        assertEquals(0, readyCount)
        assertTrue(statuses.contains("protocol_error"))
    }

    @Test fun `ready with another protocol version is rejected`() {
        connection.start()
        receive(message("challenge"))
        receive(message("ready", protocol = 2))
        assertFalse(connection.ready)
        assertEquals(0, readyCount)
    }

    @Test fun `successful authentication cancels the timeout and can set protection`() {
        connection.start()
        scope.runCurrent()
        receive(message("challenge"))
        receive(message("ready"))
        assertTrue(connection.ready)
        assertEquals(1, readyCount)
        verify(client).newWebSocket(argThat { url.encodedPath == "/v1/ws/room/${"a".repeat(64)}" }, any())
        assertTrue(connection.protect("1".repeat(64)))
        scope.advanceTimeBy(35000)
        scope.runCurrent()
        assertEquals(1, sessions.size)
        verify(sessions.first().first, never()).cancel()
    }

    @Test fun `callbacks from a stopped generation cannot revive the connection`() {
        connection.start()
        connection.stop()
        connection.start()
        receive(message("challenge"), index = 0)
        receive(message("ready"), index = 0)
        sessions.first().second.onClosed(sessions.first().first, 1000, "closed")
        assertFalse(connection.ready)
        assertEquals(0, readyCount)
        assertEquals(2, sessions.size)
    }

    @Test fun `malformed owner metadata fails before an authentication response`() {
        connection.start()
        receive(JSONObject(message("challenge")).put("owner", "invalid-owner").toString())
        verify(sessions.first().first, never()).send(any<String>())
        assertTrue(statuses.contains("protocol_error"))
    }

    @Test fun `stopping during backoff discards the old retry generation`() {
        connection.start()
        sessions.first().second.onClosed(sessions.first().first, 1000, "closed")
        scope.runCurrent()
        connection.stop()
        connection.start()
        scope.advanceTimeBy(2000)
        scope.runCurrent()
        assertEquals(2, sessions.size)
        assertFalse(connection.ready)
    }

    @Test fun `late ready from a failed socket cannot revive it during backoff`() {
        connection.start()
        receive(message("challenge"))
        val (socket, listener) = sessions.first()
        listener.onFailure(socket, java.io.IOException("synthetic disconnect"), null)

        receive(message("ready"), index = 0)

        assertFalse(connection.ready)
        assertEquals(0, readyCount)
        scope.advanceTimeBy(2000)
        scope.runCurrent()
        assertEquals(2, sessions.size)
    }

    @Test fun `late challenge from a failed socket cannot send authentication`() {
        connection.start()
        val (socket, listener) = sessions.first()
        listener.onFailure(socket, java.io.IOException("synthetic disconnect"), null)

        receive(message("challenge"), index = 0)

        verify(socket, never()).send(any<String>())
        assertFalse(connection.ready)
    }

    @Test fun `simultaneous room ready callbacks can write to each other without deadlock`() {
        val bothReady = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(4) { task -> Thread(task).apply { isDaemon = true } }
        val other = RelayConnection("wss://relay.test", "room", "a".repeat(64), scope, client,
            crypto, "0".repeat(16), { _, _, _ -> true }, {
                bothReady.countDown()
                check(bothReady.await(5, TimeUnit.SECONDS))
                check(executor.submit<Boolean> { connection.protect("1".repeat(64)) }.get(5, TimeUnit.SECONDS))
            }, {})
        readyAction = {
            bothReady.countDown()
            check(bothReady.await(5, TimeUnit.SECONDS))
            check(executor.submit<Boolean> { other.protect("1".repeat(64)) }.get(5, TimeUnit.SECONDS))
        }
        try {
            connection.start()
            other.start()
            receive(message("challenge"), 0)
            receive(message("challenge"), 1)
            val first = executor.submit { receive(message("ready"), 0) }
            val second = executor.submit { receive(message("ready"), 1) }
            first.get(10, TimeUnit.SECONDS)
            second.get(10, TimeUnit.SECONDS)
            assertTrue(connection.ready)
            assertTrue(other.ready)
        } finally {
            executor.shutdownNow()
            // Neither callback takes another connection's receive monitor.
            other.stop()
        }
    }

    @Test fun `control writes respect the same bounded socket queue as packets`() {
        connection.start()
        receive(message("challenge"))
        receive(message("ready"))
        val socket = sessions.single().first
        clearInvocations(socket)
        whenever(socket.queueSize()).thenReturn(1_048_575)

        assertFalse(connection.protect("1".repeat(64)))
        verify(socket, never()).send(any<String>())
    }

    @Test fun `mailbox acknowledges admitted packets but retains overloaded packets for reconnect`() {
        var accepted = true
        val mail = RelayConnection("wss://relay.test", "mail", "0".repeat(16), scope, client,
            crypto, "0".repeat(16), { _, _, _ -> accepted }, {}, {})
        try {
            mail.start()
            val (socket, listener) = sessions.last()
            listener.onMessage(socket, JSONObject(message("challenge"))
                .put("kind", "mail").put("scope", "0".repeat(16)).toString())
            listener.onMessage(socket, "{\"type\":\"ready\",\"protocol\":1}")
            val packet = BitchatPacket(version = 1u, type = MessageType.ANNOUNCE.value,
                senderID = ByteArray(8) { 1 }, recipientID = null, timestamp = 1uL,
                payload = byteArrayOf(1), ttl = 7u)
            val bytes = requireNotNull(packet.toBinaryData(padding = false))
            val frame = JSONObject().put("type", "packet").put("peer", "01".repeat(8))
                .put("id", TopicPayload.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))
                .put("packet", Base64.getEncoder().encodeToString(bytes)).toString()

            listener.onMessage(socket, frame)
            accepted = false
            listener.onMessage(socket, frame)

            val writes = argumentCaptor<String>()
            verify(socket, atLeastOnce()).send(writes.capture())
            assertEquals(1, writes.allValues.count { JSONObject(it).optString("type") == "received" })
            assertFalse(mail.ready)
            verify(socket).cancel()
        } finally { mail.stop() }
    }
}
