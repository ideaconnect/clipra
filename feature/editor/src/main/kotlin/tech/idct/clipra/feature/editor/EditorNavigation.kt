package tech.idct.clipra.feature.editor

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.placeholder.Board

@Serializable
data object EditorRoute

@Serializable
data object LayersRoute

/**
 * The boards this module builds, from design/README.md ("Start and edit").
 */
object EditorBoards {
    val editor = Board(id = "Editor", title = R.string.editor_title_editor, route = EditorRoute)
    val layers = Board(id = "Layers", title = R.string.editor_title_layers, route = LayersRoute)

    val all = listOf(editor, layers)
}

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.editorScreens(screen: @Composable (Board) -> Unit) {
    composable<EditorRoute> { screen(EditorBoards.editor) }
    composable<LayersRoute> { screen(EditorBoards.layers) }
}
