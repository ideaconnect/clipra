package tech.idct.clipra.core.engine.media

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.export.ExportRenderer
import tech.idct.clipra.core.engine.render.PauseGate
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipGeometry
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.FaceBox
import tech.idct.clipra.core.model.FaceTracking
import tech.idct.clipra.core.model.Keyframe
import tech.idct.clipra.core.model.Keyframed
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Size
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.model.transformAt
import java.io.File
import java.net.URL
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Follow face (#52) on a test clip where the head moves: MediaPipe's test portrait (public
 * domain) panned across a 9:16 frame and zoomed in over three seconds, exported by Clipra. The
 * tracked path stays on the face all the way, where the clip's own transform puts it.
 */
@RunWith(AndroidJUnit4::class)
class FaceTracksTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "face-tracks-test")

    /** The face in the portrait, as the detector finds it on the still. */
    private val faceU = 0.487f
    private val faceV = 0.226f

    private val moving = Keyframed(
        Transform(x = -0.12f, y = 0.05f),
        listOf(
            Keyframe(0, Transform(x = -0.12f, y = 0.05f), Easing.Linear),
            Keyframe(3_000_000, Transform(x = 0.12f, y = 0.15f, scale = 1.25f), Easing.Linear),
        ),
    )

    @Test
    fun theMaskStaysOnTheFaceAsTheHeadMoves() = runBlocking {
        val photoClip = Clip(
            "p",
            ClipType.Photo,
            0,
            3_000_000,
            MediaRef("portrait", 0, 0),
            transform = moving,
        )
        val video = clip(photoClip)
        val asset = MediaInspector.inspect(context, Uri.fromFile(video))
        val clip = Clip(
            "v",
            ClipType.Video,
            0,
            asset.durationUs,
            MediaRef(asset.id, 0, asset.durationUs),
        )
        val tracks = FaceTracks(context, MediaCache(File(root, "cache")))
        val started = System.nanoTime()
        var progress = 0f
        val samples = tracks.find(asset, clip, onProgress = { progress = it })!!
        Log.i(TAG, "${samples.size} samples in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertEquals(1f, progress, 0f)
        val seen = samples.count { it.box != null }
        assertTrue("face seen in $seen of ${samples.size}", seen >= samples.size * 0.9)

        val path = FaceTracking.track(samples)!!
        val frame = Size(1080, 1920)
        val source = Size(820, 1024)
        var worst = 0f
        for ((timeUs, box) in path) {
            if (timeUs >= 3_000_000) continue
            val expected = ClipGeometry.toFrame(
                photoClip.transformAt(timeUs),
                frame,
                source,
                faceU,
                faceV,
            )
            val dx = abs(box.x - expected.x / frame.width)
            val dy = abs(box.y - expected.y / frame.height)
            worst = maxOf(worst, dx, dy)
            assertTrue("at $timeUs: off by $dx, $dy", dx < 0.03f && dy < 0.03f)
        }
        Log.i(TAG, "worst distance from the face: $worst")
        // The keyframes follow the path five times a second, over the head.
        val keyframes = FaceTracking.keyframes(path, tech.idct.clipra.core.model.Mask())
        assertTrue("keyframes ${keyframes.size}", keyframes.size in 14..17)
    }

    /**
     * A face about 70 px of the 1080 px wide frame, too small to find in all of it, is followed
     * from the mask put on it (#182): the mask's tiles find it.
     */
    @Test
    fun aSmallFaceIsFollowedFromTheMaskOnIt() = runBlocking {
        val walking = Keyframed(
            Transform(x = -0.2f, y = -0.25f, scale = 0.3f),
            listOf(
                Keyframe(0, Transform(x = -0.2f, y = -0.25f, scale = 0.3f), Easing.Linear),
                Keyframe(3_000_000, Transform(x = 0.15f, y = -0.2f, scale = 0.3f), Easing.Linear),
            ),
        )
        val photoClip = Clip(
            "p",
            ClipType.Photo,
            0,
            3_000_000,
            MediaRef("portrait", 0, 0),
            transform = walking,
        )
        val asset = MediaInspector.inspect(context, Uri.fromFile(clip(photoClip)))
        val clip = Clip(
            "v",
            ClipType.Video,
            0,
            asset.durationUs,
            MediaRef(asset.id, 0, asset.durationUs),
        )
        val frame = Size(1080, 1920)
        val source = Size(820, 1024)
        fun face(timeUs: Long) =
            ClipGeometry.toFrame(photoClip.transformAt(timeUs), frame, source, faceU, faceV)
        val start = face(0)
        val mask = FaceBox(start.x / frame.width, start.y / frame.height, 0.1f, 0.07f)
        val tracks = FaceTracks(context, MediaCache(File(root, "cache-small")))
        val started = System.nanoTime()
        val samples = tracks.find(asset, clip, 0, mask)!!
        Log.i(TAG, "small: ${samples.size} samples in ${(System.nanoTime() - started) / 1e6} ms")
        val seen = samples.count { it.box != null }
        assertTrue("face seen in $seen of ${samples.size}", seen >= samples.size * 0.8)
        for ((timeUs, box) in FaceTracking.track(samples)!!) {
            if (timeUs >= 3_000_000) continue
            val expected = face(timeUs)
            val dx = abs(box.x - expected.x / frame.width)
            val dy = abs(box.y - expected.y / frame.height)
            assertTrue("at $timeUs: off by $dx, $dy", dx < 0.02f && dy < 0.02f)
        }
    }

    /** [photo] of the portrait exported at 1080 × 1920. */
    private suspend fun clip(photo: Clip): File {
        root.mkdirs()
        val portrait = portrait()
        val asset = MediaInspector.inspect(context, Uri.fromFile(portrait))
        val project = Project(
            id = "face-tracks-test",
            name = "Face tracks test",
            media = listOf(asset),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(photo.copy(source = MediaRef(asset.id, 0, 0))),
                ),
            ),
        )
        val renderer = ExportRenderer(context, MediaCache(File(root, "export")))
        return renderer.render(project, ExportSettings(), PauseGate()) { }.file
    }

    private fun portrait(): File {
        val file = File(root, "portrait.jpg")
        if (!file.exists() || sha256(file) != PORTRAIT_SHA256) {
            URL(PORTRAIT_URL).openStream().use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }
        assertEquals(PORTRAIT_SHA256, sha256(file))
        return file
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "FaceTracksTest"
        const val PORTRAIT_URL = "https://storage.googleapis.com/mediapipe-assets/portrait.jpg"
        const val PORTRAIT_SHA256 =
            "a6f11efaa834706db23f275b6115058fa87fc7f14362681e6abe14e82749de3e"
    }
}
