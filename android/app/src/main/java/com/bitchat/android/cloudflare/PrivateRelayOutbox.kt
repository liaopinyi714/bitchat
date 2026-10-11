package com.bitchat.android.cloudflare

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
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
        var lastSentAt: Long? = null,
        var attempting: Boolean = false
    )

    private val pending = linkedMapOf<String, Pending>()
    private var retryJob: Job? = null

    fun enqueue(content: String, peerID: String, nickname: String, messageID: String) {
        val accepted = synchronized(this) {
            if (messageID in pending) return
            if (pending.size >= 100) false
            else { pending[messageID] = Pending(content, peerID, nickname, now()); true }
        }
        if (!accepted) { notifyExpired(messageID); return }
        // No external callback runs under the outbox monitor: a send may cause an immediate
        // receipt on another thread, and expiry updates also acquire application state locks.
        tick()
        val job = synchronized(this) {
            if (pending.isEmpty() || retryJob != null) null
            else scope.launch(start = CoroutineStart.LAZY) {
                while (isActive) {
                    delay(RETRY_INTERVAL_MS)
                    tick()
                    synchronized(this@PrivateRelayOutbox) {
                        if (pending.isEmpty()) { retryJob = null; return@launch }
                    }
                }
            }.also { retryJob = it }
        }
        job?.start()
    }

    private fun tick() {
        val time = now()
        val entries = synchronized(this) { pending.toList() }
        entries.forEach { (id, entry) ->
            var expired = false
            val attempt = synchronized(this) {
                if (pending[id] !== entry || entry.attempting) false
                else if (time - entry.createdAt >= MAX_AGE_MS) {
                    pending.remove(id)
                    expired = true
                    false
                } else if (entry.lastSentAt == null || time - requireNotNull(entry.lastSentAt) >= RETRY_INTERVAL_MS) {
                    entry.attempting = true
                    true
                } else false
            }
            if (expired) notifyExpired(id)
            if (!attempt) return@forEach
            try {
                if (canSend(entry.peerID)) {
                    val stillPending = synchronized(this) {
                        (pending[id] === entry).also { if (it) entry.lastSentAt = time }
                    }
                    // Re-encrypt with the current Noise session on each attempt. Never replay a nonce.
                    if (stillPending) send(entry.content, entry.peerID, entry.nickname, id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A transient failure for one recipient must not cancel every pending retry.
            } finally {
                synchronized(this) { entry.attempting = false }
            }
        }
    }

    private fun notifyExpired(messageID: String) {
        try { onExpired(messageID) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* The failed message is removed even if its UI is unavailable. */ }
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

    private companion object {
        const val RETRY_INTERVAL_MS = 10_000L
        const val MAX_AGE_MS = 300_000L
    }
}
