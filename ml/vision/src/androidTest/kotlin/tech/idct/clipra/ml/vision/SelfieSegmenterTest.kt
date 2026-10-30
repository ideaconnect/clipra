package tech.idct.clipra.ml.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * The selfie segmenter (#50) finds the person in MediaPipe's own test portrait (a public-domain
 * photo, fetched once and checked with SHA-256) and nothing in an empty picture.
 */
@RunWith(AndroidJUnit4::class)
class SelfieSegmenterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun findsThePersonAndNotTheBackground() {
        SelfieSegmenter(context).use { segmenter ->
            assertArrayEquals(intArrayOf(1, SIZE, SIZE, 3), segmenter.inputShape)
            assertArrayEquals(intArrayOf(1, SIZE, SIZE, 1), segmenter.outputShape)

            val person = FloatArray(SIZE * SIZE)
            segmenter.segment(pixels(portrait()), person)
            assertTrue(person.all { it in 0f..1f })
            // The face, the tie, and the curtain and flag on either side, in the 820 × 1024
            // portrait squeezed to 256 × 256.
            val face = person[at(x = 400, y = 200)]
            val tie = person[at(x = 360, y = 520)]
            val curtain = person[at(x = 160, y = 160)]
            val flag = person[at(x = 760, y = 300)]
            Log.i(TAG, "face $face tie $tie curtain $curtain flag $flag")
            assertTrue("face $face", face > 0.9f)
            assertTrue("tie $tie", tie > 0.9f)
            assertTrue("curtain $curtain", curtain < 0.1f)
            assertTrue("flag $flag", flag < 0.1f)

            val empty = FloatArray(SIZE * SIZE)
            val wall = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            wall.eraseColor(Color.rgb(120, 60, 50))
            segmenter.segment(pixels(wall), empty)
            assertEquals(0.0, empty.average(), 0.05)
        }
    }

    /** The portrait's pixel ([x], [y]) in the squeezed picture. */
    private fun at(x: Int, y: Int) = (y * SIZE / HEIGHT) * SIZE + x * SIZE / WIDTH

    private fun pixels(bitmap: Bitmap): IntArray {
        val square = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        return IntArray(SIZE * SIZE).also { square.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
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
        const val TAG = "SelfieSegmenterTest"
        const val SIZE = SelfieSegmenter.SIZE
        const val WIDTH = 820
        const val HEIGHT = 1024
        const val PORTRAIT_URL = "https://storage.googleapis.com/mediapipe-assets/portrait.jpg"
        const val PORTRAIT_SHA256 =
            "a6f11efaa834706db23f275b6115058fa87fc7f14362681e6abe14e82749de3e"
    }
}
