package tech.idct.clipra.feature.home

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.engine.media.MediaCache
import javax.inject.Inject

/** The pictures in Home's project frames (Main board), by media id. */
fun interface ProjectPictures {
    suspend fun picture(mediaId: String): ImageBitmap?
}

/** Home's pictures; none unless Home's route gives them, as in screens without media. */
internal val LocalProjectPictures = staticCompositionLocalOf { ProjectPictures { null } }

/**
 * The thumbnails made when the media was imported, decoded once while Home is open. A
 * thumbnail cleared with the cache shows nothing until the project's media is shown again.
 */
class CachedProjectPictures @Inject constructor(private val cache: MediaCache) : ProjectPictures {
    private val pictures = LruCache<String, ImageBitmap>(MAX_PICTURES)

    override suspend fun picture(mediaId: String): ImageBitmap? {
        pictures.get(mediaId)?.let { return it }
        val picture = withContext(Dispatchers.IO) {
            cache.thumbnail(mediaId).takeIf { it.exists() }
                ?.let { BitmapFactory.decodeFile(it.path) }
                ?.asImageBitmap()
        } ?: return null
        pictures.put(mediaId, picture)
        return picture
    }

    private companion object {
        const val MAX_PICTURES = 64
    }
}
