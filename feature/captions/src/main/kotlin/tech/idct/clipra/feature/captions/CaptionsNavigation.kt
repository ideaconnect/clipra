package tech.idct.clipra.feature.captions

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.placeholder.Board

@Serializable
data object CaptionsSetupRoute

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
        route = CaptionsSetupRoute,
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

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.captionsScreens(screen: @Composable (Board) -> Unit) {
    composable<CaptionsSetupRoute> { screen(CaptionsBoards.captionsSetup) }
    composable<CaptionsEditRoute> { screen(CaptionsBoards.captionsEdit) }
    composable<TranscriptRoute> { screen(CaptionsBoards.transcript) }
}
