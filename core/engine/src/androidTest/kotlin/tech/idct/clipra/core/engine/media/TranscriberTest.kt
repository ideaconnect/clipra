package tech.idct.clipra.core.engine.media

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.VoiceCleanup
import tech.idct.clipra.core.model.VoiceEngine
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelDownloader
import tech.idct.clipra.core.models.ModelStore
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Auto captions' transcription (#44) with Whisper Base on 42 s of English (a public domain
 * LibriVox reading): within the spike's time, with word starts at pauses within 100 ms of
 * where the speech starts again, measured here with the test's own onset detector. Downloads
 * Base (148 MB) from its official source the first time.
 */
@RunWith(AndroidJUnit4::class)
class TranscriberTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private val file by lazy { copy("golden/speech/english.flac") }
    private val asset by lazy {
        MediaAsset(
            id = "reading",
            uri = file.path,
            kind = MediaKind.Audio,
            durationUs = 42_000_000,
            hasAudio = true,
        )
    }

    private fun project(inPointUs: Long, durationUs: Long) = Project(
        id = "p",
        name = "p",
        media = listOf(asset),
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    Clip(
                        id = "clip",
                        type = ClipType.Audio,
                        startUs = 5_000_000,
                        durationUs = durationUs,
                        source = MediaRef("reading", inPointUs, inPointUs + durationUs),
                    ),
                ),
            ),
        ),
    )

    private val transcriber by lazy {
        Transcriber(context, MediaCache(File(context.cacheDir, "transcriber-test")), store)
    }

    @Test
    fun baseTranscribes42SecondsInTimeAndTimesWordsAtPauses() {
        val started = System.nanoTime()
        val transcript = transcriber.transcribe(
            project(0, 42_000_000),
            listOf("clip"),
            ModelCatalog.whisperBase,
            Transcriber.AUTO,
            translate = false,
        ).single()
        val seconds = (System.nanoTime() - started) / 1e9
        val words = transcript.words
        Log.i(
            TAG,
            "Base: ${words.size} words in %.1f s, language ${transcript.language}"
                .format(seconds),
        )
        assertEquals("en", transcript.language)
        assertTrue("${words.size} words", words.size in 85..100)
        assertTrue(words.first().text.contains("mud") || words[1].text.contains("mud"))
        // The spike measured 3.7 s on the emulator; 15 s leaves room for a busy machine.
        assertTrue("took $seconds s", seconds < 15)

        val samples = decode(file)
        val onsets = onsets(samples)
        assertTrue("${onsets.size} onsets", onsets.size >= 5)
        val errors = onsets.map { onset -> words.minOf { abs(it.startUs - onset) } }
        Log.i(
            TAG,
            "Word starts at ${onsets.size} onsets: worst ${errors.max() / 1000} ms, " +
                "mean ${errors.average().toLong() / 1000} ms",
        )
        errors.forEachIndexed { i, error ->
            assertTrue("onset at ${onsets[i] / 1000} ms is $error us off", error <= 100_000)
        }
    }

    @Test
    fun aTrimmedClipGetsWordsInItsMediaTime() {
        val transcript = transcriber.transcribe(
            project(20_000_000, 10_000_000),
            listOf("clip"),
            ModelCatalog.whisperBase,
            "en",
            translate = false,
        ).single()
        assertTrue(transcript.words.isNotEmpty())
        for (word in transcript.words) {
            assertTrue("${word.text} at ${word.startUs}", word.startUs in 20_000_000..30_000_000)
        }
    }

    @Test
    fun aCleanedVoiceIsWhatGetsTranscribed() {
        val cleanup = VoiceCleanup(VoiceEngine.RNNoise, 1f)
        val cache = MediaCache(File(context.cacheDir, "transcriber-voice"))
        assertNotNull(VoiceCleaner(context, cache, store).prepare(asset, cleanup))
        val base = project(10_000_000, 10_000_000)
        val clip = base.layers[0].clips[0]
        val cleaned = base.copy(
            layers = listOf(
                base.layers[0].copy(
                    clips = listOf(clip.copy(audio = clip.audio.copy(voiceCleanup = cleanup))),
                ),
            ),
        )
        val transcript = Transcriber(context, cache, store).transcribe(
            cleaned,
            listOf("clip"),
            ModelCatalog.whisperBase,
            "en",
            translate = false,
        ).single()
        assertTrue(transcript.words.size > 10)
        for (word in transcript.words) {
            assertTrue("${word.text} at ${word.startUs}", word.startUs in 10_000_000..20_000_000)
        }
    }

    @Test
    fun aStoppedTranscriptionThrows() {
        try {
            transcriber.transcribe(
                project(0, 42_000_000),
                listOf("clip"),
                ModelCatalog.whisperBase,
                "en",
                translate = false,
                stopped = { true },
            )
            fail("stopped")
        } catch (_: Transcriber.Cancelled) {
        }
    }

    /** Where speech starts after 200 ms or more of quiet: 5 ms frames, 12 dB over the floor. */
    private fun onsets(samples: FloatArray): List<Long> {
        val hop = 80
        val levels = DoubleArray(samples.size / hop) { f ->
            var sum = 0.0
            for (i in f * hop until (f + 1) * hop) sum += samples[i] * samples[i]
            20 * log10(sqrt(sum / hop) + 1e-9)
        }
        val floor = levels.sorted()[levels.size / 10]
        val result = mutableListOf<Long>()
        var quiet = 0
        for ((f, level) in levels.withIndex()) {
            if (level < floor + 12) {
                quiet++
            } else {
                if (quiet >= 40) result += f * 5_000L
                quiet = 0
            }
        }
        return result
    }

    private fun decode(file: File): FloatArray {
        val samples = ArrayList<Float>()
        AudioDecoder.decode(context, file.path, 0, Long.MAX_VALUE) { s, count, format, _ ->
            for (i in 0 until count step format.channels) samples += s[i] / 32768f
        }
        return samples.toFloatArray()
    }

    private fun copy(asset: String): File {
        val target = File(context.cacheDir, asset.substringAfterLast('/'))
        instrumentation.context.assets.open(asset).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }

    companion object {
        private const val TAG = "Transcriber"
        private lateinit var store: ModelStore

        @BeforeClass
        @JvmStatic
        fun downloadBase() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            store = ModelStore(File(context.filesDir, "models"))
            ModelDownloader(store).download(ModelCatalog.whisperBase)
        }
    }
}
