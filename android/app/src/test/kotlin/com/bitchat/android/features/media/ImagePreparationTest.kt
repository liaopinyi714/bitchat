package com.bitchat.android.features.media

import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ImagePreparationTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `cancellation before queued preparation deletes the original without running decoder`() = runTest {
        val worker = QueuedDispatcher()
        val source = files.newFile("source.jpg")
        var prepared = false
        var delivered = false
        val job = ImagePreparation.start(this, worker, source, { { delivered = true } }) {
            prepared = true
            files.newFile("outgoing.jpg").path
        }

        job.cancel()
        worker.runQueued()
        runCurrent()
        worker.runQueued()
        runCurrent()

        assertTrue(job.isCompleted)
        assertFalse(prepared)
        assertFalse(delivered)
        assertFalse(source.exists())
    }

    @Test
    fun `cancellation after worker output before UI return deletes both owned files`() = runTest {
        val worker = QueuedDispatcher()
        val source = files.newFile("source.jpg")
        lateinit var output: File
        var delivered = false
        val job = ImagePreparation.start(this, worker, source, { { delivered = true } }) {
            output = files.newFile("outgoing.jpg")
            output.path
        }
        worker.runQueued()
        assertTrue(output.exists())

        job.cancel()
        runCurrent()
        worker.runQueued()
        runCurrent()

        assertTrue(job.isCompleted)
        assertFalse(delivered)
        assertFalse(source.exists())
        assertFalse(output.exists())
    }

    @Test
    fun `already cancelled owner still cleans its captured original`() = runTest {
        val worker = QueuedDispatcher()
        val source = files.newFile("source.jpg")
        val cancelledScope = CoroutineScope(coroutineContext + Job().apply { cancel() })
        var prepared = false
        val job = ImagePreparation.start(cancelledScope, worker, source, { { _: String -> } }) {
            prepared = true
            null
        }
        worker.runQueued()
        runCurrent()
        worker.runQueued()
        runCurrent()

        assertTrue(job.isCompleted)
        assertFalse(prepared)
        assertFalse(source.exists())
    }

    @Test
    fun `delivery keeps captured conversation and transfers only the prepared output`() = runTest {
        val worker = QueuedDispatcher()
        val source = files.newFile("source.jpg")
        val output = files.newFile("outgoing.jpg")
        var selectedPeer: String? = "peer-a"
        var selectedChannel: String? = null
        var deliveredTo: Pair<String?, String?>? = null
        var deliveredPath: String? = null
        val job = ImagePreparation.start(this, worker, source, captureReceiver = {
            val peer = selectedPeer
            val channel = selectedChannel
            val receiver: (String) -> Unit = { path ->
                deliveredTo = peer to channel
                deliveredPath = path
            }
            receiver
        }) { output.path }
        selectedPeer = null
        selectedChannel = "#public"

        worker.runQueued()
        runCurrent()
        worker.runQueued()
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals("peer-a" to null, deliveredTo)
        assertEquals(output.path, deliveredPath)
        assertFalse(source.exists())
        assertTrue(output.exists())
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun runQueued() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }
}
