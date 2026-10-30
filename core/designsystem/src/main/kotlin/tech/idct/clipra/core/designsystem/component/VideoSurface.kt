package tech.idct.clipra.core.designsystem.component

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A surface for a video player's picture (the editor's preview and the tool screens'). The
 * player draws on the [Surface] from [onAttach], at the given size, until [onDetach].
 *
 * A [TextureView] keeps its surface, and the picture on it, while another screen (a picker) or
 * the background covers the app; a SurfaceView's would be destroyed and come back blank.
 */
@Composable
fun VideoSurface(
    onAttach: (surface: Surface, width: Int, height: Int) -> Unit,
    /** The surface given to [onAttach] is going away. */
    onDetach: (surface: Surface) -> Unit,
    modifier: Modifier = Modifier,
) {
    val attach by rememberUpdatedState(onAttach)
    val detach by rememberUpdatedState(onDetach)
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null

                    override fun onSurfaceTextureAvailable(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        val created = Surface(texture)
                        surface = created
                        attach(created, width, height)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        surface?.let { attach(it, width, height) }
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        surface?.let(detach)
                        surface?.release()
                        surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        modifier = modifier,
    )
}
