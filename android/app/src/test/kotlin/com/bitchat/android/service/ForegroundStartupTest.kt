package com.bitchat.android.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35])
class ForegroundStartupTest {
    @Before
    fun setUp() {
        MeshServicePreferences.init(RuntimeEnvironment.getApplication())
        MeshServicePreferences.setBackgroundEnabled(true)
    }

    private fun permittedContext(): Context = mock<Context>().also {
        whenever(it.checkPermission(any(), any(), any())).thenReturn(PackageManager.PERMISSION_GRANTED)
    }

    @Test
    fun `background startup denied by system does not crash caller`() {
        val context = permittedContext()
        whenever(context.startForegroundService(any())).thenThrow(ForegroundServiceStartNotAllowedException("synthetic policy"))
        MeshForegroundService.start(context)
        verify(context).startForegroundService(any())
    }

    @Test
    fun `permission revoked between check and startup does not crash caller`() {
        val context = permittedContext()
        whenever(context.startForegroundService(any())).thenThrow(SecurityException("synthetic permission"))
        MeshForegroundService.start(context)
        verify(context).startForegroundService(any())
    }

    @Test
    fun `disabled background startup stops before creating transports`() {
        MeshServicePreferences.setBackgroundEnabled(false)
        val service = Robolectric.buildService(MeshForegroundService::class.java).get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent().setAction(MeshForegroundService.ACTION_START), 0, 1))
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `unrelated broadcast cannot start mesh`() {
        val context = mock<Context>()
        BootCompletedReceiver().onReceive(context, Intent("synthetic.UNRELATED"))
        verify(context, never()).startForegroundService(any())
        verify(context, never()).getSharedPreferences(any(), any())
    }
}
