package com.bitchat.android.ui.media

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bitchat.android.features.media.ImageUtils
import com.bitchat.android.features.media.ImagePreparation
import com.bitchat.android.ui.ComposerActionSurface
import com.bitchat.android.ui.ComposerIconSize
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImagePickerButton(
    modifier: Modifier = Modifier,
    onImagePreparationStarted: () -> (String) -> Unit
) {
    val context = LocalContext.current
    val preparationScope = rememberCoroutineScope()
    val captureReceiver by rememberUpdatedState(onImagePreparationStarted)
    var capturedImagePath by remember { mutableStateOf<String?>(null) }
    
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            ImagePreparation.start(preparationScope, imageDecodeDispatcher, captureReceiver = captureReceiver) {
                ImageUtils.downscaleAndSaveToAppFiles(context, uri)
            }
        }
    }
    
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val path = capturedImagePath
        if (success && !path.isNullOrBlank()) {
            ImagePreparation.start(preparationScope, imageDecodeDispatcher, File(path), captureReceiver) {
                ImageUtils.downscalePathAndSaveToAppFiles(context, path)
            }
        } else {
            // Cleanup on cancel/failure
            path?.let { runCatching { File(it).delete() } }
        }
        capturedImagePath = null
    }

    fun startCameraCapture() {
        try {
            val dir = File(context.filesDir, "images/outgoing").apply { mkdirs() }
            val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            capturedImagePath = file.absolutePath
            takePictureLauncher.launch(uri)
        } catch (e: Exception) {
            android.util.Log.e("ImagePickerButton", "Camera capture failed", e)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraCapture()
        }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Shares the composer's button treatment so camera, microphone and send read as one set.
    ComposerActionSurface(
        isActive = false,
        isPressed = isPressed,
        modifier = modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = { imagePicker.launch("image/*") },
            onLongClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    startCameraCapture()
                } else {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        )
    ) { tint ->
        Icon(
            imageVector = Icons.Filled.PhotoCamera,
            contentDescription = stringResource(com.bitchat.android.R.string.pick_image),
            tint = tint,
            modifier = Modifier.size(ComposerIconSize)
        )
    }

    // No custom preview: native camera UI handles confirmation
}
