package com.bitchat.android.mesh

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.content.Context
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BluetoothComponentShutdownTest {
    private val scope = TestScope()
    private val adapter = mock<BluetoothAdapter>()
    private val bluetooth = mock<BluetoothManager>()
    private val scanner = mock<BluetoothLeScanner>()
    private val context = mock<Context>()
    private val permissions = mock<BluetoothPermissionManager>()
    private val power = mock<PowerManager>()
    private val tracker = BluetoothConnectionTracker(scope, power)

    init {
        whenever(context.getSystemService(Context.BLUETOOTH_SERVICE)).thenReturn(bluetooth)
        whenever(bluetooth.adapter).thenReturn(adapter)
        whenever(adapter.isEnabled).thenReturn(true)
        whenever(adapter.bluetoothLeScanner).thenReturn(scanner)
        whenever(adapter.bluetoothLeAdvertiser).thenReturn(mock<BluetoothLeAdvertiser>())
        whenever(permissions.hasBluetoothPermissions()).thenReturn(true)
    }

    @After
    fun tearDown() { scope.cancel() }

    @Test
    fun `server is closed even when its owner scope has already been cancelled`() {
        val gatt = mock<BluetoothGattServer>()
        whenever(bluetooth.openGattServer(any(), any())).thenReturn(gatt)
        val server = BluetoothGattServerManager(context, scope, tracker, permissions, power, null, "0011223344556677")
        assertTrue(server.start())
        scope.runCurrent()
        assertSame(gatt, server.getGattServer())
        scope.cancel()

        server.stop()

        verify(gatt).close()
        assertNull(server.getGattServer())
    }

    @Test
    fun `permission revocation cannot prevent clearing server state`() {
        val gatt = mock<BluetoothGattServer>()
        whenever(bluetooth.openGattServer(any(), any())).thenReturn(gatt)
        val server = BluetoothGattServerManager(context, scope, tracker, permissions, power, null, "0011223344556677")
        assertTrue(server.start())
        scope.runCurrent()
        doThrow(SecurityException("synthetic revoked permission")).whenever(gatt).close()

        server.stop()

        assertNull(server.getGattServer())
    }

    @Test
    fun `scanner shutdown runs synchronously after scope cancellation and permission revocation`() {
        val client = BluetoothGattClientManager(context, scope, tracker, permissions, power, null)
        assertTrue(client.start())
        client.onScanStateChanged(true)
        scope.cancel()
        whenever(permissions.hasBluetoothPermissions()).thenReturn(false)

        client.stop()

        verify(scanner).stopScan(any<android.bluetooth.le.ScanCallback>())
    }

    private fun connectedClient(): Triple<BluetoothGattClientManager, BluetoothGatt, BluetoothGattCallback> {
        val device = mock<BluetoothDevice>()
        val gatt = mock<BluetoothGatt>()
        val callback = argumentCaptor<BluetoothGattCallback>()
        whenever(device.address).thenReturn("AA:BB:CC:DD:EE:01")
        whenever(gatt.device).thenReturn(device)
        whenever(adapter.getRemoteDevice("AA:BB:CC:DD:EE:01")).thenReturn(device)
        whenever(device.connectGatt(eq(context), eq(false), callback.capture(), eq(BluetoothDevice.TRANSPORT_LE)))
            .thenReturn(gatt)
        val client = BluetoothGattClientManager(context, scope, tracker, permissions, power, null)
        assertTrue(client.start())
        assertTrue(client.connectToAddress("AA:BB:CC:DD:EE:01"))
        return Triple(client, gatt, callback.lastValue)
    }

    @Test
    fun `stopping before MTU closes pending GATT and late callback cannot revive it after restart`() {
        val (client, gatt, callback) = connectedClient()
        client.stop()
        verify(gatt).close()
        assertTrue(client.start())

        callback.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS)

        assertEquals(0, tracker.getConnectionCount())
        verify(gatt, never()).discoverServices()
        verify(gatt, times(1)).close()
        client.stop()
    }

    @Test
    fun `permission loss in service discovery closes link and removes tracked state`() {
        val (client, gatt, callback) = connectedClient()
        whenever(gatt.discoverServices()).thenThrow(SecurityException("synthetic revoked permission"))

        callback.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS)

        assertEquals(0, tracker.getConnectionCount())
        verify(gatt).close()
        client.stop()
    }

    @Test
    fun `disconnection closes GATT even when delayed cleanup cannot run`() {
        val (client, gatt, callback) = connectedClient()
        callback.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS)
        scope.cancel()

        callback.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)

        verify(gatt).close()
        assertEquals(0, tracker.getConnectionCount())
        client.stop()
        verify(gatt, times(1)).close()
    }

    @Test
    fun `stopped client rejects new connection attempts`() {
        val client = BluetoothGattClientManager(context, scope, tracker, permissions, power, null)
        assertFalse(client.connectToAddress("AA:BB:CC:DD:EE:01"))
        verify(adapter, never()).getRemoteDevice(any<String>())
    }

    @Test
    fun `old server callback cannot register a connection after restart`() {
        val callback = argumentCaptor<BluetoothGattServerCallback>()
        val delegate = mock<BluetoothConnectionManagerDelegate>()
        whenever(bluetooth.openGattServer(eq(context), callback.capture())).thenReturn(mock<BluetoothGattServer>())
        val server = BluetoothGattServerManager(context, scope, tracker, permissions, power, delegate, "0011223344556677")
        assertTrue(server.start())
        scope.runCurrent()
        val oldCallback = callback.lastValue
        server.stop()
        assertTrue(server.start())
        scope.runCurrent()
        val device = mock<BluetoothDevice>()
        whenever(device.address).thenReturn("AA:BB:CC:DD:EE:01")

        oldCallback.onConnectionStateChange(device, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        oldCallback.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)

        assertEquals(0, tracker.getConnectionCount())
        verify(delegate, never()).onGattServerNotificationComplete(any(), org.mockito.kotlin.anyOrNull(), any())
        server.stop()
    }
}
