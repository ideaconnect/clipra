package tech.idct.clipra.feature.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import tech.idct.clipra.core.designsystem.component.VideoSurface
import tech.idct.clipra.core.engine.preview.PreviewPlayer

/** The preview: the player's picture. */
@Composable
fun PreviewSurface(player: PreviewPlayer, modifier: Modifier = Modifier) {
    VideoSurface(
        onAttach = { surface, width, height -> player.attach(surface, width, height) },
        onDetach = player::detach,
        modifier = modifier
            .fillMaxSize()
            .testTag("preview-surface"),
    )
}
