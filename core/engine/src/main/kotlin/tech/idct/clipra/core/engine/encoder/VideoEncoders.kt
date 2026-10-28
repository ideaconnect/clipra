package tech.idct.clipra.core.engine.encoder

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/** What the phone's video encoders can do, checked before offering a format. */
object VideoEncoders {
    /** Whether a hardware or software encoder for [mime] takes frames of [width] × [height]. */
    fun supportsSize(
        width: Int,
        height: Int,
        mime: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    ): Boolean = encoders(mime).any { info ->
        val video = info.getCapabilitiesForType(mime).videoCapabilities ?: return@any false
        // Portrait frames are often encoded as landscape with a rotation flag.
        video.isSizeSupported(width, height) || video.isSizeSupported(height, width)
    }

    private fun encoders(mime: String): List<MediaCodecInfo> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
}
