package tech.idct.clipra.feature.editor

import android.content.Context
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.model.MediaAsset

/**
 * The project's media whose original Clipra can't read any more (moved or deleted, or access
 * lost), by media id (#213). The export reads only originals, so it leaves their clips out,
 * whatever the cache holds. [previewOnly] are those of them whose proxy is still cached: the
 * preview goes on playing them from it until the system clears the cache.
 */
data class MissingMedia(
    val ids: Set<String> = emptySet(),
    val previewOnly: Set<String> = emptySet(),
) {
    companion object {
        /**
         * Checks [media] with [readable] (can its original be opened?) and [cached] (is its
         * proxy there?). Each check opens a file or asks MediaProvider, so this runs off the
         * main thread.
         */
        fun check(
            media: List<MediaAsset>,
            readable: (MediaAsset) -> Boolean,
            cached: (MediaAsset) -> Boolean,
        ): MissingMedia {
            val gone = media.filterNot(readable)
            return MissingMedia(
                ids = gone.map { it.id }.toSet(),
                previewOnly = gone.filter(cached).map { it.id }.toSet(),
            )
        }

        /** Checks [media] on this phone: the originals as the export opens them, and [cache]. */
        fun check(context: Context, cache: MediaCache, media: List<MediaAsset>): MissingMedia =
            check(
                media,
                readable = { ProjectMediaResolver.isAvailable(context, it.uri) },
                cached = { cache.proxy(it.id).exists() },
            )
    }
}
