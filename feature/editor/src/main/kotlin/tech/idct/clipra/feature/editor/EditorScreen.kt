package tech.idct.clipra.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.component.NavItem
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.ToolStrip
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.feature.editor.timeline.Timeline
import tech.idct.clipra.feature.editor.timeline.TimelineActions
import tech.idct.clipra.feature.editor.timeline.formatTimecode

/** Tools that open another feature's screen; the app connects them. */
enum class EditorTool {
    Audio,
    Text,
    Filters,
    Transitions,
    Captions,
    Transcript,
    Color,
    Cutout,
    Reframe,
    Masks,
    Layer,
}

/** Everything the editor screen can ask for. */
class EditorCallbacks(
    val onClose: () -> Unit,
    val onExport: () -> Unit,
    val onTool: (EditorTool) -> Unit,
    val onPlayPause: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onPickVisual: () -> Unit,
    val onPickAudio: () -> Unit,
    val onPickReplacement: () -> Unit,
    val onSplit: () -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
    val onFreeze: () -> Unit,
    val onExtract: () -> Unit,
    val onLayers: () -> Unit,
    val onCrop: () -> Unit,
    val onDismissMessage: () -> Unit,
    val timeline: TimelineActions,
)

/**
 * The phone editor (Editor board): header with close and Export, the preview, the transport
 * (timecode, play, undo, redo, keyframe), the timeline and the tool strip, which swaps to the
 * clip tools while a clip is selected.
 */
@Composable
fun EditorScreen(
    state: EditorUiState,
    playing: Boolean,
    callbacks: EditorCallbacks,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    previewOverlay: @Composable () -> Unit = {},
    filmstrip: @Composable (
        tech.idct.clipra.core.model.Clip,
        Float,
        Float,
    ) -> Unit = { _, _, _ -> },
) {
    val colors = ClipraTheme.colors
    val project = state.project ?: return
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { stringResource(it.textRes()) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            callbacks.onDismissMessage()
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Editor"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = project.name,
                subtitle = formatLine(project.format),
                navigation = HeaderNavigation.Close,
                onNavigate = callbacks.onClose,
                actions = {
                    PrimaryButton(
                        text = stringResource(R.string.editor_export),
                        onClick = callbacks.onExport,
                        leadingIcon = ClipraIcons.Export,
                        compact = true,
                        enabled = project.durationUs > 0,
                        modifier = Modifier.testTag("export"),
                    )
                },
            )
            PreviewArea(project.format, preview, previewOverlay)
            Transport(state, playing, callbacks)
            Timeline(
                project = project,
                playheadUs = state.playheadUs,
                dpPerSecond = state.dpPerSecond,
                selectedClipId = state.selectedClipId,
                missingMedia = state.missingMedia,
                actions = callbacks.timeline,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                filmstrip = filmstrip,
            )
            Tools(state.selectedClipId != null, callbacks)
        }
        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp),
        )
    }
}

@Composable
private fun formatLine(format: Format): String = stringResource(
    R.string.editor_format_line,
    format.ratio.label,
    format.resolution.label,
    format.frameRate.fps,
)

/**
 * The preview, letterboxed in black at the project's ratio. [overlay] covers the whole area,
 * since a moved clip's handles can sit beyond the frame, and is clipped to it.
 */
@Composable
private fun PreviewArea(
    format: Format,
    content: @Composable () -> Unit,
    overlay: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .clipToBounds()
            .background(ClipraTheme.colors.ad.background),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(
                    format.ratio.width.toFloat() / format.ratio.height,
                    matchHeightConstraintsFirst = true,
                )
                .testTag("preview"),
        ) {
            content()
        }
        overlay()
    }
}

@Composable
private fun Transport(state: EditorUiState, playing: Boolean, callbacks: EditorCallbacks) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = formatTimecode(state.playheadUs),
                style = type.meta.copy(fontSize = type.chip.fontSize),
                color = colors.textPrimary,
                modifier = Modifier.testTag("timecode"),
            )
            Text(
                text = "/ " + formatTimecode(state.project?.durationUs ?: 0),
                style = type.meta.copy(fontSize = type.chip.fontSize),
                color = colors.textTertiary,
            )
        }
        Surface(
            onClick = callbacks.onPlayPause,
            modifier = Modifier
                .size(44.dp)
                .semantics { role = Role.Button }
                .testTag("play"),
            shape = CircleShape,
            color = colors.textPrimary,
            contentColor = colors.background,
        ) {
            Box(contentAlignment = Alignment.Center) {
                ClipraIcon(
                    icon = if (playing) ClipraIcons.Pause else ClipraIcons.Play,
                    contentDescription = stringResource(
                        if (playing) R.string.editor_pause else R.string.editor_play,
                    ),
                    size = 20.dp,
                )
            }
        }
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            IconAction(
                icon = ClipraIcons.Undo,
                contentDescription = stringResource(R.string.editor_undo),
                onClick = callbacks.onUndo,
                enabled = state.canUndo,
                modifier = Modifier.testTag("undo"),
            )
            IconAction(
                icon = ClipraIcons.Redo,
                contentDescription = stringResource(R.string.editor_redo),
                onClick = callbacks.onRedo,
                enabled = state.canRedo,
                modifier = Modifier.testTag("redo"),
            )
            // Keyframes come with M2 (#21).
            IconAction(
                icon = ClipraIcons.Keyframe,
                contentDescription = stringResource(R.string.editor_keyframe),
                onClick = {},
                enabled = false,
            )
        }
    }
}

