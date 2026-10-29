package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.MaskShape
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.filter

class EffectCommandsTest {
    private fun clip(id: String, type: ClipType = ClipType.Video) =
        Clip(id, type, 0, 1_000_000, MediaRef(id, 0, 1_000_000))

    private val project = Project(
        id = "p",
        name = "p",
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    clip("a").copy(effects = listOf(Effect("chroma-key"))),
                    clip("music", ClipType.Audio),
                ),
            ),
            Layer("L2", clips = listOf(clip("b", ClipType.Photo)), locked = true),
            Layer("L3", clips = listOf(clip("c", ClipType.Photo))),
        ),
    )

    private fun Project.clip(id: String) = findClip(id)!!.second

    @Test
    fun pickingAFilterReplacesTheOldOneAndKeepsOtherEffects() {
        val warm = SetFilter("a", "warm", 0.8f).apply(project)
        assertEquals(listOf("warm", "chroma-key"), warm.clip("a").effects.map { it.type })
        val noir = SetFilter("a", "noir", 0.5f).apply(warm)
        assertEquals(listOf("noir", "chroma-key"), noir.clip("a").effects.map { it.type })
        assertEquals(0.5f, noir.clip("a").filter!!.strength)
    }

    @Test
    fun noneRemovesTheFilter() {
        val edited = SetFilter("a", null, 0f).apply(SetFilter("a", "mono", 1f).apply(project))
        assertNull(edited.clip("a").filter)
        assertEquals(listOf("chroma-key"), edited.clip("a").effects.map { it.type })
    }

    @Test
    fun theSameFilterKeepsItsParameters() {
        val mosaic = SetFilter("a", "mosaic", 0.8f).apply(project).let { p ->
            val clip = p.clip("a")
            val sized = clip.filter!!.copy(params = mapOf(Filters.MOSAIC_SIZE to 0.2f))
            p.copy(
                layers = p.layers.map { layer ->
                    layer.copy(
                        clips = layer.clips.map {
                            if (it.id == "a") it.copy(effects = listOf(sized)) else it
                        },
                    )
                },
            )
        }
        val stronger = SetFilter("a", "mosaic", 1f).apply(mosaic)
        assertEquals(0.2f, stronger.clip("a").filter!!.params[Filters.MOSAIC_SIZE])
    }

    @Test
    fun applyToAllSkipsSoundAndLockedLayers() {
        val edited = ApplyFilterToAll("vivid", 0.8f).apply(project)
        assertEquals("vivid", edited.clip("a").filter?.type)
        assertEquals("vivid", edited.clip("c").filter?.type)
        assertNull(edited.clip("b").filter)
        assertNull(edited.clip("music").filter)
    }

    @Test
    fun lockedClipsAndUnknownFiltersRefuse() {
        assertThrows(EditRejected::class.java) { SetFilter("b", "warm", 1f).apply(project) }
        assertThrows(IllegalArgumentException::class.java) {
            SetFilter("a", "sparkle", 1f).apply(project)
        }
    }

    @Test
    fun theCatalogHas24Filters() {
        assertEquals(13, Filters.looks.size)
        assertEquals(11, Filters.stylized.size)
        assertEquals(24, Filters.all.toSet().size)
    }

    @Test
    fun aMaskAddsTheEffectItLimitsInOneStep() {
        val oval = Mask(MaskShape.Oval, feather = 0.05f, effect = "mosaic")
        val edited = SetMask("c", oval, addIfMissing = "mosaic").apply(project)
        val clip = edited.clip("c")
        assertEquals(oval, clip.mask)
        assertEquals("mosaic", clip.filter?.type)
        // A clip that already has it keeps its filter as it is.
        val again = SetMask("c", oval.copy(invert = true), "mosaic").apply(edited)
        assertEquals(clip.filter, again.clip("c").filter)
        assertNull(SetMask("c", null).apply(again).clip("c").mask)
    }

    @Test
    fun effectParametersChangeOnlyThatEffect() {
        val mosaic = SetFilter("a", "mosaic", 0.8f).apply(project)
        val sized = SetEffectParam("a", "mosaic", Filters.MOSAIC_SIZE, 0.12f).apply(mosaic)
        assertEquals(0.12f, sized.clip("a").filter!!.params[Filters.MOSAIC_SIZE])
        assertEquals(emptyMap<String, Float>(), sized.clip("a").effects[1].params)
        assertThrows(EditRejected::class.java) {
            SetEffectParam("c", "mosaic", Filters.MOSAIC_SIZE, 0.1f).apply(project)
        }
    }
}
