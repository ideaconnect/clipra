package tech.idct.clipra.feature.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.placeholder.Board
import tech.idct.clipra.core.engine.preview.PreviewPlayer
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Cut
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.cutAfter
import tech.idct.clipra.core.model.cuts
import tech.idct.clipra.core.model.edit.takesFilters
import tech.idct.clipra.feature.editor.layers.LayerTab
import tech.idct.clipra.feature.editor.layers.LayersActions
import tech.idct.clipra.feature.editor.layers.LayersScreen
import tech.idct.clipra.feature.editor.layers.LayersUiState
import tech.idct.clipra.feature.editor.layers.TransformOverlay
import tech.idct.clipra.feature.editor.layers.isVisual
import tech.idct.clipra.feature.editor.layers.sourceSize
import tech.idct.clipra.feature.editor.timeline.Filmstrip
import tech.idct.clipra.feature.editor.timeline.FilmstripLoader
import tech.idct.clipra.feature.editor.timeline.TimelineActions
import tech.idct.clipra.feature.editor.timeline.WaveformLoader
import kotlin.math.abs

/** The editor for [projectId]; null opens the placeholder board (debug screen index). */
@Serializable
data class EditorRoute(val projectId: String? = null)

@Serializable
data object LayersRoute

/**
 * The boards this module builds, from design/README.md ("Start and edit").
 */
object EditorBoards {
    val editor = Board(id = "Editor", title = R.string.editor_title_editor, route = EditorRoute())
    val layers = Board(id = "Layers", title = R.string.editor_title_layers, route = LayersRoute)

    val all = listOf(editor, layers)
}

/**
 * What a tool screen opens on: the project (shared through `OpenProjects`), the clip it works
 * on, if any, and the playhead.
 */
data class ToolTarget(val projectId: String, val clipId: String?, val playheadUs: Long)

/** Where the editor leads; the app connects it to the other features. */
class EditorActions(
    val onClose: () -> Unit,
    val onExport: (projectId: String) -> Unit,
    val onTool: (EditorTool, ToolTarget) -> Unit,
)

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.editorScreens(screen: @Composable (Board) -> Unit, actions: EditorActions) {
    composable<EditorRoute> { entry ->
        if (entry.toRoute<EditorRoute>().projectId == null) {
            screen(EditorBoards.editor)
        } else {
            EditorRoute(actions)
        }
    }
    composable<LayersRoute> { screen(EditorBoards.layers) }
}

