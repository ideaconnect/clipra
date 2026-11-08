package tech.idct.clipra.core.designsystem.component

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.R
import tech.idct.clipra.core.designsystem.theme.ClipraSpacing

/**
 * A surface for a video player's picture (the editor's preview and the tool screens'). The
 * player draws on the [Surface] from [onAttach], at the given size, until [onDetach].
 *
 * A [TextureView] keeps its surface, and the picture on it, while another screen (a picker) or
 * the background covers the app; a SurfaceView's would be destroyed and come back blank.
 *
 * While the picture changes (playback, a drag), a small label in the top left corner shows how
 * many new pictures a second reach the view (#227).
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
    val meter = remember { FrameRateMeter() }
    var fps by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(meter) {
        while (true) {
            delay(FPS_UPDATE_MS)
            fps = meter.fps(System.nanoTime())
        }
    }
    Box(modifier = modifier) {
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

                        // On the main thread, once for each picture the view takes.
                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) =
                            meter.onFrame(System.nanoTime())
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        fps?.let { FrameRateLabel(it, Modifier.align(Alignment.TopStart)) }
    }
}

/** The pictures a second a video surface shows, in small type over its corner. */
@Composable
fun FrameRateLabel(fps: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.designsystem_preview_fps, fps),
        style = ClipraTheme.typography.clipLabel,
        color = ClipraTheme.colors.textSecondary,
        modifier = modifier
            .testTag("preview-fps")
            .padding(ClipraSpacing.Xs)
            .background(ClipraTheme.colors.background.copy(alpha = LABEL_BACKGROUND_ALPHA))
            .padding(horizontal = ClipraSpacing.Xs, vertical = ClipraSpacing.Xxs),
    )
}

private const val FPS_UPDATE_MS = 500L
private const val LABEL_BACKGROUND_ALPHA = 0.6f
