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
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.Keyframe
import tech.idct.clipra.core.model.Keyframed
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Stabilizing
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.model.transformAt
import java.io.File
import java.net.URL
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sin

/**
 * Video stabilization's analysis (#54) on a handheld-looking test clip: MediaPipe's test portrait
 * (public domain) shaken and slowly panned by keyframes, exported by Clipra at 1080 × 1920. The
 * camera path found matches the shake that was put in, and steadied, the clip keeps its pan.
 */
@RunWith(AndroidJUnit4::class)
class StabilizerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "stabilizer-test")

    /** Shake ten times a second around a slow pan to the right, zoomed in to keep edges hidden. */
    private val shake = Keyframed(
        Transform(scale = 1.15f),
        List(31) { i ->
            val t = i / 10f
            Keyframe(
                (i * 100_000).toLong(),
                Transform(
                    x = 0.02f * t + 0.012f * sin(t * 17f) + 0.006f * sin(t * 41f),
                    y = 0.008f * sin(t * 13f + 1f),
                    scale = 1.15f,
                ),
                Easing.Linear,
            )
        },
    )

    @Test
    fun theShakeIsFoundAndSteadied() = runBlocking {
        val photo =
            Clip("p", ClipType.Photo, 0, 3_000_000, MediaRef("portrait", 0, 0), transform = shake)
        val video = clip(photo)
        val asset = MediaInspector.inspect(context, Uri.fromFile(video))
        val stabilizer =
            Stabilizer(context, MediaCache(File(root, "cache").apply { deleteRecursively() }))

        val started = System.nanoTime()
        assertTrue(stabilizer.prepare(asset))
        val seconds = (System.nanoTime() - started) / 1e9
        val motion = stabilizer.motion(asset)!!
        Log.i(TAG, "${motion.steps.size} frames analysed in ${"%.1f".format(seconds)} s")
        assertEquals(1080f / 1920f, motion.aspect, 0.01f)

        // The camera path found against the picture's own movement, in fractions of the width.
        var x = 0f
        var y = 0f
        var worst = 0f
        val start = photo.transformAt(0)
        for ((i, step) in motion.steps.withIndex()) {
            if (i == 0 || step.timeUs >= 3_000_000) continue
            x += step.dx
            y += step.dy
            val truth = photo.transformAt(step.timeUs)
            val dx = x - (truth.x - start.x)
            val dy = y - (truth.y - start.y) * 1920f / 1080f
            worst = maxOf(worst, abs(dx), abs(dy))
        }
        // In pixels of a 720-wide proxy.
        Log.i(TAG, "worst path error ${worst * 720} px")
        assertTrue("worst ${worst * 720} px", worst * 720 < 2f)

        // Steadied at the default strength: the shake goes, the pan stays, no edge shows.
        val steps = motion.steps.filter { it.timeUs < 3_000_000 }
        val steadied = Stabilizing.stabilize(steps, 50, motion.aspect)
        val path = steps.runningFold(0f) { sum, step -> sum + step.dx }.drop(1)
        val after = path.mapIndexed { i, p -> p + steadied.corrections[i].dx }
        val jitter = { xs: List<Float> -> xs.zipWithNext { a, b -> abs(b - a) }.average() }
        Log.i(TAG, "jitter ${jitter(path)} -> ${jitter(after)}, zoom ${steadied.zoom}")
        assertTrue(jitter(after) < jitter(path) * 0.4)
        assertTrue("pan ${after[75] - after[15]}", after[75] - after[15] > 0.025f)
    }

    /** [photo] of the portrait exported at 1080 × 1920. */
    private suspend fun clip(photo: Clip): File {
        root.mkdirs()
        val portrait = portrait()
        val asset = MediaInspector.inspect(context, Uri.fromFile(portrait))
        val project = Project(
            id = "stabilizer-test",
            name = "Stabilizer test",
            media = listOf(asset),
            layers = listOf(
                Layer("L1", clips = listOf(photo.copy(source = MediaRef(asset.id, 0, 0)))),
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
        const val TAG = "StabilizerTest"
        const val PORTRAIT_URL = "https://storage.googleapis.com/mediapipe-assets/portrait.jpg"
        const val PORTRAIT_SHA256 =
            "a6f11efaa834706db23f275b6115058fa87fc7f14362681e6abe14e82749de3e"
    }
}
