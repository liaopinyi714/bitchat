package com.bitchat.android.cloudflare

import android.content.Context
import com.bitchat.android.BuildConfig
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.mesh.*
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.sync.GossipSyncManager
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Cloud relay carries the original signed packets and Noise sessions. It is not a mesh gateway. */
class CloudflareMeshService(
    context: Context,
    override val myPeerID: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build(),
    private val crypto: EncryptionService = EncryptionService(context.applicationContext),
    presenceClock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val mirrorTopic: (RoutedPacket) -> Boolean
) : MeshService {
    private val dataManager = com.bitchat.android.ui.DataManager(context.applicationContext)
    private val rooms = ConcurrentHashMap<String, RelayConnection>()
    private val links = ConcurrentHashMap<String, String>()
    private val histories = ConcurrentHashMap<String, GossipSyncManager>()
    private val peerPresence = RelayPeerPresence(presenceClock)
    // Routing knowledge is not online presence. Keep private targets reachable after their
    // stale Noise/session state expires; panic clearing explicitly discards this runtime cache.
    private val privateTargets = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var running = false
    private val transportWriteLock = Any()
    private var transportGeneration = 0L
    private var presenceJob: Job? = null
    private val notificationManager = com.bitchat.android.ui.NotificationManager(context.applicationContext,
        androidx.core.app.NotificationManagerCompat.from(context.applicationContext))
    private lateinit var mailbox: RelayConnection
    private lateinit var sender: FragmentingPacketSender

    private val transport: MeshTransport = object : MeshTransport {
        override val id = "cloudflare"
        override fun broadcastPacket(routed: RoutedPacket): Boolean {
            val generation = synchronized(transportWriteLock) {
                if (!running) return false
                transportGeneration
            }
            val packet = routed.packet
            // Relay traffic from another sender never escapes its original channel or transport.
            if (TopicPayload.hex(packet.senderID) != myPeerID) return false
            val recipient = packet.recipientID?.takeUnless { it.contentEquals(SpecialRecipients.BROADCAST) }
            if (recipient != null) return sendDirected(TopicPayload.hex(recipient), routed)
            val channel = TopicPayload.peekChannel(packet.payload)
            if (channel != null) {
                cachePublicPacket(packet)
                val connection = rooms[channel]
                val mirror = if (connection?.ready == true) routed.copy(reportTransferProgress = false) else routed
                val local = if (packet.type == MessageType.REQUEST_SYNC.value) false else mirrorTopic(mirror)
                val online = if (connection?.ready == true) sender.send(routed, "topic") { fragment ->
                    sendIfCurrent(generation) { connection.send(fragment.packet) }
                } else false
                if (!online) TopicRelayState.setStatus(channel, "offline")
                return local || online
            }
            if (packet.type != MessageType.ANNOUNCE.value && packet.type != MessageType.LEAVE.value) return false
            var accepted = false
            rooms.forEach { (name, connection) ->
                histories[name]?.onPublicPacketSeen(packet)
                if (sendIfCurrent(generation) { connection.send(packet) }) accepted = true
            }
            if (packet.type == MessageType.ANNOUNCE.value) {
                // A private peer need not share a room. Send the same signed identity update
                // through its mailbox, without publishing it to unrelated channels.
                core.getPeerNicknames().keys.forEach { peer ->
                    val sharesReadyRoom = peerPresence.sharesReadyRoom(peer) { rooms[it]?.ready == true }
                    if (peer != myPeerID && !sharesReadyRoom && core.getPeerInfo(peer)?.isConnected == true &&
                        sendDirected(peer, routed)) accepted = true
                }
            }
            return accepted
        }
        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean = sendDirected(peerID, RoutedPacket(packet))
        override fun sendPacketToLink(relayAddress: String, ingressLinkID: String, packet: BitchatPacket): Boolean {
            if (links[relayAddress] != ingressLinkID) return false
            return sendDirected(relayAddress, RoutedPacket(packet))
        }
        override fun cancelTransfer(transferId: String): Boolean = sender.cancelTransfer(transferId)
    }

    private val core: MeshCore = MeshCore(
        context.applicationContext, scope, transport, crypto, myPeerID, 7u, null,
        object : GossipSyncManager.ConfigProvider {
            override fun seenCapacity() = 100
            override fun gcsMaxBytes() = 400
            override fun gcsTargetFpr() = 0.01
        },
        hooks = MeshCore.Hooks(
            onAnnounceProcessed = ::announced,
            onVerifiedPublicPacket = ::cachePublicPacket,
            onRequestSync = ::receiveSync,
            onMessageReceived = ::admitMessage,
            canAcknowledgePrivateDuplicate = { message ->
                running && message.senderPeerID?.let(::isBlocked) != true &&
                    com.bitchat.android.services.IncomingMessageAdmission.canAcknowledgePrivateDuplicate(message)
            },
            onPrivateDeliveryAcknowledged = { id, peer -> privateOutbox.acknowledge(id, peer) },
            onStarted = ::onCoreStarted,
            announcementNicknameProvider = { com.bitchat.android.services.NicknameProvider.getNickname(context, myPeerID) }
        ),
        bridgeToOtherTransports = false,
        enablePeriodicGossip = false,
        directedAnnouncements = true
    )

    private val privateOutbox = PrivateRelayOutbox(
        scope = scope,
        canSend = { peer ->
            val connected = running && mailbox.ready
            val established = core.hasEstablishedSession(peer)
            if (connected && !established) initiateNoiseHandshake(peer)
            connected && established
        },
        send = core::sendPrivateMessage,
        onExpired = { id ->
            com.bitchat.android.services.AppStateStore.updatePrivateMessageStatus(
                id, com.bitchat.android.model.DeliveryStatus.Failed("Delivery was not confirmed")
            )
        }
    )

    init {
        sender = FragmentingPacketSender(scope, core.fragmentManager, "TopicRelay", 5L)
        mailbox = connection("mail", myPeerID, null)
    }

    private fun announced(routed: RoutedPacket, first: Boolean) {
        routed.peerID?.let { core.setDirectConnection(it, true) }
        val room = routed.ingressLinkID?.split(":")?.getOrNull(1)
        histories.entries.firstOrNull { TopicPayload.roomId(it.key) == room }?.value?.onPublicPacketSeen(routed.packet)
    }

    private fun onCoreStarted() {
        if (!running) return
        privateTargets.forEach { core.sendAnnouncementToPeer(it) }
        rooms.forEach { (channel, connection) ->
            if (connection.ready) histories[channel]?.scheduleInitialSync(1000)
        }
    }

    private fun cachePublicPacket(packet: BitchatPacket) {
        val channel = TopicPayload.peekChannel(packet.payload) ?: return
        histories[channel]?.onPublicPacketSeen(packet)
    }

    private fun receiveSync(routed: RoutedPacket) {
        val peer = routed.peerID ?: return
        if (core.getPeerInfo(peer)?.isVerifiedNickname != true) return
        val decoded = TopicPayload.decode(routed.packet.payload) ?: return
        val ingressRoom = routed.ingressLinkID?.split(":")?.getOrNull(1) ?: return
        if (TopicPayload.roomId(decoded.channel) != ingressRoom) return
        val request = RequestSyncPacket.decode(decoded.content) ?: return
        if (request.p !in 1..32) return
        histories[decoded.channel]?.handleRequestSync(peer, request)
    }

    private fun history(channel: String): GossipSyncManager = GossipSyncManager(myPeerID, scope,
        object : GossipSyncManager.ConfigProvider {
            override fun seenCapacity() = 100
            override fun gcsMaxBytes() = 400
            override fun gcsTargetFpr() = 0.01
        }).also { manager ->
        manager.delegate = object : GossipSyncManager.Delegate {
            override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket {
                val wrapped = packet.copy(payload = TopicPayload.encode(channel, packet.payload), signature = null)
                return wrapped.copy(signature = crypto.signData(requireNotNull(wrapped.toBinaryDataForSigning())))
            }
            override fun sendPacket(packet: BitchatPacket) { rooms[channel]?.send(packet) }
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) { rooms[channel]?.send(packet, peerID, historical = true) }
        }
    }

    private fun removePeerFromTopic(peer: String, channel: String) {
        peerPresence.removePeerFromTopic(peer, channel, ::removeUnreachablePeer)
    }

    private fun removeUnreachablePeer(peer: String) { links.remove(peer); core.removePeer(peer) }

    private fun admitMessage(message: BitchatMessage): Boolean {
        if (!running) return false
        if (message.channel != null && !TopicPayload.joinedTopics.contains(message.channel)) return false
        if (message.senderPeerID?.let(::isBlocked) == true) return false
        if (!com.bitchat.android.services.IncomingMessageAdmission.admitToAppState(message)) return false
        if (delegate == null && message.isPrivate && message.senderPeerID != null) {
            notificationManager.setAppBackgroundState(true)
            notificationManager.showPrivateMessageNotification(message.senderPeerID, message.sender,
                com.bitchat.android.ui.NotificationTextUtils.buildPrivateMessagePreview(message))
        }
        return true
    }

    private fun isBlocked(peer: String): Boolean = synchronized(dataManager) {
        dataManager.loadBlockedUsers()
        core.getPeerFingerprint(peer)?.let(dataManager::isUserBlocked) == true
    }

    override var delegate: MeshDelegate?
        get() = core.delegate
        set(value) { core.delegate = value }

    @Synchronized fun join(channel: String) {
        val name = TopicPayload.normalize(channel)
        TopicPayload.joinedTopics.add(name)
        histories.computeIfAbsent(name, ::history)
        rooms[name]?.let { existing ->
            if (running) existing.start()
            return
        }
        val connection = connection("room", TopicPayload.roomId(name), name)
        rooms[name] = connection
        if (running) connection.start()
    }

    @Synchronized fun leave(channel: String) {
        rooms.remove(channel)?.stop(); histories.remove(channel)?.clear()
        peerPresence.removeTopic(channel, ::removeUnreachablePeer)
        TopicRelayState.remove(channel); TopicPayload.joinedTopics.remove(channel)
    }
    fun protect(channel: String): Boolean = (TopicPayload.pendingCommitment(channel) ?: TopicPayload.expectedCommitment(channel))
        ?.let { rooms[channel]?.protect(it) } == true
    fun isOnline(peerID: String): Boolean = running && mailbox.ready && core.getPeerInfo(peerID)?.isConnected == true &&
        (peerPresence.hasRecentMailboxTraffic(peerID) || peerPresence.sharesReadyRoom(peerID) { rooms[it]?.ready == true })
    fun canQueuePrivateMessage(peerID: String): Boolean = running &&
        (peerID in privateTargets || core.getPeerInfo(peerID) != null)

    private fun connection(kind: String, id: String, channel: String?): RelayConnection = RelayConnection(
        BuildConfig.RELAY_URL, kind, id, scope, client, crypto, myPeerID,
        onPacket = { packet, peer, link ->
            // A room never accepts an envelope naming another topic. Fragment contents
            // are checked after signature verification by the shared message handler.
            val declared = TopicPayload.peekChannel(packet.payload)
            if (!running) false
            else if (!isBlocked(peer) && (channel == null || declared == null || declared == channel)) {
                links[peer] = link
                peerPresence.record(peer, channel)
                if (channel == null) privateTargets.add(peer)
                core.processIncoming(packet, peer, peer, link)
            } else true // Permanently rejected content is consumed, never retried indefinitely.
        },
        onReady = {
            core.sendBroadcastAnnounce()
            if (channel == null) privateTargets.forEach { core.sendAnnouncementToPeer(it) }
            if (channel != null) histories[channel]?.scheduleInitialSync(1000)
            if (channel != null && TopicRelayState.owners[channel] == TopicPayload.hex(requireNotNull(crypto.getStaticPublicKey())) &&
                (TopicPayload.hasKey(channel) || TopicPayload.pendingCommitment(channel) != null)) protect(channel)
        },
        onStatus = { status -> if (channel != null) {
            TopicRelayState.setStatus(channel, status)
            if (status == "disconnected" || status == "password_required") peerPresence.removeTopic(channel, ::removeUnreachablePeer)
        } },
        onProtection = if (channel == null) null else { owner, commitment ->
            if (owner != null) TopicRelayState.owners[channel] = owner
            if (commitment != null) {
                if (TopicPayload.expectedCommitment(channel) != commitment) histories[channel]?.clear()
                if (TopicPayload.confirmProtection(channel, commitment)) TopicRelayState.confirmProtection(channel)
                TopicPayload.requireCommitment(channel, commitment)
            }
            when {
                commitment != null && !TopicPayload.hasKey(channel) -> "password_required"
                commitment == null && owner != null && owner != TopicPayload.hex(requireNotNull(crypto.getStaticPublicKey())) && TopicPayload.hasKey(channel) -> "public_room_password"
                else -> null
            }
        },
        onPeerLeft = { peer -> if (channel != null) removePeerFromTopic(peer, channel) },
        onProtected = { commitment -> if (channel != null) {
            if (TopicPayload.confirmProtection(channel, commitment)) {
                histories[channel]?.clear()
                TopicRelayState.confirmProtection(channel)
                core.sendBroadcastAnnounce()
            } else require(TopicPayload.expectedCommitment(channel) == commitment)
        } }
    )

    private fun sendDirected(peerID: String, routed: RoutedPacket): Boolean {
        val generation = synchronized(transportWriteLock) {
            if (!running || TopicPayload.hex(routed.packet.senderID) != myPeerID || !mailbox.ready) return false
            transportGeneration
        }
        return sender.send(routed, "private relay") { fragment ->
            sendIfCurrent(generation) { mailbox.send(fragment.packet, peerID) }
        }
    }

    // The sender can outlive a service stop while waiting between fragments. Serialize
    // each concrete write with generation changes, without holding this lock in core calls.
    private fun sendIfCurrent(generation: Long, send: () -> Boolean): Boolean = synchronized(transportWriteLock) {
        running && generation == transportGeneration && send()
    }

    @Synchronized override fun startServices() {
        if (running) return
        synchronized(transportWriteLock) { transportGeneration++; running = true }
        core.startCore(); mailbox.start(); rooms.values.forEach { it.start() }
        presenceJob = scope.launch {
            while (isActive && running) {
                delay(60_000)
                peerPresence.expireMailboxRoutes(::removeUnreachablePeer)
                if (mailbox.ready || rooms.values.any { it.ready }) core.sendBroadcastAnnounce()
            }
        }
    }
    @Synchronized override fun stopServices() {
        synchronized(transportWriteLock) { running = false; transportGeneration++ }
        presenceJob?.cancel(); presenceJob = null
        privateOutbox.clear()
        mailbox.stop()
        rooms.forEach { (channel, connection) -> connection.stop(); TopicRelayState.setStatus(channel, "disconnected") }
        core.stopCore(); core.clearAllInternalData(); links.clear(); peerPresence.clear()
        com.bitchat.android.services.AppStateStore.clearTransportPeers(transport.id)
        com.bitchat.android.services.AppStateStore.clearTransportDirectPeers(transport.id)
    }
    override fun sendMessage(content: String, mentions: List<String>, channel: String?) = core.sendMessage(content, mentions, channel)
    @Synchronized override fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String?) {
        if (!running || content.isEmpty() || recipientPeerID.isEmpty()) return
        privateTargets.add(recipientPeerID)
        privateOutbox.enqueue(content, recipientPeerID, recipientNickname, messageID ?: java.util.UUID.randomUUID().toString())
    }
    override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) = core.sendReadReceipt(messageID, recipientPeerID, readerNickname)
    override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) = core.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)
    override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) = core.sendVerifyResponse(peerID, noiseKeyHex, nonceA)
    override fun sendFileBroadcast(file: BitchatFilePacket) = core.sendFileBroadcast(file, TopicPayload.currentChannel)
    override fun sendFileToTopic(file: BitchatFilePacket, channel: String) = core.sendFileBroadcast(file, channel)
    override fun sendTopicVoiceFrame(channel: String, payload: ByteArray) = core.sendVoiceFrame(null, TopicPayload.encode(channel, payload))
    override fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) = core.sendFilePrivate(recipientPeerID, file)
    override fun sendVoiceFrame(recipientPeerID: String?, payload: ByteArray) {
        val channel = TopicPayload.currentChannel
        val encoded = if (recipientPeerID == null && channel != null) TopicPayload.encode(channel, payload) else payload
        core.sendVoiceFrame(recipientPeerID, encoded)
    }
    override fun prepareFilePrivate(recipientPeerID: String, file: BitchatFilePacket, transferId: String, allowLegacyFallback: Boolean) = core.prepareFilePrivate(recipientPeerID, file, transferId, allowLegacyFallback)
    override fun cancelFileTransfer(transferId: String) = sender.cancelTransfer(transferId)
    override fun sendBroadcastAnnounce() = core.sendBroadcastAnnounce()
    override fun sendAnnouncementToPeer(peerID: String) = core.sendAnnouncementToPeer(peerID)
    override fun getPeerNicknames() = core.getPeerNicknames()
    override fun getPeerRSSI(): Map<String, Int> = emptyMap()
    override fun getActivePeerCount() = core.getActivePeerCount()
    override fun hasEstablishedSession(peerID: String) = core.hasEstablishedSession(peerID)
    override fun getSessionState(peerID: String) = core.getSessionState(peerID)
    override fun initiateNoiseHandshake(peerID: String) { core.sendAnnouncementToPeer(peerID); core.initiateNoiseHandshake(peerID) }
    override fun getPeerFingerprint(peerID: String) = core.getPeerFingerprint(peerID)
    override fun getPeerInfo(peerID: String) = core.getPeerInfo(peerID)
    override fun updatePeerInfo(peerID: String, nickname: String, noisePublicKey: ByteArray, signingPublicKey: ByteArray, isVerified: Boolean) = core.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)
    override fun getIdentityFingerprint() = core.getIdentityFingerprint()
    override fun getStaticNoisePublicKey() = core.getStaticNoisePublicKey()
    override fun shouldShowEncryptionIcon(peerID: String) = core.shouldShowEncryptionIcon(peerID)
    override fun getEncryptedPeers() = core.getPeerNicknames().keys.filter(core::hasEstablishedSession)
    override fun getDeviceAddressForPeer(peerID: String): String? = null
    override fun getDeviceAddressToPeerMapping(): Map<String, String> = emptyMap()
    override fun printDeviceAddressesForPeers() = "Cloud relay"
    override fun getDebugStatus() = "Cloud relay: ${rooms.size} joined topics"
    @Synchronized override fun clearAllInternalData() {
        stopServices(); rooms.clear(); histories.values.forEach { it.clear() }; histories.clear(); peerPresence.clear()
        privateTargets.clear()
        core.clearAllInternalData(); TopicRelayState.clear(); TopicPayload.clear()
    }
    override fun clearAllEncryptionData() = core.clearAllEncryptionData()
}
