package com.bitchat.android.features.media

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import java.io.File

internal object ImageExport {
    /** Called on an I/O worker. A failed write never publishes an empty or pending item. */
    fun saveToDownloads(resolver: ContentResolver, source: File): Boolean {
        var pending: Uri? = null
        return try {
            val mime = when (source.extension.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, source.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }))
            pending = uri
            requireNotNull(resolver.openOutputStream(uri)).use { output -> source.inputStream().use { it.copyTo(output) } }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) > 0)
            pending = null
            true
        } catch (_: Exception) {
            false
        } finally {
            pending?.let { runCatching { resolver.delete(it, null, null) } }
        }
    }
}
