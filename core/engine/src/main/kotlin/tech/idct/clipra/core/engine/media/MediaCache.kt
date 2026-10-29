package tech.idct.clipra.core.engine.media

import java.io.File

/**
 * Proxies, thumbnails and filmstrip frames, keyed by media id. They live in the cache directory:
 * the system may clear them, and Clipra makes them again when they are needed. Project documents
 * never store these paths.
 */
class MediaCache(private val root: File) {
    /** The 720p H.264 proxy the editor plays instead of the original. */
    fun proxy(mediaId: String): File = File(root, "proxies/$mediaId.mp4")

    /** A still for Home and the media bin. */
    fun thumbnail(mediaId: String): File = File(root, "thumbnails/$mediaId.jpg")

    /** The waveform's peaks (#36), one byte per 20 ms. */
    fun waveform(mediaId: String): File = File(root, "waveforms/$mediaId.peaks")

    /** One filmstrip frame per [FILMSTRIP_STEP_US] of the media, for the timeline. */
    fun filmstripFrame(mediaId: String, index: Int): File =
        File(root, "filmstrips/$mediaId/$index.jpg")

    companion object {
        const val FILMSTRIP_STEP_US = 1_000_000L
    }
}
