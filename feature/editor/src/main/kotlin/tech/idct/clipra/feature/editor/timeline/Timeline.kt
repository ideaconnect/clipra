package tech.idct.clipra.feature.editor.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.cuts
import tech.idct.clipra.core.model.edit.Edge
import tech.idct.clipra.core.model.edit.LayerTarget
import tech.idct.clipra.core.model.edit.Snapping
import tech.idct.clipra.core.model.edit.linkedPartners
import tech.idct.clipra.core.model.edit.randomId
import tech.idct.clipra.core.model.keyframeTimes
import tech.idct.clipra.core.model.transitionOn
import tech.idct.clipra.feature.editor.R
import kotlin.math.roundToInt

/** What the timeline asks the editor to do. */
class TimelineActions(
    val onSeek: (Long) -> Unit,
    val onZoom: (Float) -> Unit,
    val onSelect: (String?) -> Unit,
    val onMove: (clipId: String, startUs: Long, target: LayerTarget) -> Unit,
    val onTrim: (clipId: String, edge: Edge, toUs: Long) -> Unit,
    val onAddMedia: () -> Unit,
    /** Opens the transition on the cut after [fromClipId]. */
    val onTransition: (fromClipId: String) -> Unit = {},
    /** Moves the selected clip's keyframes at [fromUs] to [toUs], both in clip time (#21). */
    val onMoveKeyframe: (clipId: String, fromUs: Long, toUs: Long) -> Unit = { _, _, _ -> },
)

/**
 * The tablet timeline's layer headers (TabletEditor board, #69): show or hide, mute and lock
 * each layer.
 */
class LayerHeaderActions(
    val onHidden: (layerId: String, hidden: Boolean) -> Unit,
    val onMuted: (layerId: String, muted: Boolean) -> Unit,
    val onLocked: (layerId: String, locked: Boolean) -> Unit,
)

/**
 * The timeline (Editor board): a ruler, one lane per layer with the highest on top and an
 * L-chip gutter, the playhead fixed in the middle. Drag to scrub, pinch to zoom, tap a clip to
 * select it, long-press and drag to move it (to another layer too), drag a selected clip's
 * handles to trim. Moves and trims snap to the playhead and cuts unless [snap] is off.
 *
 * With [headers] it is the tablet's (TabletEditor board, #69): each layer has a header with its
 * eye, sound and lock, the lanes are a little taller, and the playhead stands a third of the
 * way in.
 */
