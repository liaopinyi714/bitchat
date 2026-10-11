package com.bitchat.android.ui.media

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.bitchat.android.features.media.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Bound concurrent decodes during a fast scroll. Bitmap ownership stays with Compose;
// recycling an image still referenced by a render frame would race the renderer.
internal val imageDecodeDispatcher = Dispatchers.IO.limitedParallelism(2)

@Composable
internal fun rememberImageBitmap(path: String, width: Int, height: Int): State<Bitmap?> =
    produceState<Bitmap?>(null, path, width, height) {
        value = null
        var decoded: Bitmap? = null
        try {
            withContext(imageDecodeDispatcher) {
                decoded = ImageUtils.decodeSampledBitmap(path, width.coerceAtLeast(1), height.coerceAtLeast(1))
            }
            value = decoded
            decoded = null // Compose now owns the bitmap.
        } finally {
            // A cancelled scroll/pager request may finish decoding before dispatching back.
            // It was never published, so this particular bitmap can be released immediately.
            decoded?.recycle()
        }
    }
