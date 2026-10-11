package com.bitchat.android.features.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageUtilsTest {
    @get:Rule val files = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `camera image preparation samples before resizing without upscaling small inputs`() {
        assertEquals(8, ImageUtils.sampleSize(8000, 6000, 512, 512))
        assertEquals(1, ImageUtils.sampleSize(320, 240, 512, 512))
    }

    @Test fun `decode budget remains bounded for huge square and panoramic metadata`() {
        for ((width, height) in listOf(8000 to 6000, Int.MAX_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to 1)) {
            val sample = ImageUtils.sampleSize(width, height, Int.MAX_VALUE, Int.MAX_VALUE)
            assertTrue(sample > 0)
            assertEquals(0, sample and (sample - 1))
            val decodedPixels = ((width.toLong() + sample - 1) / sample) * ((height.toLong() + sample - 1) / sample)
            assertTrue(decodedPixels <= 4_194_304L)
        }
    }

    @Test fun `outgoing path and content uri preserve aspect and use distinct output files`() {
        val source = createImage(1024, 512)
        val first = requireNotNull(ImageUtils.downscalePathAndSaveToAppFiles(context, source.path))
        val second = requireNotNull(ImageUtils.downscaleAndSaveToAppFiles(context, Uri.fromFile(source)))
        try {
            assertNotEquals(first, second)
            for (path in listOf(first, second)) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                assertEquals(512, bounds.outWidth)
                assertEquals(256, bounds.outHeight)
            }
            assertTrue(source.exists())
        } finally { File(first).delete(); File(second).delete() }
    }

    @Test fun `small image retains its dimensions`() {
        val source = createImage(80, 40)
        val output = requireNotNull(ImageUtils.downscalePathAndSaveToAppFiles(context, source.path))
        try {
            val bitmap = requireNotNull(BitmapFactory.decodeFile(output))
            assertEquals(80, bitmap.width)
            assertEquals(40, bitmap.height)
            bitmap.recycle()
        } finally { File(output).delete() }
    }

    @Test fun `invalid input fails without leaving an outgoing file`() {
        val source = files.newFile().apply { writeText("synthetic invalid image") }
        val outgoing = File(context.filesDir, "images/outgoing")
        val before = outgoing.list()?.toSet().orEmpty()
        assertNull(ImageUtils.downscalePathAndSaveToAppFiles(context, source.path))
        assertNull(ImageUtils.downscalePathAndSaveToAppFiles(context, source.path, maxDim = 0))
        assertEquals(before, outgoing.list()?.toSet().orEmpty())
    }

    @Test fun `EXIF orientations preserve mirrored corners and rotate dimensions`() {
        val source = files.newFile("synthetic-oriented.jpg")
        val original = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888)
        for (y in 0 until 40) for (x in 0 until 60) {
            original.setPixel(x, y, when {
                x < 30 && y < 20 -> android.graphics.Color.RED
                x >= 30 && y < 20 -> android.graphics.Color.GREEN
                x < 30 -> android.graphics.Color.BLUE
                else -> android.graphics.Color.YELLOW
            })
        }
        source.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        original.recycle()
        val base = requireNotNull(BitmapFactory.decodeFile(source.path))
        val topLeftSource = mapOf(2 to (59 to 0), 3 to (59 to 39), 4 to (0 to 39),
            5 to (0 to 0), 6 to (0 to 39), 7 to (59 to 39), 8 to (59 to 0))
        try {
            for ((orientation, corner) in topLeftSource) {
                ExifInterface(source).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    saveAttributes()
                }
                val decoded = requireNotNull(ImageUtils.loadBitmapWithExifOrientation(source.path))
                try {
                    assertEquals(if (orientation >= 5) 40 else 60, decoded.width)
                    assertEquals(if (orientation >= 5) 60 else 40, decoded.height)
                    assertEquals("EXIF orientation $orientation", base.getPixel(corner.first, corner.second), decoded.getPixel(0, 0))
                } finally { decoded.recycle() }
            }
        } finally { base.recycle() }
    }

    private fun createImage(width: Int, height: Int): File {
        val source = files.newFile("synthetic.png")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return source
    }
}
