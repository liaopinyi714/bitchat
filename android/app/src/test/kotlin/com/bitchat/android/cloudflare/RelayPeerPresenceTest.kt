package com.bitchat.android.cloudflare

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RelayPeerPresenceTest {
    @Test fun `mailbox survives the final room leave but expires without fresh traffic`() {
        var clock = 0L
        val presence = RelayPeerPresence { clock }
        val removed = mutableListOf<String>()
        presence.record("peer", "#room")
        presence.record("peer", null)
        presence.removeTopic("#room", removed::add)
        assertTrue(presence.hasRecentMailboxTraffic("peer"))
        assertTrue(removed.isEmpty())

        clock = 180_000L
        assertFalse(presence.hasRecentMailboxTraffic("peer"))
        presence.expireMailboxRoutes(removed::add)
        assertEquals(listOf("peer"), removed)
    }

    @Test fun `one room departure never removes another room route`() {
        val presence = RelayPeerPresence { 0L }
        val removed = mutableListOf<String>()
        presence.record("peer", "#first")
        presence.record("peer", "#second")
        presence.removePeerFromTopic("peer", "#first", removed::add)
        assertTrue(presence.sharesReadyRoom("peer") { it == "#second" })
        assertTrue(removed.isEmpty())
        presence.removeTopic("#second", removed::add)
        assertEquals(listOf("peer"), removed)
    }

    @Test fun `concurrent new room arrival and old room departure retain the new route`() {
        val presence = RelayPeerPresence { 0L }
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) { index ->
                val peer = "synthetic-$index"
                val start = CountDownLatch(1)
                presence.record(peer, "#old")
                val arrival = executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    presence.record(peer, "#new")
                }
                val departure = executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    presence.removePeerFromTopic(peer, "#old") {}
                }
                start.countDown()
                arrival.get(10, TimeUnit.SECONDS)
                departure.get(10, TimeUnit.SECONDS)
                assertTrue(presence.sharesReadyRoom(peer) { it == "#new" })
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun `peer cleanup can query presence on another thread without lock inversion`() {
        val presence = RelayPeerPresence { 0L }
        val executor = Executors.newSingleThreadExecutor()
        try {
            presence.record("peer", "#room")
            presence.removeTopic("#room") { peer ->
                assertFalse(executor.submit<Boolean> { presence.hasRecentMailboxTraffic(peer) }.get(5, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow() }
    }
}
