package com.bitchat.android.cloudflare

import java.util.concurrent.ConcurrentHashMap

/** Transport presence is separate from cached identity/session information. */
internal class RelayPeerPresence(
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    private data class Routes(val rooms: Set<String> = emptySet(), val mailboxSeenAt: Long? = null)
    private val peers = ConcurrentHashMap<String, Routes>()

    @Synchronized fun record(peer: String, channel: String?) {
        val routes = peers[peer] ?: Routes()
        if (channel == null) peers[peer] = routes.copy(mailboxSeenAt = now())
        else if (channel !in routes.rooms) peers[peer] = routes.copy(rooms = routes.rooms + channel)
    }

    // Immutable values allow lock-free reads from UI/state callbacks while removal notifies
    // MeshCore. This avoids a presence-monitor/application-state lock inversion.
    fun sharesReadyRoom(peer: String, ready: (String) -> Boolean): Boolean =
        peers[peer]?.rooms?.any(ready) == true

    fun hasRecentMailboxTraffic(peer: String): Boolean = peers[peer]?.let(::hasRecentMail) == true

    // Removal and its peer cleanup callback share the same monitor as record(). A new room
    // observation can never be erased by an old room's read-empty-then-remove sequence.
    @Synchronized fun removeTopic(channel: String, onUnreachable: (String) -> Unit) {
        peers.keys.toList().forEach { removePeerFromTopic(it, channel, onUnreachable) }
    }

    @Synchronized fun removePeerFromTopic(peer: String, channel: String, onUnreachable: (String) -> Unit) {
        val existing = peers[peer] ?: return
        val routes = existing.copy(rooms = existing.rooms - channel)
        peers[peer] = routes
        if (routes.rooms.isEmpty() && !hasRecentMail(routes)) {
            peers.remove(peer)
            onUnreachable(peer)
        }
    }

    @Synchronized fun expireMailboxRoutes(onUnreachable: (String) -> Unit) {
        peers.keys.toList().forEach { peer ->
            val routes = peers[peer] ?: return@forEach
            if (!hasRecentMail(routes)) {
                peers[peer] = routes.copy(mailboxSeenAt = null)
                if (routes.rooms.isEmpty()) {
                    peers.remove(peer)
                    onUnreachable(peer)
                }
            }
        }
    }

    @Synchronized fun clear() = peers.clear()

    private fun hasRecentMail(routes: Routes): Boolean = routes.mailboxSeenAt?.let {
        now() - it < MAILBOX_PRESENCE_TTL_MS
    } == true

    private companion object { const val MAILBOX_PRESENCE_TTL_MS = 180_000L }
}