@Composable
private fun EditorRoute(actions: EditorActions, viewModel: EditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var playing by remember { mutableStateOf(false) }
    var layers by remember { mutableStateOf(false) }
    // The Layers board (#19, #20) over the editor, on the selected clip; null when closed.
    var board by remember { mutableStateOf<LayerTab?>(null) }
    var cropping by remember { mutableStateOf(false) }
    // Values a gesture is changing, shown before they are committed as one edit.
    var liveTransform by remember { mutableStateOf<Transform?>(null) }
    var liveOpacity by remember { mutableStateOf<Float?>(null) }
    val context = LocalContext.current
    val filmstrips = remember { FilmstripLoader(context.applicationContext, viewModel.mediaCache) }
    val waveforms = remember { WaveformLoader(context.applicationContext, viewModel.mediaCache) }
    val currentState by rememberUpdatedState(state)

    val pickVisual =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
            viewModel.import(it)
        }
    val pickAudio =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            viewModel.import(it)
        }
    val pickReplacement =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
            it?.let(viewModel::replace)
        }

    // Saves when the app goes to the background, so a killed app loses nothing (#10).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        playing = false
        viewModel.flush()
    }

    // The preview player (#14): the composition follows every edit; while playing, the player
    // drives the playhead, and while paused, the playhead (scrubbing) seeks the player.
    val player = remember { PreviewPlayer(context, viewModel.mediaCache) }
    DisposableEffect(player) {
        player.onEnded = { playing = false }
        onDispose { player.release() }
    }
    // A cleaned voice (#39) that becomes ready also rebuilds, so the preview plays it.
    val voiceVersion by viewModel.mediaCache.voiceVersion.collectAsStateWithLifecycle()
    LaunchedEffect(state.project, voiceVersion) {
        liveTransform = null
        liveOpacity = null
        val project = state.project ?: return@LaunchedEffect
        // Edits come in bursts while dragging; rebuild once they settle.
        delay(150)
        player.setProject(project, currentState.playheadUs)
        if (playing) player.play()
    }
    LaunchedEffect(playing) {
        if (!playing) {
            player.pause()
            return@LaunchedEffect
        }
        player.play()
        while (playing) {
            withFrameNanos { }
            viewModel.seek(player.positionUs)
        }
    }
    LaunchedEffect(state.playheadUs, playing) {
        if (playing) return@LaunchedEffect
        // Coalesce the seeks of a fast scrub.
        delay(16)
        player.seekTo(state.playheadUs)
    }

    if (state.loaded && state.project == null) {
        // The project is gone (deleted elsewhere): back to Home.
        LaunchedEffect(Unit) { actions.onClose() }
        return
    }
    val project = state.project ?: return
    val selected = state.selectedClipId?.let { project.findClip(it)?.second }
    val newText = stringResource(R.string.editor_new_text)
    val openBoard = { tab: LayerTab, crop: Boolean ->
        selected?.let { clip ->
            playing = false
            // The handles need the clip on screen.
            if (!clip.contains(state.playheadUs)) viewModel.seek(clip.startUs)
            board = if (clip.isVisual()) tab else LayerTab.Layers
            cropping = crop && clip.isVisual()
        }
    }
    val transformLive = { clipId: String, transform: Transform ->
        liveTransform = transform
        player.setLiveTransform(clipId, transform)
    }
    val currentBoard = board
    if (currentBoard != null && selected != null) {
        BackHandler { board = null }
        LayersScreen(
            state = LayersUiState(
                project = project,
                clip = selected,
                playheadUs = state.playheadUs,
                tab = currentBoard,
                cropping = cropping,
                transform = liveTransform ?: selected.transform.value,
                opacity = liveOpacity ?: selected.opacity.value,
            ),
            actions = LayersActions(
                onDone = {
                    board = null
                    cropping = false
                },
                onTab = {
                    board = it
                    if (it != LayerTab.Transform) cropping = false
                },
                onCrop = { cropping = it },
                onTransformLive = { transformLive(selected.id, it) },
                onTransform = { viewModel.setTransform(selected.id, it) },
                onBlend = { viewModel.setBlend(selected.id, it, selected.opacity.value) },
                onOpacityLive = {
                    liveOpacity = it
                    player.setLiveOpacity(selected.id, it)
                },
                onOpacityDone = {
                    liveOpacity?.let { viewModel.setBlend(selected.id, selected.blend, it) }
                },
                onSelectLayer = viewModel::selectOnLayer,
                layers = layerActions(viewModel),
            ),
            preview = { PreviewSurface(player = player) },
        )
        return
    }
    if (currentBoard != null) board = null
    EditorScreen(
        state = state,
        playing = playing,
        callbacks = EditorCallbacks(
            onClose = {
                viewModel.flush()
                actions.onClose()
            },
            onExport = {
                viewModel.flush()
                actions.onExport(project.id)
            },
            onPlayPause = {
                if (!playing && state.playheadUs >= project.durationUs) {
                    viewModel.seek(0)
                    player.seekTo(0)
                }
                playing = !playing
            },
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onPickVisual = {
                pickVisual.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            },
            onPickAudio = { pickAudio.launch(arrayOf("audio/*")) },
            onPickReplacement = {
                pickReplacement.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            },
            onSplit = viewModel::split,
            onDuplicate = viewModel::duplicate,
            onDelete = viewModel::delete,
            onFreeze = viewModel::freeze,
            onExtract = viewModel::extractAudio,
            onTool = { tool ->
                when (tool) {
                    EditorTool.Layer -> openBoard(LayerTab.Blend, false)

                    EditorTool.Filters, EditorTool.Masks -> {
                        // The selected clip, or the one on top at the playhead.
                        val clip = selected?.takeIf { it.takesFilters() }
                            ?: project.layers.reversed().flatMap { it.clips }
                                .firstOrNull { it.takesFilters() && it.contains(state.playheadUs) }
                            ?: project.layers.flatMap { it.clips }.firstOrNull { it.takesFilters() }
                        if (clip == null) {
                            viewModel.say(EditorMessage.NeedsPicture)
                        } else {
                            playing = false
                            val playhead = if (clip.contains(state.playheadUs)) {
                                state.playheadUs
                            } else {
                                clip.startUs
                            }
                            actions.onTool(tool, ToolTarget(project.id, clip.id, playhead))
                        }
                    }

                    EditorTool.Text -> {
                        playing = false
                        val clipId = selected?.takeIf {
                            it.type == ClipType.Text || it.type == ClipType.TextCard
                        }?.id ?: viewModel.addText(newText)
                        if (clipId != null) {
                            val clip = project.findClip(clipId)?.second
                            val playhead = clip?.takeIf { !it.contains(state.playheadUs) }
                                ?.startUs ?: state.playheadUs
                            actions.onTool(tool, ToolTarget(project.id, clipId, playhead))
                        }
                    }

                    EditorTool.Audio -> {
                        // The selected clip, or the one on top with sound at the playhead.
                        val sound = { c: Clip ->
                            c.type == ClipType.Audio || (
                                c.type == ClipType.Video &&
                                    c.source?.let { project.asset(it.mediaId) }?.hasAudio == true
                                )
                        }
                        val clip = selected?.takeIf(sound)
                            ?: project.layers.reversed().flatMap { it.clips }
                                .firstOrNull { sound(it) && it.contains(state.playheadUs) }
                            ?: project.layers.flatMap { it.clips }.firstOrNull(sound)
                        if (clip == null) {
                            viewModel.say(EditorMessage.NeedsSound)
                        } else {
                            playing = false
                            actions.onTool(tool, ToolTarget(project.id, clip.id, state.playheadUs))
                        }
                    }

                    EditorTool.Transitions -> {
                        // The cut at either end of the selected clip, or the one nearest the
                        // playhead.
                        val cuts = project.cuts()
                        val near = { cut: Cut -> abs(cut.timeUs - state.playheadUs) }
                        val cut = selected?.let { clip ->
                            cuts.filter { it.from.id == clip.id || it.to.id == clip.id }
                                .minByOrNull(near)
                        } ?: cuts.minByOrNull(near)
                        if (cut == null) {
                            viewModel.say(EditorMessage.NeedsCut)
                        } else {
                            playing = false
                            actions.onTool(tool, ToolTarget(project.id, cut.from.id, cut.timeUs))
                        }
                    }

                    else -> actions.onTool(tool, ToolTarget(project.id, null, state.playheadUs))
                }
            },
            onLayers = { layers = true },
            onCrop = { openBoard(LayerTab.Transform, true) },
            onDismissMessage = viewModel::dismissMessage,
            timeline = TimelineActions(
                onSeek = {
                    playing = false
                    viewModel.seek(it)
                },
                onZoom = viewModel::zoom,
                onSelect = viewModel::select,
                onMove = viewModel::move,
                onTrim = viewModel::trim,
                onTransition = { fromClipId ->
                    project.cutAfter(fromClipId)?.let { cut ->
                        playing = false
                        actions.onTool(
                            EditorTool.Transitions,
                            ToolTarget(project.id, cut.from.id, cut.timeUs),
                        )
                    }
                },
                onAddMedia = {
                    pickVisual.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                        ),
                    )
                },
            ),
        ),
        preview = { PreviewSurface(player = player) },
        previewOverlay = {
            // Handles on the selected clip while it is on screen and the preview is paused.
            if (selected != null && selected.isVisual() && !playing &&
                selected.contains(state.playheadUs)
            ) {
                TransformOverlay(
                    transform = liveTransform ?: selected.transform.value,
                    frame = project.format.outputSize,
                    source = sourceSize(project, selected),
                    onChange = { transformLive(selected.id, it) },
                    onCommit = { viewModel.setTransform(selected.id, it) },
                )
            }
        },
        filmstrip = { clip, width, height ->
            Filmstrip(filmstrips, project, clip, width, height, state.dpPerSecond)
        },
        waveforms = waveforms,
    )
    if (layers) {
        LayersSheet(
            project = project,
            playheadUs = state.playheadUs,
            actions = layerActions(viewModel),
            onDismiss = { layers = false },
        )
    }
}

private fun layerActions(viewModel: EditorViewModel) = LayerActions(
    onHidden = viewModel::setHidden,
    onMuted = viewModel::setMuted,
    onLocked = viewModel::setLocked,
    onMove = viewModel::moveLayer,
    onAdd = viewModel::addLayer,
)
