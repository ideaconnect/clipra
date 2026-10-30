package tech.idct.clipra.feature.editor.timeline

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.Thumbnails
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.mediaTimeAt
import kotlin.math.ceil

/** Filmstrip frames for the timeline, decoded once and kept in memory while the editor is open. */
class FilmstripLoader(private val context: Context, private val cache: MediaCache) {
    private val frames = LruCache<String, ImageBitmap>(MAX_FRAMES)

    suspend fun frame(project: Project, mediaId: String, index: Int): ImageBitmap? {
        val key = "$mediaId/$index"
        frames.get(key)?.let { return it }
        val asset = project.asset(mediaId) ?: return null
        val bitmap = withContext(Dispatchers.IO) {
            Thumbnails.filmstripFrame(context, asset, cache, index)
                ?.let { BitmapFactory.decodeFile(it.path) }
                ?.asImageBitmap()
        } ?: return null
        frames.put(key, bitmap)
        return bitmap
    }

    /** The picture made when [mediaId] was imported, for the tablet's media bin (#69). */
    suspend fun thumbnail(mediaId: String): ImageBitmap? {
        val key = "thumbnail/$mediaId"
        frames.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            cache.thumbnail(mediaId).takeIf { it.exists() }
                ?.let { BitmapFactory.decodeFile(it.path) }
                ?.asImageBitmap()
        } ?: return null
        frames.put(key, bitmap)
        return bitmap
    }

    private companion object {
        const val MAX_FRAMES = 240
    }
}

/** A video clip's frames side by side, one tile per frame width along the clip. */
@Composable
fun Filmstrip(
    loader: FilmstripLoader,
    project: Project,
    clip: Clip,
    widthDp: Float,
    heightDp: Float,
    dpPerSecond: Float,
) {
    val source = clip.source ?: return
    val asset = project.asset(source.mediaId) ?: return
    val aspect = if (asset.height > 0) asset.width.toFloat() / asset.height else 16f / 9f
    val tile = heightDp * aspect
    val count = ceil(widthDp / tile).toInt().coerceIn(1, 64)
    Row {
        repeat(count) { i ->
            // The frame at the tile's start, at the clip's speed (#22).
            val sourceUs = clip.mediaTimeAt(((i * tile / dpPerSecond) * 1_000_000).toLong())
                ?: source.inPointUs
            val index = (sourceUs / MediaCache.FILMSTRIP_STEP_US).toInt()
            val bitmap by produceState<ImageBitmap?>(null, source.mediaId, index) {
                value = loader.frame(project, source.mediaId, index)
            }
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(tile.dp, heightDp.dp),
                )
            }
        }
    }
}
