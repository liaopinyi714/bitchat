package com.bitchat.android.cloudflare

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.testing.InMemoryKeyStoreApplication
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = InMemoryKeyStoreApplication::class)
class UnifiedPrivateRoutingTest {
    private val context = mock<Context>()
    private val bluetooth = mock<BluetoothMeshService>()
    private val online = mock<CloudflareMeshService>()
    private val adapter = mock<BluetoothAdapter>()
    private val manager = mock<BluetoothManager>()
    private val peer = "1122334455667788"
    private val service: UnifiedMeshService

    init {
        whenever(context.applicationContext).thenReturn(ApplicationProvider.getApplicationContext<Application>())
        whenever(context.getSystemService(BluetoothManager::class.java)).thenReturn(manager)
        whenever(manager.adapter).thenReturn(adapter)
        whenever(bluetooth.myPeerID).thenReturn("0011223344556677")
        val stalePeer = PeerInfo(peer, "name", true, true,
            ByteArray(32) { 1 }, ByteArray(32) { 2 }, true, 1L)
        whenever(bluetooth.getPeerInfo(peer)).thenReturn(stalePeer)
        whenever(bluetooth.hasEstablishedSession(peer)).thenReturn(true)
        whenever(online.isOnline(peer)).thenReturn(true)
        service = UnifiedMeshService(context, bluetooth, online)
    }

    @After fun cleanup() { service.stopServices() }

    @Test fun `disabled Bluetooth never captures mobile private text despite cached peer and Noise session`() {
        whenever(adapter.isEnabled).thenReturn(false)
        service.sendPrivateMessage("synthetic text", peer, "name", "message-1")
        verify(online).sendPrivateMessage("synthetic text", peer, "name", "message-1")
        verify(bluetooth, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test fun `revoked Bluetooth permission keeps private text on the mobile relay`() {
        whenever(adapter.isEnabled).thenReturn(true)
        whenever(context.checkPermission(any(), any(), any())).thenReturn(android.content.pm.PackageManager.PERMISSION_DENIED)
        service.sendPrivateMessage("synthetic text", peer, "name", "message-1")
        verify(online).sendPrivateMessage("synthetic text", peer, "name", "message-1")
    }

    @Test fun `disabled local radio and disconnected mailbox never report cached peer as ready`() {
        whenever(adapter.isEnabled).thenReturn(false)
        whenever(online.isOnline(peer)).thenReturn(false)
        org.junit.Assert.assertFalse(requireNotNull(service.getPeerInfo(peer)).isConnected)
        org.junit.Assert.assertFalse(service.hasEstablishedSession(peer))
    }

    @Test fun `mailbox reconnect retains a new private message instead of dropping it into disabled radios`() {
        whenever(adapter.isEnabled).thenReturn(false)
        whenever(online.isOnline(peer)).thenReturn(false)
        whenever(online.canQueuePrivateMessage(peer)).thenReturn(true)

        service.sendPrivateMessage("synthetic text", peer, "name", "message-1")

        verify(online).sendPrivateMessage("synthetic text", peer, "name", "message-1")
        verify(bluetooth, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }
}
