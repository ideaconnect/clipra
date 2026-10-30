package tech.idct.clipra.feature.captions

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.placeholder.Board

/** Auto captions for [projectId]; no project opens the placeholder board (screen index). */
@Serializable
data class CaptionsSetupRoute(val projectId: String? = null)

@Serializable
data object CaptionsEditRoute

@Serializable
data object TranscriptRoute

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
        route = CaptionsEditRoute,
    )
    val transcript = Board(
        id = "Transcript",
        title = R.string.captions_title_transcript,
        route = TranscriptRoute,
    )

    val all = listOf(captionsSetup, captionsEdit, transcript)
}

/** Where the captions screens lead; the app connects it. */
class CaptionsNavigationActions(
    val onBack: () -> Unit,
    /** Auto captions finished: the transcripts are in the project. */
    val onTranscribed: (projectId: String) -> Unit,
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
    composable<CaptionsEditRoute> { screen(CaptionsBoards.captionsEdit) }
    composable<TranscriptRoute> { screen(CaptionsBoards.transcript) }
}

@Composable
private fun CaptionsSetupDestination(
    onBack: () -> Unit,
    onTranscribed: () -> Unit,
    viewModel: CaptionsSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    LaunchedEffect(viewModel) { viewModel.done.collect { onTranscribed() } }
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
        ),
    )
}
