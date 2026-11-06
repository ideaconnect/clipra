package tech.idct.clipra.core.engine.render

import android.graphics.Color
import android.net.Uri
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.export.ExportRenderer
import tech.idct.clipra.core.engine.media.AudioDecoder
import tech.idct.clipra.core.engine.media.Loudness
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.MediaInspector
import tech.idct.clipra.core.engine.media.VoiceCleaner
import tech.idct.clipra.core.engine.media.WavWriter
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.LoudnessTargets
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Resolution
import tech.idct.clipra.core.model.VoiceCleanup
import tech.idct.clipra.core.model.VoiceEngine
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelDownloader
import tech.idct.clipra.core.models.ModelStore
import tech.idct.clipra.ml.voice.DeepFilterNet
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

    /**
     * A voice in strong wind, cleaned up with DeepFilterNet and normalized from the loudness of
     * its recording, as a clip keeps it when its cleanup finishes after it was measured: the
     * export measures what it plays and still reaches the target (#35). On phone footage the
     * cleanup took up to 23 LU away, and the export missed the target by as much.
     */
    @Test
    fun aCleanedUpVoiceReachesTheTargetFromItsRecordingsLoudness() = runBlocking {
        val windy = windyVoice()
        val asset = MediaInspector.inspect(context, Uri.fromFile(windy))
        val recorded = Loudness.measure(context, windy.path, 0, asset.durationUs)!!
        val cleanup = VoiceCleanup(VoiceEngine.DeepFilterNet)
        val audio = AudioSettings(
            loudnessTarget = LoudnessTargets.SOCIAL,
            measuredLufs = recorded.lufs,
            truePeakDb = recorded.truePeakDb,
            voiceCleanup = cleanup,
        )
        val project = Project(
            id = "loudness-windy",
            name = "Windy voice",
            media = listOf(asset),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip(
                            "voice",
                            ClipType.Audio,
                            0,
                            asset.durationUs,
                            MediaRef(asset.id, 0, asset.durationUs),
                            audio = audio,
                        ),
                    ),
                ),
            ),
        )
        val store = ModelStore(File(context.filesDir, "models"))
        ModelDownloader(store).download(ModelCatalog.deepFilterNet)
        val cache = MediaCache(File(context.cacheDir, "loudness-windy"))
        cache.voiceRecorded(asset.id).parentFile?.deleteRecursively()
        val cleaner = VoiceCleaner(context, cache, store)

        val rendered = ExportRenderer(context, cache, cleaner)
            .render(project, ExportSettings(resolution = Resolution.HD), PauseGate()) {}

        val mix = cleaner.mix(asset, cleanup)!!
        val cleaned = Loudness.measure(context, mix.path, 0, asset.durationUs)!!
        val result = Loudness.measure(context, rendered.file.path, 0, asset.durationUs)!!
        rendered.file.delete()
        Log.i(
            TAG,
            "windy voice: recorded ${recorded.lufs} LUFS, cleaned ${cleaned.lufs} LUFS; " +
                "export ${result.lufs} LUFS, peak ${result.truePeakDb}",
        )
        // Normalizing from the recording would miss the target by this much.
        val removed = recorded.lufs - cleaned.lufs
        assertTrue("the cleanup took $removed LU away", removed > 3f)
        assertEquals(LoudnessTargets.SOCIAL.toDouble(), result.lufs.toDouble(), 1.0)
        assertTrue("peak ${result.truePeakDb} dBTP", result.truePeakDb < 0f)
    }

    /**
     * VoiceCleanupTest's voice 6 dB under its wind: `wind.flac` is `speech.flac` plus the wind,
     * so the wind is their difference, and the voice is turned down by half.
     */
    private fun windyVoice(): File {
        val speech = samples(copy("golden/voice/speech.flac"))
        val wind = samples(copy("golden/voice/wind.flac"))
        val mixed = FloatArray(minOf(speech.size, wind.size)) {
            WINDY_SPEECH * speech[it] + (wind[it] - speech[it])
        }
        val file = File(context.cacheDir, "windy-voice.wav")
        WavWriter(file, DeepFilterNet.SAMPLE_RATE).use { it.write(mixed, mixed.size) }
        return file
    }

    private fun samples(file: File): FloatArray {
        val out = ArrayList<Float>()
        AudioDecoder.decode(context, file.path, 0, Long.MAX_VALUE) { s, count, format, _ ->
            for (i in 0 until count step format.channels) out += s[i] / 32768f
        }
        return out.toFloatArray()
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

        /** The voice's share in [windyVoice]: 6 dB under its wind. */
        const val WINDY_SPEECH = 0.5f
    }
}
