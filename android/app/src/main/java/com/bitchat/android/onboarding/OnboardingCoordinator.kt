package com.bitchat.android.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Local discovery and notifications are optional; online channels require neither. */
class OnboardingCoordinator(
    private val activity: ComponentActivity,
    private val permissionManager: PermissionManager,
    private val onOnboardingComplete: () -> Unit,
    private val onOnboardingFailed: (String) -> Unit
) {
    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { completeOnboarding() }

    fun startOnboarding() {
        if (permissionManager.areRequiredPermissionsGranted()) completeOnboarding()
    }

    fun requestPermissions() {
        val permissions = (permissionManager.getMissingPermissions() +
            permissionManager.getUnrequestedOptionalPermissions()).distinct()
        permissionManager.markOptionalPermissionsRequested(permissions)
        if (permissions.isEmpty()) completeOnboarding()
        else permissionLauncher.launch(permissions.toTypedArray())
    }

    fun skipLocalDiscovery() = completeOnboarding()

    private fun completeOnboarding() {
        permissionManager.markOnboardingComplete()
        activity.lifecycleScope.launch {
            delay(100)
            onOnboardingComplete()
        }
    }

    fun openAppSettings() {
        try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", activity.packageName, null)
            })
        } catch (error: Exception) {
            onOnboardingFailed(error.message ?: "Unable to open app settings")
        }
    }

    fun getDiagnostics(): String = permissionManager.getPermissionDiagnostics()
}
