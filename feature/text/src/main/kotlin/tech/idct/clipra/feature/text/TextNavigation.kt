package tech.idct.clipra.feature.text

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.placeholder.Board

/** Text clip [clipId] of [projectId]; no project opens the placeholder board (screen index). */
@Serializable
data class TextRoute(
    val projectId: String? = null,
    val clipId: String? = null,
    val playheadUs: Long = 0,
)

/**
 * The boards this module builds, from design/README.md ("Effects and look").
 */
object TextBoards {
    val text = Board(id = "Text", title = R.string.text_title_text, route = TextRoute())

    val all = listOf(text)
}

/** Where the text screens lead; the app connects it. */
class TextNavigationActions(val onDone: () -> Unit)

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.textScreens(
    screen: @Composable (Board) -> Unit,
    actions: TextNavigationActions,
) {
    composable<TextRoute> { entry ->
        if (entry.toRoute<TextRoute>().projectId == null) {
            screen(TextBoards.text)
        } else {
            TextDestination(onDone = actions.onDone)
        }
    }
}
