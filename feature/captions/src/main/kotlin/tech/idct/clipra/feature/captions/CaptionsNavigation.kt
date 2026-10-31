package tech.idct.clipra.feature.captions

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.component.ReadableWidth
import tech.idct.clipra.core.designsystem.component.VideoSurface
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.placeholder.Board
import tech.idct.clipra.core.engine.preview.PreviewPlayer

/** Auto captions for [projectId]; no project opens the placeholder board (screen index). */
@Serializable
data class CaptionsSetupRoute(val projectId: String? = null)

/** The captions of [projectId] (#45); no project opens the placeholder board. */
@Serializable
data class CaptionsEditRoute(val projectId: String? = null)

/** Edit by text for [projectId] (#48); no project opens the placeholder board. */
@Serializable
data class TranscriptRoute(val projectId: String? = null)

/**
 * The boards this module builds, from design/README.md ("On-device AI").
 */
object CaptionsBoards {
    val captionsSetup = Board(
        id = "CaptionsSetup",
        title = R.string.captions_title_captions_setup,
        route = CaptionsSetupRoute(),
    )
    val captionsEdit = Board(
        id = "CaptionsEdit",
        title = R.string.captions_title_captions_edit,
        route = CaptionsEditRoute(),
    )
    val transcript = Board(
        id = "Transcript",
        title = R.string.captions_title_transcript,
        route = TranscriptRoute(),
    )

    val all = listOf(captionsSetup, captionsEdit, transcript)
}

/** Where the captions screens lead; the app connects it. */
class CaptionsNavigationActions(
    val onBack: () -> Unit,
    /** Auto captions finished: the transcripts and captions are in the project. */
    val onTranscribed: (projectId: String) -> Unit,
    val onEditByText: (projectId: String) -> Unit,
    val onAutoCaptions: (projectId: String) -> Unit,
)

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.captionsScreens(
    screen: @Composable (Board) -> Unit,
    actions: CaptionsNavigationActions,
) {
    composable<CaptionsSetupRoute> { entry ->
        val projectId = entry.toRoute<CaptionsSetupRoute>().projectId
        if (projectId == null) {
            screen(CaptionsBoards.captionsSetup)
        } else {
            CaptionsSetupDestination(
                onBack = actions.onBack,
                onTranscribed = { actions.onTranscribed(projectId) },
            )
        }
    }
    composable<CaptionsEditRoute> { entry ->
        val projectId = entry.toRoute<CaptionsEditRoute>().projectId
        if (projectId == null) {
            screen(CaptionsBoards.captionsEdit)
        } else {
            CaptionsEditDestination(
                onDone = actions.onBack,
                onEditByText = { actions.onEditByText(projectId) },
            )
        }
    }
    composable<TranscriptRoute> { entry ->
        val projectId = entry.toRoute<TranscriptRoute>().projectId
        if (projectId == null) {
            screen(CaptionsBoards.transcript)
        } else {
            EditByTextDestination(
                onBack = actions.onBack,
                onAutoCaptions = { actions.onAutoCaptions(projectId) },
            )
        }
    }
}

@Composable
private fun CaptionsSetupDestination(
    onBack: () -> Unit,
    onTranscribed: () -> Unit,
    viewModel: CaptionsSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importSrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::importSrt)
    }
    BackHandler(onBack = onBack)
    LaunchedEffect(viewModel) { viewModel.done.collect { onTranscribed() } }
    ReadableWidth {
        CaptionsSetupScreen(
            state = state,
            actions = CaptionsSetupActions(
                onBack = onBack,
                onListenTo = viewModel::setListenTo,
                onLanguage = viewModel::setLanguage,
                onTranslate = viewModel::setTranslate,
                onModel = viewModel::selectModel,
                onDownload = viewModel::download,
                onOptions = viewModel::setOptions,
                onGenerate = viewModel::generate,
                onCancel = viewModel::cancel,
                onImportSrt = { importSrt.launch(SrtFiles.types) },
            ),
        )
    }
}

