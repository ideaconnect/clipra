package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.Project

/** Puts a clip into a mosaic cell (#17), or back over the whole frame with null. */
data class SetCell(val clipId: String, val cell: Int?) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        if (cell != null && cell !in 0 until project.format.layout.cells) {
            throw EditRejected(EditRejected.Reason.OutOfRange)
        }
        return project.replaceClip(clip.copy(cell = cell))
    }
}

/**
 * Changes the project's mosaic layout (#17). Clips keep their cell numbers: a cell the new
 * layout doesn't have folds back into it (see `Cells.rect`), and changing back puts every clip
 * where it was.
 */
data class SetLayout(val layout: Layout) : EditCommand {
    override fun apply(project: Project): Project =
        project.copy(format = project.format.copy(layout = layout))
}

/**
 * Adds an imported picture to a mosaic project (#17): into the first empty cell, from the start,
 * on the lowest unlocked layer with room ([newLayerId] when none has). Once every cell has a
 * clip, it follows the last clip of the first cell. A Single project appends it to the main
 * layer, as always.
 */
data class AddToMosaic(val clip: Clip, val newLayerId: String) : EditCommand {
    override fun apply(project: Project): Project {
        val cells = project.format.layout.cells
        val main = project.layers.first()
        if (cells <= 1) {
            val start = main.clips.maxOfOrNull { it.endUs } ?: 0L
            return AddClip(clip.copy(startUs = start), LayerTarget.Existing(main.id)).apply(project)
        }
        val pictures = project.layers.flatMap { it.clips }.filter { it.type.isPicture }
        val used = pictures.mapNotNull { it.cell }.toSet()
        val free = (0 until cells).firstOrNull { it !in used }
        if (free == null) {
            val first = pictures.filter { it.cell == 0 }.maxBy { it.endUs }
            val layer = project.layers.first { layer -> layer.clips.any { it.id == first.id } }
            val placed = clip.copy(startUs = first.endUs, cell = 0)
            return AddClip(placed, LayerTarget.Existing(layer.id)).apply(project)
        }
        val placed = clip.copy(startUs = 0, cell = free)
        val layer = project.layers.firstOrNull { layer ->
            !layer.locked && layer.clips.none { it.overlaps(placed) }
        }
        val target = layer?.let { LayerTarget.Existing(it.id) } ?: LayerTarget.NewOnTop(newLayerId)
        return AddClip(placed, target).apply(project)
    }

    private val ClipType.isPicture: Boolean
        get() = this == ClipType.Video || this == ClipType.Photo
}
