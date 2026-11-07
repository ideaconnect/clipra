package tech.idct.clipra.core.engine.render

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.FrameRate
import kotlin.math.abs

/**
 * The preview's canvas clips (#227): one per project frame rate, a frame every 1/rate s for
 * ten minutes, with a key frame a second for quick seeks. Logs how long each took to make.
 */
@RunWith(AndroidJUnit4::class)
class CanvasClipsTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyFrameRateGetsAClipAtItsRate() {
        for (rate in FrameRate.entries) {
            CanvasClips.forget(context, rate.fps)
            val started = SystemClock.elapsedRealtime()
            val clip = CanvasClips.make(context, rate.fps)
            val madeMs = SystemClock.elapsedRealtime() - started
            val times = ArrayList<Long>()
            var keys = 0
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(clip.file.path)
                val format = extractor.getTrackFormat(0)
                assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
                assertEquals(CanvasClips.SIDE, format.getInteger(MediaFormat.KEY_WIDTH))
                extractor.selectTrack(0)
                while (extractor.sampleTime >= 0) {
                    times += extractor.sampleTime
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) keys++
                    extractor.advance()
                }
            } finally {
                extractor.release()
            }
            Log.i(
                TAG,
                "${rate.fps} fps: made in $madeMs ms, ${clip.file.length()} bytes, " +
                    "${times.size} frames, $keys key frames, ${clip.durationUs} us",
            )
            val frames = (CanvasClips.DURATION_US * rate.fps / 1_000_000L).toInt()
            assertEquals("${rate.fps} fps frames", frames, times.size)
            val frameUs = 1_000_000.0 / rate.fps
            times.sorted().forEachIndexed { i, timeUs ->
                assertEquals("${rate.fps} fps, frame $i", i * frameUs, timeUs.toDouble(), 2.0)
            }
            assertEquals("${rate.fps} fps key frames", frames / rate.fps, keys)
            val offUs = clip.durationUs - CanvasClips.DURATION_US
            assertTrue("${rate.fps} fps: ${clip.durationUs} us", abs(offUs) <= frameUs)
            // A second call finds the same clip.
            assertEquals(clip, CanvasClips.make(context, rate.fps))
        }
    }

    private companion object {
        const val TAG = "CanvasClips"
    }
}
