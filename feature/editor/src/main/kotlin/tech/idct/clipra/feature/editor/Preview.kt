package tech.idct.clipra.feature.editor

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.engine.preview.PreviewPlayer

/**
 * The preview: the player's picture on a SurfaceView, with the selected clip's handles drawn
 * over it when the clip is on screen. Clips fill the frame until transform lands (#19), so the
 * handles mark the whole frame.
 */
@Composable
fun PreviewSurface(player: PreviewPlayer, showHandles: Boolean, modifier: Modifier = Modifier) {
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
        if (showHandles) SelectionHandles()
    }
}

@Composable
private fun SelectionHandles() {
    val accent = ClipraTheme.colors.accent
    Canvas(modifier = Modifier.fillMaxSize().testTag("preview-handles")) {
        val inset = 1.dp.toPx()
        drawRect(
            color = accent,
            topLeft = Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(
                size.width - 2 * inset,
                size.height - 2 * inset,
            ),
            style = Stroke(width = 2.dp.toPx()),
        )
        val radius = 5.dp.toPx()
        listOf(
            Offset(inset, inset),
            Offset(size.width - inset, inset),
            Offset(inset, size.height - inset),
            Offset(size.width - inset, size.height - inset),
        ).forEach { drawCircle(accent, radius, it) }
    }
}
