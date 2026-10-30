package tech.idct.clipra.core.engine.render

import android.graphics.Color
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.AudioDecoder
import tech.idct.clipra.core.model.Speed
import tech.idct.clipra.core.model.SpeedCurve
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * Speed (#22) in the export, which the preview shares: at 2x the tone plays in half the time at
 * the same pitch, and a ramp's sound fills its clip.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SpeedAudioTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var tone: File

    @Before
    fun copyMedia() {
        tone = File(context.cacheDir, "tone.m4a")
        instrumentation.context.assets.open("golden/tone.m4a").use { input ->
            tone.outputStream().use { input.copyTo(it) }
        }
    }

    @Test
    fun atTwiceTheSpeedThePitchIsUnchanged() {
        val source = pitch(tone, 500_000, 2_500_000)
        val fast = export(Speed(rate = 2.0))
        val pitch = pitch(fast, 200_000, 1_200_000)
        Log.i(TAG, "pitch source $source Hz, at 2x $pitch Hz")
        assertEquals(1.0, pitch / source, 0.03)
        // Three seconds of tone play in one and a half.
        assertTrue(rms(fast, 1_250_000, 1_450_000) > rms(tone, 1_000_000, 2_000_000) * 0.5)
    }

    @Test
    fun aRampsSoundFillsItsClip() {
        val ramp = Speed(ramp = "montage", rampSteps = SpeedCurve.stepsFor(SPAN_US))
        val exported = export(ramp)
        val length = SpeedCurve.timelineDuration(ramp, SPAN_US)
        val start = rms(exported, 100_000, 600_000)
        val end = rms(exported, length - 600_000, length - 100_000)
        Log.i(TAG, "montage $length us: rms start $start, end $end")
        assertTrue("start $start, end $end", end > start * 0.5)
        assertEquals(
            1.0,
            pitch(exported, 100_000, length - 100_000) / pitch(tone, 0, SPAN_US),
            0.05,
        )
    }

    /** Three seconds of the tone as a project, played at [speed]. */
    private fun export(speed: Speed): File {
        val duration = SpeedCurve.timelineDuration(speed, SPAN_US)
        val timeline = RenderTimeline(
            width = 320,
            height = 240,
            frameRate = 30,
            durationUs = duration,
            background = Color.BLACK,
            layers = listOf(
                RenderLayer(
                    listOf(
                        RenderClip(
                            "tone",
                            0,
                            duration,
                            RenderSource.Audio(tone.path),
                            speed = speed,
                            sourceSpanUs = SPAN_US,
                        ),
                    ),
                ),
            ),
        )
        val output = File(context.cacheDir, "speed-${speed.hashCode()}.mp4").apply { delete() }
        val done = CountDownLatch(1)
        val error = AtomicReference<ExportException?>()
        instrumentation.runOnMainSync {
            Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.countDown()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        error.set(exportException)
                        done.countDown()
                    }
                })
                .build()
                .start(CompositionFactory.create(context, timeline), output.path)
        }
        assertTrue("export timed out", done.await(5, TimeUnit.MINUTES))
        error.get()?.let { throw it }
        return output
    }

    /** The tone's frequency in [file] between two times, from its rising zero crossings. */
    private fun pitch(file: File, fromUs: Long, toUs: Long): Double {
        var crossings = 0
        var first = -1L
        var last = -1L
        var previous = 0
        var index = 0L
        var rate = 0
        AudioDecoder.decode(context, file.path, fromUs, toUs) { samples, count, format, _ ->
            rate = format.sampleRate
            // The first channel.
            var i = 0
            while (i < count) {
                val s = samples[i].toInt()
                if (previous < 0 && s >= 0) {
                    crossings++
                    if (first < 0) first = index
                    last = index
                }
                previous = s
                index++
                i += format.channels
            }
        }
        assertTrue("no tone in ${file.name}", crossings > 10 && rate > 0)
        return (crossings - 1) * rate.toDouble() / (last - first)
    }

    /** The loudness (root mean square, 0 to 1) of [file] between two times. */
    private fun rms(file: File, fromUs: Long, toUs: Long): Double {
        var sum = 0.0
        var n = 0L
        AudioDecoder.decode(context, file.path, fromUs, toUs) { samples, count, _, _ ->
            for (i in 0 until count) {
                val s = samples[i] / 32768.0
                sum += s * s
            }
            n += count
        }
        return sqrt(sum / maxOf(1, n))
    }

    private companion object {
        const val TAG = "SpeedAudio"
        const val SPAN_US = 3_000_000L
    }
}
