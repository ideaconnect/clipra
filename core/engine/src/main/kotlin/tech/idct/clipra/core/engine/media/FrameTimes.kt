package tech.idct.clipra.core.engine.media

import android.content.Context
import android.media.MediaFormat
import tech.idct.clipra.core.engine.render.MediaSources

/**
 * When each frame of a video shows, from its container, without decoding: the times a frame
 * index of `MediaMetadataRetriever` stands for. Phone footage is rarely evenly spaced at the
 * rate it claims (30.06 fps, or variable), so spreading the duration evenly over the frames put
 * the steadying and the masks a little off the frames they belong to.
 */
internal class FrameTimes private constructor(private val times: LongArray) {
    /** Frame [index]'s time, or [fallback] when the container lists fewer frames. */
    fun at(index: Int, fallback: Long): Long = times.getOrElse(index) { fallback }

    /** The index of the frame nearest [timeUs], or [fallback] when none are listed. */
    fun nearest(timeUs: Long, fallback: Int): Int {
        if (times.isEmpty()) return fallback
        val i = times.binarySearch(timeUs)
        if (i >= 0) return i
        val after = -i - 1
        return when {
            after == 0 -> 0
            after == times.size -> times.lastIndex
            timeUs - times[after - 1] <= times[after] - timeUs -> after - 1
            else -> after
        }
    }

    companion object {
        /** The frame times of [source] (a file path or a content URI); none when unreadable. */
        fun of(context: Context, source: String): FrameTimes = FrameTimes(
            runCatching {
                val extractor = MediaSources.open(context, source)
                try {
                    val track = (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                            ?.startsWith("video/") == true
                    } ?: return@runCatching LongArray(0)
                    extractor.selectTrack(track)
                    val times = ArrayList<Long>()
                    while (extractor.sampleTime >= 0) {
                        times += extractor.sampleTime
                        extractor.advance()
                    }
                    // Samples come in decoding order; frames show in time order.
                    times.sorted().toLongArray()
                } finally {
                    extractor.release()
                }
            }.getOrDefault(LongArray(0)),
        )
    }
}
