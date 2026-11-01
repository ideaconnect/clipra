package tech.idct.clipra.feature.editor.tablet

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.edit.linkedPartners
import tech.idct.clipra.feature.editor.EditorCallbacks
import tech.idct.clipra.feature.editor.EditorUiState
import tech.idct.clipra.feature.editor.R
import tech.idct.clipra.feature.editor.timeline.TimelineGeometry
import kotlin.math.ln

/**
 * The tablet toolbar (TabletEditor board, #69): the clip tools as labelled buttons, Snap and
 * Ripple, and the timeline zoom. Clip tools are off until a clip is selected.
 */
@Composable
internal fun TabletToolbar(
    state: EditorUiState,
    selected: Clip?,
    callbacks: EditorCallbacks,
    actions: TabletEditorActions,
    onSpeed: () -> Unit,
) {
    val colors = ClipraTheme.colors
    val clip = selected != null
    val video = selected?.type == ClipType.Video
    val timed = selected != null && (video || selected.type == ClipType.Audio)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .edge(colors.borderSubtle, bottom = true)
            .padding(horizontal = 12.dp)
            .testTag("tablet-toolbar"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolButton(R.string.editor_tool_split, ClipraIcons.Split, clip, callbacks.onSplit)
            ToolButton(R.string.editor_tool_delete, ClipraIcons.Delete, clip, callbacks.onDelete)
            ToolButton(
                R.string.editor_tool_duplicate,
                ClipraIcons.Duplicate,
                clip,
                callbacks.onDuplicate,
            )
            ToolButton(R.string.editor_tool_speed, ClipraIcons.Speed, timed, onSpeed)
            ToolButton(R.string.editor_tool_freeze, ClipraIcons.Freeze, video, callbacks.onFreeze)
            ToolButton(
                R.string.editor_tool_stabilize,
                ClipraIcons.Stabilize,
                video,
                actions.onStabilize,
            )
            ToolButton(
                R.string.editor_tablet_detach_audio,
                ClipraIcons.Music,
                video,
                callbacks.onExtract,
            )
            // Lit while the selected clip is linked (#184, TabletEditor board).
            val linked = selected != null &&
                state.project?.linkedPartners(selected.id)?.isNotEmpty() == true
            ToolButton(
                R.string.editor_tool_linked,
                ClipraIcons.Link,
                clip,
                callbacks.onLinked,
                on = linked,
            )
            ToolButton(R.string.editor_layer_add, ClipraIcons.Layers, true, actions.onAddLayer)
            ToolButton(
                R.string.editor_add_adjustment,
                ClipraIcons.Effects,
                true,
                callbacks.onAddAdjustment,
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .width(1.dp)
                    .height(24.dp)
                    .background(colors.border),
            )
            TogglePill(
                label = R.string.editor_tablet_snap,
                icon = ClipraIcons.Snap,
                on = state.snap,
                onToggle = actions.onSnap,
                tag = "snap",
            )
            TogglePill(
                label = R.string.editor_tablet_ripple,
                icon = ClipraIcons.Ripple,
                on = state.ripple,
                onToggle = actions.onRipple,
                tag = "ripple",
            )
        }
        Zoom(state.dpPerSecond, callbacks.timeline.onZoom)
    }
}

/** A tool; [on] lights it (Linked while the clip is linked) and tells TalkBack so. */
@Composable
private fun ToolButton(
    label: Int,
    icon: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    on: Boolean? = null,
) {
    val colors = ClipraTheme.colors
    val text = stringResource(label)
    val state = on?.let {
        stringResource(if (it) R.string.editor_tablet_on else R.string.editor_tablet_off)
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .height(44.dp)
            .semantics {
                role = Role.Button
                if (state != null) stateDescription = state
            }
            .testTag("tool:$text"),
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        contentColor = when {
            !enabled -> colors.textDisabled
            on == true -> colors.accent
            else -> colors.textPrimary
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ClipraIcon(icon = icon, contentDescription = null, size = 18.dp)
            Text(text = text, style = ClipraTheme.typography.label)
        }
    }
}

/** Snap or Ripple: lit while on. */
@Composable
private fun TogglePill(
    label: Int,
    icon: Int,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    tag: String,
) {
    val colors = ClipraTheme.colors
    val state = stringResource(if (on) R.string.editor_tablet_on else R.string.editor_tablet_off)
    Surface(
        onClick = { onToggle(!on) },
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .height(36.dp)
            .semantics {
                role = Role.Switch
                selected = on
                stateDescription = state
            }
            .testTag(tag),
        shape = RoundedCornerShape(18.dp),
        color = if (on) colors.accentChipBackground else colors.surface,
        contentColor = if (on) colors.accent else colors.textSecondary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ClipraIcon(icon = icon, contentDescription = null, size = 16.dp)
            Text(text = stringResource(label), style = ClipraTheme.typography.chip)
        }
    }
}

/** Zoom out, a slider over the zoom range (evenly in steps of doubling), zoom in. */
@Composable
private fun Zoom(dpPerSecond: Float, onZoom: (Float) -> Unit) {
    val colors = ClipraTheme.colors
    val min = TimelineGeometry.MIN_DP_PER_SECOND
    val max = TimelineGeometry.MAX_DP_PER_SECOND
    val position = (ln(dpPerSecond / min) / ln(max / min)).coerceIn(0f, 1f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconAction(
            icon = ClipraIcons.ZoomOut,
            contentDescription = stringResource(R.string.editor_tablet_zoom_out),
            onClick = { onZoom(1 / ZOOM_STEP) },
            tint = colors.textSecondary,
            modifier = Modifier.width(36.dp),
        )
        Slider(
            value = position,
            onValueChange = { to ->
                val target = min * Math.pow((max / min).toDouble(), to.toDouble()).toFloat()
                onZoom(target / dpPerSecond)
            },
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.surfaceRaised,
            ),
            modifier = Modifier
                .width(150.dp)
                .testTag("zoom"),
        )
        IconAction(
            icon = ClipraIcons.ZoomIn,
            contentDescription = stringResource(R.string.editor_tablet_zoom_in),
            onClick = { onZoom(ZOOM_STEP) },
            tint = colors.textSecondary,
            modifier = Modifier.width(36.dp),
        )
    }
}

private const val ZOOM_STEP = 1.5f
