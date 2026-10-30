package tech.idct.clipra.feature.effects.masks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.CellRect
import tech.idct.clipra.core.model.Cells
import tech.idct.clipra.core.model.ClipGeometry
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.MaskShape
import tech.idct.clipra.core.model.Point
import tech.idct.clipra.core.model.Size
import tech.idct.clipra.core.model.Transform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The mask on the preview (Masks board, #27): its outline with a dashed line where the feather
 * ends, handles on its four sides that stretch it, and a knob above that turns it. Dragging
 * inside moves it and two fingers pinch it. The mask lives in the clip's picture, so it follows
 * the clip's [transform]. [onChange] reports each step of a gesture, [onCommit] its end.
 */
@Composable
fun MaskOverlay(
    mask: Mask,
    transform: Transform,
    frame: Size,
    source: Size,
    chip: String,
    onChange: (Mask) -> Unit,
    onCommit: (Mask) -> Unit,
    modifier: Modifier = Modifier,
    /** A clip in a mosaic cell (#17) is laid out in it, as the compositor draws it. */
    cell: CellRect? = null,
) {
    val current by rememberUpdatedState(mask)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    Layout(
        content = {
            Outline(mask, transform, frame, source, cell)
            Chip(chip)
        },
        modifier = modifier
            .fillMaxSize()
            .testTag("mask-overlay")
            .pointerInput(transform, frame, source, cell) {
                maskGestures(
                    transform,
                    frame,
                    source,
                    cell,
                    current = { current },
                    onChange = { change(it) },
                    onCommit = { commit(it) },
                )
            },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val outline = measurables[0].measure(constraints)
        val chipPlaceable = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(width, height) {
            outline.place(0, 0)
            // Under the mask's lowest point, kept on the preview.
            val box = FrameBox.of(width.toFloat(), height.toFloat(), frame, cell)
            val space = MaskSpace(mask, transform, Cells.size(frame, cell), source)
            val points = space.outline(0f).map { box.view(it) }
            val bottom = points.maxOf { it.y }
            val centre = box.view(space.frame(0f, 0f)).x
            val x = (centre - chipPlaceable.width / 2f).roundToInt()
                .coerceIn(0, maxOf(0, width - chipPlaceable.width))
            val y = (bottom + 12.dp.toPx()).roundToInt()
                .coerceIn(0, maxOf(0, height - chipPlaceable.height))
            chipPlaceable.place(x, y)
        }
    }
}

@Composable
private fun Outline(mask: Mask, transform: Transform, frame: Size, source: Size, cell: CellRect?) {
    val colors = ClipraTheme.colors
    val knobIcon = rememberVectorPainter(ImageVector.vectorResource(ClipraIcons.Reset))
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (mask.shape == MaskShape.None) return@Canvas
        val box = FrameBox.of(size.width, size.height, frame, cell)
        val space = MaskSpace(mask, transform, Cells.size(frame, cell), source)
        val stroke = 1.5.dp.toPx()
        drawShape(space.outline(0f).map { box.view(it) }, colors.accent, Stroke(stroke))
        if (mask.feather > 0f) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            val feathered = space.outline(space.featherPx).map { box.view(it) }
            drawShape(
                feathered,
                colors.accent.copy(alpha = 0.7f),
                Stroke(1.dp.toPx(), pathEffect = dash),
            )
        }
        val (top, knob) = knob(space, box, KNOB_DISTANCE.toPx())
        drawLine(colors.accent, top, knob, strokeWidth = stroke)
        space.handles().forEach { handle ->
            val at = box.view(handle)
            drawCircle(colors.textPrimary, 5.dp.toPx(), at)
            drawCircle(colors.accent, 5.dp.toPx(), at, style = Stroke(stroke))
        }
        drawCircle(colors.accent, 7.dp.toPx(), knob)
        val icon = 10.dp.toPx()
        translate(knob.x - icon / 2, knob.y - icon / 2) {
            with(knobIcon) {
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
            .testTag("mask-chip"),
    )
}

private val KNOB_DISTANCE = 26.dp

private fun DrawScope.drawShape(points: List<Offset>, color: Color, stroke: Stroke) {
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, color, style = stroke)
}

