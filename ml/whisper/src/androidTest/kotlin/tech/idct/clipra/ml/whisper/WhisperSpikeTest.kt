package tech.idct.clipra.ml.whisper

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.AudioDecoder
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The whisper.cpp spike (#7): tiny, base and small on 42 s of English and 42 s of Polish
 * (public domain LibriVox readings), with time, real-time factor, peak memory and the words.
 *
 * The models are not downloaded here: copy the official files (checked against
 * `ModelCatalog`) into the test app's files with `adb push` to /data/local/tmp and
 * `run-as tech.idct.clipra.ml.whisper.test cp` into `files/models/ggml-<name>.bin`. Results
 * go to logcat (tag WhisperSpike) and to `files/whisper-spike.md`.
 */
@RunWith(AndroidJUnit4::class)
class WhisperSpikeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun transcribeTheTestClips() {
        val folder = File(context.filesDir, "models")
        // Token timestamps with flash attention, then base again with DTW timestamps.
        val models = listOf(
            "tiny" to null,
            "base" to null,
            "small" to null,
            "base" to Whisper.AlignmentHeads.Base,
        ).filter { File(folder, "ggml-${it.first}.bin").exists() }
        assumeTrue("Push the models to $folder", models.isNotEmpty())
        val threads = Runtime.getRuntime().availableProcessors().coerceAtMost(4)
        val report = StringBuilder()
        report.appendLine("System: ${Whisper.systemInfo()}")
        report.appendLine("Threads: $threads")
        for ((language, clip) in listOf("en" to "english.flac", "pl" to "polish.flac")) {
            val samples = decode(clip)
            val seconds = samples.size / Whisper.SAMPLE_RATE.toDouble()
            for ((name, heads) in models) {
                val loadStart = System.nanoTime()
                val whisper = Whisper.load(File(folder, "ggml-$name.bin"), heads)
                val loadSeconds = (System.nanoTime() - loadStart) / 1e9
                val peak = PeakMemory()
                val start = System.nanoTime()
                val words = whisper.use { it.transcribe(samples, language, threads).words }
                val elapsed = (System.nanoTime() - start) / 1e9
                val peakMb = peak.stop()
                val line = String.format(
                    Locale.ROOT,
                    "%s %s%s: %.1f s for %.1f s (RTF %.2f), load %.1f s, peak RSS %d MB, " +
                        "%d words, mean p %.2f",
                    language,
                    name,
                    if (heads != null) " DTW" else "",
                    elapsed,
                    seconds,
                    elapsed / seconds,
                    loadSeconds,
                    peakMb,
                    words.size,
                    words.map { it.probability }.average(),
                )
                Log.i(TAG, line)
                report.appendLine()
                report.appendLine("## $line")
                report.appendLine(words.joinToString(" ") { it.text })
                report.appendLine()
                report.appendLine(
                    words.joinToString("\n") {
                        String.format(
                            Locale.ROOT,
                            "%6.2f-%6.2f dtw %6.2f p %.2f %s",
                            it.startUs / 1e6,
                            it.endUs / 1e6,
                            it.dtwUs / 1e6,
                            it.probability,
                            it.text,
                        )
                    },
                )
            }
        }
        File(context.filesDir, "whisper-spike.md").writeText(report.toString())
    }

    private fun decode(asset: String): FloatArray {
        val file = File(context.cacheDir, asset)
        instrumentation.context.assets.open(asset).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val samples = ArrayList<Float>()
        AudioDecoder.decode(context, file.path, 0, Long.MAX_VALUE) { s, count, format, _ ->
            check(format.sampleRate == Whisper.SAMPLE_RATE)
            for (i in 0 until count step format.channels) samples += s[i] / 32768f
        }
        return samples.toFloatArray()
    }

    /** The process's peak resident memory while it runs, sampled every 50 ms. */
    private class PeakMemory {
        @Volatile private var running = true
        private var peakKb = 0L
        private val sampler = thread {
            while (running) {
                peakKb = maxOf(peakKb, residentKb())
                Thread.sleep(50)
            }
        }

        fun stop(): Long {
            running = false
            sampler.join()
            return peakKb / 1024
        }

        private fun residentKb(): Long = File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.filter { it.isDigit() }?.toLongOrNull() ?: 0
    }

    private companion object {
        const val TAG = "WhisperSpike"
    }
}
