package tech.idct.clipra.core.model

import kotlin.math.roundToInt

/** A region of the frame in fractions of it: [left] and [top] from its top-left corner. */
data class CellRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * The cells of a mosaic (split-screen) layout (#17), as the design draws them
 * (design/canvas/NewProject.dc.html): Split 2 and Split 3 stack in a portrait frame and sit side
 * by side in a landscape or square one; 2×2 and 3×3 are grids. Cells tile the frame without
 * gaps and are numbered row by row from the top left.
 *
 * A clip in a cell is laid out as if the cell were its frame (`ClipGeometry` with the cell's
 * size), so Fill covers the cell, and nothing of it shows outside the cell.
 */
object Cells {
    /** Columns and rows of [format]'s layout. */
    fun grid(format: Format): Pair<Int, Int> {
        val portrait = format.ratio.height > format.ratio.width
        return when (format.layout) {
            Layout.Single -> 1 to 1
            Layout.Split2 -> if (portrait) 1 to 2 else 2 to 1
            Layout.Split3 -> if (portrait) 1 to 3 else 3 to 1
            Layout.Grid2x2 -> 2 to 2
            Layout.Grid3x3 -> 3 to 3
        }
    }

    /**
     * The region of [cell] in [format], or null for the whole frame: a Single layout, or a clip
     * in no cell. A cell the layout doesn't have folds back into it, so a clip keeps its cell
     * when the layout changes and returns to it when the layout changes back.
     */
    fun rect(format: Format, cell: Int?): CellRect? {
        if (cell == null || format.layout == Layout.Single) return null
        val (columns, rows) = grid(format)
        val index = cell.mod(columns * rows)
        val width = 1f / columns
        val height = 1f / rows
        return CellRect(index % columns * width, index / columns * height, width, height)
    }

    /** The cell's size in frame pixels; the whole [frame] for no cell. */
    fun size(frame: Size, cell: CellRect?): Size = if (cell == null) {
        frame
    } else {
        Size((frame.width * cell.width).roundToInt(), (frame.height * cell.height).roundToInt())
    }

    /** Where the cell's top-left corner is, in frame pixels. */
    fun origin(frame: Size, cell: CellRect?): Point =
        if (cell == null) Point(0f, 0f) else Point(frame.width * cell.left, frame.height * cell.top)

    /** The cell under [point] (frame pixels), or null in a Single layout. */
    fun at(format: Format, frame: Size, point: Point): Int? {
        if (format.layout == Layout.Single) return null
        val (columns, rows) = grid(format)
        val column = (point.x / frame.width * columns).toInt().coerceIn(0, columns - 1)
        val row = (point.y / frame.height * rows).toInt().coerceIn(0, rows - 1)
        return row * columns + column
    }
}
