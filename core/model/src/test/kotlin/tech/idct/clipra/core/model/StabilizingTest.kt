package tech.idct.clipra.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.edit.EditRejected
import tech.idct.clipra.core.model.edit.ReplaceMedia
import tech.idct.clipra.core.model.edit.SetStabilize
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** Video stabilization's arithmetic (#54): steadier, pans kept, and never an edge showing. */
class StabilizingTest {
    private val aspect = 16f / 9f
    private val frameUs = 33_333L

    /** A handheld walk with a slow pan: per-frame steps of a pan plus shake. */
    private fun walk(frames: Int, pan: Float = 0.002f, shake: Float = 0.006f): List<CameraStep> {
        val random = Random(54)
        var lastX = 0f
        var lastY = 0f
        return List(frames) { i ->
            val x = pan * i + shake * sin(i * 1.7f) + shake * 0.5f * (random.nextFloat() - 0.5f)
            val y = shake * sin(i * 1.1f + 0.5f)
            val step = CameraStep(i * frameUs, x - lastX, y - lastY, 0.002f * sin(i * 0.9f), 1f)
            lastX = x
            lastY = y
            if (i == 0) step.copy(dx = 0f, dy = 0f, angle = 0f) else step
        }
    }

    /** Where the camera has got to at each frame. */
    private fun path(steps: List<CameraStep>): List<Float> =
        steps.runningFold(0f) { x, step -> x + step.dx }.drop(1)

    @Test
    fun shakeGoesAndThePanStays() {
        val steps = walk(200)
        val stabilized = Stabilizing.stabilize(steps, strength = 50, aspect = aspect)
        val before = path(steps)
        val after = before.mapIndexed { i, x -> x + stabilized.corrections[i].dx }
        // Frame-to-frame jumps beyond the pan's own steady step.
        val jitter = { xs: List<Float> ->
            xs.zipWithNext { a, b -> abs(b - a - 0.002f) }.average()
        }
        assertTrue(
            "jitter ${jitter(after)} vs ${jitter(before)}",
            jitter(after) < jitter(before) * 0.2,
        )
        // The pan is still there: most of the way across, end to end.
        val panned = after[150] - after[50]
        assertEquals(0.2f, panned, 0.03f)
    }

    @Test
    fun theZoomAlwaysCoversTheFrame() {
        val random = Random(1)
        repeat(50) {
            val corrections = List(20) {
                Correction(
                    dx = (random.nextFloat() - 0.5f) * 0.1f,
                    dy = (random.nextFloat() - 0.5f) * 0.06f,
                    angle = (random.nextFloat() - 0.5f) * 0.06f,
                    scale = 1f + (random.nextFloat() - 0.5f) * 0.04f,
                )
            }
            val zoom = Stabilizing.zoomFor(corrections, aspect)
            for (c in corrections) assertCovers(c, zoom)
        }
        // And at every strength, on a real-looking walk.
        val steps = walk(150)
        for (strength in listOf(1, 25, 50, 75, 100)) {
            val stabilized = Stabilizing.stabilize(steps, strength, aspect)
            assertTrue(stabilized.zoom >= 1f)
            for (c in stabilized.corrections) assertCovers(c, stabilized.zoom)
        }
    }

    /** Every corner of the picture reads from inside the frame. */
    private fun assertCovers(correction: Correction, zoom: Float) {
        val hw = 0.5f
        val hh = 0.5f / aspect
        for ((qx, qy) in listOf(-hw to -hh, hw to -hh, -hw to hh, hw to hh)) {
            val (px, py) = Stabilizing.source(correction, zoom, qx, qy)
            assertTrue("$correction at $zoom: $px", abs(px) <= hw + 1e-4f)
            assertTrue("$correction at $zoom: $py", abs(py) <= hh + 1e-4f)
        }
    }

    @Test
    fun moreStrengthIsSteadierAndCropsMore() {
        val steps = walk(200)
        val light = Stabilizing.stabilize(steps, 20, aspect)
        val strong = Stabilizing.stabilize(steps, 90, aspect)
        assertTrue(strong.crop >= light.crop)
        val off = Stabilizing.stabilize(steps, 0, aspect)
        assertEquals(1f, off.zoom, 0f)
        assertTrue(off.corrections.all { it == Correction.NONE })
    }

    @Test
    fun aViolentShakeIsHeldToTheMostZoom() {
        val steps = walk(120, pan = 0f, shake = 0.08f)
        val stabilized = Stabilizing.stabilize(steps, 100, aspect)
        assertTrue(stabilized.zoom <= Stabilizing.MAX_ZOOM)
        for (c in stabilized.corrections) assertCovers(c, stabilized.zoom)
    }

    @Test
    fun betweenFramesTheCorrectionIsInBetween() {
        val stabilized = Stabilized(
            listOf(0L, 100L),
            listOf(Correction(0f, 0f, 0f, 1f), Correction(0.1f, -0.2f, 0.02f, 1.1f)),
            1.2f,
        )
        assertEquals(Correction(0.05f, -0.1f, 0.01f, 1.05f), stabilized.at(50))
        assertEquals(stabilized.corrections.last(), stabilized.at(500))
        assertEquals(1f - 1f / 1.2f, stabilized.crop, 1e-6f)
    }

    @Test
    fun aFaceFollowsTheFrameOntoThePath() {
        val correction = Correction(0.04f, -0.02f, 0.05f, 1.02f)
        // Into the steadied picture and back is where it started.
        val (qx, qy) = Stabilizing.picture(correction, 1.2f, 0.1f, -0.15f)
        val (px, py) = Stabilizing.source(correction, 1.2f, qx, qy)
        assertEquals(0.1f, px, 1e-5f)
        assertEquals(-0.15f, py, 1e-5f)
        // A face in the middle moves with the correction, and grows with the zoom.
        val face = FaceBox(0.5f, 0.5f, 0.2f, 0.3f)
        val moved = Stabilizing.steady(face, Correction(0.1f, 0.05f, 0f, 1f), 1.25f, aspect)
        assertEquals(0.5f + 0.125f, moved.x, 1e-5f)
        assertEquals(0.5f + 0.0625f * aspect, moved.y, 1e-5f)
        assertEquals(0.25f, moved.width, 1e-5f)
        assertEquals(0.375f, moved.height, 1e-5f)
        assertEquals(face, Stabilizing.steady(face, Correction.NONE, 1f, aspect))
    }

    @Test
    fun onlyVideoClipsAreSteadied() {
        val video = Clip("v", ClipType.Video, 0, 1_000_000, MediaRef("m", 0, 1_000_000))
        val photo = Clip("p", ClipType.Photo, 1_000_000, 1_000_000, MediaRef("m", 0, 0))
        val project = Project(
            id = "p",
            name = "Shaky",
            layers = listOf(Layer("L1", clips = listOf(video, photo))),
        )
        val steadied = SetStabilize("v", Stabilization(140)).apply(project)
        assertEquals(Stabilization(100), steadied.findClip("v")?.second?.stabilize)
        val off = SetStabilize("v", null).apply(steadied)
        assertEquals(null, off.findClip("v")?.second?.stabilize)
        assertThrows(EditRejected::class.java) {
            SetStabilize("p", Stabilization()).apply(project)
        }
        // A photo put in its place isn't steadied.
        val withPhoto = steadied.copy(
            media = listOf(MediaAsset("still", "content://still", MediaKind.Photo)),
        )
        val replaced = ReplaceMedia("v", "still").apply(withPhoto)
        assertEquals(null, replaced.findClip("v")?.second?.stabilize)
    }
}
