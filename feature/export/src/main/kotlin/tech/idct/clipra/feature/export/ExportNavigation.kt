package tech.idct.clipra.feature.export

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.placeholder.Board

/** Export for [projectId]; null opens the placeholder board (debug screen index). */
@Serializable
data class ExportRoute(val projectId: String? = null)

@Serializable
data object ExportProgressRoute

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
        route = ExportProgressRoute,
    )
    val exportDone = Board(
        id = "ExportDone",
        title = R.string.export_title_export_done,
        route = ExportDoneRoute,
    )

    val all = listOf(export, exportProgress, exportDone)
}

/** Where Export leads; the app connects it. */
class ExportActions(val onBack: () -> Unit, val onStarted: (projectId: String) -> Unit)

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.exportScreens(screen: @Composable (Board) -> Unit, actions: ExportActions) {
    composable<ExportRoute> { entry ->
        if (entry.toRoute<ExportRoute>().projectId == null) {
            screen(ExportBoards.export)
        } else {
            ExportRoute(actions)
        }
    }
    composable<ExportProgressRoute> { screen(ExportBoards.exportProgress) }
    composable<ExportDoneRoute> { screen(ExportBoards.exportDone) }
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
