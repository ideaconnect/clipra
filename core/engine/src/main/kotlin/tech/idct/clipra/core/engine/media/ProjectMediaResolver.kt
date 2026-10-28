package tech.idct.clipra.core.engine.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import tech.idct.clipra.core.engine.render.MediaResolver
import tech.idct.clipra.core.model.MediaAsset

/**
 * Finds a project's media for the engine. Preview reads the proxy when it is ready and the
 * original until then; export always reads the original. Missing media (moved or deleted, or
 * access lost) resolves to null, and its clips are left out instead of failing.
 */
class ProjectMediaResolver(
    private val context: Context,
    private val cache: MediaCache,
    private val forExport: Boolean,
    private val maxImageSide: Int,
) : MediaResolver {
    override fun source(asset: MediaAsset): String? {
        if (!forExport) {
            val proxy = cache.proxy(asset.id)
            if (proxy.exists()) return proxy.path
        }
        return asset.uri.takeIf { isAvailable(context, it) }
    }

    override fun image(asset: MediaAsset): Bitmap? {
        if (!isAvailable(context, asset.uri)) return null
        return Thumbnails.decodePhoto(context, Uri.parse(asset.uri), maxImageSide)
    }

    companion object {
        /** Whether Clipra can still read [uri]. */
        fun isAvailable(context: Context, uri: String): Boolean = try {
            context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