@Composable
fun Timeline(
    project: Project,
    playheadUs: Long,
    dpPerSecond: Float,
    selectedClipId: String?,
    missingMedia: Set<String>,
    actions: TimelineActions,
    modifier: Modifier = Modifier,
    filmstrip: @Composable (clip: Clip, widthDp: Float, heightDp: Float) -> Unit = { _, _, _ -> },
    waveforms: WaveformLoader? = null,
    /** Beat markers (#40): each clip's beats on the timeline; cuts snap to them. */
    beats: Map<String, List<Long>> = emptyMap(),
    headers: LayerHeaderActions? = null,
    snap: Boolean = true,
    /** Times that Edit by text will cut (#70, TabletTranscript), hatched across every layer. */
    marked: List<LongRange> = emptyList(),
) {
    val colors = ClipraTheme.colors
    val density = LocalDensity.current
    val currentPlayhead by rememberUpdatedState(playheadUs)
    val currentDps by rememberUpdatedState(dpPerSecond)
    val scrub = rememberScrollableState { deltaPx ->
        val deltaDp = with(density) { deltaPx.toDp().value }
        actions.onSeek(currentPlayhead - (deltaDp / currentDps * 1_000_000).toLong())
        deltaPx
    }
    val zoom = rememberTransformableState { zoomChange, _, _ -> actions.onZoom(zoomChange) }
    // Highest layer first, as on the board.
    val lanes = project.layers.withIndex().reversed()

    BoxWithConstraints(
        modifier = modifier
            .background(colors.timelineBackground)
            .testTag("timeline"),
    ) {
        val viewWidth = maxWidth.value
        val tablet = headers != null
        val gutter = if (tablet) TimelineGeometry.HEADER else TimelineGeometry.GUTTER
        val lanesWidth = viewWidth - gutter
        val geometry = TimelineGeometry(
            dpPerSecond,
            playheadX = if (tablet) {
                lanesWidth * TimelineGeometry.TABLET_PLAYHEAD
            } else {
                viewWidth / 2 - gutter
            },
            playheadUs,
        )
        // The headers scroll with the lanes.
        val vertical = rememberScrollState()
        val laneHeight = { layer: Layer ->
            if (tablet) {
                TimelineGeometry.tabletLaneHeight(
                    layer,
                )
            } else {
                TimelineGeometry.laneHeight(layer)
            }
        }
        Row(modifier = Modifier.fillMaxSize()) {
            if (headers != null) {
                val selectedLayer = selectedClipId?.let { project.findClip(it)?.first?.id }
                LayerHeaders(
                    lanes.map { it.index to it.value },
                    selectedLayer,
                    headers,
                    vertical,
                    laneHeight,
                    modifier = Modifier.width(gutter.dp),
                )
            } else {
                Gutter(
                    lanes.map { it.index to it.value },
                    vertical,
                    modifier = Modifier.width(gutter.dp),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .scrollable(scrub, Orientation.Horizontal, reverseDirection = false)
                    .transformable(zoom, canPan = { false })
                    .pointerInput(Unit) { detectTapGestures { actions.onSelect(null) } },
            ) {
                Ruler(geometry, lanesWidth)
                Column(
                    modifier = Modifier
                        .padding(top = TimelineGeometry.RULER.dp)
                        .fillMaxWidth()
                        .verticalScroll(vertical),
                ) {
                    val partners = selectedClipId?.let { project.linkedPartners(it) }.orEmpty()
                    lanes.forEach { (index, layer) ->
                        val laneHeight = laneHeight(layer)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height((laneHeight + TimelineGeometry.LANE_GAP).dp)
                                .padding(vertical = (TimelineGeometry.LANE_GAP / 2).dp),
                        ) {
                            layer.clips.forEach { clip ->
                                ClipBlock(
                                    project = project,
                                    clip = clip,
                                    layerNumber = index + 1,
                                    geometry = geometry,
                                    laneHeight = laneHeight,
                                    selected = clip.id == selectedClipId,
                                    linked = clip.id in partners,
                                    missing = clip.source?.mediaId in missingMedia,
                                    locked = layer.locked,
                                    actions = actions,
                                    filmstrip = filmstrip,
                                    waveforms = waveforms,
                                    beats = beats,
                                    snap = snap,
                                )
                            }
                            project.cuts().filter { it.layerId == layer.id }.forEach { cut ->
                                if (project.transitionOn(cut) != null) {
                                    TransitionMarker(
                                        x = geometry.x(cut.timeUs),
                                        laneHeight = laneHeight,
                                        description = stringResource(
                                            R.string.editor_transition_marker,
                                            clipName(project, cut.from),
                                            clipName(project, cut.to),
                                        ),
                                        onClick = { actions.onTransition(cut.from.id) },
                                    )
                                }
                            }
                            if (index == 0) {
                                AddMediaButton(
                                    x = geometry.x(layer.clips.maxOfOrNull { it.endUs } ?: 0) + 6,
                                    height = laneHeight,
                                    onClick = actions.onAddMedia,
                                )
                            }
                        }
                    }
                }
                if (marked.isNotEmpty()) Marks(marked, geometry)
                Playhead(geometry.playheadX)
            }
        }
    }
}

