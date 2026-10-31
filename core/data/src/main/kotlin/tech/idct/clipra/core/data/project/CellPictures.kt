package tech.idct.clipra.core.data.project

import tech.idct.clipra.core.model.Cells
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project

/**
 * The pictures Home shows in a project's frame (Main board): one per cell, row by row from the
 * top left, and one for a Single layout. A cell's picture is the media of its first photo or
 * video, the higher layer's when two start together; a clip in no cell fills every cell. Hidden
 * layers don't count, and a cell with nothing to show has null.
 */
internal fun Project.cellPictures(): List<String?> {
    val (columns, rows) = Cells.grid(format)
    val count = columns * rows
    val visuals = layers.withIndex()
        .filterNot { it.value.hidden }
        .flatMap { (index, layer) -> layer.clips.map { index to it } }
        .filter { (_, clip) ->
            (clip.type == ClipType.Video || clip.type == ClipType.Photo) && clip.source != null
        }
        .sortedWith(compareBy({ it.second.startUs }, { -it.first }))
        .map { it.second }
    return List(count) { cell ->
        visuals.firstOrNull { clip ->
            val at = clip.cell
            count == 1 || at == null || at.mod(count) == cell
        }?.source?.mediaId
    }
}
