package com.bitchat.android.features.media

import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns temporary image files until the captured recipient accepts the prepared image. */
internal object ImagePreparation {
    fun start(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
        sourceToDelete: File? = null,
        captureReceiver: () -> (String) -> Unit,
        prepare: () -> String?
    ): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        var preparedPath: String? = null
        var delivered = false
        try {
            // Capture the conversation on the caller's UI thread before decoding can suspend.
            val receiver = captureReceiver()
            withContext(dispatcher) {
                // Assign inside the worker: cancellation may discard withContext's result while
                // returning to the UI, but the file still belongs to this operation.
                preparedPath = prepare()
            }
            preparedPath?.takeIf(String::isNotBlank)?.let {
                receiver(it)
                delivered = true
            }
        } finally {
            // UNDISPATCHED startup also reaches this cleanup when the scope was already
            // cancelled, before a queued decoder has had the chance to start.
            withContext(NonCancellable + dispatcher) {
                sourceToDelete?.let { runCatching { it.delete() } }
                if (!delivered) {
                    preparedPath?.takeIf(String::isNotBlank)?.let {
                        runCatching { File(it).delete() }
                    }
                }
            }
        }
    }
}
