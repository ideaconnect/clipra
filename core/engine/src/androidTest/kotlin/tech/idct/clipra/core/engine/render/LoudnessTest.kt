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
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.AudioDecoder
import tech.idct.clipra.core.engine.media.Loudness
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.LoudnessTargets
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Loudness normalization (#37): clips measured on the device and exported at a target come out
 * within 1 LU of it, with peaks held near the -1 dBTP ceiling.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LoudnessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun aQuietToneReachesEachTarget() {
        val tone = copy("golden/tone.m4a")
        for (target in listOf(LoudnessTargets.SOCIAL, LoudnessTargets.BROADCAST)) {
            check(tone, 3_000_000, target)
        }
    }

    @Test
    fun speechWithLoudPeaksReachesTheTargetUnderTheCeiling() {
        val speech = copy("golden/speechy.m4a")
        val peak = check(speech, 12_000_000, LoudnessTargets.SOCIAL)
        // AAC encoding may overshoot the limiter's ceiling a little.
        assertTrue("peak $peak dBTP", peak < 0f)
    }

    /** Normalizes [file] to [target], exports it, and returns the export's true peak. */
    private fun check(file: File, durationUs: Long, target: Int): Float {
        val measured = Loudness.measure(context, file.path, 0, durationUs)!!
        val audio = AudioSettings(
            loudnessTarget = target,
            measuredLufs = measured.lufs,
            truePeakDb = measured.truePeakDb,
        )
        val output = export(file, durationUs, audio)
        val result = Loudness.measure(context, output.path, 0, durationUs)!!
        var channels = 0
        AudioDecoder.decode(context, output.path, 0, 100_000) { _, _, format, _ ->
            channels = format.channels
        }
        Log.i(
            TAG,
            "${file.name} at $target: measured ${measured.lufs} LUFS, " +
                "peak ${measured.truePeakDb}; export ${result.lufs} LUFS, " +
                "peak ${result.truePeakDb}, $channels channels",
        )
        assertEquals(target.toDouble(), result.lufs.toDouble(), 1.0)
        return result.truePeakDb
    }

    private fun export(file: File, durationUs: Long, audio: AudioSettings): File {
        val timeline = RenderTimeline(
            width = 320,
            height = 240,
            frameRate = 30,
            durationUs = durationUs,
            background = Color.BLACK,
            layers = listOf(
                RenderLayer(
                    listOf(
                        RenderClip(
                            "sound",
                            0,
                            durationUs,
                            RenderSource.Audio(file.path),
                            audio = audio,
                        ),
                    ),
                ),
            ),
        )
        val output = File(context.cacheDir, "loudness-${file.name}-${audio.loudnessTarget}.mp4")
        output.delete()
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

    private fun copy(asset: String): File {
        val file = File(context.cacheDir, asset.substringAfterLast('/'))
        instrumentation.context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private companion object {
        const val TAG = "Loudness"
    }
}
