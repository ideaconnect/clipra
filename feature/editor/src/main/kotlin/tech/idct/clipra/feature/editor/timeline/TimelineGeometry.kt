package tech.idct.clipra.feature.editor.timeline

import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Where things sit on the phone timeline (Editor board, design/canvas/Editor.dc.html): the
 * playhead is fixed in the middle of the view and the timeline moves under it. Positions are in
 * dp from the left of the lanes; times are microseconds.
 */
data class TimelineGeometry(
    /** dp per second: 32 by default, changed by pinch to zoom. */
    val dpPerSecond: Float = DEFAULT_DP_PER_SECOND,
    /** Where the playhead line sits, in dp from the left of the lanes. */
    val playheadX: Float,
    val playheadUs: Long,
) {
    fun x(timeUs: Long): Float = playheadX + (timeUs - playheadUs) / 1_000_000f * dpPerSecond

    fun width(durationUs: Long): Float = durationUs / 1_000_000f * dpPerSecond

    /** The time under [x] dp. */
    fun time(x: Float): Long =
        playheadUs + ((x - playheadX) / dpPerSecond * 1_000_000).roundToLong()

    /** Microseconds that [dp] of drag move. */
    fun duration(dp: Float): Long = (dp / dpPerSecond * 1_000_000).roundToLong()

    /** Ruler ticks every half second, long on whole seconds, labelled every 2 s, where visible. */
    fun ticks(viewWidth: Float): List<Tick> {
        val first = (time(0f) / HALF_SECOND_US - 1).coerceAtLeast(0)
        val last = time(viewWidth) / HALF_SECOND_US + 1
        val labelEvery = when {
            dpPerSecond >= 24 -> 4L
            dpPerSecond >= 10 -> 10L
            else -> 20L
        }
        return (first..last).map { n ->
            val time = n * HALF_SECOND_US
            Tick(time, x(time), long = n % 2 == 0L, labelled = n % labelEvery == 0L)
        }
    }

    data class Tick(val timeUs: Long, val x: Float, val long: Boolean, val labelled: Boolean)

    companion object {
        const val DEFAULT_DP_PER_SECOND = 32f
        const val MIN_DP_PER_SECOND = 4f
        const val MAX_DP_PER_SECOND = 256f
        private const val HALF_SECOND_US = 500_000L

        /** Lane height: video lanes are tall for the filmstrip and audio strip. */
        fun laneHeight(layer: Layer): Float =
            if (layer.clips.any { it.type == ClipType.Video }) VIDEO_LANE else LANE

        /** The tablet's lanes (TabletEditor board): video tallest, captions thinnest. */
        fun tabletLaneHeight(layer: Layer): Float = when {
            layer.clips.any { it.type == ClipType.Video } -> TABLET_VIDEO_LANE

            layer.clips.isNotEmpty() && layer.clips.all { it.type == ClipType.Audio } ->
                TABLET_AUDIO_LANE

            layer.clips.isNotEmpty() && layer.clips.all { it.type == ClipType.Caption } ->
                TABLET_CAPTION_LANE

            else -> LANE
        }

        const val VIDEO_LANE = 50f
        const val LANE = 28f
        const val AUDIO_STRIP = 11f
        const val LANE_GAP = 4f
        const val RULER = 24f
        const val GUTTER = 36f

        /** The tablet's layer headers, and where its playhead stands across the lanes. */
        const val HEADER = 120f
        const val TABLET_PLAYHEAD = 0.3f
        const val TABLET_DP_PER_SECOND = 48f
        const val TABLET_VIDEO_LANE = 56f
        const val TABLET_AUDIO_LANE = 32f
        const val TABLET_CAPTION_LANE = 24f
    }
}

/** "00:07.12": minutes, seconds and hundredths, as the transport shows the playhead. */
fun formatTimecode(us: Long): String {
    val hundredths = us / 10_000
    val minutes = hundredths / 6_000
    val seconds = (hundredths / 100) % 60
    return "%02d:%02d.%02d".format(minutes, seconds, hundredths % 100)
}

/** "00:04": the ruler's labels. */
fun formatRulerLabel(us: Long): String {
    val seconds = us / 1_000_000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}

/** "9.2s": a clip's duration badge, one decimal in the user's number format. */
fun formatClipDuration(us: Long, locale: Locale = Locale.getDefault()): String =
    String.format(locale, "%.1fs", us / 1_000_000.0)
