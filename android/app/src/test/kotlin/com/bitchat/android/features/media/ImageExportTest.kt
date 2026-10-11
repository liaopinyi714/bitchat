package com.bitchat.android.features.media

import android.app.Application
import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ImageExportTest {
    @get:Rule val files = TemporaryFolder()
    private val resolver = mock<ContentResolver>()
    private val uri = Uri.parse("content://media/external/downloads/1")

    @Test fun `successful export publishes bytes only after the stream closes`() {
        val source = files.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        whenever(resolver.insert(any(), any())).thenReturn(uri)
        whenever(resolver.openOutputStream(uri)).thenReturn(output)
        whenever(resolver.update(eq(uri), any(), isNull(), isNull())).thenAnswer {
            assertTrue(closed)
            assertArrayEquals(source.readBytes(), output.toByteArray())
            assertEquals(0, it.getArgument<ContentValues>(1).getAsInteger(MediaStore.Downloads.IS_PENDING))
            1
        }
        assertTrue(ImageExport.saveToDownloads(resolver, source))
        verify(resolver, never()).delete(any(), anyOrNull(), anyOrNull())
    }

    @Test fun `missing output stream removes the pending item and reports failure`() {
        val source = files.newFile()
        whenever(resolver.insert(any(), any())).thenReturn(uri)
        assertFalse(ImageExport.saveToDownloads(resolver, source))
        verify(resolver).delete(uri, null, null)
        verify(resolver, never()).update(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test fun `failed copy removes the pending item without publishing it`() {
        val source = files.newFile().apply { writeText("synthetic image bytes") }
        whenever(resolver.insert(any(), any())).thenReturn(uri)
        whenever(resolver.openOutputStream(uri)).thenReturn(object : OutputStream() {
            override fun write(value: Int) { throw IOException("synthetic write failure") }
        })
        assertFalse(ImageExport.saveToDownloads(resolver, source))
        verify(resolver).delete(uri, null, null)
        verify(resolver, never()).update(any(), any(), anyOrNull(), anyOrNull())
    }

    @Test fun `failed publication removes the completed but still pending item`() {
        val source = files.newFile().apply { writeText("synthetic image bytes") }
        whenever(resolver.insert(any(), any())).thenReturn(uri)
        whenever(resolver.openOutputStream(uri)).thenReturn(ByteArrayOutputStream())
        whenever(resolver.update(eq(uri), any(), isNull(), isNull())).thenReturn(0)
        assertFalse(ImageExport.saveToDownloads(resolver, source))
        verify(resolver).delete(uri, null, null)
    }
}
