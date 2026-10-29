package tech.idct.clipra.feature.editor

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import tech.idct.clipra.core.engine.preview.PreviewPlayer

/** The preview: the player's picture on a SurfaceView. */
@Composable
fun PreviewSurface(player: PreviewPlayer, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            player.attach(holder.surface, width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) = player.detach()
                    })
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("preview-surface"),
        )
    }
}
