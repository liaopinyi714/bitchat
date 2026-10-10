package com.bitchat.android.cloudflare

import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** One hibernatable room/mail connection with bounded writes and reconnect backoff. */
class RelayConnection(
    private val endpoint: String,
    private val kind: String,
    private val scopeId: String,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val crypto: EncryptionService,
    private val peerID: String,
    private val onPacket: (BitchatPacket, String, String) -> Unit,
    private val onReady: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onProtection: ((String?, String?) -> String?)? = null,
    private val onPeerLeft: (String) -> Unit = {},
    private val onProtected: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile var ready: Boolean = false
        private set
    private var retry: Job? = null
    private var authenticationTimeout: Job? = null
    private var attempts = 0
    @Volatile private var generation = 0L

    @Synchronized fun start() { if (running.compareAndSet(false, true)) connect() }
    @Synchronized fun stop() {
        running.set(false); generation++; retry?.cancel(); retry = null
        authenticationTimeout?.cancel(); authenticationTimeout = null
        ready = false; socket?.cancel(); socket = null
    }

    @Synchronized private fun connect() {
        if (!running.get()) return
        val current = ++generation
        ready = false
        onStatus("connecting")
        val request = Request.Builder().url("${endpoint.trimEnd('/')}/v1/ws/$kind/$scopeId").build()
        var challenged = false
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(this@RelayConnection) {
                    if (current != generation || !running.get()) return
                    try {
                        require(text.toByteArray(Charsets.UTF_8).size <= 98_304)
                        val frame = JSONObject(text)
                        when (frame.getString("type")) {
                            "challenge" -> {
                                require(!challenged && !ready)
                                require(frame.getInt("protocol") == 1 && frame.getString("scope") == scopeId && frame.getString("kind") == kind)
                                val owner = optionalKey(frame, "owner")
                                val commitment = optionalKey(frame, "commitment")
                                val rejection = onProtection?.invoke(owner, commitment)
                                if (rejection != null) { stop(); onStatus(rejection); return }
                                val noise = TopicPayload.hex(requireNotNull(crypto.getStaticPublicKey()))
                                val signing = TopicPayload.hex(requireNotNull(crypto.getSigningPublicKey()))
                                val key = frame.getString("key")
                                require(key.matches(Regex("[a-f0-9]{64}")))
                                require(frame.getString("nonce").length in 1..128)
                                val bytes = listOf("bitchat-relay-v1", kind, scopeId, frame.getString("nonce"), peerID, noise, signing, key)
                                    .joinToString("\n").toByteArray(Charsets.UTF_8)
                                val auth = JSONObject().put("type", "auth").put("peer", peerID).put("noise", noise).put("signing", signing)
                                    .put("signature", TopicPayload.hex(requireNotNull(crypto.signData(bytes))))
                                    .put("proof", TopicPayload.hex(crypto.createRelayIdentityProof(TopicPayload.unhex(key), bytes)))
                                require(webSocket.send(auth.toString()))
                                challenged = true
                            }
                            "ready" -> {
                                require(challenged && !ready && frame.getInt("protocol") == 1)
                                val owner = optionalKey(frame, "owner")
                                val commitment = optionalKey(frame, "commitment")
                                require(kind != "room" || owner != null)
                                val rejection = onProtection?.invoke(owner, commitment)
                                if (rejection != null) { stop(); onStatus(rejection); return }
                                authenticationTimeout?.cancel(); authenticationTimeout = null
                                ready = true; attempts = 0; onStatus("connected"); onReady()
                            }
                            "packet" -> {
                                require(ready)
                                val bytes = android.util.Base64.decode(frame.getString("packet"), android.util.Base64.NO_WRAP)
                                require(bytes.size <= 65_536)
                                val id = frame.optString("id")
                                require(id.isEmpty() || id == TopicPayload.hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                                val packet = requireNotNull(BitchatPacket.fromBinaryData(bytes))
                                require(id.isNotEmpty() || packet.type == 1.toUByte())
                                val sender = frame.getString("peer")
                                require(sender.matches(Regex("[a-f0-9]{16}")) && TopicPayload.hex(packet.senderID) == sender)
                                onPacket(packet, sender, "$kind:$scopeId:$current")
                                // Relay acknowledgement means received by this client transport;
                                // UI delivery still requires the original encrypted mesh receipt.
                                if (kind == "mail" && id.isNotEmpty()) webSocket.send(JSONObject().put("type", "received").put("id", id).toString())
                            }
                            "protected" -> {
                                require(ready && kind == "room")
                                onProtected(requireNotNull(optionalKey(frame, "commitment")))
                            }
                            "error" -> {
                                val code = frame.optString("code", "connection_error")
                                if (code == "password_changed") { ready = false; onStatus(code) }
                                else onStatus(code)
                            }
                            "peer_left" -> {
                                require(ready && kind == "room")
                                val peer = frame.getString("peer")
                                require(peer.matches(Regex("[a-f0-9]{16}")))
                                onPeerLeft(peer)
                            }
                        }
                    } catch (_: Exception) { ready = false; onStatus("protocol_error"); webSocket.cancel(); reconnect(current) }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { reconnect(current) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                reconnect(current)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { reconnect(current) }
        })
        authenticationTimeout = scope.launch {
            delay(30_000)
            synchronized(this@RelayConnection) {
                if (current == generation && running.get() && !ready) {
                    socket?.cancel()
                    reconnect(current)
                }
            }
        }
    }

    private fun optionalKey(frame: JSONObject, name: String): String? {
        if (!frame.has(name) || frame.isNull(name)) return null
        return frame.getString(name).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    }

    @Synchronized private fun reconnect(current: Long) {
        if (!running.get() || current != generation || retry?.isActive == true) return
        authenticationTimeout?.cancel(); authenticationTimeout = null
        socket = null
        ready = false; onStatus("disconnected")
        val waitMs = (1000L shl attempts.coerceAtMost(6)).coerceAtMost(60_000L)
        attempts++
        retry = scope.launch {
            delay(waitMs + kotlin.random.Random.nextLong(0, 500))
            synchronized(this@RelayConnection) {
                if (current != generation || !running.get()) return@launch
                retry = null
                connect()
            }
        }
    }

    @Synchronized fun send(packet: BitchatPacket, target: String? = null, historical: Boolean = false): Boolean {
        val webSocket = socket ?: return false
        if (!ready || webSocket.queueSize() > 1_048_576) return false
        val bytes = packet.toBinaryData(padding = false) ?: return false
        if (bytes.size > 65_536) return false
        val id = TopicPayload.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val frame = JSONObject().put("type", "packet").put("id", id)
            .put("packet", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        if (target != null) frame.put("target", target)
        if (historical) frame.put("historical", true)
        return webSocket.send(frame.toString())
    }

    @Synchronized fun protect(commitment: String): Boolean = ready && socket?.send(JSONObject().put("type", "protect").put("commitment", commitment).toString()) == true
}