/** [ranges] as bands of the cut colour with stripes and edges, the height of the timeline. */
@Composable
private fun Marks(ranges: List<LongRange>, geometry: TimelineGeometry) {
    val band = ClipraTheme.colors.cutBand
    val edge = ClipraTheme.colors.danger
    val stripe = edge.copy(alpha = STRIPE_ALPHA)
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .testTag("timeline-marks"),
    ) {
        val step = 8.dp.toPx()
        val stroke = 1.5.dp.toPx()
        for (range in ranges) {
            val from = geometry.x(range.first).dp.toPx()
            val to = geometry.x(range.last).dp.toPx()
            if (to < 0 || from > size.width) continue
            drawRect(band, Offset(from, 0f), Size(to - from, size.height))
            clipRect(left = from, right = to) {
                var x = from - size.height
                while (x < to) {
                    val start = Offset(x, size.height)
                    drawLine(stripe, start, Offset(x + size.height, 0f), stroke)
                    x += step
                }
            }
            drawLine(edge, Offset(from, 0f), Offset(from, size.height), stroke)
            drawLine(edge, Offset(to, 0f), Offset(to, size.height), stroke)
        }
    }
}

@Composable
private fun Gutter(lanes: List<Pair<Int, Layer>>, scroll: ScrollState, modifier: Modifier) {
    val colors = ClipraTheme.colors
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(colors.timelineBackground)
            .padding(top = TimelineGeometry.RULER.dp)
            .verticalScroll(scroll),
    ) {
        lanes.forEach { (index, layer) ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((TimelineGeometry.laneHeight(layer) + TimelineGeometry.LANE_GAP).dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "L${index + 1}",
                    style = ClipraTheme.typography.clipLabel,
                    color = if (layer.hidden) colors.textDisabled else colors.textPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceRaised)
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * The tablet's layer headers: "Layers" over the ruler, then for each lane its L chip (lit on the
 * selected clip's layer), eye, sound and lock.
 */
@Composable
private fun LayerHeaders(
    lanes: List<Pair<Int, Layer>>,
    selectedLayerId: String?,
    actions: LayerHeaderActions,
    scroll: ScrollState,
    laneHeight: (Layer) -> Float,
    modifier: Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(colors.layerHeaderBackground)
            .drawBehind {
                drawLine(
                    colors.borderSubtle,
                    Offset(size.width, 0f),
                    Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .testTag("layer-headers"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(TimelineGeometry.RULER.dp)
                .padding(start = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = stringResource(R.string.editor_layers_title),
                style = type.caption,
                color = colors.textTertiary,
            )
        }
        Column(modifier = Modifier.verticalScroll(scroll)) {
            lanes.forEach { (index, layer) ->
                val selected = layer.id == selectedLayerId
                val name = "L${index + 1}"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((laneHeight(layer) + TimelineGeometry.LANE_GAP).dp)
                        .background(
                            if (selected) colors.accent.copy(alpha = 0.07f) else Color.Transparent,
                        )
                        .padding(start = 10.dp, end = 2.dp)
                        .testTag("layer-header:$name"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = name,
                        style = type.clipLabel,
                        color = when {
                            selected -> colors.onAccent
                            layer.hidden -> colors.textDisabled
                            else -> colors.textPrimary
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) colors.accent else colors.surfaceRaised)
                            .padding(horizontal = 5.dp, vertical = 3.dp),
                    )
                    Box(modifier = Modifier.weight(1f))
                    HeaderToggle(
                        icon = if (layer.hidden) ClipraIcons.Hidden else ClipraIcons.Visible,
                        description = stringResource(
                            if (layer.hidden) {
                                R.string.editor_layer_show
                            } else {
                                R.string.editor_layer_hide
                            },
                        ),
                        on = layer.hidden,
                        onClick = { actions.onHidden(layer.id, !layer.hidden) },
                    )
                    HeaderToggle(
                        icon = if (layer.muted) ClipraIcons.Muted else ClipraIcons.Volume,
                        description = stringResource(
                            if (layer.muted) {
                                R.string.editor_layer_unmute
                            } else {
                                R.string.editor_layer_mute
                            },
                        ),
                        on = layer.muted,
                        onClick = { actions.onMuted(layer.id, !layer.muted) },
                    )
                    HeaderToggle(
                        icon = if (layer.locked) ClipraIcons.Lock else ClipraIcons.Unlock,
                        description = stringResource(
                            if (layer.locked) {
                                R.string.editor_layer_unlock
                            } else {
                                R.string.editor_layer_lock
                            },
                        ),
                        on = layer.locked,
                        onClick = { actions.onLocked(layer.id, !layer.locked) },
                    )
                }
            }
        }
    }
}

/** One of a layer header's toggles: dim when off, lit when hiding, muting or locking. */
@Composable
private fun HeaderToggle(icon: Int, description: String, on: Boolean, onClick: () -> Unit) {
    val colors = ClipraTheme.colors
    Box(
        modifier = Modifier
            .size(width = 26.dp, height = 32.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        ClipraIcon(
            icon = icon,
            contentDescription = null,
            size = 15.dp,
            tint = if (on) colors.accent else colors.textTertiary,
        )
    }
}

@Composable
private fun Ruler(geometry: TimelineGeometry, width: Float) {
    val colors = ClipraTheme.colors
    Box(modifier = Modifier.fillMaxWidth().height(TimelineGeometry.RULER.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            geometry.ticks(width).forEach { tick ->
                val x = tick.x.dp.toPx()
                val length = if (tick.long) 8.dp.toPx() else 4.dp.toPx()
                drawLine(
                    colors.borderStrong,
                    Offset(x, 0f),
                    Offset(x, length),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
        geometry.ticks(width).filter { it.labelled && it.x in -20f..width }.forEach { tick ->
            Text(
                text = formatRulerLabel(tick.timeUs),
                style = ClipraTheme.typography.clipLabel,
                color = colors.textTertiary,
                modifier = Modifier.offset(x = (tick.x - 14).dp, y = 7.dp),
            )
        }
    }
}

@Composable
private fun Playhead(x: Float) {
    val accent = ClipraTheme.colors.accent
    Canvas(modifier = Modifier.fillMaxSize()) {
        val px = x.dp.toPx()
        drawLine(accent, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.dp.toPx())
        drawCircle(accent, radius = 5.dp.toPx(), center = Offset(px, 5.dp.toPx()))
    }
}

@Composable
private fun AddMediaButton(x: Float, height: Float, onClick: () -> Unit) {
    val colors = ClipraTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier
            .offset(x = x.dp)
            .size(
                width = ClipraDimens.TouchTarget,
                height = maxOf(height.dp, ClipraDimens.TouchTarget),
            )
            .semantics { role = Role.Button }
            .testTag("add-media"),
        shape = RoundedCornerShape(10.dp),
        color = colors.textPrimary,
        contentColor = colors.background,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ClipraIcon(
                icon = ClipraIcons.Add,
                contentDescription = stringResource(R.string.editor_add_media),
            )
        }
    }
}

@Composable
private fun ClipBlock(
    project: Project,
    clip: Clip,
    layerNumber: Int,
    geometry: TimelineGeometry,
    laneHeight: Float,
    selected: Boolean,
    linked: Boolean,
    missing: Boolean,
    locked: Boolean,
    actions: TimelineActions,
    filmstrip: @Composable (Clip, Float, Float) -> Unit,
    waveforms: WaveformLoader?,
    beats: Map<String, List<Long>> = emptyMap(),
    snap: Boolean = true,
) {
    val colors = ClipraTheme.colors
    val density = LocalDensity.current
    val beatsUs = remember(beats) { beats.values.flatten() }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var trimStart by remember { mutableFloatStateOf(0f) }
    var trimEnd by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val currentClip by rememberUpdatedState(clip)
    val currentGeometry by rememberUpdatedState(geometry)

    val x = geometry.x(clip.startUs) + dragX + trimStart
    val width = (geometry.width(clip.durationUs) - trimStart + trimEnd).coerceAtLeast(8f)
    val fill = clipColor(clip.type, project.isVoiceover(clip))
    val shape = RoundedCornerShape(7.dp)
    val description = clipDescription(project, clip, layerNumber, selected, linked, missing)
    // Snap off (tablet toolbar) leaves moves and trims where they are let go.
    val thresholdUs = if (snap) geometry.duration(8f) else 0L

    Box(
        modifier = Modifier
            .offset { IntOffset(x.dp.roundToPx(), dragY.roundToInt()) }
            // Clips can be much wider than the view.
            .wrapContentSize(Alignment.TopStart, unbounded = true)
            .size(width.dp, laneHeight.dp)
            .clip(shape)
            .background(fill)
            // Adjustment clips (#23) are a hatch with a border, as in the design system.
            .then(
                if (clip.type == ClipType.Adjustment) {
                    Modifier
                        .hatch(colors.clip.adjustmentStripeB)
                        .border(1.dp, colors.clip.adjustmentBorder, shape)
                } else {
                    Modifier
                },
            )
            .then(if (selected) Modifier.border(2.dp, colors.accent, shape) else Modifier)
            .then(if (linked) Modifier.dashedOutline(colors.accent) else Modifier)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
                onClick {
                    actions.onSelect(clip.id)
                    true
                }
            }
            .pointerInput(clip.id, locked) {
                detectTapGestures { actions.onSelect(currentClip.id) }
            }
            .pointerInput(clip.id, locked) {
                if (locked) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dragging = true
                        actions.onSelect(currentClip.id)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragX += with(density) { amount.x.toDp().value }
                        dragY += amount.y
                    },
                    onDragEnd = {
                        val g = currentGeometry
                        val wanted = currentClip.startUs + g.duration(dragX)
                        val targets = Snapping.targets(
                            project,
                            g.playheadUs,
                            currentClip.id,
                            beatsUs,
                        )
                        val start = Snapping.snapMove(
                            wanted,
                            currentClip.durationUs,
                            targets,
                            thresholdUs,
                        )
                            .coerceAtLeast(0)
                        val lanePx = with(density) {
                            (laneHeight + TimelineGeometry.LANE_GAP).dp.toPx()
                        }
                        val lanesMoved = -(dragY / lanePx)
                            .roundToInt()
                        val toIndex = layerNumber - 1 + lanesMoved
                        val target = when {
                            lanesMoved == 0 -> null

                            toIndex >= project.layers.size -> LayerTarget.NewOnTop(randomId())

                            else -> project.layers.getOrNull(toIndex)?.let {
                                LayerTarget.Existing(it.id)
                            }
                        }
                        val layerId = project.layers[layerNumber - 1].id
                        actions.onMove(
                            currentClip.id,
                            start,
                            target ?: LayerTarget.Existing(layerId),
                        )
                        dragX = 0f
                        dragY = 0f
                        dragging = false
                    },
                    onDragCancel = {
                        dragX = 0f
                        dragY = 0f
                        dragging = false
                    },
                )
            }
            .testTag("clip:${clip.id}"),
    ) {
        // Videos without sound have no audio strip.
        val sound = clip.type == ClipType.Video &&
            clip.source?.let { project.asset(it.mediaId) }?.hasAudio != false
        if (clip.type == ClipType.Video && !missing) {
            val strip = if (sound) TimelineGeometry.AUDIO_STRIP else 0f
            filmstrip(clip, width, laneHeight - strip)
        }
        if (sound) {
            SoundWave(
                loader = waveforms,
                project = project,
                clip = clip,
                muted = clip.audio.volume == 0f,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(TimelineGeometry.AUDIO_STRIP.dp),
            )
        }
        if (clip.type == ClipType.Audio) {
            SoundWave(
                loader = waveforms,
                project = project,
                clip = clip,
                muted = clip.audio.volume == 0f,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height((laneHeight * 0.55f).dp),
            )
        }
        // The label and the duration badge stick to the visible left edge, clear of the start
        // handle. A short lane has no room for the badge under the label, so it follows it.
        val inset = (-x).coerceIn(0f, (width - 60f).coerceAtLeast(0f))
        val handles = selected && !locked && !dragging
        val labelStart = if (handles) maxOf(inset + 5, HANDLE_WIDTH + 2) else inset + 5
        val duration = formatClipDuration(clip.durationUs + geometry.duration(trimEnd - trimStart))
        val badgeBelow = laneHeight >= TimelineGeometry.VIDEO_LANE
        // Words sit in the middle of a short lane, as the Editor board draws them.
        val centred = !missing && clip.type in WORDS && !badgeBelow
        Row(
            modifier = Modifier
                .align(if (centred) Alignment.CenterStart else Alignment.TopStart)
                .padding(start = labelStart.dp, top = if (centred) 0.dp else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClipLabel(clip, project, missing, modifier = Modifier.weight(1f, fill = false))
            if (selected && !badgeBelow) DurationBadge(duration, Modifier)
        }
        // Beat markers (#40): a tick at each beat along the top.
        beats[clip.id]?.takeIf { it.isNotEmpty() }?.let { times ->
            Canvas(
                modifier = Modifier.fillMaxWidth().height(BEAT_TICK.dp).testTag("beats:${clip.id}"),
            ) {
                for (time in times) {
                    val x =
                        (time - clip.startUs) / 1_000_000f * geometry.dpPerSecond * density.density
                    drawRect(
                        color = colors.clip.beatMarker,
                        topLeft = Offset(x - 1.dp.toPx(), 0f),
                        size = Size(2.dp.toPx(), size.height),
                    )
                }
            }
        }
        // How many effects the clip stacks (#29), at the visible right edge.
        if (clip.effects.isNotEmpty() && width >= FX_MIN_WIDTH) {
            val visibleRight = 2 * geometry.playheadX + TimelineGeometry.GUTTER
            val endInset = (x + width - visibleRight).coerceIn(0f, width - FX_MIN_WIDTH)
            Text(
                text = stringResource(R.string.editor_timeline_fx, clip.effects.size),
                style = ClipraTheme.typography.clipLabel,
                color = colors.onAccent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = (endInset + 6).dp, top = 4.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(colors.accent)
                    .padding(horizontal = 5.dp, vertical = 1.dp)
                    .testTag("fx:${clip.id}"),
            )
        }
        if (selected && badgeBelow) {
            DurationBadge(
                duration,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = (inset + 14).dp,
                        bottom = (TimelineGeometry.AUDIO_STRIP + 3).dp,
                    ),
            )
        }
    }
    if (selected && !locked && !dragging) {
        // Keyframes on the selected clip (#21): tap one to go to it, drag it to move it.
        clip.keyframeTimes.filter { it in 0..clip.durationUs }.forEach { time ->
            KeyframeMarker(
                x = geometry.x(clip.startUs + time),
                laneHeight = laneHeight,
                onTap = { actions.onSeek(clip.startUs + time) },
            ) { offsetDp ->
                val to = Snapping.snap(
                    clip.startUs + time + geometry.duration(offsetDp),
                    Snapping.targets(project, geometry.playheadUs, clip.id, beatsUs),
                    thresholdUs,
                )
                actions.onMoveKeyframe(clip.id, time, to - clip.startUs)
            }
        }
        TrimHandle(x, laneHeight, start = true) { amount, done ->
            trimStart += amount
            if (done) {
                val to = Snapping.snap(
                    clip.startUs + geometry.duration(trimStart),
                    Snapping.targets(project, geometry.playheadUs, clip.id, beatsUs),
                    thresholdUs,
                )
                trimStart = 0f
                actions.onTrim(clip.id, Edge.Start, to)
            }
        }
        TrimHandle(x + width - HANDLE_WIDTH, laneHeight, start = false) { amount, done ->
            trimEnd += amount
            if (done) {
                val to = Snapping.snap(
                    clip.endUs + geometry.duration(trimEnd),
                    Snapping.targets(project, geometry.playheadUs, clip.id, beatsUs),
                    thresholdUs,
                )
                trimEnd = 0f
                actions.onTrim(clip.id, Edge.End, to)
            }
        }
    }
}

