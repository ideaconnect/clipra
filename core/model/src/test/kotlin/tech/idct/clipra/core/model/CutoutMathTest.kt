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

    // Chroma key (#51).

    private val green = ChromaKey()
    private val keyChroma = chromaOf(ChromaKey.DEFAULT_COLOR)

    private fun chromaOf(argb: Long): FloatArray {
        val c = argb.toInt()
        return CutoutMath.chroma(
            (c shr 16 and 255) / 255f,
            (c shr 8 and 255) / 255f,
            (c and 255) / 255f,
        )
    }

    private fun keyed(r: Int, g: Int, b: Int, key: ChromaKey = green): Float {
        val d = CutoutMath.chromaDistance(r / 255f, g / 255f, b / 255f, chromaOf(key.color))
        return CutoutMath.keyAlpha(d, key.similarity, key.smoothness)
    }

    @Test
    fun aGreenScreenGoesAndTheSubjectStays() {
        // The screen itself, in shadow, lit up and noisy.
        val screen = listOf(
            Triple(59, 179, 74),
            Triple(30, 110, 40),
            Triple(120, 230, 130),
            Triple(70, 190, 60),
        )
        for ((r, g, b) in screen) {
            assertEquals("$r $g $b", 0f, keyed(r, g, b), 0.001f)
        }
        // Skin, a blue shirt, white, black, grey and dark hair.
        val subject = listOf(
            Triple(200, 140, 105),
            Triple(48, 78, 140),
            Triple(240, 240, 240),
            Triple(20, 20, 20),
            Triple(128, 128, 128),
            Triple(48, 32, 28),
        )
        for ((r, g, b) in subject) {
            assertEquals("$r $g $b", 1f, keyed(r, g, b), 0.001f)
        }
        // More similarity takes more away; none takes only the key itself.
        val greenish = Triple(120, 140, 115)
        assertTrue(keyed(greenish.first, greenish.second, greenish.third) > 0.5f)
        assertEquals(
            0f,
            keyed(greenish.first, greenish.second, greenish.third, green.copy(similarity = 1f)),
            0.001f,
        )
    }

    @Test
    fun spillRemovalTakesTheKeysTintOffWhatStays() {
        // Skin lit green by the screen.
        val tinted = floatArrayOf(170 / 255f, 175 / 255f, 110 / 255f)
        val d = CutoutMath.chromaDistance(tinted[0], tinted[1], tinted[2], keyChroma)
        val cleaned = CutoutMath.despill(tinted, d, green.similarity, green.smoothness, green.spill)
        val excess = { c: FloatArray -> c[1] - maxOf(c[0], c[2]) }
        assertTrue(excess(cleaned) < excess(tinted))
        // Without spill removal, nothing changes; far from the key, nothing changes either.
        assertArrayEquals(tinted, CutoutMath.despill(tinted, d, 0.42f, 0.18f, 0f), 0f)
        val skin = floatArrayOf(200 / 255f, 140 / 255f, 105 / 255f)
        val far = CutoutMath.chromaDistance(skin[0], skin[1], skin[2], keyChroma)
        assertArrayEquals(skin, CutoutMath.despill(skin, far, 0.42f, 0.18f, 1f), 0.001f)
    }

    @Test
    fun theKeyIsGuessedFromTheEdges() {
        val w = 40
        val h = 60
        // A green screen with a person-coloured block in the middle.
        val screen = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in 12..27 && y in 15..59) 0xFFC88C69.toInt() else 0xFF3CB44A.toInt()
        }
        val guessed = CutoutMath.guessKey(screen, w, h)!!
        assertEquals(0x3C, (guessed shr 16 and 255).toInt())
        assertEquals(0xB4, (guessed shr 8 and 255).toInt())
        // A blue screen.
        val blue = IntArray(w * h) { 0xFF1E46DC.toInt() }
        assertEquals(0xFF1E46DC, CutoutMath.guessKey(blue, w, h))
        // No strong colour at the edges: no guess.
        val grey = IntArray(w * h) { 0xFF808080.toInt() }
        assertNull(CutoutMath.guessKey(grey, w, h))
    }

    @Test
    fun setCutoutKeepsTheKeyInRange() {
        val video = Clip("v", ClipType.Video, 0, 1_000_000, MediaRef("m", 0, 1_000_000))
        val project =
            Project(id = "p", name = "p", layers = listOf(Layer("L1", clips = listOf(video))))
        val key = ChromaKey(similarity = 2f, smoothness = -1f, spill = 0.5f)
        val keyed = SetCutout("v", Cutout(key = key)).apply(project)
        assertEquals(
            ChromaKey(similarity = 1f, smoothness = 0f, spill = 0.5f),
            keyed.findClip("v")!!.second.cutout!!.key,
        )
    }
}
