package com.bitchat.android.ui

import com.bitchat.android.cloudflare.TopicPayload
import com.bitchat.android.cloudflare.TopicRelayState
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Topic membership is local; the relay records the authenticated creator. */
class ChannelManager(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val dataManager: DataManager,
    private val coroutineScope: CoroutineScope
) {
    var onJoin: (String) -> Unit = {}
    var onLeave: (String) -> Unit = {}
    var onProtect: (String) -> Boolean = { false }
    private val pending = mutableSetOf<String>()
    private var epoch = 0L

    fun joinChannel(channel: String, password: String? = null, myPeerID: String): Boolean {
        val name = runCatching { TopicPayload.normalize(channel) }.getOrElse {
            messageManager.addSystemMessage("Invalid channel name"); return false
        }
        if (password != null) {
            if (!pending.add(name)) return false
            val expectedEpoch = epoch
            coroutineScope.launch {
                try {
                    val key = withContext(Dispatchers.Default) { TopicPayload.derive(password, name) }
                    if (expectedEpoch != epoch) return@launch
                    if (!TopicPayload.setKey(name, key)) {
                        messageManager.addSystemMessage("Incorrect channel password")
                        prompt(name)
                    } else {
                        state.setPasswordProtectedChannels(state.getPasswordProtectedChannelsValue() + name)
                        hidePasswordPrompt()
                        joinChannel(name, null, myPeerID)
                    }
                } catch (_: Exception) { messageManager.addSystemMessage("Unable to unlock channel"); prompt(name) }
                finally { pending.remove(name) }
            }
            return true
        }
        if ((isChannelPasswordProtected(name) || TopicPayload.expectedCommitment(name) != null) && !TopicPayload.hasKey(name)) {
            prompt(name); return false
        }
        state.setJoinedChannels(state.getJoinedChannelsValue() + name)
        TopicPayload.joinedTopics.add(name)
        if (!dataManager.channelCreators.containsKey(name)) dataManager.addChannelCreator(name, myPeerID)
        dataManager.addChannelMember(name, myPeerID)
        if (!state.getChannelMessagesValue().containsKey(name)) state.setChannelMessages(state.getChannelMessagesValue() + (name to emptyList()))
        switchToChannel(name)
        saveChannelData()
        onJoin(name)
        return true
    }

    fun prompt(channel: String) {
        TopicPayload.requirePassword(channel)
        state.setPasswordProtectedChannels(state.getPasswordProtectedChannelsValue() + channel)
        state.setPasswordPromptChannel(channel)
        state.setShowPasswordPrompt(true)
        saveChannelData()
    }

    fun leaveChannel(channel: String) {
        epoch++
        onLeave(channel)
        state.setJoinedChannels(state.getJoinedChannelsValue() - channel)
        if (state.getCurrentChannelValue() == channel) switchToChannel(null)
        messageManager.removeChannelMessages(channel)
        dataManager.removeChannelMembers(channel)
        TopicPayload.forget(channel)
        dataManager.removeChannelCreator(channel)
        saveChannelData()
    }

    fun switchToChannel(channel: String?) {
        if (channel != null && isChannelPasswordProtected(channel) && !TopicPayload.hasKey(channel)) prompt(channel)
        TopicPayload.currentChannel = channel
        state.setCurrentChannel(channel)
        state.setSelectedPrivateChatPeer(null)
        channel?.let(messageManager::clearChannelUnreadCount)
    }

    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? =
        TopicPayload.decode(encryptedContent)?.takeIf { it.channel == channel }?.content?.toString(Charsets.UTF_8)

    fun addChannelMessage(channel: String, message: BitchatMessage, senderPeerID: String?) {
        messageManager.addChannelMessage(channel, message)
        senderPeerID?.let { dataManager.addChannelMember(channel, it) }
    }
    fun removeChannelMember(channel: String, peerID: String) = dataManager.removeChannelMember(channel, peerID)
    fun cleanupDisconnectedMembers(connectedPeers: List<String>, myPeerID: String) = dataManager.cleanupAllDisconnectedMembers(connectedPeers, myPeerID)
    fun isChannelPasswordProtected(channel: String) = state.getPasswordProtectedChannelsValue().contains(channel) || TopicPayload.expectedCommitment(channel) != null
    fun hasChannelKey(channel: String) = TopicPayload.hasKey(channel)
    fun getChannelPassword(channel: String): String? = null
    fun isChannelCreator(channel: String, peerID: String): Boolean {
        val owner = TopicRelayState.owners[channel] ?: return dataManager.isChannelCreator(channel, peerID)
        return TopicPayload.hex(MessageDigest.getInstance("SHA-256").digest(TopicPayload.unhex(owner))).take(16) == peerID
    }
    fun getJoinedChannelsList() = state.getJoinedChannelsValue().toList().sorted()
    private fun saveChannelData() = dataManager.saveChannelData(state.getJoinedChannelsValue(), state.getPasswordProtectedChannelsValue())
    fun loadChannelData() = dataManager.loadChannelData()
    fun hidePasswordPrompt() { state.setShowPasswordPrompt(false); state.setPasswordPromptChannel(null) }

    fun setChannelPassword(channel: String, password: String) {
        if (!pending.add(channel)) return
        val expectedEpoch = epoch
        coroutineScope.launch {
            try {
                val key = withContext(Dispatchers.Default) { TopicPayload.derive(password, channel) }
                if (expectedEpoch != epoch) return@launch
                TopicPayload.replaceKey(channel, key)
                state.setPasswordProtectedChannels(state.getPasswordProtectedChannelsValue() + channel)
                saveChannelData()
                if (!onProtect(channel)) onJoin(channel)
            } catch (_: Exception) { messageManager.addSystemMessage("Unable to protect channel") }
            finally { pending.remove(channel) }
        }
    }

    fun clearAllChannels() {
        epoch++
        state.getJoinedChannelsValue().forEach(onLeave)
        state.setJoinedChannels(emptySet()); switchToChannel(null)
        state.setPasswordProtectedChannels(emptySet()); hidePasswordPrompt()
        pending.clear(); TopicPayload.clear(); TopicRelayState.clear()
    }
}
