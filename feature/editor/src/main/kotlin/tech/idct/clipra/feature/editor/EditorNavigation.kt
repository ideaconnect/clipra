package tech.idct.clipra.feature.editor

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
import tech.idct.clipra.feature.editor.timeline.Filmstrip
import tech.idct.clipra.feature.editor.timeline.FilmstripLoader
import tech.idct.clipra.feature.editor.timeline.TimelineActions

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

/** Where the editor leads; the app connects it to the other features. */
class EditorActions(
    val onClose: () -> Unit,
    val onExport: (projectId: String) -> Unit,
    val onTool: (EditorTool) -> Unit,
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
    val context = LocalContext.current
    val filmstrips = remember { FilmstripLoader(context.applicationContext, viewModel.mediaCache) }
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
    LaunchedEffect(state.project) {
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
            onTool = actions.onTool,
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
            onLayers = { layers = true },
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
                onAddMedia = {
                    pickVisual.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                        ),
                    )
                },
            ),
        ),
        preview = {
            val selected = state.selectedClipId?.let { project.findClip(it)?.second }
            PreviewSurface(
                player = player,
                showHandles = selected != null && selected.contains(state.playheadUs),
            )
        },
        filmstrip = { clip, width, height ->
            Filmstrip(filmstrips, project, clip, width, height, state.dpPerSecond)
        },
    )
    if (layers) {
        LayersSheet(
            project = project,
            actions = LayerActions(
                onHidden = viewModel::setHidden,
                onMuted = viewModel::setMuted,
                onLocked = viewModel::setLocked,
                onMove = viewModel::moveLayer,
                onAdd = viewModel::addLayer,
            ),
            onDismiss = { layers = false },
        )
    }
}
