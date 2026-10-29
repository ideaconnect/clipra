package tech.idct.clipra.core.designsystem.component

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A surface for a video player's picture (the editor's preview and the tool screens'). The
 * player draws on the [Surface] from [onAttach], at the given size, until [onDetach].
 */
@Composable
fun VideoSurface(
    onAttach: (surface: Surface, width: Int, height: Int) -> Unit,
    onDetach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val attach by rememberUpdatedState(onAttach)
    val detach by rememberUpdatedState(onDetach)
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
                    ) = attach(holder.surface, width, height)

                    override fun surfaceDestroyed(holder: SurfaceHolder) = detach()
                })
            }
        },
        modifier = modifier,
    )
}
