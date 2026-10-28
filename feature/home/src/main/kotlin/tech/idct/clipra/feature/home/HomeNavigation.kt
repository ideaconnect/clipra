package tech.idct.clipra.feature.home

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.placeholder.Board

@Serializable
data object MainRoute

@Serializable
data object NewProjectRoute

/**
 * The boards this module builds, from design/README.md ("Start and edit").
 */
object HomeBoards {
    val main = Board(id = "Main", title = R.string.home_title_main, route = MainRoute)
    val newProject = Board(
        id = "NewProject",
        title = R.string.home_title_new_project,
        route = NewProjectRoute,
    )

    val all = listOf(main, newProject)
}

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.homeScreens(screen: @Composable (Board) -> Unit) {
    composable<MainRoute> { screen(HomeBoards.main) }
    composable<NewProjectRoute> { screen(HomeBoards.newProject) }
}
