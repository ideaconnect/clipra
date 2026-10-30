package tech.idct.clipra.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.edit.EditHistory
import tech.idct.clipra.core.model.edit.EditRejected
import tech.idct.clipra.core.model.edit.SetCutout

/** Person cutout (#50): how much of a pixel stays, how masks are steadied, and the command. */
class CutoutMathTest {
    private val defaults = Cutout()

    private fun alpha(m: Float, cutout: Cutout = defaults) =
        CutoutMath.alpha(m, cutout.edgeRefine, cutout.hairDetail)

    @Test
    fun surePixelsStayOrGoAndTheEdgeIsInBetween() {
        assertEquals(1f, alpha(1f), 0f)
        assertEquals(0f, alpha(0f), 0f)
        assertEquals(0f, alpha(0.05f), 0f)
        // Never less as the model gets surer.
        val steps = (0..100).map { alpha(it / 100f) }
        assertTrue(steps.zipWithNext().all { (a, b) -> b >= a })
        // At the Cutout board's defaults (40 and 70), the model's 50 % line is kept by the hair
        // detail more than by the firm edge.
        assertEquals(0.632f, alpha(0.5f), 0.01f)
        assertEquals(0.293f, alpha(0.5f, defaults.copy(hairDetail = 0f)), 0.01f)
    }

    @Test
    fun edgeRefineFirmsTheEdgeAndHairDetailKeepsTheFringe() {
        val soft = Cutout(edgeRefine = 0f, hairDetail = 0f)
        val crisp = Cutout(edgeRefine = 1f, hairDetail = 0f)
        // The firm edge is narrower when refined: it goes from 0 to 1 over less confidence.
        val width = { c: Cutout ->
            val from = (0..1000).first { alpha(it / 1000f, c) > 0.05f }
            val to = (0..1000).first { alpha(it / 1000f, c) > 0.95f }
            to - from
        }
        assertTrue(width(crisp) < width(soft) / 3)
        // Hair detail keeps the faint fringe partly: none at 0, most of it at 1.
        val fringe = 0.3f
        assertEquals(0f, alpha(fringe, crisp), 0.001f)
        assertEquals(0.385f, alpha(fringe, crisp.copy(hairDetail = 1f)), 0.01f)
        assertEquals(0.27f, alpha(fringe, crisp.copy(hairDetail = 0.7f)), 0.01f)
    }

    @Test
    fun smoothingSteadiesWaveringAndFollowsMotion() {
        val previous = floatArrayOf(0.5f, 0.0f, 1.0f, 0.2f)
        val current = floatArrayOf(0.55f, 1.0f, 0.0f, 0.2f)
        val out = FloatArray(4)
        CutoutMath.smooth(previous, current, out)
        // A small waver mostly keeps the last value.
        assertTrue(out[0] in 0.51f..0.52f)
        // Someone moving in or out is taken at once.
        assertEquals(1f, out[1], 0f)
        assertEquals(0f, out[2], 0f)
        assertEquals(0.2f, out[3], 0f)
        // In place, as the mask cache uses it.
        CutoutMath.smooth(previous, current, previous)
        assertArrayEquals(out, previous, 0f)
    }

    @Test
    fun setCutoutOnPictureClipsOnly() {
        val video = Clip("v", ClipType.Video, 0, 1_000_000, MediaRef("m", 0, 1_000_000))
        val text = Clip("t", ClipType.Text, 0, 1_000_000, text = TextSpec("Hi"))
        val project = Project(
            id = "p",
            name = "p",
            layers = listOf(Layer("L1", clips = listOf(video)), Layer("L2", clips = listOf(text))),
        )
        val history = EditHistory(project)
        val cut = history.apply(SetCutout("v", Cutout(Behind.Blur, edgeRefine = 3f)))
        assertEquals(Cutout(Behind.Blur, edgeRefine = 1f), cut.findClip("v")!!.second.cutout)
        assertEquals(project, history.undo())
        val off = SetCutout("v", null).apply(cut)
        assertNull(off.findClip("v")!!.second.cutout)
        assertThrows(EditRejected::class.java) { SetCutout("t", Cutout()).apply(project) }
    }
}
