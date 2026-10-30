package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.ColorGrade
import tech.idct.clipra.core.model.Cutout
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project

/** Adjustment clips (#23): added over every layer, graded and filtered, never cut out. */
class AdjustmentTest {
    private val sec = 1_000_000L

    private val project = Project(
        id = "p",
        name = "Lisbon",
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(Clip("v", ClipType.Video, 0, 10 * sec, MediaRef("m", 0, 10 * sec))),
            ),
            Layer("L2", clips = listOf(Clip("t", ClipType.Text, 6 * sec, 2 * sec))),
        ),
    )

    private fun adjustment(start: Long) = Clip("fx", ClipType.Adjustment, start, 3 * sec)

    @Test
    fun itGoesOnTheTopLayerWhenThereIsRoom() {
        val added = AddAdjustment(adjustment(0), "new").apply(project)
        assertEquals(2, added.layers.size)
        // Clips on a layer are in time order.
        assertEquals(listOf("fx", "t"), added.layers.last().clips.map { it.id })
    }

    @Test
    fun otherwiseOnANewLayerOnTop() {
        // The text on L2 is in the way at 6 s.
        val added = AddAdjustment(adjustment(5 * sec), "new").apply(project)
        assertEquals(listOf("L1", "L2", "new"), added.layers.map { it.id })
        assertEquals(listOf("fx"), added.layers.last().clips.map { it.id })
    }

    @Test
    fun itTakesAGradeAndFiltersButNoCutout() {
        val added = AddAdjustment(adjustment(0), "new").apply(project)
        val graded = SetColor("fx", ColorGrade(saturation = -100f)).apply(added)
        assertEquals(-100f, graded.findClip("fx")!!.second.color!!.saturation)
        val filtered = SetFilter("fx", "warm", 0.8f).apply(graded)
        assertTrue(filtered.findClip("fx")!!.second.effects.any { it.type == "warm" })
        assertThrows(EditRejected::class.java) { SetCutout("fx", Cutout()).apply(filtered) }
    }

    @Test
    fun onlyAdjustmentClipsAreAddedThisWay() {
        val video = Clip("x", ClipType.Video, 0, sec, MediaRef("m", 0, sec))
        assertThrows(EditRejected::class.java) { AddAdjustment(video, "new").apply(project) }
    }
}
