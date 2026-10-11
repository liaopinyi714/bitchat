package com.bitchat.android.mesh

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Abstract base tracker for mesh connections (BLE, Wi-Fi Aware, etc.)
 * Encapsulates common state machine logic:
 * - Connection attempt tracking (retries, backoff)
 * - Pending connection management
 * - Automatic cleanup of expired attempts
 */
abstract class MeshConnectionTracker(
    private val scope: CoroutineScope,
    protected val tag: String
) {
    companion object {
        const val CONNECTION_RETRY_DELAY = 5_000L
        const val MAX_CONNECTION_ATTEMPTS = 3
        const val CLEANUP_INTERVAL = 30_000L
    }

    /**
     * Connection attempt tracking with automatic expiry
     */
    protected data class ConnectionAttempt(
        val attempts: Int,
        val lastAttempt: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean =
            System.currentTimeMillis() - lastAttempt > CONNECTION_RETRY_DELAY * 2

        fun shouldRetry(): Boolean =
            attempts < MAX_CONNECTION_ATTEMPTS &&
                    System.currentTimeMillis() - lastAttempt > CONNECTION_RETRY_DELAY
    }

    // Tracks in-progress or failed attempts
    protected val pendingConnections = ConcurrentHashMap<String, ConnectionAttempt>()

    @Volatile private var isActive = false
    private var cleanupJob: Job? = null

    /**
     * Start the tracker and its cleanup loop
     */
    @Synchronized
    open fun start() {
        if (isActive) return
        isActive = true
        startPeriodicCleanup()
    }

    /**
     * Stop the tracker
     */
    @Synchronized
    open fun stop() {
        isActive = false
        cleanupJob?.cancel()
        cleanupJob = null
        pendingConnections.clear()
    }

    /**
     * Check if a connection attempt is allowed for this peer/address
     */
    fun isConnectionAttemptAllowed(id: String): Boolean {
        // If already connected, usually no need to retry (subclasses can override logic if needed,
        // but typically the caller checks isConnected() first).
        
        val existingAttempt = pendingConnections[id]
        return existingAttempt?.let {
            it.isExpired() || it.shouldRetry()
        } ?: true
    }

    /**
     * Record a new connection attempt.
     * Returns true if the attempt was recorded (allowed), false if skipped.
     */
    fun addPendingConnection(id: String): Boolean {
        synchronized(pendingConnections) {
            val currentAttempt = pendingConnections[id]
            
            // If strictly not allowed right now, reject
            if (currentAttempt != null && !currentAttempt.isExpired() && !currentAttempt.shouldRetry()) {
                Log.d(tag, "Connection attempt already in progress for $id")
                return false
            }

            // Update attempt count
            // Reset to 1 if expired, otherwise increment
            val attempts = if (currentAttempt?.isExpired() == true) 1 else (currentAttempt?.attempts ?: 0) + 1
            pendingConnections[id] = ConnectionAttempt(attempts)
            Log.d(tag, "Added pending connection for $id (attempts: $attempts)")
            return true
        }
    }

    /**
     * Remove a pending attempt (e.g., on success or fatal error)
     */
    fun removePendingConnection(id: String) {
        pendingConnections.remove(id)
    }

    /**
     * Abstract: Subclasses must define what "connected" means
     */
    abstract fun isConnected(id: String): Boolean

    /**
     * Abstract: Subclasses must implement disconnect logic
     */
    abstract fun disconnect(id: String)

    /**
     * Abstract: Subclasses report their active connection count
     */
    abstract fun getConnectionCount(): Int

    private fun startPeriodicCleanup() {
        cleanupJob = scope.launch {
            while (isActive) {
                try {
                    delay(CLEANUP_INTERVAL)
                    if (!isActive) break

                    // Clean up expired pending connections
                    val expired = pendingConnections.filter { it.value.isExpired() }
                    // Do not erase a replacement attempt recorded after this snapshot.
                    expired.forEach { (id, attempt) -> pendingConnections.remove(id, attempt) }

                    if (expired.isNotEmpty()) {
                        Log.d(tag, "Cleaned up ${expired.size} expired connection attempts")
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w(tag, "Error in periodic cleanup: ${e.message}")
                }
            }
        }
    }
}
