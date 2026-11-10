package tech.idct.clipra.core.engine.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExperimentalAnalyzerModeFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.export.ExportRenderer
import tech.idct.clipra.core.engine.render.CompositionFactory
import tech.idct.clipra.core.engine.render.MediaResolver
import tech.idct.clipra.core.engine.render.PauseGate
import tech.idct.clipra.core.engine.render.ProjectRender
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Speed
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.export.ExportSettings
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * The media's sound decoded to PCM below Android 12 (#268): at its own rate and channels, as the
 * export decodes the original, played by the preview and the export in place of the compressed
 * sound, so the two mix the same samples. The mixes are taken after Media3 has mixed the layers:
 * the export's from Transformer, the preview's from `CompositionPlayer` as it plays. Android 12
 * and later make no copies, so there the tests are skipped.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
@RunWith(AndroidJUnit4::class)
class DecodedSoundsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val root = File(context.cacheDir, "decoded-sounds-test").apply {
        deleteRecursively()
        mkdirs()
    }
    private val cache = MediaCache(File(root, "cache"))
    private val preview = ProjectMediaResolver(context, cache, forExport = false, maxImageSide = 64)
    private val export = ProjectMediaResolver(context, cache, forExport = true, maxImageSide = 64)

    /** The originals, as both read them before #268 and from Android 12 on. */
    private val originals = object : MediaResolver {
        override fun source(asset: MediaAsset) = export.source(asset)

        override fun image(asset: MediaAsset) = export.image(asset)

        override fun text(spec: TextSpec, card: Boolean, width: Int, height: Int) =
            export.text(spec, card, width, height)
    }

    @Before
    fun belowAndroid12() {
        assumeTrue("Android 12 and later play the compressed sound", DecodedSounds.NEEDED)
    }

    @Test
    fun aDecodeKeepsTheSoundsRateChannelsAndLength() {
        for (asset in listOf(asset("spike/gray.mp4"), asset("golden/speechy.m4a"))) {
            assertTrue(DecodedSounds(context, cache).prepare(asset))
            val source = soundFormat(Uri.parse(asset.uri).path!!)
            val copy = soundFormat(cache.decodedSound(asset.id).path)
            Log.i(TAG, "${asset.displayName}: $source, copy $copy")
            assertEquals(MimeTypes.AUDIO_RAW, copy.getString(MediaFormat.KEY_MIME))
            for (key in listOf(MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT)) {
                assertEquals(key, source.getInteger(key), copy.getInteger(key))
            }
            // As long as the sound, less the encoder's delay the decoder leaves out.
            val lengthUs = source.getLong(MediaFormat.KEY_DURATION).toDouble()
            assertEquals(lengthUs, copy.getLong(MediaFormat.KEY_DURATION).toDouble(), 25_000.0)
        }
    }

    /**
     * The copy holds the samples the export decodes from the original: an untrimmed clip mixes
     * the same from both, up to the original's last frame, which its decode ends early.
     */
    @Test
    fun aDecodeHoldsTheSamplesTheExportDecodes() {
        val video = asset("spike/gray.mp4")
        val project = oneClip(video, 0)
        assertTrue(DecodedSounds(context, cache).prepare(video))

        val fromOriginal = exportMix(composition(project, originals))
        val fromCopy = exportMix(composition(project, export))

        val difference = compare(fromOriginal, fromCopy)
        Log.i(TAG, "an untrimmed clip from the copy against the original: $difference")
        assertEquals("lag", 0, difference.lag)
        assertTrue("$difference", difference.maxDiffBeforeLastFrame <= MAX_DIFF)
    }

    /**
     * Bartosz's condition for the fix: the preview plays the export's mix. Two layers, a video's
     * sound with a fade in and a sound trimmed at its start with a fade out, mixed by Transformer
     * as the export mixes them and by `CompositionPlayer` as the preview plays them. From the
     * originals, as before #268, the two are logged beside it.
     */
    @Test
    fun thePreviewAndTheExportMixTheSameSamples() {
        val video = asset("spike/gray.mp4")
        val sound = asset("golden/speechy.m4a")
        val project = Project(
            id = "mix",
            name = "Mix",
            media = listOf(video, sound),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip(
                            "video",
                            ClipType.Video,
                            0,
                            4_000_000,
                            MediaRef(video.id, 0, 4_000_000),
                            audio = AudioSettings(volume = 0.8f, fadeInUs = 500_000),
                        ),
                    ),
                ),
                Layer(
                    "L2",
                    clips = listOf(
                        Clip(
                            "sound",
                            ClipType.Audio,
                            500_000,
                            3_000_000,
                            MediaRef(sound.id, 1_300_000, 4_300_000),
                            audio = AudioSettings(fadeOutUs = 1_000_000),
                        ),
                    ),
                ),
            ),
        )
        val before = compare(
            exportMix(composition(project, originals)),
            previewMix(composition(project, originals)),
        )
        Log.i(TAG, "from the originals, the preview against the export: $before")
        val sounds = DecodedSounds(context, cache)
        assertTrue(sounds.prepare(video))
        assertTrue(sounds.prepare(sound))

        val exported = exportMix(composition(project, export))
        val previewed = previewMix(composition(project, preview))

        val difference = compare(exported, previewed)
        Log.i(TAG, "from the copies, the preview against the export: $difference")
        assertEquals(exported.sampleRate, previewed.sampleRate)
        assertEquals(exported.channels, previewed.channels)
        assertEquals(exported.frames, previewed.frames)
        assertEquals("lag", 0, difference.lag)
        assertTrue("$difference", difference.maxDiff <= MAX_DIFF)
    }

    /**
     * A clip trimmed at its start plays its media from the in point: what the untrimmed clip
     * plays there, sample for sample. From the original, the export plays it a frame late.
     */
    @Test
    fun aTrimmedClipPlaysFromItsInPoint() {
        val sound = asset("golden/speechy.m4a")
        assertTrue(DecodedSounds(context, cache).prepare(sound))
        val inUs = 1_300_000L
        val whole = exportMix(composition(oneClip(sound, 0, 6_000_000), export))
        val trimmed = exportMix(composition(oneClip(sound, inUs), export))
        val fromOriginal = exportMix(composition(oneClip(sound, inUs), originals))

        val skip = (inUs * whole.sampleRate / 1_000_000).toInt()
        val difference = compare(whole.from(skip), trimmed)
        val before = compare(whole.from(skip), fromOriginal)
        Log.i(TAG, "trimmed at ${inUs / 1_000} ms, from the copy: $difference")
        Log.i(TAG, "trimmed at ${inUs / 1_000} ms, from the original: $before")
        assertEquals("lag", 0, difference.lag)
        assertTrue("$difference", difference.maxDiffBeforeLastFrame <= MAX_DIFF)
    }

    @Test
    fun thePreviewAndTheExportPlayTheDecodeOnceItIsMade() {
        val asset = asset("golden/speechy.m4a")
        val project = oneClip(asset, 0)
        // Until it is made, both play the compressed sound.
        assertNull(decodedSoundOf(project, preview))
        assertNull(decodedSoundOf(project, export))

        assertTrue(DecodedSounds(context, cache).prepare(asset))

        val copy = cache.decodedSound(asset.id).path
        assertEquals(copy, decodedSoundOf(project, preview))
        assertEquals(copy, decodedSoundOf(project, export))
    }

    @Test
    fun theEditorQueuesTheDecodesOfTheSoundsClipsPlay() {
        val video = asset("spike/gray.mp4")
        val sound = asset("golden/speechy.m4a")
        val forwards = oneClip(video, 0)
        val project = forwards.copy(
            media = listOf(video, sound),
            layers = forwards.layers + Layer(
                "L2",
                clips = listOf(
                    // Reversed: it plays a file of its own (#22), so the sound needs no decode.
                    Clip(
                        "back",
                        ClipType.Audio,
                        0,
                        1_000_000,
                        MediaRef(sound.id, 0, 1_000_000),
                        speed = Speed(reverse = true),
                    ),
                ),
            ),
        )
        val version = cache.voiceVersion.value

        DecodedSounds(context, cache).requestAll(project)

        val decoded = cache.decodedSound(video.id)
        val until = System.currentTimeMillis() + 30_000
        while (!decoded.exists() && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue(decoded.exists())
        assertTrue(cache.voiceVersion.value > version)
        assertFalse(cache.decodedSound(sound.id).exists())
    }

    @Test
    fun theExportMakesTheDecodesItPlays() = runBlocking {
        val video = asset("spike/gray.mp4")
        val project = oneClip(video, 0, 2_000_000)

        val rendered = ExportRenderer(context, cache)
            .render(project, ExportSettings(), PauseGate()) {}

        assertTrue(cache.decodedSound(video.id).exists())
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(rendered.file.path)
            val audio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            assertEquals("yes", audio)
        }
        rendered.file.delete()
        Unit
    }

    @Test
    fun aSoundThatIsPcmAlreadyGetsNoDecode() {
        val file = File(root, "silence.wav")
        file.outputStream().use {
            it.write(PcmWav.header(48_000, 1, 48_000))
            it.write(ByteArray(96_000))
        }
        val asset = MediaAsset(
            "silence",
            Uri.fromFile(file).toString(),
            MediaKind.Audio,
            durationUs = 1_000_000,
            hasAudio = true,
        )

        assertTrue(DecodedSounds(context, cache).prepare(asset))

        assertFalse(cache.decodedSound(asset.id).exists())
    }

    /** One clip of [asset] from [inUs], [lengthUs] long, at the timeline's start. */
    private fun oneClip(asset: MediaAsset, inUs: Long, lengthUs: Long = 3_000_000): Project {
        val type = if (asset.kind == MediaKind.Audio) ClipType.Audio else ClipType.Video
        val clip = Clip("clip", type, 0, lengthUs, MediaRef(asset.id, inUs, inUs + lengthUs))
        return Project(
            id = "one",
            name = "One",
            media = listOf(asset),
            layers = listOf(Layer("L1", clips = listOf(clip))),
        )
    }

    private fun decodedSoundOf(project: Project, media: MediaResolver): String? =
        ProjectRender.timeline(project, media, 64, 64).layers.single().clips.single()
            .decodedSoundPath

    /** The sound sequences of [project]'s composition, as the preview and the export build it. */
    private fun composition(project: Project, media: MediaResolver): Composition {
        val timeline = ProjectRender.timeline(project, media, 64, 64)
        // The first sequence is the picture's canvas.
        val sounds = CompositionFactory.create(context, timeline).sequences.drop(1)
        return Composition.Builder(sounds).build()
    }

    /** [composition] mixed by Transformer as the export mixes it, written by nothing. */
    private fun exportMix(composition: Composition): Mix {
        val tap = MixTap()
        val done = CountDownLatch(1)
        val error = AtomicReference<ExportException?>()
        instrumentation.runOnMainSync {
            ExperimentalAnalyzerModeFactory.buildAnalyzer(context)
                .buildUpon()
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
                .start(tapped(composition, tap), File(root, "none.mp4").path)
        }
        assertTrue("export timed out", done.await(2, TimeUnit.MINUTES))
        error.get()?.let { throw it }
        return tap.mix()
    }

    /** [composition] played by `CompositionPlayer` as the preview plays it, silently. */
    private fun previewMix(composition: Composition): Mix {
        val tap = MixTap()
        val done = CountDownLatch(1)
        val error = AtomicReference<PlaybackException?>()
        lateinit var player: CompositionPlayer
        instrumentation.runOnMainSync {
            player = CompositionPlayer.Builder(context).build()
            player.volume = 0f
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) done.countDown()
                }

                override fun onPlayerError(playbackError: PlaybackException) {
                    error.set(playbackError)
                    done.countDown()
                }
            })
            player.setComposition(tapped(composition, tap))
            player.prepare()
            player.play()
        }
        val ended = done.await(1, TimeUnit.MINUTES)
        instrumentation.runOnMainSync { player.release() }
        error.get()?.let { throw it }
        assertTrue("preview timed out", ended)
        return tap.mix()
    }

    /** [composition] with [tap] after the mix. */
    private fun tapped(composition: Composition, tap: MixTap) =
        Composition.Builder(composition.sequences)
            .setEffects(Effects(listOf(tap), emptyList()))
            .build()

    /** A mix: interleaved samples from -1 to 1. */
    private class Mix(val sampleRate: Int, val channels: Int, val samples: FloatArray) {
        val frames: Int get() = samples.size / channels

        /** The mix from [frame] on. */
        fun from(frame: Int) =
            Mix(sampleRate, channels, samples.copyOfRange(frame * channels, samples.size))
    }

    /**
     * How two mixes differ: the lag that lines them up best (the second's frame for the first's
     * frame 0), the largest difference then and where it is, the largest before the last AAC
     * frame of the shorter one, and the mean.
     */
    private data class Difference(
        val lag: Int,
        val maxDiff: Float,
        val maxAtFrame: Int,
        val maxDiffBeforeLastFrame: Float,
        val meanDiff: Double,
        val frames: Int,
    )

    private fun compare(a: Mix, b: Mix): Difference {
        val channels = a.channels
        val frames = minOf(a.frames, b.frames)
        fun meanDiff(lag: Int, from: Int, to: Int): Double {
            var sum = 0.0
            var n = 0
            for (frame in from until to) {
                val other = frame + lag
                if (other !in 0 until b.frames) continue
                for (c in 0 until channels) {
                    sum += abs(a.samples[frame * channels + c] - b.samples[other * channels + c])
                    n++
                }
            }
            return if (n == 0) Double.MAX_VALUE else sum / n
        }
        // Lined up on a quarter of a second after the first.
        val from = minOf(a.sampleRate, frames)
        val to = minOf(frames, from + a.sampleRate / 4)
        val lag = (-MAX_LAG..MAX_LAG).minBy { meanDiff(it, from, to) }
        var max = 0f
        var maxAt = -1
        var maxBeforeLast = 0f
        var sum = 0.0
        var n = 0
        for (frame in 0 until frames) {
            val other = frame + lag
            if (other !in 0 until b.frames) continue
            for (c in 0 until channels) {
                val d = abs(a.samples[frame * channels + c] - b.samples[other * channels + c])
                if (d > max) {
                    max = d
                    maxAt = frame
                }
                if (frame < frames - AAC_FRAME && d > maxBeforeLast) maxBeforeLast = d
                sum += d
                n++
            }
        }
        return Difference(lag, max, maxAt, maxBeforeLast, sum / maxOf(1, n), frames)
    }

    /** Keeps every sample of the mix that passes, and passes it on. */
    private class MixTap : BaseAudioProcessor() {
        private var format: AudioFormat? = null
        private var samples = FloatArray(1 shl 16)
        private var count = 0

        override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
            val encoding = inputAudioFormat.encoding
            if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
                throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }
            format = inputAudioFormat
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val input = inputBuffer.order(ByteOrder.nativeOrder())
            val output = replaceOutputBuffer(input.remaining())
            val float = format!!.encoding == C.ENCODING_PCM_FLOAT
            synchronized(this) {
                while (input.remaining() >= if (float) 4 else 2) {
                    val sample = if (float) {
                        input.getFloat().also { output.putFloat(it) }
                    } else {
                        input.getShort().also { output.putShort(it) } / 32768f
                    }
                    if (count == samples.size) samples = samples.copyOf(samples.size * 2)
                    samples[count++] = sample
                }
            }
            output.flip()
        }

        @Synchronized
        fun mix(): Mix {
            val f = format!!
            return Mix(f.sampleRate, f.channelCount, samples.copyOf(count))
        }
    }

    private fun soundFormat(path: String): MediaFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            return (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .first { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
        } finally {
            extractor.release()
        }
    }

    private fun asset(path: String): MediaAsset {
        val file = File(root, path.substringAfterLast('/'))
        instrumentation.context.assets.open(path).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return MediaInspector.inspect(context, Uri.fromFile(file))
    }

    private companion object {
        const val TAG = "DecodedSounds"

        /** How far apart the mixes are looked for, in frames, to line them up. */
        const val MAX_LAG = 2_048

        /** Samples in an AAC frame. */
        const val AAC_FRAME = 1_024

        /** Two 16-bit steps: the same samples, rounded the same way. */
        const val MAX_DIFF = 2f / 32768f
    }
}
