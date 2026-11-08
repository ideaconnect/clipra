package tech.idct.clipra.core.engine.export

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.MediaInspector
import tech.idct.clipra.core.engine.render.PauseGate
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.FrameRate
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Resolution
import tech.idct.clipra.core.model.export.ExportSettings
import java.io.File
import kotlin.math.abs

/**
 * A 60 fps export gets the video bitrate it asks for (#228). The encoder was told 30 fps, and
 * one whose rate control spends the bitrate per frame by that wrote twice as much. Film grain at
 * full strength changes every pixel of every frame, so the encoder uses all of its bitrate.
 */
@RunWith(AndroidJUnit4::class)
class ExportBitrateTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    private fun asset(path: String): Uri {
        val file = File(
            context.cacheDir,
            "export-bitrate-test/${path.substringAfterLast('/')}",
        ).apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open(path).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(file)
    }

    @Test
    fun aSixtyFpsExportGetsTheBitrateItAsksFor() = runBlocking {
        val gray = MediaInspector.inspect(context, asset("spike/gray.mp4"))
        val grain = Effect("grain", strength = 1f)
        val project = Project(
            id = "export-bitrate-test",
            name = "Export bitrate test",
            media = listOf(gray),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip(
                            "a",
                            ClipType.Video,
                            0,
                            DURATION_US,
                            MediaRef(gray.id, 0, DURATION_US),
                            effects = listOf(grain),
                        ),
                    ),
                ),
            ),
        )
        // 720p asks for 11.25 Mb/s at 60 fps: within reach of the emulators' software encoder.
        val settings = ExportSettings(resolution = Resolution.HD, frameRate = FrameRate.Fps60)
        val renderer = ExportRenderer(
            context,
            MediaCache(File(context.cacheDir, "export-bitrate-test/cache")),
        )

        val rendered = renderer.render(project, settings, PauseGate()) {}

        try {
            val video = VideoTrack.of(rendered.file)
            val ratio = video.bitrate / settings.videoBitrate
            Log.i(
                "ExportBitrateTest",
                "720p 60 fps: ${video.frames} frames, video ${video.bitrate.toLong()} b/s for " +
                    "${settings.videoBitrate} (${"%.0f".format(100 * ratio)}%)",
            )
            // Three seconds at 60 frames a second.
            assertTrue("${video.frames} frames", abs(video.frames - 180) <= 2)
            assertTrue(
                "video ${video.bitrate.toLong()} b/s for ${settings.videoBitrate}",
                ratio in 0.85..1.15,
            )
        } finally {
            rendered.file.delete()
        }
        Unit
    }

    /** The video track of an MP4: its frames and bitrate, from the samples' sizes. */
    private class VideoTrack(val frames: Int, val bitrate: Double) {
        companion object {
            fun of(file: File): VideoTrack {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.path)
                    val track = (0 until extractor.trackCount).first {
                        val mime = extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                        mime.orEmpty().startsWith("video/")
                    }
                    extractor.selectTrack(track)
                    var frames = 0
                    var bytes = 0L
                    while (extractor.sampleSize >= 0) {
                        frames++
                        bytes += extractor.sampleSize
                        extractor.advance()
                    }
                    val durationUs = extractor.getTrackFormat(track)
                        .getLong(MediaFormat.KEY_DURATION)
                    assertEquals(DURATION_US.toDouble(), durationUs.toDouble(), 50_000.0)
                    return VideoTrack(frames, bytes * 8 * 1_000_000.0 / durationUs)
                } finally {
                    extractor.release()
                }
            }
        }
    }

    private companion object {
        const val DURATION_US = 3_000_000L
    }
}
