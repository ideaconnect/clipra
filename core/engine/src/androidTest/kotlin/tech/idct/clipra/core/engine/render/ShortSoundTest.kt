package tech.idct.clipra.core.engine.render

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.MediaInspector
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.engine.media.WavWriter
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.VoiceCleanup
import java.io.File
import kotlin.math.abs

/**
 * A sound shorter than its clip: a cleaned voice covers only the sound track, which can stop
 * before the picture. It plays to its end, and later clips keep their time. Media3 refused the
 * composition before, so the preview crashed once the voice was ready.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ShortSoundTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val root = File(context.cacheDir, "short-sound-test").apply {
        deleteRecursively()
        mkdirs()
    }

    @Test
    fun aVoiceShorterThanItsClipKeepsLaterClipsInTime() {
        val gray = File(root, "gray.mp4")
        instrumentation.context.assets.open("spike/gray.mp4").use { input ->
            gray.outputStream().use { input.copyTo(it) }
        }
        val asset = MediaInspector.inspect(context, Uri.fromFile(gray))
        // The cleaned voice of the first clip lasts a second of its two.
        val voice = File(root, "voice.wav")
        WavWriter(voice, RATE).use { it.write(FloatArray(RATE), RATE) }
        val cache = MediaCache(File(root, "cache"))
        val media = object : MediaResolver by ProjectMediaResolver(context, cache, true, 64) {
            override fun voice(asset: MediaAsset, cleanup: VoiceCleanup?, noise: Float?): String =
                voice.path
        }
        val project = Project(
            id = "short",
            name = "Short",
            media = listOf(asset),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip(
                            "cleaned",
                            ClipType.Video,
                            0,
                            2_000_000,
                            MediaRef(asset.id, 0, 2_000_000),
                            audio = AudioSettings(voiceCleanup = VoiceCleanup()),
                        ),
                        Clip(
                            "next",
                            ClipType.Video,
                            2_000_000,
                            2_000_000,
                            MediaRef(asset.id, 2_000_000, 4_000_000),
                        ),
                    ),
                ),
            ),
        )
        val timeline = ProjectRender.timeline(project, media, 64, 64)

        val composition = CompositionFactory.create(context, timeline)

        val sound = composition.sequences[1].editedMediaItems
        val totalUs = sound.sumOf { it.presentationDurationUs }
        assertEquals(4_000_000.0, totalUs.toDouble(), 2_000.0)
        // The second clip's sound starts at 2 s: a second of voice, then a second of gap.
        val beforeNext = sound.dropLast(1).sumOf { it.presentationDurationUs }
        assertTrue("$beforeNext us before the next clip", abs(beforeNext - 2_000_000) < 2_000)
        // The player takes it (it threw IllegalArgumentException before).
        instrumentation.runOnMainSync {
            val player = CompositionPlayer.Builder(context).build()
            player.setComposition(composition)
            player.release()
        }
    }

    private companion object {
        const val RATE = 48_000
    }
}
