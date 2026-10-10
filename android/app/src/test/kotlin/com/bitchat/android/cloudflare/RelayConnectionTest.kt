package com.bitchat.android.cloudflare

import android.app.Application
import com.bitchat.android.crypto.EncryptionService
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RelayConnectionTest {
    private val scope = TestScope()
    private val crypto = mock<EncryptionService>()
    private val client = mock<OkHttpClient>()
    private val sessions = mutableListOf<Pair<WebSocket, WebSocketListener>>()
    private val statuses = mutableListOf<String>()
    private var readyCount = 0
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
            crypto, "0".repeat(16), { _, _, _ -> }, { readyCount++ }, statuses::add)
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
}
