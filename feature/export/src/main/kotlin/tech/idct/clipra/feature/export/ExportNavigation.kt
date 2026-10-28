package tech.idct.clipra.feature.export

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.placeholder.Board

@Serializable
data object ExportRoute

@Serializable
data object ExportProgressRoute

@Serializable
data object ExportDoneRoute

/**
 * The boards this module builds, from design/README.md ("Export with two ads").
 */
object ExportBoards {
    val export = Board(id = "Export", title = R.string.export_title_export, route = ExportRoute)
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

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.exportScreens(screen: @Composable (Board) -> Unit) {
    composable<ExportRoute> { screen(ExportBoards.export) }
    composable<ExportProgressRoute> { screen(ExportBoards.exportProgress) }
    composable<ExportDoneRoute> { screen(ExportBoards.exportDone) }
}
