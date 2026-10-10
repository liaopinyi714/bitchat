package com.bitchat.android.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.cloudflare.TopicPayload
import com.bitchat.android.cloudflare.TopicRelayState
import com.bitchat.android.services.AppStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ChannelLifecycleTest {
    private val scope = TestScope()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val state = ChatState(scope)
    private val messages = MessageManager(state, context)
    private val key = SecretKeySpec(ByteArray(32) { 9 }, "AES")

    @After fun cleanup() {
        TopicPayload.clear(); TopicRelayState.clear(); AppStateStore.clear()
    }

    private fun manager(result: CompletableDeferred<SecretKeySpec>) =
        ChannelManager(state, messages, DataManager(context), scope) { _, _ -> result.await() }

    @Test fun `leaving a channel discards its outstanding password operation`() {
        val result = CompletableDeferred<SecretKeySpec>()
        val channels = manager(result)
        channels.joinChannel("#sample", "synthetic", "synthetic-peer")
        scope.runCurrent()
        channels.leaveChannel("#sample")
        result.complete(key)
        scope.runCurrent()
        assertFalse(TopicPayload.hasKey("#sample"))
        assertFalse(state.getJoinedChannelsValue().contains("#sample"))
        assertFalse(state.getShowPasswordPromptValue())
    }

    @Test fun `leaving another channel does not cancel an independent password operation`() {
        val result = CompletableDeferred<SecretKeySpec>()
        val channels = manager(result)
        channels.joinChannel("#sample", "synthetic", "synthetic-peer")
        scope.runCurrent()
        channels.leaveChannel("#other")
        result.complete(key)
        scope.runCurrent()
        assertTrue(TopicPayload.hasKey("#sample"))
        assertTrue(state.getJoinedChannelsValue().contains("#sample"))
    }

    @Test fun `failed derivation after panic cannot reopen the password prompt`() {
        val result = CompletableDeferred<SecretKeySpec>()
        val channels = manager(result)
        channels.joinChannel("#sample", "synthetic", "synthetic-peer")
        scope.runCurrent()
        channels.clearAllChannels()
        result.completeExceptionally(IllegalArgumentException("synthetic failure"))
        scope.runCurrent()
        assertFalse(state.getShowPasswordPromptValue())
        assertTrue(state.getJoinedChannelsValue().isEmpty())
        assertFalse(TopicPayload.hasKey("#sample"))
    }

    @Test fun `offline password change preserves the active key and does not report success`() {
        val result = CompletableDeferred<SecretKeySpec>()
        val channels = manager(result)
        val old = SecretKeySpec(ByteArray(32) { 7 }, "AES")
        TopicPayload.setKey("#sample", old)
        val before = TopicPayload.expectedCommitment("#sample")
        channels.setChannelPassword("#sample", "synthetic")
        scope.runCurrent()
        result.complete(key)
        scope.runCurrent()
        assertEquals(before, TopicPayload.expectedCommitment("#sample"))
        assertNull(TopicPayload.pendingCommitment("#sample"))
        assertEquals(messages.getString(com.bitchat.android.R.string.topic_protect_requires_online),
            state.getMessagesValue().last().content)
    }

    @Test fun `password change waits for relay acknowledgement`() {
        val result = CompletableDeferred<SecretKeySpec>()
        val channels = manager(result)
        channels.onProtect = { true }
        channels.joinChannel("#sample", myPeerID = "synthetic-peer")
        channels.setChannelPassword("#sample", "synthetic")
        scope.runCurrent()
        result.complete(key)
        scope.runCurrent()
        assertFalse(TopicPayload.hasKey("#sample"))
        assertFalse(state.getPasswordProtectedChannelsValue().contains("#sample"))
        assertTrue(TopicPayload.confirmProtection("#sample", TopicPayload.commitment(key)))
        channels.onProtectionConfirmed("#sample")
        assertTrue(TopicPayload.hasKey("#sample"))
        assertTrue(state.getPasswordProtectedChannelsValue().contains("#sample"))
        assertEquals(messages.getString(com.bitchat.android.R.string.command_password_changed, "#sample"),
            state.getMessagesValue().last().content)
    }
}
