package tech.idct.clipra.core.model

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A point in frame pixels (x right, y down), or in fractions of a source (u right, v down). */
data class Point(val x: Float, val y: Float)

/**
 * Where a clip's picture lands in the frame (#19). The compositor draws with it and the editor
 * draws the handles with it, so both agree at any size.
 *
 * At scale 1 the source covers the frame ([Fit.Fill]) or shows whole inside it ([Fit.Fit]),
 * centred. [Transform.scale] grows it from its centre, [Transform.x] and [Transform.y] move the
 * centre in fractions of the frame, and [Transform.rotation] turns it clockwise by degrees around
 * its centre. Flips mirror the picture in place. The crop trims the source's edges where it is:
 * the rest of the picture keeps its size and position.
 */
object ClipGeometry {
    /** The source size at scale 1, before [Transform.scale]. */
    fun baseScale(fit: Fit, frame: Size, source: Size): Float {
        if (source.width <= 0 || source.height <= 0) return 1f
        val horizontal = frame.width.toFloat() / source.width
        val vertical = frame.height.toFloat() / source.height
        return if (fit == Fit.Fill) maxOf(horizontal, vertical) else minOf(horizontal, vertical)
    }

    /** The frame point that shows the source point ([u], [v]), in fractions of the source. */
    fun toFrame(transform: Transform, frame: Size, source: Size, u: Float, v: Float): Point {
        val (width, height) = drawnSize(transform, frame, source)
        val flippedU = if (transform.flipHorizontal) 1 - u else u
        val flippedV = if (transform.flipVertical) 1 - v else v
        val offsetX = (flippedU - 0.5f) * width
        val offsetY = (flippedV - 0.5f) * height
        val angle = Math.toRadians(transform.rotation.toDouble())
        val cos = cos(angle).toFloat()
        val sin = sin(angle).toFloat()
        val centre = centre(transform, frame)
        return Point(
            centre.x + offsetX * cos - offsetY * sin,
            centre.y + offsetX * sin + offsetY * cos,
        )
    }

    /** The source point (fractions, possibly outside 0 to 1) under the frame [point]. */
    fun toSource(transform: Transform, frame: Size, source: Size, point: Point): Point {
        val (width, height) = drawnSize(transform, frame, source)
        val centre = centre(transform, frame)
        val dx = point.x - centre.x
        val dy = point.y - centre.y
        val angle = Math.toRadians(transform.rotation.toDouble())
        val cos = cos(angle).toFloat()
        val sin = sin(angle).toFloat()
        val offsetX = dx * cos + dy * sin
        val offsetY = -dx * sin + dy * cos
        val u = if (width == 0f) 0.5f else offsetX / width + 0.5f
        val v = if (height == 0f) 0.5f else offsetY / height + 0.5f
        return Point(
            if (transform.flipHorizontal) 1 - u else u,
            if (transform.flipVertical) 1 - v else v,
        )
    }

    /**
     * The corners of the picture as drawn (after the crop), by source corner: top-left,
     * top-right, bottom-left, bottom-right of the source.
     */
    fun corners(transform: Transform, frame: Size, source: Size): List<Point> {
        val left = transform.cropLeft
        val right = 1 - transform.cropRight
        val top = transform.cropTop
        val bottom = 1 - transform.cropBottom
        return listOf(
            toFrame(transform, frame, source, left, top),
            toFrame(transform, frame, source, right, top),
            toFrame(transform, frame, source, left, bottom),
            toFrame(transform, frame, source, right, bottom),
        )
    }

    /** The corners of the whole source, ignoring the crop, in the order of [corners]. */
    fun uncroppedCorners(transform: Transform, frame: Size, source: Size): List<Point> = corners(
        transform.copy(cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f),
        frame,
        source,
    )

    /** Where the picture's centre is, in frame pixels. */
    fun centre(transform: Transform, frame: Size): Point =
        Point(frame.width * (0.5f + transform.x), frame.height * (0.5f + transform.y))

    /** Whether the frame [point] is on the drawn (cropped) picture. */
    fun contains(transform: Transform, frame: Size, source: Size, point: Point): Boolean {
        val (u, v) = toSource(transform, frame, source, point)
        return u in transform.cropLeft..(1 - transform.cropRight) &&
            v in transform.cropTop..(1 - transform.cropBottom)
    }

    /** The angle of [point] around [centre], in degrees clockwise from the x axis. */
    fun angle(centre: Point, point: Point): Float =
        Math.toDegrees(atan2(point.y - centre.y, point.x - centre.x).toDouble()).toFloat()

    fun distance(a: Point, b: Point): Float = hypot(a.x - b.x, a.y - b.y)

    private fun drawnSize(transform: Transform, frame: Size, source: Size): Pair<Float, Float> {
        val known = source.width > 0 && source.height > 0
        val sourceWidth = if (known) source.width else frame.width
        val sourceHeight = if (known) source.height else frame.height
        val scale = baseScale(transform.fit, frame, Size(sourceWidth, sourceHeight)) *
            transform.scale
        return sourceWidth * scale to sourceHeight * scale
    }
}
