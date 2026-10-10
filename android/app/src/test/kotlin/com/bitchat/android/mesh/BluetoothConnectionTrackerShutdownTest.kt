package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BluetoothConnectionTrackerShutdownTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val tracker = BluetoothConnectionTracker(scope, mock())

    @After
    fun tearDown() {
        tracker.stop()
        scope.cancel()
    }

    private fun connect(address: String, gatt: BluetoothGatt) {
        val device = mock<BluetoothDevice>()
        whenever(device.address).thenReturn(address)
        tracker.addDeviceConnection(address, BluetoothConnectionTracker.DeviceConnection(device, gatt))
    }

    @Test
    fun `shutdown closes every connection before returning and clears state`() {
        val first = mock<BluetoothGatt>()
        val second = mock<BluetoothGatt>()
        connect("AA:BB:CC:DD:EE:01", first)
        connect("AA:BB:CC:DD:EE:02", second)

        tracker.stop()

        verify(first).disconnect()
        verify(first).close()
        verify(second).disconnect()
        verify(second).close()
        assertEquals(0, tracker.getConnectionCount())
    }

    @Test
    fun `cancelled owner scope cannot prevent GATT cleanup`() {
        val gatt = mock<BluetoothGatt>()
        connect("AA:BB:CC:DD:EE:01", gatt)
        scope.cancel()

        tracker.stop()

        verify(gatt).close()
        assertEquals(0, tracker.getConnectionCount())
    }

    @Test
    fun `revoked permission does not skip close or cleanup of other connections`() {
        val first = mock<BluetoothGatt>()
        val second = mock<BluetoothGatt>()
        doThrow(SecurityException("synthetic revoked permission")).whenever(first).disconnect()
        doThrow(SecurityException("synthetic revoked permission")).whenever(first).close()
        connect("AA:BB:CC:DD:EE:01", first)
        connect("AA:BB:CC:DD:EE:02", second)

        tracker.stop()

        verify(first).close()
        verify(second).close()
        assertEquals(0, tracker.getConnectionCount())
    }

    @Test
    fun `repeated shutdown cannot close a detached connection twice`() {
        val gatt = mock<BluetoothGatt>()
        connect("AA:BB:CC:DD:EE:01", gatt)

        tracker.stop()
        tracker.stop()

        verify(gatt, times(1)).disconnect()
        verify(gatt, times(1)).close()
    }
}
