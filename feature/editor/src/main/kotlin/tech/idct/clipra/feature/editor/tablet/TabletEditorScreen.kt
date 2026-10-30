package tech.idct.clipra.feature.editor.tablet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.component.NavItem
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ToolRail
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.feature.editor.EditorCallbacks
import tech.idct.clipra.feature.editor.EditorTool
import tech.idct.clipra.feature.editor.EditorUiState
import tech.idct.clipra.feature.editor.R
import tech.idct.clipra.feature.editor.textRes
import tech.idct.clipra.feature.editor.timeline.LayerHeaderActions
import tech.idct.clipra.feature.editor.timeline.Timeline
import tech.idct.clipra.feature.editor.timeline.TimelineGeometry
import tech.idct.clipra.feature.editor.timeline.WaveformLoader
import tech.idct.clipra.feature.editor.timeline.formatTimecode
import kotlin.math.roundToInt

/** What only the tablet editor asks for (#69), next to the phone editor's [EditorCallbacks]. */
class TabletEditorActions(
    /** Puts the project's media on the timeline at the playhead. */
    val onAddMedia: (mediaId: String) -> Unit,
    /** Moves the playhead to the previous or the next cut. */
    val onSeekCut: (forward: Boolean) -> Unit,
    val onSnap: (Boolean) -> Unit,
    val onRipple: (Boolean) -> Unit,
    val onAddLayer: () -> Unit,
    /** Opens the Stabilize sheet on the selected video clip. */
    val onStabilize: () -> Unit,
    val layers: LayerHeaderActions,
    val inspector: InspectorActions,
)

/** The tool rail's places past the media bin, and the tools they open. */
private val railTools = listOf(
    EditorTool.Audio,
    EditorTool.Text,
    EditorTool.Filters,
    EditorTool.Transitions,
    EditorTool.Captions,
)

/**
 * The tablet editor (TabletEditor board, #69): the tool rail and media bin, the preview with its
 * transport, the inspector of the selected clip (Clip, Color, Audio, Speed and Animate tabs),
 * then the toolbar over the full-width timeline with its layer headers. It shares the phone
 * editor's state, so a window that changes size keeps the selection, playhead and edits.
 */
