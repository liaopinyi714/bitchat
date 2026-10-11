package com.bitchat.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.MessageManager
import com.bitchat.android.ui.NoiseSessionDelegate
import com.bitchat.android.ui.PrivateChatManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.UUID
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class IncomingMessageAdmissionTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var repository: ConversationRepository
    private lateinit var storageCipher: InMemoryConversationStorageCipher
    private val repositoriesToClose = mutableListOf<ConversationRepository>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "admission-test-${UUID.randomUUID()}.db"
        storageCipher = InMemoryConversationStorageCipher()
        repository = ConversationRepository(
            context = context,
            dispatcher = Dispatchers.Unconfined,
            databaseName = databaseName,
            storageCipher = storageCipher
        )
        repositoriesToClose += repository
        AppStateStore.setConversationRepositoryForTest(repository)
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
    }

    @After
    fun tearDown() {
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(null)
        repositoriesToClose.forEach(ConversationRepository::closeForTest)
        context.deleteDatabase(databaseName)
        context.deleteDatabase("failing-$databaseName")
    }

    @Test
    fun `private message rejected during panic cannot continue transport dispatch`() {
        assertTrue(runBlocking { AppStateStore.panicClearPrivateConversations() })

        assertFalse(
            IncomingMessageAdmission.admitToAppState(
                privateMessage(id = "during-panic")
            )
        )
        assertTrue(AppStateStore.privateMessages.value.isEmpty())
    }

    @Test
    fun `duplicate private transport delivery is rejected before downstream effects`() {
        val message = privateMessage(id = "same-message-over-two-transports")

        assertTrue(IncomingMessageAdmission.admitToAppState(message))
        assertFalse(IncomingMessageAdmission.admitToAppState(message))
        assertTrue(IncomingMessageAdmission.canAcknowledgePrivateDuplicate(message))
        assertFalse(IncomingMessageAdmission.canAcknowledgePrivateDuplicate(message.copy(content = "changed")))
        assertFalse(IncomingMessageAdmission.canAcknowledgePrivateDuplicate(message.copy(senderPeerID = "other-peer")))
    }

    @Test
    fun `older retained replay is rejected after summary-only restart`() {
        val older = privateMessage(id = "older-retained")
        val latest = privateMessage(id = "latest-summary").copy(timestamp = Date(2L))
        assertTrue(IncomingMessageAdmission.admitToAppState(older))
        assertTrue(IncomingMessageAdmission.admitToAppState(latest))

        AppStateStore.clear()
        repository.reload { snapshot ->
            AppStateStore.restorePrivateConversations(snapshot)
        }
        runBlocking { repository.awaitPendingWrites() }
        assertEquals(
            listOf(latest.id),
            AppStateStore.privateMessages.value.getValue("peer-a").map { it.id }
        )

        assertFalse(IncomingMessageAdmission.admitToAppState(older))
        assertEquals(
            listOf(latest.id),
            AppStateStore.privateMessages.value.getValue("peer-a").map { it.id }
        )
    }

    @Test
    fun `same nickname incoming message persists as unread`() {
        AppStateStore.setLocalPeerID("local-peer")
        val state = ChatState(TestScope()).apply { setNickname("shared-name") }
        val manager = MessageManager(state, context)
        val incoming = privateMessage(id = "same-name-durable").copy(sender = "shared-name")

        assertTrue(runBlocking { manager.addPrivateMessageDurably("peer-a", incoming) })
        assertTrue("peer-a" in state.getUnreadPrivateMessagesValue())
        assertEquals(mapOf("peer-a" to 1), AppStateStore.unreadPrivateMessageCounts.value)

        val snapshot = requireNotNull(runBlocking { repository.loadConversationAndWait("peer-a") })
        assertEquals(mapOf("peer-a" to 1), snapshot.unreadCounts)
        assertFalse(incoming.id in snapshot.readMessageIDs)
        assertEquals("shared-name", snapshot.displayNames["peer-a"])
    }

    @Test
    fun `delivery receipt persists after older message is unloaded from memory`() {
        val older = privateMessage(id = "older-outgoing").copy(
            sender = "me",
            senderPeerID = "self",
            recipientNickname = "alice",
            deliveryStatus = DeliveryStatus.Sent
        )
        val latest = privateMessage(id = "latest-summary").copy(timestamp = Date(2L))
        assertTrue(AppStateStore.addPrivateMessage("peer-a", older, forceRead = true))
        assertTrue(IncomingMessageAdmission.admitToAppState(latest))
        runBlocking { repository.awaitPendingWrites() }

        AppStateStore.releasePrivateConversationHistory("peer-a")
        assertEquals(
            listOf(latest.id),
            AppStateStore.privateMessages.value.getValue("peer-a").map { it.id }
        )

        val delivered = DeliveryStatus.Delivered(to = "alice", at = Date(3L))
        AppStateStore.updatePrivateMessageStatus(older.id, delivered)
        runBlocking { repository.awaitPendingWrites() }

        val snapshot = runBlocking {
            repository.loadConversationAndWait("peer-a")
        }
        assertEquals(
            delivered,
            snapshot?.chats?.getValue("peer-a")
                ?.single { it.id == older.id }
                ?.deliveryStatus
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `cancelled queued admission releases its message reservation`() = runTest {
        val databaseDispatcher = QueuedDispatcher()
        repository = ConversationRepository(
            context, databaseDispatcher, databaseName, storageCipher
        ).also {
            repositoriesToClose += it
            AppStateStore.setConversationRepositoryForTest(it)
        }
        val message = privateMessage("cancelled-before-write")
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            AppStateStore.addPrivateMessageDurably("peer-a", message)
        }
        first.cancel()
        databaseDispatcher.runQueued()
        runCurrent()

        val retry = async(start = CoroutineStart.UNDISPATCHED) {
            AppStateStore.addPrivateMessageDurably("peer-a", message)
        }
        databaseDispatcher.runQueued()
        runCurrent()

        assertTrue(retry.await())
        assertEquals(listOf(message), AppStateStore.privateMessages.value.getValue("peer-a"))
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `history load finishing after a clear cannot resurrect old state`() = runTest {
        assertTrue(IncomingMessageAdmission.admitToAppState(privateMessage("before-clear")))
        val databaseDispatcher = QueuedDispatcher()
        repository = ConversationRepository(
            context, databaseDispatcher, databaseName, storageCipher
        ).also {
            repositoriesToClose += it
            AppStateStore.setConversationRepositoryForTest(it)
        }
        val loading = async(start = CoroutineStart.UNDISPATCHED) {
            AppStateStore.loadPrivateConversationHistory("peer-a")
        }
        databaseDispatcher.runQueued()
        AppStateStore.clear()
        runCurrent()

        assertFalse(loading.await())
        assertTrue(AppStateStore.privateMessages.value.isEmpty())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `queued admission from a cleared generation cannot recreate stored messages`() = runTest {
        val databaseDispatcher = QueuedDispatcher()
        repository = ConversationRepository(
            context, databaseDispatcher, databaseName, storageCipher
        ).also {
            repositoriesToClose += it
            AppStateStore.setConversationRepositoryForTest(it)
        }
        val incoming = async(start = CoroutineStart.UNDISPATCHED) {
            AppStateStore.addPrivateMessageDurably("peer-a", privateMessage("stale-write"))
        }
        AppStateStore.clear()
        databaseDispatcher.runQueued()
        runCurrent()

        assertFalse(incoming.await())
        var restored: PersistedConversationSnapshot? = null
        repository.reload { restored = it }
        databaseDispatcher.runQueued()
        assertTrue(requireNotNull(restored).chats.isEmpty())
    }

    @Test
    fun `undo captured before panic cannot restore erased private messages`() {
        assertTrue(IncomingMessageAdmission.admitToAppState(privateMessage("before-panic")))
        val deletion = requireNotNull(runBlocking {
            AppStateStore.deletePrivateConversationAndWait("peer-a")
        })
        assertTrue(runBlocking { AppStateStore.panicClearPrivateConversations() })
        AppStateStore.resumePrivateConversationsAfterPanic()

        assertFalse(runBlocking { AppStateStore.restoreDeletedConversation(deletion) })
        assertTrue(AppStateStore.privateMessages.value.isEmpty())
        assertTrue(requireNotNull(runBlocking {
            repository.loadConversationAndWait("peer-a")
        }).chats.isEmpty())
    }

    @Test
    fun `history reload clears a stale unread badge when all rows are read`() {
        val message = privateMessage("read-in-store")
        assertTrue(IncomingMessageAdmission.admitToAppState(message))
        repository.markRead(message.id)
        runBlocking { repository.awaitPendingWrites() }

        assertTrue(runBlocking { AppStateStore.loadPrivateConversationHistory("peer-a") })

        assertTrue(AppStateStore.unreadPrivateMessageCounts.value.isEmpty())
        assertTrue(message.id in AppStateStore.readPrivateMessageIDs.value)
    }

    @Test
    fun `public and channel messages preserve best effort admission`() {
        val public = BitchatMessage(
            id = "public",
            sender = "alice",
            content = "hello",
            timestamp = Date(1L)
        )
        val channel = public.copy(id = "channel", channel = "#mesh")

        assertTrue(IncomingMessageAdmission.admitToAppState(public))
        assertTrue(IncomingMessageAdmission.admitToAppState(channel))
        assertTrue(AppStateStore.publicMessages.value.contains(public))
    }

    @Test
    fun `outgoing callback runs only after local echo survives process state clear`() {
        val state = ChatState(TestScope())
        state.setNickname("me")
        val manager = PrivateChatManager(
            state = state,
            messageManager = MessageManager(state, context),
            dataManager = DataManager(context),
            noiseSessionDelegate = testNoiseDelegate()
        )
        var callbackInvoked = false

        val sent = runBlocking {
            manager.sendPrivateMessageDurably(
                content = "durable outgoing",
                peerID = "peer-a",
                recipientNickname = "alice",
                senderNickname = "me",
                myPeerID = "self"
            ) { _, _, _, _ ->
                callbackInvoked = true
            }
        }

        assertTrue(sent)
        assertTrue(callbackInvoked)
        AppStateStore.clear()
        repository.reload { snapshot ->
            assertEquals(
                "durable outgoing",
                snapshot.chats.getValue("peer-a").single().content
            )
        }
        runBlocking { repository.awaitPendingWrites() }
    }

    @Test
    fun `outgoing messages to different peers keep separate persisted conversations`() {
        val state = ChatState(TestScope())
        state.setNickname("me")
        val manager = PrivateChatManager(
            state = state,
            messageManager = MessageManager(state, context),
            dataManager = DataManager(context),
            noiseSessionDelegate = testNoiseDelegate()
        )

        runBlocking {
            assertTrue(
                manager.sendPrivateMessageDurably(
                    content = "only for alice",
                    peerID = "peer-a",
                    recipientNickname = "alice",
                    senderNickname = "me",
                    myPeerID = "self"
                ) { _, _, _, _ -> }
            )
            assertTrue(
                manager.sendPrivateMessageDurably(
                    content = "only for bob",
                    peerID = "peer-b",
                    recipientNickname = "bob",
                    senderNickname = "me",
                    myPeerID = "self"
                ) { _, _, _, _ -> }
            )

            val alice = repository.loadConversationAndWait("peer-a")
            val bob = repository.loadConversationAndWait("peer-b")

            assertEquals(
                listOf("only for alice"),
                alice?.chats?.getValue("peer-a")?.map { it.content }
            )
            assertEquals(
                listOf("only for bob"),
                bob?.chats?.getValue("peer-b")?.map { it.content }
            )
        }
    }

    @Test
    fun `outgoing callback is suppressed when durable storage fails`() {
        val failingRepository = ConversationRepository(
            context = context,
            dispatcher = Dispatchers.Unconfined,
            databaseName = "failing-$databaseName",
            storageCipher = object : ConversationStorageCipher {
                override fun encrypt(
                    plaintext: ByteArray,
                    associatedData: ByteArray
                ): ByteArray = error("storage unavailable")

                override fun decrypt(
                    envelope: ByteArray,
                    associatedData: ByteArray
                ): ByteArray = error("storage unavailable")

                override fun destroyKey() = Unit
            }
        )
        repositoriesToClose += failingRepository
        AppStateStore.setConversationRepositoryForTest(failingRepository)
        val state = ChatState(TestScope())
        val manager = PrivateChatManager(
            state = state,
            messageManager = MessageManager(state, context),
            dataManager = DataManager(context),
            noiseSessionDelegate = testNoiseDelegate()
        )
        var callbackInvoked = false

        val sent = runBlocking {
            manager.sendPrivateMessageDurably(
                "lost",
                "peer-a",
                "alice",
                "me",
                "self"
            ) { _, _, _, _ -> callbackInvoked = true }
        }

        assertFalse(sent)
        assertFalse(callbackInvoked)
        assertTrue(state.getPrivateChatsValue().isEmpty())
    }

    private fun privateMessage(id: String) = BitchatMessage(
        id = id,
        sender = "alice",
        content = "secret",
        timestamp = Date(1L),
        isPrivate = true,
        senderPeerID = "peer-a"
    )

    private fun testNoiseDelegate() = object : NoiseSessionDelegate {
        override fun hasEstablishedSession(peerID: String) = false
        override fun initiateHandshake(peerID: String) = Unit
        override fun getMyPeerID() = "self"
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
