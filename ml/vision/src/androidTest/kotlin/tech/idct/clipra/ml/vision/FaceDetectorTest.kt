package tech.idct.clipra.ml.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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

    /**
     * A face about 70 px wide in a 1280 × 720 frame (#182): too small in all of it, found in a
     * region around it, and boxed in fractions of the whole frame.
     */
    @Test
    fun findsASmallFaceInARegion() {
        FaceDetector(context).use { detector ->
            val portrait = portrait()
            val face = detector.detect(portrait).single().box
            val frame = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            frame.eraseColor(Color.rgb(90, 100, 110))
            val at = Rect(800, 300, 800 + 287, 300 + 358)
            Canvas(frame).drawBitmap(portrait, null, at, Paint(Paint.FILTER_BITMAP_FLAG))
            assertTrue(detector.detect(frame).isEmpty())

            val found = detector.detect(frame, region = Rect(760, 200, 1120, 560))
            Log.i(TAG, "small $found")
            val box = found.single().box
            assertEquals((at.left + face.centerX() * at.width()) / 1280, box.centerX(), 0.01f)
            assertEquals((at.top + face.centerY() * at.height()) / 720, box.centerY(), 0.015f)
            assertEquals(face.width() * at.width() / 1280, box.width(), 0.01f)
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