@Composable
fun TabletEditorScreen(
    state: EditorUiState,
    playing: Boolean,
    callbacks: EditorCallbacks,
    actions: TabletEditorActions,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    previewOverlay: @Composable () -> Unit = {},
    filmstrip: @Composable (Clip, Float, Float) -> Unit = { _, _, _ -> },
    waveforms: WaveformLoader? = null,
    thumbnail: suspend (mediaId: String) -> ImageBitmap? = { null },
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
    var tab by rememberSaveable { mutableStateOf(InspectorTab.Clip) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val selected = state.selectedClipId?.let { project.findClip(it) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:TabletEditor"),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Header(project, state, callbacks)
            Row(modifier = Modifier.weight(MAIN_WEIGHT).fillMaxWidth()) {
                if (!fullscreen) {
                    val rail = listOf(
                        NavItem(stringResource(R.string.editor_tablet_media), ClipraIcons.Image),
                        NavItem(stringResource(R.string.editor_tool_audio), ClipraIcons.Music),
                        NavItem(stringResource(R.string.editor_tool_text), ClipraIcons.Text),
                        NavItem(stringResource(R.string.editor_tool_effects), ClipraIcons.Effects),
                        NavItem(
                            stringResource(R.string.editor_tool_transitions),
                            ClipraIcons.Transition,
                        ),
                        NavItem(
                            stringResource(R.string.editor_tool_captions),
                            ClipraIcons.Captions,
                        ),
                    )
                    ToolRail(
                        items = rail,
                        selectedIndex = 0,
                        onSelect = { index ->
                            if (index > 0) callbacks.onTool(railTools[index - 1])
                        },
                        modifier = Modifier.edge(colors.borderSubtle, end = true),
                    )
                    MediaBin(
                        project = project,
                        onImport = callbacks.onPickVisual,
                        onAdd = actions.onAddMedia,
                        thumbnail = thumbnail,
                        modifier = Modifier
                            .width(BIN_WIDTH.dp)
                            .fillMaxHeight()
                            .edge(colors.borderSubtle, end = true),
                    )
                }
                PreviewPane(
                    project = project,
                    state = state,
                    playing = playing,
                    callbacks = callbacks,
                    onSeekCut = actions.onSeekCut,
                    fullscreen = fullscreen,
                    onFullscreen = { fullscreen = it },
                    preview = preview,
                    overlay = previewOverlay,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                if (!fullscreen) {
                    Inspector(
                        project = project,
                        selected = selected,
                        tab = tab,
                        onTab = { tab = it },
                        callbacks = callbacks,
                        actions = actions.inspector,
                        playheadUs = state.playheadUs,
                        modifier = Modifier
                            .width(INSPECTOR_WIDTH.dp)
                            .fillMaxHeight()
                            .edge(colors.borderSubtle, start = true),
                    )
                }
            }
            if (!fullscreen) {
                Column(
                    modifier = Modifier
                        .weight(TIMELINE_WEIGHT)
                        .fillMaxWidth()
                        .background(colors.timelineBackground)
                        .edge(colors.borderSubtle, top = true),
                ) {
                    TabletToolbar(
                        state = state,
                        selected = selected?.second,
                        callbacks = callbacks,
                        actions = actions,
                        onSpeed = { tab = InspectorTab.Speed },
                    )
                    Timeline(
                        project = project,
                        playheadUs = state.playheadUs,
                        // Wider than the phone's at the same zoom, as on the board.
                        dpPerSecond = state.dpPerSecond * TABLET_SCALE,
                        selectedClipId = state.selectedClipId,
                        missingMedia = state.missingMedia,
                        actions = callbacks.timeline,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        filmstrip = filmstrip,
                        waveforms = waveforms,
                        beats = state.beats,
                        headers = actions.layers,
                        snap = state.snap,
                    )
                }
            }
        }
        SnackbarHost(
            snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }
}

/** Close, the project and its format, undo and redo, the format chip and Export. */
@Composable
private fun Header(project: Project, state: EditorUiState, callbacks: EditorCallbacks) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .edge(colors.borderSubtle, bottom = true)
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconAction(
            icon = ClipraIcons.Close,
            contentDescription = stringResource(R.string.editor_tablet_close),
            onClick = callbacks.onClose,
            modifier = Modifier.testTag("close"),
        )
        Image(
            painter = painterResource(ClipraIcons.Logo),
            contentDescription = null,
            modifier = Modifier.size(32.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = project.name,
                style = type.headerTitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatLine(project.format),
                style = type.meta,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
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
        Spacer(modifier = Modifier.weight(1f))
        FormatChip(project.format)
        PrimaryButton(
            text = stringResource(R.string.editor_export),
            onClick = callbacks.onExport,
            leadingIcon = ClipraIcons.Export,
            compact = true,
            enabled = project.durationUs > 0,
            modifier = Modifier.testTag("export"),
        )
    }
}

/** "16:9 · 3840×2160 · 30 fps · proxies · auto-saved". */
@Composable
private fun formatLine(format: Format): String {
    val size = format.outputSize
    return stringResource(
        R.string.editor_tablet_format_line,
        format.ratio.label,
        size.width,
        size.height,
        format.frameRate.fps,
    )
}

/** The project's ratio and resolution, as a chip beside Export. */
@Composable
private fun FormatChip(format: Format) {
    val colors = ClipraTheme.colors
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(ClipraTheme.shapes.button)
            .background(colors.surfaceRaised)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ClipraIcon(icon = ClipraIcons.Reframe, contentDescription = null, size = 18.dp)
        Text(
            text = stringResource(
                R.string.editor_tablet_format_chip,
                format.ratio.label,
                format.resolution.label,
            ),
            style = ClipraTheme.typography.chip,
            color = colors.textPrimary,
        )
    }
}

/** The preview at the project's ratio, then the timecode, transport and fit readout. */
@Composable
private fun PreviewPane(
    project: Project,
    state: EditorUiState,
    playing: Boolean,
    callbacks: EditorCallbacks,
    onSeekCut: (Boolean) -> Unit,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    preview: @Composable () -> Unit,
    overlay: @Composable () -> Unit,
    modifier: Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    var shownWidth by remember { mutableIntStateOf(0) }
    Column(
        modifier = modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            val format = project.format
            Box(
                modifier = Modifier
                    .aspectRatio(
                        format.ratio.width.toFloat() / format.ratio.height,
                        matchHeightConstraintsFirst = true,
                    )
                    .onSizeChanged { shownWidth = it.width }
                    .testTag("preview"),
            ) {
                preview()
            }
            overlay()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = formatTimecode(state.playheadUs),
                    style = type.meta.copy(fontSize = type.chip.fontSize),
                    color = colors.textPrimary,
                    modifier = Modifier.testTag("timecode"),
                )
                Text(
                    text = "/ " + formatTimecode(project.durationUs),
                    style = type.meta.copy(fontSize = type.chip.fontSize),
                    color = colors.textTertiary,
                )
            }
            IconAction(
                icon = ClipraIcons.SkipBack,
                contentDescription = stringResource(R.string.editor_tablet_previous_cut),
                onClick = { onSeekCut(false) },
                modifier = Modifier.testTag("previous-cut"),
            )
            PlayButton(playing, callbacks.onPlayPause)
            IconAction(
                icon = ClipraIcons.SkipForward,
                contentDescription = stringResource(R.string.editor_tablet_next_cut),
                onClick = { onSeekCut(true) },
                modifier = Modifier.testTag("next-cut"),
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // How big the preview is against the output: "Fit · 14%".
                val percent = if (shownWidth > 0) {
                    (shownWidth * 100f / project.format.outputSize.width).roundToInt()
                        .coerceAtLeast(1)
                } else {
                    null
                }
                if (percent != null) {
                    Text(
                        text = stringResource(R.string.editor_tablet_fit, percent),
                        style = type.meta.copy(fontSize = type.chip.fontSize),
                        color = colors.textPrimary,
                        modifier = Modifier
                            .clip(ClipraTheme.shapes.button)
                            .background(colors.surface)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("fit"),
                    )
                }
                IconAction(
                    icon = ClipraIcons.Reframe,
                    contentDescription = stringResource(
                        if (fullscreen) {
                            R.string.editor_tablet_exit_fullscreen
                        } else {
                            R.string.editor_tablet_fullscreen
                        },
                    ),
                    onClick = { onFullscreen(!fullscreen) },
                    tint = if (fullscreen) colors.accent else colors.textPrimary,
                    modifier = Modifier.testTag("fullscreen"),
                )
            }
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val colors = ClipraTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .size(56.dp)
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
                size = 24.dp,
            )
        }
    }
}

