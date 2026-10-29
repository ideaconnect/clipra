package tech.idct.clipra.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.feature.editor.layers.LayerList

/** What the layer list can change. */
class LayerActions(
    val onHidden: (layerId: String, hidden: Boolean) -> Unit,
    val onMuted: (layerId: String, muted: Boolean) -> Unit,
    val onLocked: (layerId: String, locked: Boolean) -> Unit,
    val onMove: (layerId: String, toIndex: Int) -> Unit,
    val onAdd: () -> Unit,
)

/**
 * The layer list as a sheet, when no clip is selected (Layers--layer-list): highest layer first,
 * each with hide, mute and lock, dragged by its grip to move it up or down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayersSheet(project: Project, playheadUs: Long, actions: LayerActions, onDismiss: () -> Unit) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surfaceSunken) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .testTag("layers-sheet"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.editor_layers_title),
                style = type.screenTitle,
                color = colors.textPrimary,
            )
            Text(
                text = stringResource(R.string.editor_layers_hint),
                style = type.caption,
                color = colors.textSecondary,
            )
            LayerList(project = project, playheadUs = playheadUs, actions = actions)
            SecondaryButton(
                text = stringResource(R.string.editor_layer_add),
                onClick = actions.onAdd,
                leadingIcon = ClipraIcons.Add,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
