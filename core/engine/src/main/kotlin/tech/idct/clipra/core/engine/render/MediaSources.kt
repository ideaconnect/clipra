package tech.idct.clipra.core.engine.render

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import java.io.File

/**
 * Media is either a file in app storage (proxies, recordings) or a content URI from the photo
 * picker (originals). Engine code takes both as a string.
 */
internal object MediaSources {
    fun uri(source: String): Uri =
        if (source.startsWith("content:") || source.startsWith("file:")) {
            Uri.parse(source)
        } else {
            Uri.fromFile(File(source))
        }

    fun open(context: Context, source: String): MediaExtractor = MediaExtractor().apply {
        if (source.startsWith("content:")) {
            setDataSource(context, Uri.parse(source), null)
        } else {
            setDataSource(source.removePrefix("file://"))
        }
    }
}
