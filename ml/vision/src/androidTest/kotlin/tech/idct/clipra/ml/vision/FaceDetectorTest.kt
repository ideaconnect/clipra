package tech.idct.clipra.ml.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * The face detector (#52) finds the face in MediaPipe's test portrait (public domain, fetched
 * once and checked with SHA-256) where it is, also in a crop that moves it, and none on a wall.
 */
@RunWith(AndroidJUnit4::class)
class FaceDetectorTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun findsTheFaceWhereItIs() {
        FaceDetector(context).use { detector ->
            val portrait = portrait()
            val faces = detector.detect(portrait)
            Log.i(TAG, "faces $faces")
            assertEquals(1, faces.size)
            val box = faces.single().box
            // The face is around (400, 200) of the 820 × 1024 portrait.
            assertEquals(400f / 820, box.centerX(), 0.05f)
            assertEquals(200f / 1024, box.centerY(), 0.05f)
            assertTrue("width ${box.width()}", box.width() in 0.15f..0.4f)

            // The right part of the picture: the face is now further left.
            val crop = Bitmap.createBitmap(portrait, 250, 0, 570, 700)
            val moved = detector.detect(crop).single().box
            assertEquals(150f / 570, moved.centerX(), 0.05f)
            assertEquals(200f / 700, moved.centerY(), 0.05f)

            val wall = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
            wall.eraseColor(Color.rgb(120, 60, 50))
            assertTrue(detector.detect(wall).isEmpty())
        }
    }

    private fun portrait(): Bitmap {
        val file = File(context.cacheDir, "portrait.jpg")
        if (!file.exists() || sha256(file) != PORTRAIT_SHA256) {
            URL(PORTRAIT_URL).openStream().use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        assertEquals(PORTRAIT_SHA256, sha256(file))
        return BitmapFactory.decodeFile(file.path)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "FaceDetectorTest"
        const val PORTRAIT_URL = "https://storage.googleapis.com/mediapipe-assets/portrait.jpg"
        const val PORTRAIT_SHA256 =
            "a6f11efaa834706db23f275b6115058fa87fc7f14362681e6abe14e82749de3e"
    }
}
