package tech.idct.clipra.feature.home

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.project.ProjectRepository
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.Thumbnails
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** The pictures in Home's project frames (Main board), by project and media id. */
fun interface ProjectPictures {
    suspend fun picture(projectId: String, mediaId: String): ImageBitmap?
}

/** Home's pictures; none unless Home's route gives them, as in screens without media. */
internal val LocalProjectPictures = staticCompositionLocalOf { ProjectPictures { _, _ -> null } }

/**
 * The thumbnails made when the media was imported, decoded once while Home is open. A thumbnail
 * the cache lost (#217), as after the system or Settings cleared it, is made again from the
 * project's media, while its original can be read.
 */
class CachedProjectPictures @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cache: MediaCache,
    private val projects: ProjectRepository,
) : ProjectPictures {
    private val pictures = LruCache<String, ImageBitmap>(MAX_PICTURES)

    /** Media whose thumbnail couldn't be made while Home is open, as when its original is gone. */
    private val unreadable = ConcurrentHashMap.newKeySet<String>()

    /** One thumbnail made at a time: each reads an original, often a 4K video. */
    private val making = Mutex()

    override suspend fun picture(projectId: String, mediaId: String): ImageBitmap? {
        pictures.get(mediaId)?.let { return it }
        val picture = withContext(Dispatchers.IO) {
            val file = cached(mediaId) ?: make(projectId, mediaId)
            file?.let { BitmapFactory.decodeFile(it.path) }?.asImageBitmap()
        } ?: return null
        pictures.put(mediaId, picture)
        return picture
    }

    private fun cached(mediaId: String): File? = cache.thumbnail(mediaId).takeIf { it.exists() }

    /** Makes the thumbnail of [mediaId] again, as the import did; null when it can't be read. */
    private suspend fun make(projectId: String, mediaId: String): File? = making.withLock {
        cached(mediaId) ?: if (mediaId in unreadable) null else makeNow(projectId, mediaId)
    }

    private suspend fun makeNow(projectId: String, mediaId: String): File? {
        val project = try {
            projects.load(projectId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val made = project?.asset(mediaId)?.let { asset ->
            try {
                Thumbnails.thumbnail(context, asset, cache)
            } catch (_: Exception) {
                null
            }
        }
        if (made == null) unreadable += mediaId
        return made
    }

    private companion object {
        const val MAX_PICTURES = 64
    }
}
