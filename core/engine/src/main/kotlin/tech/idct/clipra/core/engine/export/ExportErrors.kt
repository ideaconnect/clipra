package tech.idct.clipra.core.engine.export

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException

/**
 * What a failed export tells whoever has to find out why (#236). The export screen only says
 * which [ExportFailure] it was, so the log gets Media3's error code and the exception.
 */
internal object ExportErrors {
    /**
     * Media3's name for what went wrong ("ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED"), from the
     * first [ExportException] among [error] and its causes. Null when the export failed outside
     * Transformer, for example while publishing the video or saving the extras.
     */
    @OptIn(UnstableApi::class)
    fun codeName(error: Throwable): String? {
        val seen = mutableSetOf<Throwable>()
        var next: Throwable? = error
        while (next != null && seen.add(next)) {
            if (next is ExportException) return next.errorCodeName
            next = next.cause
        }
        return null
    }

    /** "Export failed: Other (ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED)", or without the code. */
    fun message(failure: ExportFailure, codeName: String?): String =
        "Export failed: $failure" + (codeName?.let { " ($it)" } ?: "")
}
