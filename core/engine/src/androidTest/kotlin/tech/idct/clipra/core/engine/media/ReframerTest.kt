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
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipGeometry
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.Fit
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Keyframe
import tech.idct.clipra.core.model.Keyframed
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Reframe
import tech.idct.clipra.core.model.Size
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.model.transformAt
import java.io.File
import java.net.URL
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Auto-reframe (#53) on a test clip where the subject crosses the frame: MediaPipe's test
 * portrait (public domain) walking from the left of a 16:9 frame to the right over three seconds,
 * exported by Clipra. Reframed to 9:16, following the face and following the person, the face
 * stays inside the new frame, near its middle, all the way.
 */
@RunWith(AndroidJUnit4::class)
class ReframerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "reframer-test")

    /** The face in the portrait, as the detector finds it on the still. */
    private val faceU = 0.487f
    private val faceV = 0.226f

    private val wide = Format(ratio = AspectRatio.Landscape16x9)

    /** The whole portrait, a third of the way left, walking to a third of the way right. */
    private val walking = Keyframed(
        Transform(x = -0.3f, fit = Fit.Fit),
        listOf(
            Keyframe(0, Transform(x = -0.3f, fit = Fit.Fit), Easing.Linear),
            Keyframe(3_000_000, Transform(x = 0.3f, fit = Fit.Fit), Easing.Linear),
        ),
    )

    private val photoClip = Clip(
        "p",
        ClipType.Photo,
        0,
        3_000_000,
        MediaRef("portrait", 0, 0),
        transform = walking,
    )

    @Test
    fun theFaceStaysInTheNewFrameFollowingTheFace() = check(Reframe.Follow.Face)

    @Test
    fun theFaceStaysInTheNewFrameFollowingThePerson() = check(Reframe.Follow.Subject)

    private fun check(follow: Reframe.Follow): Unit = runBlocking {
        val video = clip()
        val asset = MediaInspector.inspect(context, Uri.fromFile(video))
        val clip =
            Clip("v", ClipType.Video, 0, asset.durationUs, MediaRef(asset.id, 0, asset.durationUs))
        val project = Project(
            id = "reframer-test",
            name = "Reframer test",
            format = wide,
            media = listOf(asset),
            layers = listOf(Layer("L1", clips = listOf(clip))),
        )
        val cache = MediaCache(File(root, "cache-$follow"))
        val reframer = Reframer(context, cache, FaceTracks(context, cache), Cutouts(context, cache))
        val started = System.nanoTime()
        var progress = 0f
        val samples = reframer.track(project, follow) { progress = it }
        Log.i(TAG, "$follow: tracked in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertEquals(1f, progress, 0f)
        val copy = Reframe.apply(
            project,
            AspectRatio.Portrait9x16,
            follow,
            samples,
            Reframe.DEFAULT_MOTION,
            null,
        )
        val moved = copy.findClip("v")!!.second
        assertTrue(
            "keyframes ${moved.transform.keyframes.size}",
            moved.transform.keyframes.size >= 2,
        )

        val oldFrame = wide.outputSize
        val newFrame = copy.format.outputSize
        val picture = asset.displaySize
        var worst = 0f
        for (timeUs in 0 until 3_000_000L step 100_000L) {
            // Where the face is in the exported video, then where the copy shows it.
            val inVideo = ClipGeometry.toFrame(
                photoClip.transformAt(timeUs),
                oldFrame,
                Size(820, 1024),
                faceU,
                faceV,
            )
            val shown = ClipGeometry.toFrame(
                moved.transformAt(timeUs),
                newFrame,
                picture,
                inVideo.x / oldFrame.width,
                inVideo.y / oldFrame.height,
            )
            val fromMiddle = abs(shown.x / newFrame.width - 0.5f)
            worst = maxOf(worst, fromMiddle)
            assertTrue("$follow at $timeUs: face at ${shown.x / newFrame.width}", fromMiddle < 0.3f)
        }
        Log.i(TAG, "$follow: the face strays at most $worst of the frame from its middle")
        Unit
    }

    /** The portrait walking across a 16:9 frame, exported at 1920 × 1080. */
    private suspend fun clip(): File {
        root.mkdirs()
        val made = File(root, "walking.mp4")
        if (made.exists()) return made
        val portrait = portrait()
        val asset = MediaInspector.inspect(context, Uri.fromFile(portrait))
        val project = Project(
            id = "reframer-walk",
            name = "Walk",
            format = wide,
            media = listOf(asset),
            layers = listOf(
                Layer("L1", clips = listOf(photoClip.copy(source = MediaRef(asset.id, 0, 0)))),
            ),
        )
        val renderer = ExportRenderer(context, MediaCache(File(root, "export")))
        renderer.render(project, ExportSettings.forProject(wide), PauseGate()) { }.file
            .renameTo(made)
        return made
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
        const val TAG = "ReframerTest"
        const val PORTRAIT_URL = "https://storage.googleapis.com/mediapipe-assets/portrait.jpg"
        const val PORTRAIT_SHA256 =
            "a6f11efaa834706db23f275b6115058fa87fc7f14362681e6abe14e82749de3e"
    }
}
