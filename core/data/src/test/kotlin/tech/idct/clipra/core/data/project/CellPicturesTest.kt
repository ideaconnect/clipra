package tech.idct.clipra.core.data.project

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project

class CellPicturesTest {
    private fun clip(
        media: String?,
        start: Long,
        type: ClipType = ClipType.Video,
        cell: Int? = null,
    ) = Clip(
        id = "$media@$start",
        type = type,
        startUs = start,
        durationUs = 1_000_000,
        source = media?.let { MediaRef(it, 0, 1_000_000) },
        cell = cell,
    )

    private fun project(layout: Layout, vararg layers: Layer) = Project(
        id = "p",
        name = "P",
        format = Format(ratio = AspectRatio.Square1x1, layout = layout),
        layers = layers.toList(),
    )

    @Test
    fun aSingleFrameShowsTheFirstPhotoOrVideo() {
        val project = project(
            Layout.Single,
            Layer("L1", clips = listOf(clip("song", 0, ClipType.Audio), clip("tram", 2_000_000))),
            Layer("L2", clips = listOf(clip(null, 0, ClipType.Text), clip("stamp", 1_000_000))),
        )

        assertEquals(listOf("stamp"), project.cellPictures())
    }

    @Test
    fun theHigherLayerCoversTheLowerOne() {
        val project = project(
            Layout.Single,
            Layer("L1", clips = listOf(clip("sunset", 0))),
            Layer("L2", clips = listOf(clip("logo", 0, ClipType.Photo))),
        )

        assertEquals(listOf("logo"), project.cellPictures())
    }

    @Test
    fun hiddenLayersAndTextCardsShowNothing() {
        val project = project(
            Layout.Single,
            Layer("L1", hidden = true, clips = listOf(clip("sunset", 0))),
            Layer("L2", clips = listOf(clip(null, 0, ClipType.TextCard))),
        )

        assertEquals(listOf<String?>(null), project.cellPictures())
    }

    @Test
    fun aMosaicShowsEachCellsPicture() {
        val project = project(
            Layout.Grid2x2,
            Layer(
                "L1",
                clips = listOf(
                    clip("a", 0, cell = 0),
                    clip("b", 0, cell = 1),
                    // Cell 7 folds back into cell 3 in a 2×2 grid.
                    clip("d", 0, cell = 7),
                ),
            ),
        )

        assertEquals(listOf("a", "b", null, "d"), project.cellPictures())
    }

    @Test
    fun aClipInNoCellFillsEveryCell() {
        val project = project(
            Layout.Split2,
            Layer("L1", clips = listOf(clip("backdrop", 0), clip("late", 3_000_000, cell = 1))),
        )

        assertEquals(listOf("backdrop", "backdrop"), project.cellPictures())
    }
}
