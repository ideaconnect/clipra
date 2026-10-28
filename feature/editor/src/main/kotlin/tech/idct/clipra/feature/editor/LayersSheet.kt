package tech.idct.clipra.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Project

/** What the layer list can change. */
class LayerActions(
    val onHidden: (layerId: String, hidden: Boolean) -> Unit,
    val onMuted: (layerId: String, muted: Boolean) -> Unit,
    val onLocked: (layerId: String, locked: Boolean) -> Unit,
    val onMove: (layerId: String, toIndex: Int) -> Unit,
    val onAdd: () -> Unit,
)

/**
 * The layer list (Layers--layer-list): highest layer first. Each layer has hide, mute and lock,
 * and moves up or down; every other setting is per clip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayersSheet(project: Project, actions: LayerActions, onDismiss: () -> Unit) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .testTag("layers-sheet"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.editor_layers_title),
                style = type.screenTitle,
                color = colors.textPrimary,
            )
            project.layers.withIndex().reversed().forEach { (index, layer) ->
                LayerRow(index, layer, project.layers.lastIndex, actions)
            }
            SecondaryButton(
                text = stringResource(R.string.editor_layer_add),
                onClick = actions.onAdd,
                leadingIcon = ClipraIcons.Add,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LayerRow(index: Int, layer: Layer, lastIndex: Int, actions: LayerActions) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val hide = if (layer.hidden) R.string.editor_layer_show else R.string.editor_layer_hide
    val mute = if (layer.muted) R.string.editor_layer_unmute else R.string.editor_layer_mute
    val lock = if (layer.locked) R.string.editor_layer_unlock else R.string.editor_layer_lock
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .testTag("layer:L${index + 1}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "L${index + 1}",
            style = type.clipLabel,
            color = colors.textPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceRaised)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Text(
            text = pluralStringResource(
                R.plurals.editor_layer_clips,
                layer.clips.size,
                layer.clips.size,
            ),
            style = type.caption,
            color = colors.textSecondary,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        IconAction(
            icon = if (layer.hidden) ClipraIcons.Hidden else ClipraIcons.Visible,
            contentDescription = stringResource(hide),
            onClick = { actions.onHidden(layer.id, !layer.hidden) },
        )
        IconAction(
            icon = if (layer.muted) ClipraIcons.Muted else ClipraIcons.Volume,
            contentDescription = stringResource(mute),
            onClick = { actions.onMuted(layer.id, !layer.muted) },
        )
        IconAction(
            icon = if (layer.locked) ClipraIcons.Lock else ClipraIcons.Unlock,
            contentDescription = stringResource(lock),
            onClick = { actions.onLocked(layer.id, !layer.locked) },
            tint = if (layer.locked) colors.accent else colors.textPrimary,
        )
        IconAction(
            icon = ClipraIcons.ChevronDown,
            contentDescription = stringResource(R.string.editor_layer_up),
            onClick = { actions.onMove(layer.id, index + 1) },
            enabled = index < lastIndex,
            modifier = Modifier.rotate(180f),
        )
        IconAction(
            icon = ClipraIcons.ChevronDown,
            contentDescription = stringResource(R.string.editor_layer_down),
            onClick = { actions.onMove(layer.id, index - 1) },
            enabled = index > 0,
        )
    }
}
