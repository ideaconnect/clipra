package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.filter

/** The effect stack (#29): add, order, turn off, remove, paste, and the Filters board's pick. */
class EffectStackTest {
    private val video = Clip(
        "v",
        ClipType.Video,
        0,
        1_000_000,
        MediaRef("v", 0, 1_000_000),
        effects = listOf(Effect("cinematic", 0.8f), Effect("mosaic", 0.6f, mapOf("size" to 0.1f))),
    )
    private val project = Project(
        id = "p",
        name = "Stack",
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    video,
                    Clip("t", ClipType.Text, 1_000_000, 1_000_000, text = TextSpec("Hi")),
                ),
            ),
            Layer("L2", locked = true, clips = listOf(video.copy(id = "locked"))),
        ),
    )

    private fun Project.stack(id: String = "v") = findClip(id)!!.second.effects

    private fun Project.types(id: String = "v") = stack(id).map { it.type }

    @Test
    fun effectsAreAddedOnTopOncePerType() {
        val added = AddEffect("v", "grain").apply(project)
        assertEquals(listOf("cinematic", "mosaic", "grain"), added.types())
        assertEquals(Filters.DEFAULT_STRENGTH, added.stack().last().strength, 0f)
        // Each type once; only picture clips on unlocked layers.
        assertThrows(EditRejected::class.java) { AddEffect("v", "mosaic").apply(project) }
        assertThrows(EditRejected::class.java) { AddEffect("v", "sparkles").apply(project) }
        assertThrows(EditRejected::class.java) { AddEffect("t", "grain").apply(project) }
        assertThrows(EditRejected::class.java) { AddEffect("locked", "grain").apply(project) }
    }

    @Test
    fun effectsMoveTurnOffChangeAndGo() {
        val moved = MoveEffect("v", 1, 0).apply(project)
        assertEquals(listOf("mosaic", "cinematic"), moved.types())
        // Settings travel with the effect.
        assertEquals(0.1f, moved.stack()[0].params["size"])

        val off = SetEffectEnabled("v", 0, false).apply(project)
        assertFalse(off.stack()[0].enabled)
        assertEquals(project.types(), off.types())

        assertEquals(0.3f, SetEffectStrength("v", 1, 0.3f).apply(project).stack()[1].strength, 0f)
        assertEquals(listOf("mosaic"), RemoveEffect("v", 0).apply(project).types())
        assertThrows(EditRejected::class.java) { RemoveEffect("v", 2).apply(project) }
        assertThrows(EditRejected::class.java) { MoveEffect("v", 0, 5).apply(project) }
    }

    @Test
    fun aStackIsPastedInOrder() {
        val copied = listOf(Effect("vhs", 0.5f), Effect("mono", 1f, enabled = false))
        val pasted = PasteEffects("v", copied + Effect("vhs") + Effect("unknown")).apply(project)
        assertEquals(copied, pasted.stack())
        assertThrows(EditRejected::class.java) { PasteEffects("t", copied).apply(project) }
    }

    @Test
    fun theFiltersBoardPicksTheFirstFilterInItsPlace() {
        // Picking another filter replaces the first and keeps the rest.
        val warm = SetFilter("v", "warm", 0.5f).apply(project)
        assertEquals(listOf("warm", "mosaic"), warm.types())
        // A filter further down moves up with its settings.
        val mosaic = SetFilter("v", "mosaic", 0.9f).apply(project)
        assertEquals(listOf("mosaic"), mosaic.types())
        assertEquals(0.1f, mosaic.stack()[0].params["size"])
        assertEquals("mosaic", mosaic.findClip("v")!!.second.filter?.type)
        // None takes only the first away.
        assertEquals(listOf("mosaic"), SetFilter("v", null, 0f).apply(project).types())
    }
}
