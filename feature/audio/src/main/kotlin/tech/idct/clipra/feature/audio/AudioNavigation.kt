package tech.idct.clipra.feature.audio

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.placeholder.Board

@Serializable
data object AudioRoute

/**
 * The boards this module builds, from design/README.md ("Audio, Clipra Pro and AI models").
 */
object AudioBoards {
    val audio = Board(id = "Audio", title = R.string.audio_title_audio, route = AudioRoute)

    val all = listOf(audio)
}

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.audioScreens(screen: @Composable (Board) -> Unit) {
    composable<AudioRoute> { screen(AudioBoards.audio) }
}