/** The top handle and the knob beyond it, away from the mask's centre. */
private fun knob(space: MaskSpace, box: FrameBox, distance: Float): Pair<Offset, Offset> {
    val top = box.view(space.handles()[2])
    val centre = box.view(space.frame(0f, 0f))
    val direction = (top - centre).let {
        if (it.getDistance() >
            0f
        ) {
            it / it.getDistance()
        } else {
            Offset(0f, -1f)
        }
    }
    return top to top + direction * distance
}

/**
 * The mask's own space: picture pixels from its centre along its turned axes, mapped into the
 * frame through the clip's transform.
 */
internal class MaskSpace(
    val mask: Mask,
    private val transform: Transform,
    private val frame: Size,
    private val source: Size,
) {
    private val width = (if (source.width > 0) source.width else frame.width).toFloat()
    private val height = (if (source.height > 0) source.height else frame.height).toFloat()
    private val angle = Math.toRadians(mask.rotation.toDouble())
    private val cos = cos(angle).toFloat()
    private val sin = sin(angle).toFloat()
    val halfWidth = mask.width * width / 2
    val halfHeight = mask.height * height / 2
    val featherPx = mask.feather * minOf(width, height)

    /** The frame point at ([x], [y]) from the mask's centre, along its axes. */
    fun frame(x: Float, y: Float): Point {
        val px = mask.x * width + x * cos - y * sin
        val py = mask.y * height + x * sin + y * cos
        return ClipGeometry.toFrame(transform, frame, source, px / width, py / height)
    }

    /** The inverse of [frame]. */
    fun local(point: Point): Offset {
        val uv = ClipGeometry.toSource(transform, frame, source, point)
        val dx = uv.x * width - mask.x * width
        val dy = uv.y * height - mask.y * height
        return Offset(dx * cos + dy * sin, -dx * sin + dy * cos)
    }

    /** Picture pixels per fraction of the picture, across and down. */
    val size = Offset(width, height)

    /** The outline grown by [grow] pixels: an oval, or a rectangle with rounded corners. */
    fun outline(grow: Float): List<Point> {
        val w = halfWidth + grow
        val h = halfHeight + grow
        if (mask.shape == MaskShape.Oval) {
            return List(64) { i ->
                val t = 2 * PI * i / 64
                frame((w * cos(t)).toFloat(), (h * sin(t)).toFloat())
            }
        }
        val r = (mask.cornerRadius * minOf(width, height) + grow)
            .coerceIn(0f, minOf(w, h))
        val corners = listOf(
            Offset(w - r, -h + r) to -PI / 2,
            Offset(w - r, h - r) to 0.0,
            Offset(-w + r, h - r) to PI / 2,
            Offset(-w + r, -h + r) to PI,
        )
        return corners.flatMap { (centre, start) ->
            List(9) { i ->
                val t = start + PI / 2 * i / 8
                frame(centre.x + (r * cos(t)).toFloat(), centre.y + (r * sin(t)).toFloat())
            }
        }
    }

    /** Side handles: right, left, top, bottom. */
    fun handles(): List<Point> = listOf(
        frame(halfWidth, 0f),
        frame(-halfWidth, 0f),
        frame(0f, -halfHeight),
        frame(0f, halfHeight),
    )

    /** Whether [point] is inside the mask's shape. */
    fun contains(point: Point): Boolean {
        val local = local(point)
        val x = local.x / halfWidth.coerceAtLeast(1f)
        val y = local.y / halfHeight.coerceAtLeast(1f)
        val oval = mask.shape == MaskShape.Oval
        return if (oval) x * x + y * y <= 1f else abs(x) <= 1f && abs(y) <= 1f
    }
}

/** Where the frame sits in the overlay: centred, [scale] view pixels per frame pixel. */
internal class FrameBox(private val left: Float, private val top: Float, private val scale: Float) {
    fun view(point: Point) = Offset(left + point.x * scale, top + point.y * scale)

