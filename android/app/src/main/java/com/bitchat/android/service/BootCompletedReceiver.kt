package com.bitchat.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Ensure preferences are initialized on cold boot before reading values
        try { MeshServicePreferences.init(context.applicationContext) } catch (_: Exception) { }

        if (MeshServicePreferences.isAutoStartEnabled(true)) {
            try {
                MeshForegroundService.start(context.applicationContext)
            } catch (_: android.app.ForegroundServiceStartNotAllowedException) {
                // OEM/background policy can reject boot startup; opening the app retries it.
                android.util.Log.w("BootCompletedReceiver", "System deferred mesh startup until the app is opened")
            } catch (_: SecurityException) {
                android.util.Log.w("BootCompletedReceiver", "Mesh startup requires nearby-device permission")
            }
        }
    }
}
