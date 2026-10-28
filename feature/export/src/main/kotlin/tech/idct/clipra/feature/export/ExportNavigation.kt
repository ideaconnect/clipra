package tech.idct.clipra.feature.export

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.placeholder.Board
import tech.idct.clipra.core.engine.export.ExportManager
import tech.idct.clipra.core.engine.export.ExportState
import javax.inject.Inject

/** Export for [projectId]; null opens the placeholder board (debug screen index). */
@Serializable
data class ExportRoute(val projectId: String? = null)

/** The running export; [projectId] starts one, null shows the one in progress. */
@Serializable
data class ExportProgressRoute(val projectId: String? = null)

@Serializable
data object ExportDoneRoute

/**
 * The boards this module builds, from design/README.md ("Export with two ads").
 */
object ExportBoards {
    val export = Board(id = "Export", title = R.string.export_title_export, route = ExportRoute())
    val exportProgress = Board(
        id = "ExportProgress",
        title = R.string.export_title_export_progress,
        route = ExportProgressRoute(),
    )
    val exportDone = Board(
        id = "ExportDone",
        title = R.string.export_title_export_done,
        route = ExportDoneRoute,
    )

    val all = listOf(export, exportProgress, exportDone)
}

/** Where the export screens lead; the app connects them. */
class ExportActions(
    val onBack: () -> Unit,
    val onStarted: (projectId: String) -> Unit,
    /** Leaves the progress screen; the export carries on. */
    val onKeepEditing: () -> Unit,
    val onDone: () -> Unit,
    val onPro: () -> Unit,
)

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.exportScreens(screen: @Composable (Board) -> Unit, actions: ExportActions) {
    composable<ExportRoute> { entry ->
        if (entry.toRoute<ExportRoute>().projectId == null) {
            screen(ExportBoards.export)
        } else {
            ExportRoute(actions)
        }
    }
    composable<ExportProgressRoute> { ExportProgressRoute(actions) }
    composable<ExportDoneRoute> { SavedRoute(screen, actions) }
}

@Composable
private fun ExportRoute(actions: ExportActions, viewModel: ExportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val project = state.project ?: return
    ExportScreen(
        project = project,
        settings = state.settings,
        support = state.support,
        onChange = viewModel::change,
        onExport = { viewModel.start(actions.onStarted) },
        onBack = actions.onBack,
    )
}

@Composable
private fun ExportProgressRoute(
    actions: ExportActions,
    viewModel: ExportProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val askNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        // The notification tells you when the video is in the Gallery.
        if (Build.VERSION.SDK_INT >=
            33
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(state) {
        if (state is ExportState.Done) actions.onDone()
    }
    LifecycleResumeEffect(Unit) {
        viewModel.setVisible(true)
        onPauseOrDispose { viewModel.setVisible(false) }
    }
    ExportProgressScreen(
        state = state,
        onKeepEditing = actions.onKeepEditing,
        onPause = viewModel::pause,
        onResume = viewModel::resume,
        onCancel = {
            viewModel.cancel()
            actions.onKeepEditing()
        },
    )
}

@HiltViewModel
class SavedViewModel @Inject constructor(private val exports: ExportManager) : ViewModel() {
    val state: StateFlow<ExportState> = exports.state

    /** The finished export has been seen; the next one starts fresh. */
    override fun onCleared() = exports.dismiss()
}

@Composable
private fun SavedRoute(
    screen: @Composable (Board) -> Unit,
    actions: ExportActions,
    viewModel: SavedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved = state as? ExportState.Done
    if (saved == null) {
        // Opened from the screen index without an export.
        screen(ExportBoards.exportDone)
        return
    }
    val context = LocalContext.current
    val uri = Uri.parse(saved.uri)
    SavedScreen(
        saved = saved,
        // Every user is a free user until Clipra Pro is on sale (M9).
        showPro = true,
        onShare = { shareVideo(context, uri) },
        onOpenGallery = { openInGallery(context, uri) },
        onPro = actions.onPro,
        onBackToEditing = actions.onKeepEditing,
    )
}
