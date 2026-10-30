package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.CellRect
import tech.idct.clipra.core.model.Cells
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Point
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Size

/** Mosaic (split-screen) layouts (#17): cells, putting clips in them, changing the layout. */
class MosaicTest {
    private fun format(layout: Layout, ratio: AspectRatio = AspectRatio.Portrait9x16) =
        Format(ratio = ratio, layout = layout)

    @Test
    fun cellsFollowTheDesign() {
        // Splits stack in a portrait frame and sit side by side otherwise.
        assertEquals(1 to 2, Cells.grid(format(Layout.Split2)))
        assertEquals(1 to 3, Cells.grid(format(Layout.Split3)))
        assertEquals(2 to 1, Cells.grid(format(Layout.Split2, AspectRatio.Landscape16x9)))
        assertEquals(3 to 1, Cells.grid(format(Layout.Split3, AspectRatio.Square1x1)))
        assertEquals(2 to 2, Cells.grid(format(Layout.Grid2x2)))
        assertEquals(3 to 3, Cells.grid(format(Layout.Grid3x3, AspectRatio.Cinema21x9)))

        // Row by row from the top left, tiling the frame.
        val grid = format(Layout.Grid2x2)
        assertEquals(CellRect(0f, 0f, 0.5f, 0.5f), Cells.rect(grid, 0))
        assertEquals(CellRect(0.5f, 0f, 0.5f, 0.5f), Cells.rect(grid, 1))
        assertEquals(CellRect(0f, 0.5f, 0.5f, 0.5f), Cells.rect(grid, 2))
        assertEquals(CellRect(0.5f, 0.5f, 0.5f, 0.5f), Cells.rect(grid, 3))
        val split = Cells.rect(format(Layout.Split3), 2)!!
        assertEquals(2f / 3, split.top, 1e-6f)
        assertEquals(1f / 3, split.height, 1e-6f)

        // The cell's size and corner in pixels: 9:16 FHD is 1080 × 1920.
        val frame = grid.outputSize
        assertEquals(Size(540, 960), Cells.size(frame, Cells.rect(grid, 3)))
        assertEquals(Point(540f, 960f), Cells.origin(frame, Cells.rect(grid, 3)))
        assertEquals(frame, Cells.size(frame, null))
        assertEquals(3, Cells.at(grid, frame, Point(800f, 1500f)))
    }

    @Test
    fun noCellIsTheWholeFrame() {
        assertNull(Cells.rect(format(Layout.Grid2x2), null))
        // A Single layout ignores the cell a clip keeps.
        assertNull(Cells.rect(format(Layout.Single), 2))
        assertNull(Cells.at(format(Layout.Single), Size(1080, 1920), Point(1f, 1f)))
    }

    @Test
    fun fourClipsFillAFreshTwoByTwo() {
        var project = Project(id = "p", name = "Mosaic", format = format(Layout.Grid2x2))
        repeat(4) { i -> project = AddToMosaic(clip("c$i", 5_000_000), "L${i + 2}").apply(project) }

        val placed = project.layers.flatMap { layer -> layer.clips.map { layer.id to it } }
        // One cell each, all from the start, cell 0 on the main layer and one layer per cell above.
        assertEquals(listOf(0, 1, 2, 3), placed.map { it.second.cell })
        assertEquals(listOf(0L, 0L, 0L, 0L), placed.map { it.second.startUs })
        assertEquals(listOf("L1", "L3", "L4", "L5"), placed.map { it.first })

        // A fifth follows the first cell's clip.
        project = AddToMosaic(clip("c4", 2_000_000), "L9").apply(project)
        val fifth = project.layers.first().clips.last()
        assertEquals("c4", fifth.id)
        assertEquals(0, fifth.cell)
        assertEquals(5_000_000L, fifth.startUs)
    }

    @Test
    fun aSingleProjectAppendsToTheMainLayer() {
        var project = Project(id = "p", name = "Single")
        project = AddToMosaic(clip("a", 3_000_000), "L2").apply(project)
        project = AddToMosaic(clip("b", 2_000_000), "L3").apply(project)
        assertEquals(1, project.layers.size)
        assertEquals(listOf(0L, 3_000_000L), project.layers.first().clips.map { it.startUs })
        assertEquals(listOf(null, null), project.layers.first().clips.map { it.cell })
    }

    @Test
    fun aClipMovesBetweenCells() {
        var project = Project(id = "p", name = "Mosaic", format = format(Layout.Split2))
        project = AddToMosaic(clip("a", 1_000_000), "L2").apply(project)
        project = SetCell("a", 1).apply(project)
        assertEquals(1, project.layers.first().clips.single().cell)
        project = SetCell("a", null).apply(project)
        assertNull(project.layers.first().clips.single().cell)
        assertThrows(EditRejected::class.java) { SetCell("a", 2).apply(project) }
    }

    @Test
    fun changingTheLayoutKeepsClipsWherePossible() {
        var project = Project(id = "p", name = "Mosaic", format = format(Layout.Grid3x3))
        repeat(9) { i -> project = AddToMosaic(clip("c$i", 1_000_000), "L${i + 2}").apply(project) }

        val smaller = SetLayout(Layout.Grid2x2).apply(project)
        val cells = smaller.layers.flatMap { it.clips }.associate { it.id to it.cell }
        // The clips keep their numbers; cells 0 to 3 are where they were, the rest fold back.
        assertEquals((0..8).toList(), (0..8).map { cells["c$it"] })
        assertEquals(Cells.rect(smaller.format, 1), Cells.rect(smaller.format, 5))
        assertEquals(CellRect(0.5f, 0f, 0.5f, 0.5f), Cells.rect(smaller.format, 1))

        // Back to 3×3: every clip is in its own cell again.
        val back = SetLayout(Layout.Grid3x3).apply(smaller)
        assertEquals(project.layers, back.layers)
        assertEquals(Layout.Grid3x3, back.format.layout)
    }

    private fun clip(id: String, durationUs: Long) = Clip(
        id = id,
        type = ClipType.Video,
        startUs = 0,
        durationUs = durationUs,
        source = MediaRef("m", 0, durationUs),
    )
}
