package tech.idct.clipra.core.engine.media

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.VoiceCleanup
import tech.idct.clipra.core.model.VoiceEngine
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelDownloader
import tech.idct.clipra.core.models.ModelStore
import tech.idct.clipra.ml.voice.DeepFilterNet
import tech.idct.clipra.ml.voice.OnnxDeepFilterModel
import tech.idct.clipra.ml.voice.RnNoise
import tech.idct.clipra.ml.voice.SampleSource
import tech.idct.clipra.ml.voice.VoiceDenoiser
import java.io.File
import kotlin.math.log10

/**
 * AI voice cleanup (#39) on the wind and traffic test set: 10 s of a public domain LibriVox
 * reading (CC0, "Grim: The Story of a Pike") with synthetic wind (gusting low rumble and
 * buffeting) and traffic (pink road noise, engine hum and a passing car), each at 0 dB SNR.
 * Quality is SI-SDR against the clean speech. Downloads DeepFilterNet 3 (8 MB) from its
 * official source the first time.
 */
@RunWith(AndroidJUnit4::class)
class VoiceCleanupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun deepFilterNetClearlyImprovesWindAndTraffic() {
        val clean = decode("speech.flac")
        for (noise in listOf("wind.flac", "traffic.flac")) {
            val noisy = decode(noise)
            val folder = store.file(ModelCatalog.deepFilterNet, "enc.onnx")!!.parentFile!!
            val (out, seconds) = run(DeepFilterNet(OnnxDeepFilterModel(folder)), noisy)
            val before = siSdr(noisy, clean)
            val after = siSdr(out, clean)
            Log.i(
                TAG,
                "DeepFilterNet $noise: %.1f -> %.1f dB SI-SDR, %.1f s for 10 s"
                    .format(before, after, seconds),
            )
            assertTrue("$noise improved by ${after - before} dB", after - before > 6)
        }
    }

    @Test
    fun rnnoiseImprovesWindAndTrafficAndKeepsTime() {
        val clean = decode("speech.flac")
        for (noise in listOf("wind.flac", "traffic.flac")) {
            val noisy = decode(noise)
            val (out, seconds) = run(RnNoise(), noisy)
            val before = siSdr(noisy, clean)
            val after = siSdr(out, clean)
            Log.i(
                TAG,
                "RNNoise $noise: %.1f -> %.1f dB SI-SDR, %.1f s for 10 s"
                    .format(before, after, seconds),
            )
            // A misaligned output would score far below the input.
            assertTrue("$noise improved by ${after - before} dB", after - before > 1)
        }
    }

    @Test
    fun theCleanerMakesAMixPerStrength() {
        val file = copy("wind.flac")
        val asset = MediaAsset(
            id = "voice-test",
            uri = file.path,
            kind = MediaKind.Audio,
            displayName = "wind.flac",
            durationUs = 10_000_000,
            hasAudio = true,
        )
        val cache = MediaCache(File(context.cacheDir, "voice-test"))
        cache.voiceRecorded(asset.id).parentFile?.deleteRecursively()
        val cleaner = VoiceCleaner(context, cache, store)
        val full = cleaner.prepare(asset, VoiceCleanup(VoiceEngine.DeepFilterNet, 1f))
        val none = cleaner.prepare(asset, VoiceCleanup(VoiceEngine.DeepFilterNet, 0f))
        assertNotNull(full)
        assertNotNull(none)
        val clean = decode("speech.flac")
        val recorded = read(cache.voiceRecorded(asset.id))
        val cleaned = read(full!!)
        val untouched = read(none!!)
        assertEquals(clean.size, recorded.size)
        assertEquals(recorded.size, cleaned.size)
        // At 0% the mix is the recording; at 100% it is the cleaned sound.
        assertEquals(siSdr(recorded, clean), siSdr(untouched, clean), 0.1)
        assertTrue(siSdr(cleaned, clean) - siSdr(recorded, clean) > 6)
        assertEquals(2, cache.voiceVersion.value)
    }

    private fun run(denoiser: VoiceDenoiser, input: FloatArray): Pair<FloatArray, Double> {
        val out = FloatArray(input.size)
        var written = 0
        val started = System.nanoTime()
        denoiser.use {
            it.process(source(input), input.size.toLong(), { buffer, count ->
                buffer.copyInto(out, written, 0, count)
                written += count
            })
        }
        assertEquals(input.size, written)
        return out to (System.nanoTime() - started) / 1e9
    }

    private fun decode(asset: String): FloatArray {
        val file = copy(asset)
        val samples = ArrayList<Float>()
        AudioDecoder.decode(context, file.path, 0, Long.MAX_VALUE) { s, count, format, _ ->
            for (i in 0 until count step format.channels) samples += s[i] / 32768f
        }
        return samples.toFloatArray()
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

    private fun copy(asset: String): File {
        val file = File(context.cacheDir, asset)
        InstrumentationRegistry.getInstrumentation().context.assets.open("golden/voice/$asset")
            .use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun source(samples: FloatArray): SampleSource {
        var position = 0
        return SampleSource { buffer, offset, count ->
            if (position >= samples.size) return@SampleSource -1
            val n = minOf(count, samples.size - position)
            samples.copyInto(buffer, offset, position, position + n)
            position += n
            n
        }
    }

    /** Scale-invariant signal-to-distortion ratio of [estimate] against [reference], in dB. */
    private fun siSdr(estimate: FloatArray, reference: FloatArray): Double {
        val n = minOf(estimate.size, reference.size)
        val meanE = (0 until n).sumOf { estimate[it].toDouble() } / n
        val meanR = (0 until n).sumOf { reference[it].toDouble() } / n
        var dot = 0.0
        var energy = 0.0
        for (i in 0 until n) {
            dot += (estimate[i] - meanE) * (reference[i] - meanR)
            energy += (reference[i] - meanR) * (reference[i] - meanR)
        }
        val a = dot / energy
        var target = 0.0
        var error = 0.0
        for (i in 0 until n) {
            val t = a * (reference[i] - meanR)
            target += t * t
            val e = (estimate[i] - meanE) - t
            error += e * e
        }
        return 10 * log10(target / error)
    }

    companion object {
        private const val TAG = "VoiceCleanup"
        private lateinit var store: ModelStore

        @BeforeClass
        @JvmStatic
        fun downloadModel() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            store = ModelStore(File(context.filesDir, "models"))
            ModelDownloader(store).download(ModelCatalog.deepFilterNet)
        }
    }
}