/** A 1 dp line along one or more edges, as the board divides its panes. */
internal fun Modifier.edge(
    color: Color,
    start: Boolean = false,
    top: Boolean = false,
    end: Boolean = false,
    bottom: Boolean = false,
): Modifier = drawBehind {
    val width = 1.dp.toPx()
    val half = width / 2
    if (start) drawLine(color, Offset(half, 0f), Offset(half, size.height), width)
    if (end) {
        drawLine(
            color,
            Offset(size.width - half, 0f),
            Offset(size.width - half, size.height),
            width,
        )
    }
    if (top) drawLine(color, Offset(0f, half), Offset(size.width, half), width)
    if (bottom) {
        drawLine(
            color,
            Offset(0f, size.height - half),
            Offset(size.width, size.height - half),
            width,
        )
    }
}

/**
 * The board's 400 dp of panes over 320 dp of timeline, as shares of the height; the panes get a
 * little more, so the rail still fits above a system navigation bar.
 */
private const val MAIN_WEIGHT = 420f
private const val TIMELINE_WEIGHT = 300f
private const val BIN_WIDTH = 300
private const val INSPECTOR_WIDTH = 320

/** The tablet timeline is 48 dp a second where the phone's is 32. */
private const val TABLET_SCALE =
    TimelineGeometry.TABLET_DP_PER_SECOND / TimelineGeometry.DEFAULT_DP_PER_SECOND
