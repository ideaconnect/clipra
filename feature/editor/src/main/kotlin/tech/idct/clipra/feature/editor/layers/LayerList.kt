package tech.idct.clipra.feature.editor.layers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.feature.editor.LayerActions
import tech.idct.clipra.feature.editor.R
import tech.idct.clipra.feature.editor.timeline.clipName
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The layer list (Layers--layer-list), highest layer first. Each row names what the layer shows
 * at the playhead, and has hide, mute and lock; the grip drags the layer up or down. Tapping a
 * row picks the clip it shows ([onSelect]); [selectedLayerId] is outlined.
 */
@Composable
fun LayerList(
    project: Project,
    playheadUs: Long,
    actions: LayerActions,
    modifier: Modifier = Modifier,
    selectedLayerId: String? = null,
    onSelect: ((layerId: String) -> Unit)? = null,
) {
    val pitch = with(LocalDensity.current) { (ROW_HEIGHT + ROW_GAP).toPx() }
    var dragged by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        project.layers.withIndex().reversed().forEach { (index, layer) ->
            val moving = dragged == layer.id
            LayerRow(
                project = project,
                index = index,
                layer = layer,
                playheadUs = playheadUs,
                selected = layer.id == selectedLayerId,
                actions = actions,
                onSelect = onSelect,
                modifier = Modifier
                    .zIndex(if (moving) 1f else 0f)
                    .graphicsLayer { translationY = if (moving) offset else 0f },
                grip = Modifier.pointerInput(layer.id, index) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragged = layer.id
                            offset = 0f
                        },
                        onDragEnd = {
                            // The list runs top layer first, so dragging down lowers the layer.
                            val steps = (offset / pitch).roundToInt()
                            val target = (index - steps).coerceIn(0, project.layers.lastIndex)
                            if (target != index) actions.onMove(layer.id, target)
                            dragged = null
                            offset = 0f
                        },
                        onDragCancel = {
                            dragged = null
                            offset = 0f
                        },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            offset += amount
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun LayerRow(
    project: Project,
    index: Int,
    layer: Layer,
    playheadUs: Long,
    selected: Boolean,
    actions: LayerActions,
    onSelect: ((String) -> Unit)?,
    modifier: Modifier,
    grip: Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val number = index + 1
    val here = layer.clips.firstOrNull { it.contains(playheadUs) }
    val next = layer.clips.filter { it.startUs > playheadUs }.minByOrNull { it.startUs }
    val shown = here ?: next ?: layer.clips.firstOrNull()
    val name = here?.let { displayName(project, it) }
        ?: stringResource(R.string.editor_layers_empty_here)
    val meta = when {
        here != null -> stringResource(
            R.string.editor_layers_clip_span,
            typeName(here.type),
            seconds(here.startUs),
            seconds(here.endUs),
        )

        next != null -> stringResource(
            R.string.editor_layers_next_clip,
            typeName(next.type),
            seconds(next.startUs),
        )

        else -> pluralStringResource(
            R.plurals.editor_layer_clips,
            layer.clips.size,
            layer.clips.size,
        )
    }
    val hide = if (layer.hidden) R.string.editor_layer_show else R.string.editor_layer_hide
    val mute = if (layer.muted) R.string.editor_layer_unmute else R.string.editor_layer_mute
    val lock = if (layer.locked) R.string.editor_layer_unlock else R.string.editor_layer_lock
    val moveUp = stringResource(R.string.editor_layer_up)
    val moveDown = stringResource(R.string.editor_layer_down)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clip(ClipraTheme.shapes.button)
            .background(if (selected) colors.accentSelectedBackground else colors.surface)
            .border(
                ClipraDimens.BorderWidth,
                if (selected) colors.accent else Color.Transparent,
                ClipraTheme.shapes.button,
            )
            .then(
                if (onSelect != null && here != null) {
                    Modifier.clickable { onSelect(layer.id) }
                } else {
                    Modifier
                },
            )
            .semantics {
                customActions = buildList {
                    if (index < project.layers.lastIndex) {
                        add(
                            CustomAccessibilityAction(moveUp) {
                                actions.onMove(layer.id, index + 1)
                                true
                            },
                        )
                    }
                    if (index > 0) {
                        add(
                            CustomAccessibilityAction(moveDown) {
                                actions.onMove(layer.id, index - 1)
                                true
                            },
                        )
                    }
                }
            }
            .padding(start = 6.dp, end = 4.dp)
            .testTag("layer:L$number"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = grip.size(width = 24.dp, height = ROW_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            ClipraIcon(
                icon = ClipraIcons.Grip,
                contentDescription = stringResource(R.string.editor_layers_reorder, number),
                tint = colors.textTertiary,
                size = 18.dp,
            )
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(ClipraTheme.shapes.clip)
                .background(chipColor(shown?.type)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "L$number", style = type.clipLabel, color = colors.textPrimary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = type.body,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                style = type.meta,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row {
            IconAction(
                icon = if (layer.hidden) ClipraIcons.Hidden else ClipraIcons.Visible,
                contentDescription = stringResource(hide),
                onClick = { actions.onHidden(layer.id, !layer.hidden) },
                tint = colors.textSecondaryAlt,
            )
            IconAction(
                icon = if (layer.muted) ClipraIcons.Muted else ClipraIcons.Volume,
                contentDescription = stringResource(mute),
                onClick = { actions.onMuted(layer.id, !layer.muted) },
                tint = colors.textSecondaryAlt,
            )
            IconAction(
                icon = if (layer.locked) ClipraIcons.Lock else ClipraIcons.Unlock,
                contentDescription = stringResource(lock),
                onClick = { actions.onLocked(layer.id, !layer.locked) },
                tint = if (layer.locked) colors.accent else colors.textSecondaryAlt,
            )
        }
    }
}

/** A clip's words or file name, or its type when it has neither. */
@Composable
internal fun displayName(project: Project, clip: Clip): String =
    clipName(project, clip).ifEmpty { typeName(clip.type) }

@Composable
internal fun typeName(type: ClipType): String = stringResource(
    when (type) {
        ClipType.Video -> R.string.editor_clip_video
        ClipType.Photo -> R.string.editor_clip_photo
        ClipType.TextCard, ClipType.Text -> R.string.editor_clip_text
        ClipType.Caption -> R.string.editor_clip_caption
        ClipType.Audio -> R.string.editor_clip_audio
        ClipType.Adjustment -> R.string.editor_clip_adjustment
    },
)

/** The layer badge takes the colour of what the layer holds, as on the timeline. */
@Composable
private fun chipColor(type: ClipType?): Color {
    val clip = ClipraTheme.colors.clip
    return when (type) {
        null, ClipType.Video -> ClipraTheme.colors.borderStrong
        ClipType.Photo -> clip.photo
        ClipType.TextCard, ClipType.Text -> clip.text
        ClipType.Caption -> clip.caption
        ClipType.Audio -> clip.music
        ClipType.Adjustment -> clip.adjustmentStripeA
    }
}

/** "5.0": seconds with one decimal, as the layer rows show times. */
private fun seconds(us: Long): String = String.format(Locale.getDefault(), "%.1f", us / 1_000_000.0)

private val ROW_HEIGHT = 44.dp
private val ROW_GAP = 4.dp
