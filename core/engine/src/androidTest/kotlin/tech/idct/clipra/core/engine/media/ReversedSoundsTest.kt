package tech.idct.clipra.core.engine.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.export.ExportRenderer
import tech.idct.clipra.core.engine.render.PauseGate
import tech.idct.clipra.core.engine.render.ProjectRender
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Speed
import tech.idct.clipra.core.model.export.ExportSettings
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Reversed clips' sound (#22): the stretch a clip plays, back to front, every channel kept, made
 * for the preview and the export.
 */
@RunWith(AndroidJUnit4::class)
class ReversedSoundsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val root = File(context.cacheDir, "reversed-test").apply {
        deleteRecursively()
        mkdirs()
    }
    private val cache = MediaCache(File(root, "cache"))

    @Test
    fun aReversedClipPlaysItsStretchBackToFrontInStereo() {
        val asset = ramp()
        val clip = Clip(
            "clip",
            ClipType.Audio,
            0,
            500_000,
            MediaRef(asset.id, FROM_US, TO_US),
            speed = Speed(reverse = true),
        )
        val project = project(asset, clip)

        assertTrue(ReversedSounds(context, cache).prepare(project, clip))

        val file = cache.reversedSound(asset.id, FROM_US, TO_US)
        val (channels, samples) = read(file)
        assertEquals(2, channels)
        val frames = samples.size / 2
        assertTrue("$frames frames", abs(frames - 24_000) <= 1)
        // The first frame is the stretch's last one, give or take the decoder's rounding.
        val last = (TO_US * RATE / 1_000_000).toInt() - 1
        val start = (last - 2..last + 2).first { ramp(it) == samples[0].toInt() }
        for (k in 0 until frames) {
            assertEquals("left $k", ramp(start - k), samples[2 * k].toInt())
            assertEquals("right $k", -ramp(start - k), samples[2 * k + 1].toInt())
        }

        // The render plays it.
        val media = ProjectMediaResolver(context, cache, forExport = true, maxImageSide = 64)
        assertEquals(file.path, media.reversedSound(asset, null, FROM_US, TO_US))
        val timeline = ProjectRender.timeline(project, media, 64, 64)
        assertEquals(file.path, timeline.layers.single().clips.single().reversedSoundPath)
    }

    /** A sound track that ends before the picture: the reversed clip is silent until it starts. */
    @Test
    fun aStretchPastTheSoundStartsWithSilence() {
        val asset = ramp().copy(durationUs = 1_500_000)
        val clip = Clip(
            "clip",
            ClipType.Audio,
            0,
            1_000_000,
            MediaRef(asset.id, 500_000, 1_500_000),
            speed = Speed(reverse = true),
        )

        assertTrue(ReversedSounds(context, cache).prepare(project(asset, clip), clip))

        val (_, samples) = read(cache.reversedSound(asset.id, 500_000, 1_500_000))
        val frames = samples.size / 2
        assertTrue("$frames frames", abs(frames - RATE) <= 1)
        // The half second past the sound's end, then the sound's second half, backwards.
        val silent = (0 until frames).first { samples[2 * it].toInt() != 0 }
        assertTrue("silent for $silent frames", abs(silent - RATE / 2) <= 2)
        val start = (RATE - 3 until RATE).first { ramp(it) == samples[2 * silent].toInt() }
        for (k in 0 until frames - silent) {
            assertEquals("left $k", ramp(start - k), samples[2 * (silent + k)].toInt())
        }
    }

    @Test
    fun aReversedVideoExportsWithItsSound() = runBlocking {
        val gray = File(root, "gray.mp4")
        instrumentation.context.assets.open("spike/gray.mp4").use { input ->
            gray.outputStream().use { input.copyTo(it) }
        }
        val asset = MediaInspector.inspect(context, Uri.fromFile(gray))
        val clip = Clip(
            "clip",
            ClipType.Video,
            0,
            2_000_000,
            MediaRef(asset.id, 1_000_000, 3_000_000),
            speed = Speed(reverse = true),
        )

        val rendered = ExportRenderer(context, cache)
            .render(project(asset, clip), ExportSettings(), PauseGate()) {}

        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(rendered.file.path)
            val audio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            assertEquals("yes", audio)
        }
        assertNotNull(cache.reversedSound(asset.id, 1_000_000, 3_000_000).takeIf { it.exists() })
        rendered.file.delete()
        Unit
    }

    private fun project(asset: MediaAsset, clip: Clip) = Project(
        id = "reversed",
        name = "Reversed",
        media = listOf(asset),
        layers = listOf(Layer("L1", clips = listOf(clip))),
    )

    /** A second of stereo 16-bit sound: a ramp on the left, the same upside down on the right. */
    private fun ramp(): MediaAsset {
        val file = File(root, "ramp.wav")
        val frames = RATE
        val data = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            data.putShort(ramp(i).toShort())
            data.putShort((-ramp(i)).toShort())
        }
        file.outputStream().use {
            it.write(PcmWav.header(RATE, 2, frames.toLong()))
            it.write(data.array())
        }
        return MediaAsset(
            id = "ramp",
            uri = Uri.fromFile(file).toString(),
            kind = MediaKind.Audio,
            durationUs = 1_000_000,
            hasAudio = true,
        )
    }

    private fun ramp(frame: Int) = frame % 20_000 - 10_000

    /** The channel count and the samples of a 16-bit PCM WAV with a 44-byte header. */
    private fun read(file: File): Pair<Int, ShortArray> = RandomAccessFile(file, "r").use { raf ->
        val bytes = ByteArray(raf.length().toInt())
        raf.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channels = buffer.getShort(22).toInt()
        val samples = ShortArray((bytes.size - PcmWav.HEADER) / 2)
        buffer.position(PcmWav.HEADER)
        buffer.asShortBuffer().get(samples)
        channels to samples
    }

    private companion object {
        const val RATE = 48_000
        const val FROM_US = 250_000L
        const val TO_US = 750_000L
    }
}
