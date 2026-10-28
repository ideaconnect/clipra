package tech.idct.clipra.feature.text

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.placeholder.Board

@Serializable
data object TextRoute

/**
 * The boards this module builds, from design/README.md ("Effects and look").
 */
object TextBoards {
    val text = Board(id = "Text", title = R.string.text_title_text, route = TextRoute)

    val all = listOf(text)
}

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.textScreens(screen: @Composable (Board) -> Unit) {
    composable<TextRoute> { screen(TextBoards.text) }
}
