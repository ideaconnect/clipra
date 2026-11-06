package tech.idct.clipra.core.engine.export

import android.content.Context
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.export.LeftOut
import tech.idct.clipra.core.model.export.LeftOutClip

/**
 * The originals an export can't open (#212). The export reads only originals, so a clip whose
 * original is gone (moved or deleted, or access lost) is left out of the video, even while the
 * preview still plays it from its cached proxy.
 */
object MissingOriginals {
    /**
     * The clips an export of [project] leaves out: [LeftOut.clips], with each media asked of the
     * resolver the export reads from (`forExport = true`). Each check opens a file or asks
     * MediaProvider, so this runs off the main thread.
     */
    fun leftOut(context: Context, cache: MediaCache, project: Project): List<LeftOutClip> {
        val media = ProjectMediaResolver(context, cache, forExport = true, maxImageSide = 0)
        return LeftOut.clips(project) { media.source(it) != null }
    }
}