private const val HANDLE_WIDTH = 12f

/** Clips that show words, not media, in their label. */
private val WORDS = setOf(ClipType.Text, ClipType.TextCard, ClipType.Caption, ClipType.Adjustment)

/** How tall a beat tick is, in dp. */
private const val BEAT_TICK = 5f

/** Clips narrower than this, in dp, have no room for the FX badge. */
private const val FX_MIN_WIDTH = 90f

private const val MARKER_SIZE = 12f

/** A keyframe on the selected clip (#21): a diamond on its lower edge, with room to grab. */
@Composable
private fun KeyframeMarker(
    x: Float,
    laneHeight: Float,
    onTap: () -> Unit,
    onMoved: (offsetDp: Float) -> Unit,
) {
    val colors = ClipraTheme.colors
    val density = LocalDensity.current
    var offset by remember { mutableFloatStateOf(0f) }
    // The gestures outlive a recomposition; they call the marker's latest callbacks.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnMoved by rememberUpdatedState(onMoved)
    val label = stringResource(R.string.editor_keyframe_marker)
    val touch = 28f
    Box(
        modifier = Modifier
            .offset(x = (x + offset - touch / 2).dp, y = (laneHeight - touch).dp)
            .size(touch.dp)
            .semantics { contentDescription = label }
            .pointerInput(Unit) { detectTapGestures(onTap = { currentOnTap() }) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        change.consume()
                        offset += with(density) { amount.x.toDp().value }
                    },
                    onDragEnd = {
                        currentOnMoved(offset)
                        offset = 0f
                    },
                    onDragCancel = { offset = 0f },
                )
            }
            .testTag("keyframe-marker"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(MARKER_SIZE.dp)
                .rotate(45f)
                .background(colors.accent)
                .border(1.5.dp, colors.onAccent),
        )
    }
}

