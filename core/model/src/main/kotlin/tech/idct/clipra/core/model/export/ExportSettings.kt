package tech.idct.clipra.core.model.export

import kotlinx.serialization.Serializable
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.FrameRate
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Resolution
import tech.idct.clipra.core.model.Size
import java.util.Locale
import kotlin.math.roundToInt

/** Video codecs Clipra exports: [factor] scales the H.264 bitrate for the same quality. */
@Serializable
enum class VideoCodec(val mimeType: String, val factor: Double) {
    H264("video/avc", 1.0),
    HEVC("video/hevc", 0.6),
    AV1("video/av01", 0.5),
}

@Serializable
enum class ExportQuality(val factor: Double) {
    Smaller(0.6),
    Recommended(1.0),
    High(1.6),
}

/** What happens to caption clips: drawn into the video, saved as a `.srt` file, or left out. */
@Serializable
enum class CaptionMode { BurnIn, Srt, Off }

/**
 * Export options (Export board, docs/product/spec.md section 16). The output keeps the project's
 * ratio; [resolution] is its short side.
 */
@Serializable
data class ExportSettings(
    val resolution: Resolution = Resolution.FHD,
    val frameRate: FrameRate = FrameRate.Fps30,
    val codec: VideoCodec = VideoCodec.H264,
    val quality: ExportQuality = ExportQuality.Recommended,
    val captions: CaptionMode = CaptionMode.BurnIn,
) {
    fun outputSize(project: Project): Size = project.format.copy(resolution = resolution).outputSize

    /**
     * The video bitrate: the H.264 base for the resolution at 30 fps (720p 7.5, 1080p 12,
     * 1440p 24, 2160p 45 Mbps), ×1.5 above 30 fps, times the quality and codec factors.
     */
    val videoMbps: Double
        get() {
            val base = when (resolution) {
                Resolution.HD -> 7.5
                Resolution.FHD -> 12.0
                Resolution.QHD -> 24.0
                Resolution.UHD -> 45.0
            }
            val fps = if (frameRate.fps > 30) 1.5 else 1.0
            return base * fps * quality.factor * codec.factor
        }

    val videoBitrate: Int get() = (videoMbps * 1_000_000).roundToInt()

    /** Bitrate × duration / 8 plus about 1.3 MB of audio, in decimal megabytes. */
    fun estimatedMegabytes(durationUs: Long): Double =
        videoMbps * durationUs / 1_000_000.0 / 8 + AUDIO_MB

    companion object {
        const val AUDIO_MB = 1.3

        /** Starts from the project's own resolution and frame rate. */
        fun forProject(format: Format) =
            ExportSettings(resolution = format.resolution, frameRate = format.frameRate)
    }
}

/**
 * "~64.3": rounded at 100 MB and above, one decimal below, as the design shows it, in the
 * user's number format.
 */
fun formatEstimate(megabytes: Double, locale: Locale = Locale.getDefault()): String {
    val value = if (megabytes >= 100) {
        megabytes.roundToInt().toString()
    } else {
        String.format(locale, "%.1f", megabytes)
    }
    return "~$value"
}
