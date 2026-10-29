package tech.idct.clipra.core.engine.render

import android.graphics.Color
import android.os.Build
import android.os.SystemClock
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
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.Waveforms
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/**
 * The mix (#36): a clip's volume and fades are heard in the export (the preview plays the same
 * composition), and waveforms come quickly.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class AudioMixTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var tone: File
    private lateinit var minute: File

    @Before
    fun copyMedia() {
        tone = copy("golden/tone.m4a")
        minute = copy("golden/minute.m4a")
    }

    @Test
    fun volumeIsHeardInTheExport() {
        val full = rms(export(AudioSettings(volume = 1f)), 1_000_000, 2_500_000)
        val half = rms(export(AudioSettings(volume = 0.5f)), 1_000_000, 2_500_000)
        Log.i(TAG, "rms source ${rms(tone, 1_000_000, 2_500_000)}, full $full, half $half")
        assertEquals(0.5, half / full, 0.08)
    }

    @Test
    fun aFadeInStartsQuiet() {
        val faded = export(AudioSettings(fadeInUs = 2_000_000))
        val early = rms(faded, 100_000, 400_000)
        val late = rms(faded, 2_200_000, 2_800_000)
        Log.i(TAG, "fade early $early, late $late")
        assertTrue("early $early, late $late", early < late * 0.35)
    }

    @Test
    fun aMinuteOfSoundGivesItsWaveformWithinTwoSeconds() {
        val cache =
            MediaCache(File(context.cacheDir, "waveform-test").apply { deleteRecursively() })
        val asset = MediaAsset("minute", minute.path, MediaKind.Audio, durationUs = 60_000_000)
        val media = object : MediaResolver {
            override fun source(asset: MediaAsset) = minute.path

            override fun image(asset: MediaAsset) = null

            override fun text(
                spec: tech.idct.clipra.core.model.TextSpec,
                card: Boolean,
                width: Int,
                height: Int,
            ) = null
        }
        var callbackNs = 0L
        var samples = 0L
        val decodeStart = SystemClock.elapsedRealtime()
        AudioDecoder.decode(context, minute.path) { _, count, _, _ ->
            val t = System.nanoTime()
            samples += count
            callbackNs += System.nanoTime() - t
        }
        Log.i(
            TAG,
            "decode only: ${SystemClock.elapsedRealtime() - decodeStart} ms, $samples samples",
        )
        val started = SystemClock.elapsedRealtime()
        val waveform = Waveforms.waveform(context, asset, cache, media)!!
        val tookMs = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "waveform of a minute: $tookMs ms, ${waveform.peaks.size} slices")
        // The emulator's software decoder runs in a shared codec process, about four times
        // slower than a phone's; imports start the waveform in the background anyway.
        val emulator = Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")
        val limitMs = if (emulator) 6_000 else 2_000
        assertTrue("took $tookMs ms", tookMs < limitMs)
        // 50 slices a second; the tone sits at half scale.
        assertEquals(3_000.0, waveform.peaks.size.toDouble(), 30.0)
        // The tone peaks at a sixteenth of full scale (ffmpeg's sine at half volume).
        assertEquals(0.0625, waveform.peakAt(30_000_000).toDouble(), 0.02)
        // Cached: the second time reads the file.
        assertTrue(cache.waveform("minute").exists())
    }

    /** The tone on its own as a 3-second project, exported with [audio]. */
    private fun export(audio: AudioSettings): File {
        val timeline = RenderTimeline(
            width = 320,
            height = 240,
            frameRate = 30,
            durationUs = 3_000_000,
            background = Color.BLACK,
            layers = listOf(
                RenderLayer(
                    listOf(
                        RenderClip(
                            "tone",
                            0,
                            3_000_000,
                            RenderSource.Audio(tone.path),
                            audio = audio,
                        ),
                    ),
                ),
            ),
        )
        val output = File(context.cacheDir, "mix-${audio.hashCode()}.mp4").apply { delete() }
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

    private fun copy(asset: String): File {
        val file = File(context.cacheDir, asset.substringAfterLast('/'))
        instrumentation.context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private companion object {
        const val TAG = "AudioMix"
    }
}
