package tech.idct.clipra.core.engine.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.VoiceCleanup
import tech.idct.clipra.core.model.VoiceEngine
import tech.idct.clipra.core.models.ModelStore
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Noise reduction (#38) on the device, the way a clip gets it: a recording of 50 Hz hum and hiss
 * with a tone over it is decoded, gated at the default strength in the background work's code,
 * cached, and found by the resolver that preview and export play from. The hum and hiss go
 * down, the tone stays, and the length is kept; with voice cleanup on as well, the gate runs on
 * the cleaned sound.
 */
@RunWith(AndroidJUnit4::class)
class NoiseReductionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val rate = 48_000

    @Test
    fun humAndHissGoDownInWhatTheClipPlays() {
        val (file, tone) = recording()
        val asset = MediaAsset(
            "noisy",
            file.path,
            MediaKind.Audio,
            "noisy.wav",
            SECONDS * 1_000_000L,
            hasAudio = true,
        )
        val cache = MediaCache(File(context.cacheDir, "noise-test")).also {
            it.voiceRecorded(asset.id).parentFile?.deleteRecursively()
        }
        val cleaner = VoiceCleaner(context, cache, ModelStore(File(context.filesDir, "models")))
        val gated = cleaner.prepare(asset, null, DEFAULT)
        assertNotNull(gated)
        assertEquals(gated, cleaner.played(asset, null, DEFAULT))
        val media = ProjectMediaResolver(context, cache, forExport = true, maxImageSide = 0)
        assertEquals(gated!!.path, media.voice(asset, null, DEFAULT))

        val recorded = read(cache.voiceRecorded(asset.id))
        val output = read(gated)
        assertEquals(recorded.size, output.size)
        val pauses = (0 until SECONDS * 2 step 2).map { span(it) }
        val tones = (1 until SECONDS * 2 step 2).map { span(it) }
        val hum = db(
            pauses.map { amplitude(output, it, 50.0) }.average() /
                pauses.map { amplitude(recorded, it, 50.0) }.average(),
        )
        val hiss =
            db(
                pauses.map {
                    rms(output, it)
                }.average() / pauses.map { rms(recorded, it) }.average(),
            )
        val kept = db(
            tones.map { amplitude(output, it, 1000.0) }.average() /
                tones.map { amplitude(recorded, it, 1000.0) }.average(),
        )
        android.util.Log.i("NoiseReduction", "hum $hum dB, hiss $hiss dB, tone $kept dB")
        assertTrue("hum $hum dB", hum < -10)
        assertTrue("hiss $hiss dB", hiss < -10)
        assertEquals("tone $kept dB", 0.0, kept, 1.0)
        assertTrue(tone.isNotEmpty())
    }

    @Test
    fun withVoiceCleanupTheGateRunsOnTheCleanedSound() {
        val (file, _) = recording()
        val asset = MediaAsset(
            "noisy-voice",
            file.path,
            MediaKind.Audio,
            "noisy.wav",
            SECONDS * 1_000_000L,
            hasAudio = true,
        )
        val cache = MediaCache(File(context.cacheDir, "noise-voice-test")).also {
            it.voiceRecorded(asset.id).parentFile?.deleteRecursively()
        }
        val cleaner = VoiceCleaner(context, cache, ModelStore(File(context.filesDir, "models")))
        val cleanup = VoiceCleanup(VoiceEngine.RNNoise, 0.7f)
        val both = cleaner.prepare(asset, cleanup, DEFAULT)
        assertNotNull(both)
        // The cleanup's own mix is made on the way, and the result is a file of its own.
        assertNotNull(cleaner.mix(asset, cleanup))
        assertNotEquals(cleaner.mix(asset, cleanup), both)
        assertEquals(read(cache.voiceRecorded(asset.id)).size, read(both!!).size)
    }

    /** A WAV of hum and hiss throughout, with a 1 kHz tone in every other half second. */
    private fun recording(): Pair<File, FloatArray> {
        val random = Random(3)
        val samples = FloatArray(rate * SECONDS)
        val tone = FloatArray(samples.size)
        for (i in samples.indices) {
            val t = i.toDouble() / rate
            val hum = 0.04 * sin(2 * PI * 50 * t) + 0.02 * sin(2 * PI * 100 * t)
            val on = (t * 2).toInt() % 2 == 1
            tone[i] = if (on) (0.3 * sin(2 * PI * 1000 * t)).toFloat() else 0f
            samples[i] = (hum + 0.015 * random.nextGaussian()).toFloat() + tone[i]
        }
        val file = File(context.cacheDir, "noisy.wav")
        WavWriter(file, rate).use { it.write(samples, samples.size) }
        return file to tone
    }

    private fun read(file: File): FloatArray = WavReader(file).use { reader ->
        val out = FloatArray(reader.frames.toInt())
        var n = 0
        while (n < out.size) {
            val r = reader.read(out, n, out.size - n)
            if (r < 0) break
            n += r
        }
        out
    }

    /** The middle of half second [half]. */
    private fun span(half: Int): IntRange =
        ((half * 0.5 + 0.1) * rate).toInt() until ((half * 0.5 + 0.4) * rate).toInt()

    private fun rms(x: FloatArray, range: IntRange) = sqrt(
        range.sumOf {
            (x[it] * x[it]).toDouble()
        } / range.count(),
    )

    private fun amplitude(x: FloatArray, range: IntRange, hz: Double): Double {
        var re = 0.0
        var im = 0.0
        for (i in range) {
            val phase = 2 * PI * hz * i / rate
            re += x[i] * cos(phase)
            im += x[i] * sin(phase)
        }
        return 2 * sqrt(re * re + im * im) / range.count()
    }

    private fun db(ratio: Double) = 20 * log10(ratio)

    private companion object {
        const val SECONDS = 6
        const val DEFAULT = 0.4f
    }
}
