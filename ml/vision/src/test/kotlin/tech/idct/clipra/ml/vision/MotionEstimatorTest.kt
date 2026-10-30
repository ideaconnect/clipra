package tech.idct.clipra.ml.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Camera motion (#54) from known motion: a still picture moved along a shake path is recovered
 * within 1 px of the 720p proxy (a quarter pixel of the 320 px analysis), with a person-sized
 * thing moving across it on its own.
 */
class MotionEstimatorTest {
    private val width = 320
    private val height = 180

    /** A textured scene: soft blobs of all sizes, like the detail of a street. */
    private val scene: FloatArray by lazy {
        val random = Random(7)
        val blobs = List(260) {
            floatArrayOf(
                random.nextFloat() * (width + 80) - 40,
                random.nextFloat() * (height + 80) - 40,
                2f + random.nextFloat() * 10f,
                random.nextFloat() * 2f - 1f,
            )
        }
        FloatArray(width * height) { i ->
            val x = i % width
            val y = i / width
            var v = 0.5f
            for ((bx, by, r, a) in blobs.map { it.toList() }) {
                val d2 = ((x - bx) * (x - bx) + (y - by) * (y - by)) / (r * r)
                if (d2 < 9f) v += 0.25f * a * exp(-d2)
            }
            v.coerceIn(0f, 1f)
        }
    }

    private val sceneGray: Gray by lazy { Gray(width, height, scene) }

    /**
     * The scene as the camera sees it after it moved by [motion] from the start, with an
     * optional square of its own at [thing].
     */
    private fun frame(motion: Motion, thing: Pair<Float, Float>? = null): Gray {
        val cx = width / 2f
        val cy = height / 2f
        val c = cos(-motion.angle)
        val s = sin(-motion.angle)
        val out = FloatArray(width * height) { i ->
            val u = i % width - cx - motion.dx
            val v = i / width - cy - motion.dy
            // Back through the motion to the scene.
            val x = (c * u - s * v) / motion.scale + cx
            val y = (s * u + c * v) / motion.scale + cy
            sceneGray.at(x, y)
        }
        if (thing != null) {
            val (tx, ty) = thing
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (x - tx in 0f..50f && y - ty in 0f..70f) {
                        out[y * width + x] = 0.15f + 0.6f * ((x / 6 + y / 6) % 2)
                    }
                }
            }
        }
        return Gray(width, height, out)
    }

    private val estimator = MotionEstimator()

    @Test
    fun aShiftTurnAndScaleAreRecovered() {
        val start = frame(Motion.NONE)
        for (truth in listOf(
            Motion(3.4f, -2.1f, 0f, 1f),
            Motion(-1.25f, 0.6f, 0.012f, 1f),
            Motion(0.8f, 1.7f, -0.008f, 1.015f),
        )) {
            val found = estimator.estimate(start, frame(truth))
            assertNotNull(found)
            found!!
            assertEquals("dx of $truth", truth.dx, found.dx, QUARTER)
            assertEquals("dy of $truth", truth.dy, found.dy, QUARTER)
            assertEquals("angle of $truth", truth.angle, found.angle, 0.001f)
            assertEquals("scale of $truth", truth.scale, found.scale, 0.002f)
        }
    }

    @Test
    fun aShakePathIsRecoveredWithinAPixelOfTheProxy() {
        // Handheld shake: a slow drift and a quick wobble.
        val path = List(30) { i ->
            Motion(
                dx = 0.6f * i + 2.5f * sin(i * 1.3f),
                dy = -0.3f * i + 1.8f * sin(i * 0.9f + 1f),
                angle = 0.004f * sin(i * 0.7f),
                scale = 1f,
            )
        }
        val frames = path.map { frame(it) }
        var x = 0f
        var y = 0f
        var worst = 0f
        for (i in 1 until frames.size) {
            val step = estimator.estimate(frames[i - 1], frames[i])!!
            x += step.dx
            y += step.dy
            val dx = x - (path[i].dx - path[0].dx)
            val dy = y - (path[i].dy - path[0].dy)
            worst = maxOf(worst, kotlin.math.abs(dx), kotlin.math.abs(dy))
        }
        // A quarter of a pixel here is a pixel of the 1280 × 720 proxy.
        assertTrue("worst $worst px", worst < QUARTER)
    }

    @Test
    fun somethingMovingAcrossDoesNotCount() {
        val camera = Motion(1.5f, -0.75f, 0f, 1f)
        // A person-sized thing walking right fast while the camera moves a little.
        val before = frame(Motion.NONE, thing = 60f to 60f)
        val after = frame(camera, thing = 78f to 62f)
        val found = estimator.estimate(before, after)!!
        assertEquals(camera.dx, found.dx, QUARTER)
        assertEquals(camera.dy, found.dy, QUARTER)
    }

    @Test
    fun aFlatPictureHasNothingToTrack() {
        val flat = Gray(width, height, FloatArray(width * height) { 0.4f })
        assertEquals(null, estimator.estimate(flat, flat))
    }

    private companion object {
        const val QUARTER = 0.25f
    }
}
