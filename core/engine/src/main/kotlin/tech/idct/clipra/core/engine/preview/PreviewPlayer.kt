package tech.idct.clipra.core.engine.preview

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.engine.render.CompositionFactory
import tech.idct.clipra.core.engine.render.MediaResolver
import tech.idct.clipra.core.engine.render.ProjectRender
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.Project
import kotlin.math.roundToInt

/**
 * The editor's preview (#14): the project's composition, built by the same layer compositor as
 * the export, at preview size from proxies, played by Media3's CompositionPlayer. Use it on the
 * main thread.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class PreviewPlayer(private val context: Context, cache: MediaCache) {
    private val player = CompositionPlayer.Builder(context).build()
    private val media = CachingResolver(ProjectMediaResolver(context, cache, false, IMAGE_SIDE))

    /** CompositionPlayer can't seek or play before it has a composition. */
    private var hasComposition = false

    /** Called when playback reaches the end. */
    var onEnded: (() -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
            }
        })
    }

    val positionUs: Long get() = player.currentPosition * 1_000

    val isPlaying: Boolean get() = player.playWhenReady

    /** Rebuilds the composition after an edit and returns to [positionUs]. */
    fun setProject(project: Project, positionUs: Long) {
        val size = previewSize(project.format)
        val timeline = ProjectRender.timeline(project, media, size.width, size.height)
        player.setComposition(CompositionFactory.create(context, timeline))
        hasComposition = true
        player.seekTo(positionUs / 1_000)
        player.prepare()
    }

    fun attach(surface: Surface, width: Int, height: Int) =
        player.setVideoSurface(surface, Size(width, height))

    fun detach() = player.clearVideoSurface()

    fun play() {
        if (hasComposition) player.playWhenReady = true
    }

    fun pause() {
        player.playWhenReady = false
    }

    fun seekTo(positionUs: Long) {
        if (hasComposition) player.seekTo(positionUs / 1_000)
    }

    fun release() = player.release()

    /** Photos decode once per editor session instead of on every rebuild. */
    private class CachingResolver(private val inner: MediaResolver) : MediaResolver {
        private val images = HashMap<String, Bitmap?>()

        override fun source(asset: MediaAsset): String? = inner.source(asset)

        override fun image(asset: MediaAsset): Bitmap? = images.getOrPut(asset.id) {
            inner.image(asset)
        }
    }

    companion object {
        /** Preview renders at 540p on its short side: proxies are 720p, and the view is small. */
        const val PREVIEW_SHORT_SIDE = 540
        private const val IMAGE_SIDE = PREVIEW_SHORT_SIDE * 2

        fun previewSize(format: Format): tech.idct.clipra.core.model.Size {
            val output = format.outputSize
            val scale = PREVIEW_SHORT_SIDE.toFloat() / minOf(output.width, output.height)
            fun even(v: Float) = (v / 2).roundToInt() * 2
            return tech.idct.clipra.core.model.Size(
                even(output.width * scale),
                even(output.height * scale),
            )
        }
    }
}
