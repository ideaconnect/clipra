package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec

class TextCommandsTest {
    private val sec = 1_000_000L

    private val video = Clip("v", ClipType.Video, 0, 10 * sec, MediaRef("v", 0, 10 * sec))

    private fun text(id: String, start: Long) =
        Clip(id, ClipType.Text, start, 3 * sec, text = TextSpec("Day two"))

    private val project = Project(
        id = "p",
        name = "p",
        layers = listOf(
            Layer("L1", clips = listOf(video)),
            Layer("L2", clips = listOf(text("title", 0))),
        ),
    )

    @Test
    fun textGoesOnTheFirstFreeLayerAboveTheVideo() {
        val later = AddText(text("t", 5 * sec)).apply(project)
        assertEquals(listOf("title", "t"), later.layers[1].clips.map { it.id })

        val overlapping = AddText(text("t", sec), newLayerId = { "L3" }).apply(project)
        assertEquals("L3", overlapping.layers.last().id)
        assertEquals("t", overlapping.layers.last().clips.single().id)
    }

    @Test
    fun aBackgroundMakesACard() {
        val card = SetText("title", TextSpec("Day two", background = 0xFF000000)).apply(project)
        assertEquals(ClipType.TextCard, card.findClip("title")!!.second.type)
        val overlay = SetText("title", TextSpec("Day two", background = null)).apply(card)
        assertEquals(ClipType.Text, overlay.findClip("title")!!.second.type)
        assertEquals("Day two", overlay.findClip("title")!!.second.text?.text)
    }

    @Test
    fun onlyTextClipsTakeText() {
        assertThrows(EditRejected::class.java) { SetText("v", TextSpec("No")).apply(project) }
        assertThrows(IllegalArgumentException::class.java) { AddText(video).apply(project) }
    }
}
