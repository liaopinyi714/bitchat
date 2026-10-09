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

/** One hibernatable room/mail connection, with bounded buffering and reconnect backoff. */
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
    private val onProtection: ((String?, String?) -> Boolean)? = null,
    private val onPeerLeft: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    @Volatile private var socket: WebSocket? = null
    @Volatile var ready: Boolean = false
        private set
    private var retry: Job? = null
    private var attempts = 0
    private var generation = 0L

    fun start() { if (running.compareAndSet(false, true)) connect() }
    fun stop() {
        running.set(false); generation++; retry?.cancel(); retry = null
        ready = false; socket?.close(1000, "closed"); socket = null
    }

    private fun connect() {
        if (!running.get()) return
        val current = ++generation
        onStatus("connecting")
        val request = Request.Builder().url("${endpoint.trimEnd('/')}/v1/ws/$kind/$scopeId").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                if (current != generation || !running.get()) return
                try {
                    require(text.toByteArray(Charsets.UTF_8).size <= 98_304)
                    val frame = JSONObject(text)
                    when (frame.getString("type")) {
                        "challenge" -> {
                            require(frame.getInt("protocol") == 1 && frame.getString("scope") == scopeId && frame.getString("kind") == kind)
                            val owner = frame.optString("owner").takeIf { it.isNotEmpty() }
                            val commitment = frame.optString("commitment").takeIf { it.isNotEmpty() }
                            if (onProtection?.invoke(owner, commitment) == false) {
                                running.set(false); onStatus("password_required"); ws.close(1000, "password_required"); return
                            }
                            val noise = TopicPayload.hex(requireNotNull(crypto.getStaticPublicKey()))
                            val signing = TopicPayload.hex(requireNotNull(crypto.getSigningPublicKey()))
                            val key = frame.getString("key")
                            val bytes = listOf("bitchat-relay-v1", kind, scopeId, frame.getString("nonce"), peerID, noise, signing, key)
                                .joinToString("\n").toByteArray(Charsets.UTF_8)
                            val auth = JSONObject().put("type", "auth").put("peer", peerID).put("noise", noise).put("signing", signing)
                                .put("signature", TopicPayload.hex(requireNotNull(crypto.signData(bytes))))
                                .put("proof", TopicPayload.hex(crypto.createRelayIdentityProof(TopicPayload.unhex(key), bytes)))
                            ws.send(auth.toString())
                        }
                        "ready" -> {
                            val owner = frame.optString("owner").takeIf { it.isNotEmpty() }
                            val commitment = frame.optString("commitment").takeIf { it.isNotEmpty() }
                            if (onProtection?.invoke(owner, commitment) == false) {
                                running.set(false); onStatus("password_required"); ws.close(1000, "password_required"); return
                            }
                            ready = true; attempts = 0; onStatus("connected"); onReady()
                        }
                        "packet" -> {
                            require(ready)
                            val bytes = android.util.Base64.decode(frame.getString("packet"), android.util.Base64.NO_WRAP)
                            require(bytes.size <= 65_536)
                            val id = frame.optString("id")
                            require(id.isEmpty() || id == TopicPayload.hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                            val packet = requireNotNull(BitchatPacket.fromBinaryData(bytes))
                            val sender = frame.getString("peer")
                            require(sender.matches(Regex("[a-f0-9]{16}")) && TopicPayload.hex(packet.senderID) == sender)
                            onPacket(packet, sender, "$kind:$scopeId:$current")
                            // Relay acknowledgement means received by this client transport;
                            // UI delivery still requires the original encrypted mesh receipt.
                            if (kind == "mail" && id.isNotEmpty()) ws.send(JSONObject().put("type", "received").put("id", id).toString())
                        }
                        "error" -> onStatus(frame.optString("code", "connection_error"))
                        "peer_left" -> onPeerLeft(frame.getString("peer"))
                    }
                } catch (_: Exception) { onStatus("protocol_error"); ws.close(1008, "protocol_error") }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { reconnect(current) }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, reason) }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { reconnect(current) }
        })
    }

    @Synchronized private fun reconnect(current: Long) {
        if (!running.get() || current != generation || retry?.isActive == true) return
        ready = false; onStatus("disconnected")
        val waitMs = (1000L shl attempts.coerceAtMost(6)).coerceAtMost(60_000L)
        attempts++
        retry = scope.launch { delay(waitMs + kotlin.random.Random.nextLong(0, 500)); retry = null; connect() }
    }

    fun send(packet: BitchatPacket, target: String? = null, historical: Boolean = false): Boolean {
        val ws = socket ?: return false
        if (!ready || ws.queueSize() > 1_048_576) return false
        val bytes = packet.toBinaryData(padding = false) ?: return false
        if (bytes.size > 65_536) return false
        val id = TopicPayload.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val frame = JSONObject().put("type", "packet").put("id", id)
            .put("packet", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
        if (target != null) frame.put("target", target)
        if (historical) frame.put("historical", true)
        return ws.send(frame.toString())
    }

    fun protect(commitment: String): Boolean = ready && socket?.send(JSONObject().put("type", "protect").put("commitment", commitment).toString()) == true
}