@Composable
private fun TrimHandle(
    x: Float,
    height: Float,
    start: Boolean,
    onDrag: (amountDp: Float, done: Boolean) -> Unit,
) {
    val colors = ClipraTheme.colors
    val density = LocalDensity.current
    // The gesture outlives a recomposition, so it calls the latest [onDrag]: one made for the
    // clip as it was would trim from where its edge used to be (a second trim was refused).
    val currentOnDrag by rememberUpdatedState(onDrag)
    val label = stringResource(if (start) R.string.editor_trim_start else R.string.editor_trim_end)
    Box(
        modifier = Modifier
            .offset(x = x.dp)
            .size(HANDLE_WIDTH.dp, height.dp)
            .clip(
                RoundedCornerShape(
                    if (start) 7.dp else 0.dp,
                    if (start) 0.dp else 7.dp,
                    if (start) 0.dp else 7.dp,
                    if (start) 7.dp else 0.dp,
                ),
            )
            .background(colors.accent)
            .semantics { contentDescription = label }
            .pointerInput(start) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        change.consume()
                        currentOnDrag(with(density) { amount.x.toDp().value }, false)
                    },
                    onDragEnd = { currentOnDrag(0f, true) },
                )
            }
            .testTag(if (start) "trim-start" else "trim-end"),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.size(2.dp, 14.dp).background(colors.onAccent))
    }
}

