package tech.idct.clipra.feature.editor.layers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.ClipGeometry
import tech.idct.clipra.core.model.Point
import tech.idct.clipra.core.model.Size
import tech.idct.clipra.core.model.Transform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** What dragging on the preview changes. */
enum class OverlayMode { Transform, Crop }

/**
 * The selected clip's frame on the preview (Layers board, #19): a dashed outline with corner
 * handles that scale it and a handle above that turns it. Dragging the picture moves it, and two
 * fingers pinch and turn it. In [OverlayMode.Crop] the corners trim the picture instead.
 *
 * The overlay fills the whole preview area, so handles beyond the frame can still be reached;
 * the frame sits centred in it at its ratio. [frame] is the output size and [source] the clip's
 * picture size. [onChange] reports each step of a gesture, so the preview can follow it,
 * and [onCommit] the end of it, as one edit. [chip] (blend mode and opacity) shows under the clip.
 */
@Composable
fun TransformOverlay(
    transform: Transform,
    frame: Size,
    source: Size,
    onChange: (Transform) -> Unit,
    onCommit: (Transform) -> Unit,
    modifier: Modifier = Modifier,
    mode: OverlayMode = OverlayMode.Transform,
    chip: String? = null,
) {
    val current by rememberUpdatedState(transform)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    Layout(
        content = {
            Handles(transform, frame, source, mode)
            if (chip != null) Chip(chip)
        },
        modifier = modifier
            .fillMaxSize()
            .testTag("transform-overlay")
            .pointerInput(frame, source, mode) {
                transformGestures(
                    frame = frame,
                    source = source,
                    mode = mode,
                    current = { current },
                    onChange = { change(it) },
                    onCommit = { commit(it) },
                )
            },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val handles = measurables[0].measure(constraints)
        val chipPlaceable = measurables.getOrNull(1)
            ?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(width, height) {
            handles.place(0, 0)
            if (chipPlaceable != null) {
                // Centred under the clip's lowest corner, kept on the preview.
                val box = FrameBox.of(width.toFloat(), height.toFloat(), frame)
                val corners = ClipGeometry.corners(transform, frame, source).map(box::view)
                val bottom = corners.maxOf { it.y }
                val centre = corners.sumOf { it.x.toDouble() }.toFloat() / 4
                val gap = 12.dp.roundToPx()
                val x = (centre - chipPlaceable.width / 2f).roundToInt()
                    .coerceIn(0, maxOf(0, width - chipPlaceable.width))
                val y = (bottom.roundToInt() + gap)
                    .coerceIn(0, maxOf(0, height - chipPlaceable.height))
                chipPlaceable.place(x, y)
            }
        }
    }
}

