package com.bitchat.android.mesh

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages GATT server operations, advertising, and server-side connections
 */
class BluetoothGattServerManager(
    private val context: Context,
    private val connectionScope: CoroutineScope,
    private val connectionTracker: BluetoothConnectionTracker,
    private val permissionManager: BluetoothPermissionManager,
    private val powerManager: PowerManager,
    private val delegate: BluetoothConnectionManagerDelegate?,
    private val myPeerID: String
) {
    
    companion object {
        private const val TAG = "BluetoothGattServerManager"
        // Self-healing advertising recovery tuning
        private const val ADVERTISE_RETRY_BASE_MS = 3_000L      // base backoff for transient advertise failures
        private const val ADVERTISE_MAX_RETRY_DELAY_MS = 30_000L // cap on backoff delay
    }
    
    // Core Bluetooth components
    private val bluetoothManager: BluetoothManager = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    
    // GATT server for peripheral mode
    private var gattServer: BluetoothGattServer? = null
    private val serverLinkIDs = ConcurrentHashMap<String, String>()
    private var characteristic: BluetoothGattCharacteristic? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertiseRetryCount = 0
    
    // State management
    @Volatile private var isActive = false
    @Volatile private var generation = 0L
    private var startupJob: Job? = null
    private var advertiseRestartJob: Job? = null

    private fun isBleTransportEnabled(): Boolean {
        return try {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().bleEnabled.value
        } catch (_: Exception) {
            try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
        }
    }

    private fun isServerRoleEnabled(): Boolean {
        return isBleTransportEnabled() &&
            (try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().gattServerEnabled.value } catch (_: Exception) { true })
    }

    /**
     * Disconnect a specific device (used by ConnectionManager to enforce overall limits)
     */
    fun disconnectDevice(device: BluetoothDevice) {
        try {
            gattServer?.cancelConnection(device)
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked during server disconnect")
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting device ${device.address}: ${e.message}")
        }
    }
    
    /**
     * Start GATT server
     */
    @Synchronized
    fun start(): Boolean {
        // Respect debug setting
        if (!isServerRoleEnabled()) {
            Log.i(TAG, "Server start skipped: BLE/GATT Server disabled in debug settings")
            return false
        }

        if (isActive) {
            return true
        }
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.e(TAG, "Missing Bluetooth permissions")
            return false
        }
        
        val enabled = try { bluetoothAdapter?.isEnabled == true } catch (_: SecurityException) { false }
        if (!enabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        
        if (bleAdvertiser == null) {
            Log.e(TAG, "BLE advertiser not available")
            return false
        }
        
        isActive = true
        
        startupJob = connectionScope.launch {
            try {
                setupGattServer()
            } catch (_: SecurityException) {
                stop()
                return@launch
            }
            delay(300) // Brief delay to ensure GATT server is ready
            startAdvertising()
        }
        
        return true
    }
    
    /**
     * Stop GATT server
     */
    @Synchronized
    fun stop() {
        isActive = false
        generation++
        startupJob?.cancel()
        startupJob = null
        advertiseRestartJob?.cancel()
        advertiseRestartJob = null
        stopAdvertising()
        // Complete cleanup before the owner cancels connectionScope.
        val server = gattServer
        gattServer = null
        serverLinkIDs.clear()
        connectionTracker.getConnectedDevices().values.filter { !it.isClient }.forEach { dc ->
            val peerID = connectionTracker.addressPeerMap[dc.device.address]
            if (connectionTracker.cleanupDeviceConnectionIfCurrent(dc.device.address, dc.linkID)) {
                delegate?.onDeviceDisconnected(dc.device, dc.linkID, peerID)
            }
            try {
                server?.cancelConnection(dc.device)
            } catch (_: SecurityException) {
                Log.w(TAG, "Bluetooth permission revoked during server disconnect")
            } catch (_: RuntimeException) {
                Log.w(TAG, "Server disconnect failed during shutdown")
            }
        }
        try {
            server?.close()
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked during server close")
        } catch (_: RuntimeException) {
            Log.w(TAG, "Server close failed during shutdown")
        }
        Log.i(TAG, "GATT server stopped")
    }
    
    /**
     * Get GATT server instance
     */
    fun getGattServer(): BluetoothGattServer? = gattServer
    
    /**
     * Get characteristic instance
     */
    fun getCharacteristic(): BluetoothGattCharacteristic? = characteristic
    
    /**
     * Setup GATT server with proper sequencing
     */
    @Suppress("DEPRECATION")
    @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
    @Synchronized
    private fun setupGattServer() {
        val serverGeneration = generation
        fun isCurrent() = isActive && generation == serverGeneration
        if (!permissionManager.hasBluetoothPermissions()) return
        
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                synchronized(this@BluetoothGattServerManager) {
                    // Guard against callbacks after service shutdown
                    if (!isCurrent()) {
                        return
                    }

                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.i(TAG, "Connected to ${device.address} (server)")
                            val linkID = UUID.randomUUID().toString()
                            serverLinkIDs[device.address] = linkID
                        
                            // Get best available RSSI (scan RSSI for server connections)
                            val rssi = connectionTracker.getBestRSSI(device.address) ?: Int.MIN_VALUE
                        
                            val deviceConn = BluetoothConnectionTracker.DeviceConnection(
                                device = device,
                                rssi = rssi,
                                isClient = false,
                                linkID = linkID
                            )
                            connectionTracker.addDeviceConnection(device.address, deviceConn)

                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.i(TAG, "Disconnected from ${device.address} (server)")
                            val linkID = serverLinkIDs.remove(device.address)
                            // Capture the observed peer before cleanup drops the address mapping.
                            val disconnectedPeerID = connectionTracker.addressPeerMap[device.address]
                            if (linkID != null) {
                                connectionTracker.cleanupDeviceConnectionIfCurrent(device.address, linkID)
                            }
                            // Notify delegate about device disconnection so higher layers can update direct flags
                            delegate?.onDeviceDisconnected(device, linkID, disconnectedPeerID)
                        }
                    }
                }
            }
            
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                synchronized(this@BluetoothGattServerManager) {
                    // Guard against callbacks after service shutdown
                    if (!isCurrent()) {
                        return
                    }

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "Server: Failed to add service: ${service.uuid}, status: $status")
                        stop()
                    }
                }
            }
            
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                synchronized(this@BluetoothGattServerManager) {
                    // Guard against callbacks after service shutdown
                    if (!isCurrent()) {
                        return
                    }

                    if (preparedWrite || offset != 0 || characteristic.uuid != AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID) {
                        if (responseNeeded) sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                        return
                    }
                    if (characteristic.uuid == AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID) {
                        val linkID = serverLinkIDs[device.address]
                        if (linkID == null) {
                            Log.d(TAG, "Server: Dropping packet from stale connection ${device.address}")
                            if (responseNeeded) {
                                sendResponse(
                                    device,
                                    requestId,
                                    BluetoothGatt.GATT_FAILURE,
                                    0,
                                    null
                                )
                            }
                            return
                        }
                        val packet = BitchatPacket.fromBinaryData(value)
                        if (packet != null) {
                            val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                            delegate?.onPacketReceived(packet, peerID, device, linkID)
                        } else {
                            Log.d(TAG, "Server: Failed to parse packet from ${device.address}, size: ${value.size} bytes")
                        }
                    
                        if (responseNeeded) {
                            sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                        }
                    }
                }
            }
            
            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                synchronized(this@BluetoothGattServerManager) {
                    // Guard against callbacks after service shutdown
                    if (!isCurrent()) {
                        return
                    }

                    val linkID = serverLinkIDs[device.address]
                    if (preparedWrite || offset != 0 || linkID == null ||
                        descriptor.uuid != AppConstants.Mesh.Gatt.DESCRIPTOR_UUID
                    ) {
                        if (responseNeeded) sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                        return
                    }
                    val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)
                    val disable = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE.contentEquals(value)
                    if (!enable && !disable) {
                        if (responseNeeded) sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                        return
                    }
                    if (enable && connectionTracker.addSubscribedDevice(device)) {

                        connectionScope.launch {
                            delay(100)
                            synchronized(this@BluetoothGattServerManager) {
                                if (isCurrent() && serverLinkIDs[device.address] == linkID &&
                                    connectionTracker.getSubscribedDevices().contains(device)
                                ) delegate?.onDeviceConnected(device)
                            }
                        }
                    }
                    if (disable) connectionTracker.removeSubscribedDevice(device)
                
                    if (responseNeeded) {
                        sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                    }
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                synchronized(this@BluetoothGattServerManager) {
                    if (!isCurrent()) return
                    delegate?.onGattServerNotificationComplete(
                        device.address,
                        serverLinkIDs[device.address],
                        status
                    )
                }
            }
        }
        
        // Proper cleanup sequencing to prevent race conditions
        gattServer?.let { server ->
            try {
                server.close()
            } catch (_: SecurityException) {
                Log.w(TAG, "Bluetooth permission revoked while replacing GATT server")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing existing GATT server: ${e.message}")
            }
        }

        if (!isActive) {
            return
        }
        
        // Create new server
        gattServer = bluetoothManager.openGattServer(context, serverCallback)
        if (gattServer == null) {
            stop()
            return
        }
        
        // Create characteristic with notification support
        characteristic = BluetoothGattCharacteristic(
            AppConstants.Mesh.Gatt.CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or 
            BluetoothGattCharacteristic.PROPERTY_WRITE or 
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or 
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        
        val descriptor = BluetoothGattDescriptor(
            AppConstants.Mesh.Gatt.DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic?.addDescriptor(descriptor)
        
        val service = BluetoothGattService(AppConstants.Mesh.Gatt.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        
        if (gattServer?.addService(service) != true) {
            stop()
            return
        }
        
        Log.i(TAG, "GATT server setup complete")
    }

    private fun sendResponse(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        try {
            gattServer?.sendResponse(device, requestId, status, offset, value)
        } catch (_: SecurityException) {
            // Permission may disappear between a callback and its Binder response.
            stop()
        }
    }
    
    /**
     * Start advertising
     */
    @Suppress("DEPRECATION")
    @Synchronized
    private fun startAdvertising() {
        // Respect debug setting
        val enabled = isServerRoleEnabled()

        // Guard conditions – never throw here to avoid crashing the app from a background coroutine
        if (!permissionManager.hasBluetoothPermissions()) {
            Log.w(TAG, "Not starting advertising: missing Bluetooth permissions")
            return
        }
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Not starting advertising: bluetoothAdapter is null")
            return
        }
        if (!isActive) {
            return
        }
        if (!enabled) {
            Log.d(TAG, "Not starting advertising: GATT Server disabled via debug settings")
            return
        }
        if (bleAdvertiser == null) {
            Log.w(TAG, "Not starting advertising: BLE advertiser not available on this device")
            return
        }
        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            Log.w(TAG, "Not starting advertising: multiple advertisement not supported on this device")
            return
        }

        val settings = powerManager.getAdvertiseSettings()
        
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID))
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
            
        // Add stable identity (first 8 bytes of peerID) to Scan Response
        // This allows scanners to deduplicate devices even if MAC address rotates
        val peerIDBytes = try {
            myPeerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray().take(8).toByteArray()
        } catch (e: Exception) {
            ByteArray(0)
        }
        
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(AppConstants.Mesh.Gatt.SERVICE_UUID), peerIDBytes)
            .setIncludeTxPowerLevel(false)
            .setIncludeDeviceName(false)
            .build()
        
        val advertisingGeneration = generation
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                if (!isActive || generation != advertisingGeneration || advertiseCallback !== this) return
                advertiseRetryCount = 0
                val mode = try {
                    powerManager.getPowerInfo().split("Current Mode: ")[1].split("\n")[0]
                } catch (_: Exception) { "unknown" }
                Log.i(TAG, "Advertising started (power mode: $mode)")
            }

            override fun onStartFailure(errorCode: Int) {
                if (!isActive || generation != advertisingGeneration || advertiseCallback !== this) return
                Log.e(TAG, "Advertising failed: $errorCode")
                // Previously this only logged, so if advertising failed this device became
                // undiscoverable until a manual BLE toggle. Retry transient failures with backoff.
                when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> Unit // already advertising, no retry
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> Unit // config issue, not retrying
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> Unit // unsupported, not retrying
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> {
                        scheduleAdvertiseRestart("too-many-advertisers")
                    }
                    ADVERTISE_FAILED_INTERNAL_ERROR -> {
                        scheduleAdvertiseRestart("internal-error")
                    }
                    else -> {
                        scheduleAdvertiseRestart("unknown-$errorCode")
                    }
                }
            }
        }
        
        try {
            bleAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException starting advertising (missing permission?): ${se.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting advertising: ${e.message}")
        }
    }
    
    /**
     * Stop advertising
     */
    @Suppress("DEPRECATION")
    private fun stopAdvertising() {
        val callback = advertiseCallback
        advertiseCallback = null
        if (bleAdvertiser == null) return
        try {
            callback?.let { cb -> bleAdvertiser.stopAdvertising(cb) }
        } catch (_: SecurityException) {
            Log.w(TAG, "Bluetooth permission revoked while stopping advertising")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping advertising: ${e.message}")
        }
    }
    
    /**
     * Schedule an advertising restart with incremental backoff after a transient failure.
     */
    private fun scheduleAdvertiseRestart(reason: String) {
        advertiseRetryCount++
        val delayMs = (ADVERTISE_RETRY_BASE_MS * advertiseRetryCount).coerceAtMost(ADVERTISE_MAX_RETRY_DELAY_MS)
        Log.w(TAG, "Scheduling advertising restart in ${delayMs}ms (attempt $advertiseRetryCount, reason=$reason)")
        val restartGeneration = generation
        advertiseRestartJob?.cancel()
        advertiseRestartJob = connectionScope.launch {
            delay(delayMs)
            if (isActive && generation == restartGeneration && isServerRoleEnabled()) {
                stopAdvertising()
                delay(100)
                if (generation == restartGeneration) startAdvertising()
            }
        }
    }

    /**
     * Restart advertising (for power mode changes)
     */
    fun restartAdvertising() {
        // Respect debug setting
        val enabled = isServerRoleEnabled()
        if (!isActive || !enabled) {
            stopAdvertising()
            return
        }

        val restartGeneration = generation
        advertiseRestartJob?.cancel()
        advertiseRestartJob = connectionScope.launch {
            stopAdvertising()
            delay(100)
            if (generation == restartGeneration) startAdvertising()
        }
    }
}