/** The Captions screen with its own preview; style, position and size apply to every caption. */
@Composable
private fun CaptionsEditDestination(
    onDone: () -> Unit,
    onEditByText: () -> Unit,
    viewModel: CaptionsEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val player = remember { PreviewPlayer(context, viewModel.mediaCache) }
    var playing by remember { mutableStateOf(false) }
    var liveScale by remember { mutableStateOf<Float?>(null) }
    // .srt files the user picks (#46).
    val saveSrt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SrtFiles.MIME),
    ) { uri -> uri?.let(viewModel::saveSrt) }
    val importSrt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::importSrt)
    }
    DisposableEffect(player) {
        player.onEnded = { playing = false }
        onDispose { player.release() }
    }
    LaunchedEffect(state.project) {
        liveScale = null
        state.project?.let { player.setProject(it, viewModel.state.value.playheadUs) }
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
    BackHandler(onBack = onDone)
    ReadableWidth {
        CaptionsEditScreen(
            state = state,
            scale = liveScale,
            actions = CaptionsEditActions(
                onDone = onDone,
                onStyle = viewModel::setStyle,
                onPosition = viewModel::setPosition,
                onScale = { liveScale = it },
                onScaleDone = { liveScale?.let(viewModel::setScale) },
                onSeek = {
                    playing = false
                    viewModel.seek(it)
                    player.seekTo(it)
                },
                onEditByText = onEditByText,
                onEditWord = viewModel::editWord,
                onSplit = viewModel::split,
                onMerge = viewModel::merge,
                onReplaceAll = viewModel::replaceAll,
                onSaveSrt = { state.project?.let { saveSrt.launch(SrtFiles.fileName(it)) } },
                onImportSrt = { importSrt.launch(SrtFiles.types) },
                onDismissMessage = viewModel::dismissMessage,
                onShowLanguage = { viewModel.showLanguage(it) },
                onTranslate = viewModel::openTranslate,
                onPickSource = viewModel::pickSource,
                onTranslateTo = viewModel::translate,
                onCloseTranslate = viewModel::closeTranslate,
            ),
            preview = {
                VideoSurface(
                    onAttach = { surface, width, height -> player.attach(surface, width, height) },
                    onDetach = player::detach,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    IconAction(
                        icon = if (playing) ClipraIcons.Pause else ClipraIcons.Play,
                        contentDescription = stringResource(
                            if (playing) R.string.captions_pause else R.string.captions_play,
                        ),
                        onClick = { playing = !playing },
                        modifier = Modifier.testTag("captions-play"),
                    )
                }
            },
        )
    }
}

/**
 * Edit by text: its preview plays the project as it will be after the cuts; Done makes them
 * (one undo step) and Back leaves the project as it was.
 */
@Composable
private fun EditByTextDestination(
    onBack: () -> Unit,
    onAutoCaptions: () -> Unit,
    viewModel: EditByTextViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val player = remember { PreviewPlayer(context, viewModel.mediaCache) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        player.onEnded = { playing = false }
        onDispose { player.release() }
    }
    LaunchedEffect(state.edited) {
        state.edited?.let { player.setProject(it, viewModel.state.value.playheadUs) }
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
    BackHandler(onBack = onBack)
    ReadableWidth {
        EditByTextScreen(
            state = state,
            playing = playing,
            actions = EditByTextActions(
                onBack = onBack,
                onDone = {
                    viewModel.apply()
                    onBack()
                },
                onFillers = viewModel::setFillers,
                onPauses = viewModel::setPauses,
                onThreshold = viewModel::setThreshold,
                onToken = viewModel::toggle,
                onRestoreAll = viewModel::restoreAll,
                onPlay = { playing = !playing },
                onAutoCaptions = onAutoCaptions,
            ),
            preview = {
                VideoSurface(
                    onAttach = { surface, width, height -> player.attach(surface, width, height) },
                    onDetach = player::detach,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )
    }
}
