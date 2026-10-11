package com.bitchat.android.cloudflare

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateRelayOutboxTest {
    private val scope = TestScope()
    private var connected = true
    private val attempts = mutableListOf<Pair<String, String>>()
    private val failures = mutableListOf<String>()
    private val outbox = PrivateRelayOutbox(scope, { connected },
        { content, _, _, id -> attempts += id to content }, failures::add,
        { scope.testScheduler.currentTime })

    @After fun cleanup() { outbox.clear(); scope.cancel() }

    private fun enqueue() = outbox.enqueue("synthetic text", "recipient", "name", "message-1")
    private fun advance(ms: Long) { scope.runCurrent(); scope.advanceTimeBy(ms); scope.runCurrent() }

    @Test fun `unconfirmed message is retried with its original id and stops after recipient receipt`() {
        enqueue()
        assertEquals(1, attempts.size)
        advance(10_000)
        assertEquals(listOf("message-1", "message-1"), attempts.map { it.first })
        outbox.acknowledge("message-1", "recipient")
        advance(60_000)
        assertEquals(2, attempts.size)
        assertTrue(failures.isEmpty())
    }

    @Test fun `another authenticated peer cannot acknowledge this recipient's message`() {
        enqueue()
        outbox.acknowledge("message-1", "another-peer")
        advance(10_000)
        assertEquals(2, attempts.size)
    }

    @Test fun `mobile connection loss retains the message and reconnect retries it`() {
        enqueue()
        connected = false
        advance(30_000)
        assertEquals(1, attempts.size)
        connected = true
        advance(10_000)
        assertEquals(2, attempts.size)
    }

    @Test fun `unconfirmed message expires instead of being shown delivered`() {
        connected = false
        enqueue()
        advance(300_000)
        assertEquals(listOf("message-1"), failures)
        assertTrue(attempts.isEmpty())
        connected = true
        advance(10_000)
        assertTrue(attempts.isEmpty())
    }

    @Test fun `stopping clears private retry state and never revives old sends`() {
        enqueue()
        outbox.clear()
        advance(300_000)
        assertEquals(1, attempts.size)
        assertTrue(failures.isEmpty())
    }

    @Test fun `receipt on another thread can complete during a send callback`() {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task).apply { isDaemon = true } }
        var acknowledgedDuringSend = false
        lateinit var concurrent: PrivateRelayOutbox
        concurrent = PrivateRelayOutbox(scope, { true }, { _, peer, _, id ->
            executor.submit { concurrent.acknowledge(id, peer) }.get(5, TimeUnit.SECONDS)
            acknowledgedDuringSend = true
        }, failures::add, { scope.testScheduler.currentTime })
        try {
            concurrent.enqueue("synthetic text", "recipient", "name", "message-1")
            assertTrue(acknowledgedDuringSend)
            advance(300_000)
            assertTrue(failures.isEmpty())
        } finally {
            executor.shutdownNow()
            concurrent.clear()
        }
    }

    @Test fun `one failing recipient does not prevent later recipients or retries`() {
        val sent = mutableListOf<String>()
        val retrying = PrivateRelayOutbox(scope, { true }, { _, peer, _, id ->
            if (peer == "unavailable") throw IllegalStateException("synthetic send failure")
            sent += id
        }, failures::add, { scope.testScheduler.currentTime })
        try {
            retrying.enqueue("synthetic text", "unavailable", "name", "message-1")
            retrying.enqueue("synthetic text", "recipient", "name", "message-2")
            advance(10_000)
            assertEquals(listOf("message-2", "message-2"), sent)
        } finally { retrying.clear() }
    }
}
