package com.bitchat.android.cloudflare

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Retain plaintext locally until the recipient's encrypted receipt, not a relay socket write. */
internal class PrivateRelayOutbox(
    private val scope: CoroutineScope,
    private val canSend: (String) -> Boolean,
    private val send: (String, String, String, String) -> Unit,
    private val onExpired: (String) -> Unit,
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    private data class Pending(
        val content: String,
        val peerID: String,
        val nickname: String,
        val createdAt: Long,
        var lastSentAt: Long? = null
    )

    private val pending = linkedMapOf<String, Pending>()
    private var retryJob: Job? = null

    @Synchronized
    fun enqueue(content: String, peerID: String, nickname: String, messageID: String) {
        if (messageID in pending) return
        if (pending.size >= 100) { onExpired(messageID); return }
        pending[messageID] = Pending(content, peerID, nickname, now())
        tick()
        if (pending.isNotEmpty() && retryJob?.isActive != true) {
            retryJob = scope.launch {
                while (isActive) {
                    delay(10_000)
                    tick()
                    synchronized(this@PrivateRelayOutbox) {
                        if (pending.isEmpty()) { retryJob = null; return@launch }
                    }
                }
            }
        }
    }

    @Synchronized
    private fun tick() {
        val time = now()
        // Snapshot allows a synchronous test transport to acknowledge during send.
        pending.toList().forEach { (id, entry) ->
            if (pending[id] !== entry) return@forEach
            if (time - entry.createdAt >= 300_000) {
                pending.remove(id)
                onExpired(id)
            } else if (canSend(entry.peerID) &&
                (entry.lastSentAt == null || time - requireNotNull(entry.lastSentAt) >= 10_000)) {
                entry.lastSentAt = time
                // Re-encrypt with the current Noise session on each attempt. Never replay a nonce.
                send(entry.content, entry.peerID, entry.nickname, id)
            }
        }
    }

    @Synchronized
    fun acknowledge(messageID: String, peerID: String) {
        if (pending[messageID]?.peerID != peerID) return
        pending.remove(messageID)
        if (pending.isEmpty()) { retryJob?.cancel(); retryJob = null }
    }

    @Synchronized
    fun clear() {
        retryJob?.cancel(); retryJob = null
        pending.clear()
    }
}