/** A tool on a strip and what it does. */
private class Tool(val item: NavItem, val action: () -> Unit)

@Composable
private fun tool(label: Int, icon: Int, action: () -> Unit) =
    Tool(NavItem(stringResource(label), icon), action)

@Composable
private fun Tools(clipSelected: Boolean, callbacks: EditorCallbacks) {
    var adding by remember { mutableStateOf(false) }
    val tools = if (clipSelected) clipTools(callbacks) else mainTools(callbacks) { adding = true }
    Box {
        ToolStrip(
            tools = tools.map { it.item },
            onTool = { item -> tools.first { it.item == item }.action() },
            onClose = if (clipSelected) ({ callbacks.timeline.onSelect(null) }) else null,
            modifier = Modifier.testTag(if (clipSelected) "clip-tools" else "main-tools"),
        )
        DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_add_visual)) },
                onClick = {
                    adding = false
                    callbacks.onPickVisual()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.editor_add_audio)) },
                onClick = {
                    adding = false
                    callbacks.onPickAudio()
                },
            )
        }
    }
}

/** With nothing selected (Editor--no-clip-selected). */
@Composable
private fun mainTools(callbacks: EditorCallbacks, onAdd: () -> Unit): List<Tool> {
    val open = callbacks.onTool
    return listOf(
        tool(R.string.editor_tool_add, ClipraIcons.Add, onAdd),
        tool(R.string.editor_tool_audio, ClipraIcons.Audio) { open(EditorTool.Audio) },
        tool(R.string.editor_tool_text, ClipraIcons.Text) { open(EditorTool.Text) },
        tool(R.string.editor_tool_layers, ClipraIcons.Layers, callbacks.onLayers),
        tool(R.string.editor_tool_filters, ClipraIcons.Filters) { open(EditorTool.Filters) },
        tool(
            R.string.editor_tool_transitions,
            ClipraIcons.Transition,
        ) { open(EditorTool.Transitions) },
        tool(R.string.editor_tool_captions, ClipraIcons.Captions) { open(EditorTool.Captions) },
        tool(
            R.string.editor_tool_transcript,
            ClipraIcons.EditByText,
        ) { open(EditorTool.Transcript) },
        tool(R.string.editor_tool_color, ClipraIcons.Color) { open(EditorTool.Color) },
        tool(R.string.editor_tool_cutout, ClipraIcons.Cutout) { open(EditorTool.Cutout) },
        tool(R.string.editor_tool_reframe, ClipraIcons.Reframe) { open(EditorTool.Reframe) },
        tool(R.string.editor_tool_masks, ClipraIcons.Mask) { open(EditorTool.Masks) },
    )
}

/**
 * With a clip selected. Speed, Stabilize, Linked and Reverse join when their issues land (M2,
 * M7), so every tool shown does something.
 */
@Composable
private fun clipTools(callbacks: EditorCallbacks): List<Tool> {
    val open = callbacks.onTool
    return listOf(
        tool(R.string.editor_tool_split, ClipraIcons.Split, callbacks.onSplit),
        tool(R.string.editor_tool_audio, ClipraIcons.Audio) { open(EditorTool.Audio) },
        tool(R.string.editor_tool_filters, ClipraIcons.Filters) { open(EditorTool.Filters) },
        tool(R.string.editor_tool_mask, ClipraIcons.Mask) { open(EditorTool.Masks) },
        tool(R.string.editor_tool_layer, ClipraIcons.Layers) { open(EditorTool.Layer) },
        tool(R.string.editor_tool_text, ClipraIcons.Text) { open(EditorTool.Text) },
        tool(R.string.editor_tool_color, ClipraIcons.Color) { open(EditorTool.Color) },
        tool(R.string.editor_tool_cutout, ClipraIcons.Cutout) { open(EditorTool.Cutout) },
        tool(R.string.editor_tool_duplicate, ClipraIcons.Duplicate, callbacks.onDuplicate),
        tool(R.string.editor_tool_delete, ClipraIcons.Delete, callbacks.onDelete),
        tool(R.string.editor_tool_crop, ClipraIcons.Crop, callbacks.onCrop),
        tool(R.string.editor_tool_freeze, ClipraIcons.Freeze, callbacks.onFreeze),
        tool(R.string.editor_tool_extract, ClipraIcons.Music, callbacks.onExtract),
        tool(R.string.editor_tool_replace, ClipraIcons.Replace, callbacks.onPickReplacement),
    )
}

private fun EditorMessage.textRes(): Int = when (this) {
    EditorMessage.LayerLocked -> R.string.editor_message_locked
    EditorMessage.NoRoom -> R.string.editor_message_no_room
    EditorMessage.CantSplitHere -> R.string.editor_message_out_of_range
    EditorMessage.NotAvailable -> R.string.editor_message_not_available
    EditorMessage.ImportFailed -> R.string.editor_message_import_failed
    EditorMessage.NeedsPicture -> R.string.editor_message_needs_picture
    EditorMessage.NeedsCut -> R.string.editor_message_needs_cut
}