    fun frame(offset: Offset) = Point((offset.x - left) / scale, (offset.y - top) / scale)

    companion object {
        /** The frame centred in the view; for a mosaic [cell] (#17), the cell within it. */
        fun of(viewWidth: Float, viewHeight: Float, frame: Size, cell: CellRect?): FrameBox {
            val scale = minOf(viewWidth / frame.width, viewHeight / frame.height)
            val origin = Cells.origin(frame, cell)
            return FrameBox(
                (viewWidth - frame.width * scale) / 2 + origin.x * scale,
                (viewHeight - frame.height * scale) / 2 + origin.y * scale,
                scale,
            )
        }
    }
}

private sealed interface Grab {
    data object Move : Grab

    data object Turn : Grab

    data class Side(val index: Int) : Grab
}

private suspend fun PointerInputScope.maskGestures(
    transform: Transform,
    frame: Size,
    source: Size,
    cell: CellRect?,
    current: () -> Mask,
    onChange: (Mask) -> Unit,
    onCommit: (Mask) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = current()
        if (start.shape == MaskShape.None) return@awaitEachGesture
        val box = FrameBox.of(size.width.toFloat(), size.height.toFloat(), frame, cell)
        val space = MaskSpace(start, transform, Cells.size(frame, cell), source)
        val reach = 22.dp.toPx()
        val (_, knob) = knob(space, box, KNOB_DISTANCE.toPx())
        val handles = space.handles().map { box.view(it) }
        val nearest = handles.indices.minBy { (handles[it] - down.position).getDistance() }
        var grab: Grab? = when {
            (knob - down.position).getDistance() <= reach -> Grab.Turn
            (handles[nearest] - down.position).getDistance() <= reach -> Grab.Side(nearest)
            space.contains(box.frame(down.position)) -> Grab.Move
            else -> null
        }
        if (grab != null) down.consume()
        val centre = box.view(space.frame(0f, 0f))
        val startUv = ClipGeometry.toSource(transform, frame, source, box.frame(down.position))
        var zoom = 1f
        var result = start
        var changed = false
        do {
            val event = awaitPointerEvent()
            if (grab == null && event.changes.count { it.pressed } >= 2) grab = Grab.Move
            val position = event.changes.first().position
            result = when (val held = grab) {
                null -> result

                Grab.Move -> {
                    zoom *= event.calculateZoom()
                    val uv = ClipGeometry.toSource(transform, frame, source, box.frame(position))
                    start.copy(
                        x = (start.x + uv.x - startUv.x).coerceIn(0f, 1f),
                        y = (start.y + uv.y - startUv.y).coerceIn(0f, 1f),
                        width = (start.width * zoom).coerceIn(MIN_SIZE, MAX_SIZE),
                        height = (start.height * zoom).coerceIn(MIN_SIZE, MAX_SIZE),
                    )
                }

                Grab.Turn -> {
                    val turn = angle(centre, position) - angle(centre, down.position)
                    start.copy(rotation = normalize(start.rotation + turn))
                }

                is Grab.Side -> {
                    val local = space.local(box.frame(position))
                    if (held.index < 2) {
                        start.copy(
                            width = (2 * abs(local.x) / space.size.x).coerceIn(MIN_SIZE, MAX_SIZE),
                        )
                    } else {
                        start.copy(
                            height = (2 * abs(local.y) / space.size.y).coerceIn(MIN_SIZE, MAX_SIZE),
                        )
                    }
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

private fun angle(centre: Offset, point: Offset): Float = Math.toDegrees(
    atan2((point.y - centre.y).toDouble(), (point.x - centre.x).toDouble()),
).toFloat()

private fun normalize(degrees: Float): Float {
    var value = degrees % 360f
    if (value > 180f) value -= 360f
    if (value <= -180f) value += 360f
    return value
}

private const val MIN_SIZE = 0.03f
private const val MAX_SIZE = 2f