/** A cut with a transition (Editor board): a light chip on the cut; tap to edit it. */
@Composable
private fun TransitionMarker(
    x: Float,
    laneHeight: Float,
    description: String,
    onClick: () -> Unit,
) {
    val colors = ClipraTheme.colors
    val touch = ClipraDimens.TouchTarget.value
    Box(
        modifier = Modifier
            .offset(x = (x - touch / 2).dp, y = ((laneHeight - touch) / 2).dp)
            .size(ClipraDimens.TouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag("transition-marker"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .shadow(4.dp, ClipraTheme.shapes.thumb)
                .clip(ClipraTheme.shapes.thumb)
                .background(colors.textPrimary),
            contentAlignment = Alignment.Center,
        ) {
            ClipraIcon(
                icon = ClipraIcons.Transition,
                contentDescription = null,
                tint = colors.onAccent,
                size = 14.dp,
            )
        }
    }
}

@Composable
private fun ClipLabel(clip: Clip, project: Project, missing: Boolean, modifier: Modifier) {
    val colors = ClipraTheme.colors
    val name = when {
        missing -> stringResource(R.string.editor_missing_media)

        // "fx Adjustment": an adjustment clip (#23) has no media to name it.
        clip.type == ClipType.Adjustment -> stringResource(R.string.editor_adjustment_label)

        else -> clipName(project, clip)
    }
    if (name.isEmpty()) return
    // Words rather than media (Editor board): straight on the clip's colour, after a T for text
    // and a link mark for captions that move with their video.
    if (!missing && clip.type in WORDS) {
        val mark = when (clip.type) {
            ClipType.Text, ClipType.TextCard -> ClipraIcons.Text

            ClipType.Caption -> ClipraIcons.Link.takeIf {
                project.links.any { clip.id in it.clipIds }
            }

            else -> null
        }
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            mark?.let {
                ClipraIcon(it, contentDescription = null, tint = colors.textPrimary, size = 10.dp)
            }
            Text(
                text = name,
                style = ClipraTheme.typography.clipTitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    Text(
        text = name,
        style = ClipraTheme.typography.clipLabel,
        color = if (missing) colors.danger else colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(colors.background.copy(alpha = 0.8f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** The selected clip's duration, "9.2s", while it is trimmed too. */
@Composable
private fun DurationBadge(text: String, modifier: Modifier) {
    val colors = ClipraTheme.colors
    Text(
        text = text,
        style = ClipraTheme.typography.clipLabel,
        color = colors.onAccent,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(colors.accent)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/** A voiceover recorded in Clipra (#41), drawn in the voice colours. */
internal fun Project.isVoiceover(clip: Clip): Boolean =
    clip.type == ClipType.Audio && clip.source?.let { asset(it.mediaId) }?.recorded == true

@Composable
private fun clipColor(type: ClipType, voice: Boolean): Color {
    val clip = ClipraTheme.colors.clip
    if (voice) return clip.voice
    return when (type) {
        ClipType.Video -> ClipraTheme.colors.surfaceRaised
        ClipType.Photo -> clip.photo
        ClipType.TextCard, ClipType.Text -> clip.text
        ClipType.Caption -> clip.caption
        ClipType.Audio -> clip.music
        ClipType.Adjustment -> clip.adjustmentStripeA
    }
}

/** TalkBack: type, name, duration and layer, plus selected or linked (#13). */
@Composable
private fun clipDescription(
    project: Project,
    clip: Clip,
    layerNumber: Int,
    selected: Boolean,
    linked: Boolean,
    missing: Boolean,
): String {
    val type = stringResource(
        when (clip.type) {
            ClipType.Video -> R.string.editor_clip_video

            ClipType.Photo -> R.string.editor_clip_photo

            ClipType.TextCard, ClipType.Text -> R.string.editor_clip_text

            ClipType.Caption -> R.string.editor_clip_caption

            ClipType.Audio -> if (project.isVoiceover(clip)) {
                R.string.editor_clip_voiceover
            } else {
                R.string.editor_clip_audio
            }

            ClipType.Adjustment -> R.string.editor_clip_adjustment
        },
    )
    val name = clipName(project, clip)
    val parts = mutableListOf(
        if (name.isEmpty()) {
            stringResource(
                R.string.editor_clip_description_unnamed,
                type,
                formatClipDuration(clip.durationUs),
                layerNumber,
            )
        } else {
            stringResource(
                R.string.editor_clip_description,
                type,
                name,
                formatClipDuration(clip.durationUs),
                layerNumber,
            )
        },
    )
    // Selected is announced from the clip's state, so it isn't said twice.
    if (linked) parts += stringResource(R.string.editor_clip_linked)
    if (missing) parts += stringResource(R.string.editor_missing_media)
    return parts.joinToString(", ")
}

/** Diagonal stripes of [color] over the clip's fill, 6 dp apart. */
private fun Modifier.hatch(color: Color): Modifier = drawBehind {
    val step = 6.dp.toPx()
    val stroke = 2.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), stroke)
        x += step
    }
}

private fun Modifier.dashedOutline(color: Color): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(
            width = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
        ),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
    )
}

/** A text clip's words, or the media's file name. */
internal fun clipName(project: Project, clip: Clip): String =
    clip.text?.text ?: clip.source?.let { project.asset(it.mediaId)?.displayName }.orEmpty()

/** How strongly a mark's stripes show over its band. */
private const val STRIPE_ALPHA = 0.5f
