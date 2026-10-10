package com.bitchat.android.onboarding

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.mesh.BluetoothPermissionManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NamedChannelPermissionsTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("bitchat_permissions", Context.MODE_PRIVATE)
            .edit().clear().commit()
        shadowOf(application).denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
    }

    @Test
    @Config(sdk = [31])
    fun `Android 12 discovers BLE peers without location permission`() {
        assertModernPermissions()
    }

    @Test
    fun `Android 13 discovers BLE peers without location permission`() {
        assertModernPermissions()
    }

    private fun assertModernPermissions() {
        val manager = PermissionManager(application)
        val nearby = listOf(Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        shadowOf(application).grantPermissions(*nearby.toTypedArray())
        assertFalse(manager.getRequiredPermissions().any { it.contains("LOCATION") })
        assertTrue(manager.areRequiredPermissionsGranted())
        assertTrue(BluetoothPermissionManager(application).hasBluetoothPermissions())
        assertFalse(manager.getCategorizedPermissions().any {
            it.type == PermissionType.PRECISE_LOCATION || it.type == PermissionType.BACKGROUND_LOCATION
        })
    }

    @Test
    @Config(sdk = [26])
    fun `Android 8 retains its system BLE scan permission only`() = assertLegacyPermissions()

    @Test
    @Config(sdk = [30])
    fun `Android 11 retains its system BLE scan permission only`() = assertLegacyPermissions()

    private fun assertLegacyPermissions() {
        val manager = PermissionManager(application)
        assertTrue(manager.getRequiredPermissions().contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertFalse(manager.needsBackgroundLocationPermission())
        assertNull(manager.getBackgroundLocationPermission())
        assertTrue(manager.getMissingBackgroundLocationPermission().isEmpty())
    }

    @Test
    fun `online only onboarding completes with no local discovery permissions`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).create().get()
        val manager = PermissionManager(activity)
        var complete = false
        OnboardingCoordinator(activity, manager, { complete = true }, { fail(it) })
            .skipLocalDiscovery()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150))
        assertTrue(complete)
        assertFalse(manager.isFirstTimeLaunch())
    }

    @Test
    fun `denied nearby permissions still completes online onboarding`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val activity = controller.get()
        val manager = PermissionManager(activity)
        shadowOf(application).denyPermissions(*manager.getRequiredPermissions().toTypedArray())
        var complete = false
        val coordinator = OnboardingCoordinator(activity, manager, { complete = true }, { fail(it) })
        controller.start().resume()
        coordinator.requestPermissions()
        val pending = shadowOf(activity).lastRequestedPermission
        val requested = pending.requestedPermissions
        assertFalse(requested.any { it.contains("LOCATION") })
        activity.activityResultRegistry.dispatchResult(
            pending.requestCode, android.app.Activity.RESULT_OK,
            Intent().putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, requested)
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                    IntArray(requested.size) { android.content.pm.PackageManager.PERMISSION_DENIED })
        )
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150))
        assertTrue(complete)
        assertFalse(manager.isFirstTimeLaunch())
    }
}