@Composable
private fun Handles(transform: Transform, frame: Size, source: Size, mode: OverlayMode) {
    val colors = ClipraTheme.colors
    val rotateIcon = rememberVectorPainter(ImageVector.vectorResource(ClipraIcons.Reset))
    Canvas(modifier = Modifier.fillMaxSize()) {
        val box = FrameBox.of(size.width, size.height, frame)
        fun view(point: Point) = box.view(point)
        val corners = ClipGeometry.corners(transform, frame, source).map(::view)
        if (mode == OverlayMode.Crop) {
            val whole = ClipGeometry.uncroppedCorners(transform, frame, source).map(::view)
            drawPolygon(whole, colors.textTertiary, dashed = true)
            drawPolygon(corners, colors.accent, dashed = false)
            corners.forEach {
                drawHandle(it, transform.rotation, colors.textPrimary, colors.accent)
            }
            return@Canvas
        }
        val expanded = expand(corners, 4.dp.toPx())
        drawPolygon(expanded, colors.accent, dashed = true)
        expanded.forEach { drawHandle(it, transform.rotation, colors.textPrimary, colors.accent) }

        val (top, up) = topOf(corners, transform.rotation)
        drawLine(
            color = colors.accent,
            start = top + up * 6.dp.toPx(),
            end = top + up * 24.dp.toPx(),
            strokeWidth = 1.5.dp.toPx(),
        )
        val knob = top + up * KNOB_DISTANCE.toPx()
        drawCircle(colors.accent, 7.dp.toPx(), knob)
        val icon = 10.dp.toPx()
        translate(knob.x - icon / 2, knob.y - icon / 2) {
            with(rotateIcon) {
                draw(
                    androidx.compose.ui.geometry.Size(icon, icon),
                    colorFilter = ColorFilter.tint(colors.onAccent),
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    val colors = ClipraTheme.colors
    Text(
        text = text,
        style = ClipraTheme.typography.meta,
        color = colors.accent,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(colors.background.copy(alpha = 0.85f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("blend-chip"),
    )
}

private val KNOB_DISTANCE = 29.dp

/** The polygon through [corners] (source top-left, top-right, bottom-left, bottom-right). */
private fun DrawScope.drawPolygon(
    corners: List<Offset>,
    color: androidx.compose.ui.graphics.Color,
    dashed: Boolean,
) {
    val path = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        lineTo(corners[1].x, corners[1].y)
        lineTo(corners[3].x, corners[3].y)
        lineTo(corners[2].x, corners[2].y)
        close()
    }
    val effect = if (dashed) {
        PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
    } else {
        null
    }
    drawPath(path, color, style = Stroke(width = 1.5.dp.toPx(), pathEffect = effect))
}

/** A 10 dp corner handle, turned with the clip. */
private fun DrawScope.drawHandle(
    at: Offset,
    rotation: Float,
    fill: androidx.compose.ui.graphics.Color,
    border: androidx.compose.ui.graphics.Color,
) {
    val half = 5.dp.toPx()
    val radius = CornerRadius(3.dp.toPx())
    rotate(rotation, pivot = at) {
        val topLeft = Offset(at.x - half, at.y - half)
        val size = androidx.compose.ui.geometry.Size(half * 2, half * 2)
        drawRoundRect(fill, topLeft, size, radius)
        drawRoundRect(border, topLeft, size, radius, style = Stroke(1.5.dp.toPx()))
    }
}

/** [corners] pushed out by [margin] along the picture's own edges. */
private fun expand(corners: List<Offset>, margin: Float): List<Offset> {
    val across = unit(corners[1] - corners[0])
    val down = unit(corners[2] - corners[0])
    return listOf(
        corners[0] - across * margin - down * margin,
        corners[1] + across * margin - down * margin,
        corners[2] - across * margin + down * margin,
        corners[3] + across * margin + down * margin,
    )
}

/** The middle of the picture's upper edge as seen on screen, and the screen's "up" for it. */
private fun topOf(corners: List<Offset>, rotation: Float): Pair<Offset, Offset> {
    val angle = Math.toRadians(rotation.toDouble())
    val up = Offset(sin(angle).toFloat(), -cos(angle).toFloat())
    val centre = Offset(
        corners.sumOf { it.x.toDouble() }.toFloat() / 4,
        corners.sumOf { it.y.toDouble() }.toFloat() / 4,
    )
    val extent = corners.maxOf { (it - centre).x * up.x + (it - centre).y * up.y }
    return centre + up * extent to up
}

private fun unit(offset: Offset): Offset {
    val length = offset.getDistance()
    return if (length == 0f) Offset.Zero else offset / length
}

/** What a gesture grabbed. */
private sealed interface Grab {
    data object Picture : Grab

    data object Rotate : Grab

    data class Corner(val index: Int) : Grab

    data class CropCorner(val index: Int) : Grab
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.transformGestures(
    frame: Size,
    source: Size,
    mode: OverlayMode,
    current: () -> Transform,
    onChange: (Transform) -> Unit,
    onCommit: (Transform) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = current()
        val box = FrameBox.of(size.width.toFloat(), size.height.toFloat(), frame)
        val reaches = Reaches(22.dp.toPx(), 4.dp.toPx(), KNOB_DISTANCE.toPx())
        var grab = grabAt(start, frame, source, mode, box, down.position, reaches)
        if (grab != null) down.consume()

        val centre = box.view(ClipGeometry.centre(start, frame))
        var x = start.x
        var y = start.y
        var scale = start.scale
        var rotation = start.rotation
        var result = start
        var changed = false
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (grab == null && pressed >= 2 && mode == OverlayMode.Transform) grab = Grab.Picture
            val position = event.changes.first().position
            result = when (val held = grab) {
                null -> result

                Grab.Picture -> {
                    val pan = event.calculatePan()
                    x += pan.x / box.width
                    y += pan.y / box.height
                    scale = (scale * event.calculateZoom()).coerceIn(MIN_SCALE, MAX_SCALE)
                    rotation += event.calculateRotation()
                    val snapX = SNAP.toPx() / box.width
                    val snapY = SNAP.toPx() / box.height
                    start.copy(
                        x = if (abs(x) < snapX) 0f else x,
                        y = if (abs(y) < snapY) 0f else y,
                        scale = scale,
                        rotation = snapAngle(rotation),
                    )
                }

                Grab.Rotate -> {
                    val turn = angle(centre, position) - angle(centre, down.position)
                    start.copy(rotation = snapAngle(normalizeDegrees(start.rotation + turn)))
                }

                is Grab.Corner -> {
                    val from = (down.position - centre).getDistance()
                    val to = (position - centre).getDistance()
                    val factor = if (from > 0f) to / from else 1f
                    start.copy(scale = (start.scale * factor).coerceIn(MIN_SCALE, MAX_SCALE))
                }

                is Grab.CropCorner -> {
                    val to = ClipGeometry.toSource(start, frame, source, box.frame(position))
                    crop(start, held.index, to)
                }
            }
            if (grab != null) event.changes.forEach { if (it.positionChanged()) it.consume() }
            if (result != start) {
                changed = true
                onChange(result)
            }
        } while (event.changes.any { it.pressed })
        if (changed) onCommit(result)
    }
}

/**
 * Where the frame sits in the overlay: centred, [width] × [height] view pixels, [scale] view
 * pixels per frame pixel.
 */
private class FrameBox(
    val left: Float,
    val top: Float,
    val scale: Float,
    val width: Float,
    val height: Float,
) {
    fun view(point: Point) = Offset(left + point.x * scale, top + point.y * scale)

    fun frame(offset: Offset) = Point((offset.x - left) / scale, (offset.y - top) / scale)

    companion object {
        fun of(viewWidth: Float, viewHeight: Float, frame: Size): FrameBox {
            val scale = minOf(viewWidth / frame.width, viewHeight / frame.height)
            val width = frame.width * scale
            val height = frame.height * scale
            return FrameBox(
                (viewWidth - width) / 2,
                (viewHeight - height) / 2,
                scale,
                width,
                height,
            )
        }
    }
}

/** Touch distances in pixels: how far a handle reaches, the outline margin, the knob's offset. */
private class Reaches(val handle: Float, val margin: Float, val knob: Float)

private fun grabAt(
    transform: Transform,
    frame: Size,
    source: Size,
    mode: OverlayMode,
    box: FrameBox,
    at: Offset,
    reaches: Reaches,
): Grab? {
    val corners = ClipGeometry.corners(transform, frame, source).map(box::view)
    if (mode == OverlayMode.Crop) {
        val index = corners.indices.minBy { (corners[it] - at).getDistance() }
        val near = (corners[index] - at).getDistance() <= reaches.handle
        return if (near) Grab.CropCorner(index) else null
    }
    val (top, up) = topOf(corners, transform.rotation)
    val knob = top + up * reaches.knob
    if ((knob - at).getDistance() <= reaches.handle) return Grab.Rotate
    val expanded = expand(corners, reaches.margin)
    val index = expanded.indices.minBy { (expanded[it] - at).getDistance() }
    if ((expanded[index] - at).getDistance() <= reaches.handle) return Grab.Corner(index)
    return if (ClipGeometry.contains(transform, frame, source, box.frame(at))) {
        Grab.Picture
    } else {
        null
    }
}

/** Moves the crop edges at source corner [index] to [to], keeping some picture. */
private fun crop(transform: Transform, index: Int, to: Point): Transform {
    val u = to.x.coerceIn(0f, 1f)
    val v = to.y.coerceIn(0f, 1f)
    var result = transform
    if (index == 0 || index == 2) {
        result = result.copy(cropLeft = u.coerceAtMost(1 - result.cropRight - MIN_KEPT))
    } else {
        result = result.copy(cropRight = (1 - u).coerceAtMost(1 - result.cropLeft - MIN_KEPT))
    }
    if (index == 0 || index == 1) {
        result = result.copy(cropTop = v.coerceAtMost(1 - result.cropBottom - MIN_KEPT))
    } else {
        result = result.copy(cropBottom = (1 - v).coerceAtMost(1 - result.cropTop - MIN_KEPT))
    }
    return result
}

private fun angle(centre: Offset, point: Offset): Float =
    ClipGeometry.angle(Point(centre.x, centre.y), Point(point.x, point.y))

/** Snaps to the nearest quarter turn within a few degrees. */
private fun snapAngle(degrees: Float): Float {
    val quarter = (degrees / 90f).roundToInt() * 90f
    return if (abs(degrees - quarter) < ANGLE_SNAP) quarter else degrees
}

private const val MIN_SCALE = 0.05f
private const val MAX_SCALE = 20f
private const val MIN_KEPT = 0.05f
private const val ANGLE_SNAP = 3f
private val SNAP = 8.dp
