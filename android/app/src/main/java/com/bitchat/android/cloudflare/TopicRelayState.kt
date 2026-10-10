package com.bitchat.android.cloudflare

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** Presentation state contains no messages, passwords or private keys. */
object TopicRelayState {
    private val mutableStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val status = mutableStatus.asStateFlow()
    val owners = ConcurrentHashMap<String, String>()
    private val mutableProtectionConfirmed = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val protectionConfirmed = mutableProtectionConfirmed.asSharedFlow()
    fun confirmProtection(channel: String) { mutableProtectionConfirmed.tryEmit(channel) }
    fun setStatus(channel: String, status: String) { mutableStatus.update { it + (channel to status) } }
    fun remove(channel: String) { owners.remove(channel); mutableStatus.update { it - channel } }
    fun clear() { owners.clear(); mutableStatus.value = emptyMap() }
}
