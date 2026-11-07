package tech.idct.clipra.core.designsystem.component

import kotlin.math.roundToInt

/**
 * Counts the frames a video surface shows (#227): [onFrame] for each new picture, [fps] for
 * the rate over the last [windowNs]. Times are nanoseconds on one clock. Not thread-safe: the
 * preview calls it on the main thread.
 */
class FrameRateMeter(
    private val windowNs: Long = 1_000_000_000L,
    /** A picture older than this means the preview is still: no rate. */
    private val staleNs: Long = 500_000_000L,
) {
    private val times = ArrayDeque<Long>()

    fun onFrame(timeNs: Long) {
        times.addLast(timeNs)
        drop(timeNs)
    }

    /** Frames a second at [nowNs], or null when the picture isn't changing. */
    fun fps(nowNs: Long): Int? {
        drop(nowNs)
        if (times.size < 2 || nowNs - times.last() > staleNs) return null
        val spanNs = times.last() - times.first()
        if (spanNs <= 0) return null
        return ((times.size - 1) * 1_000_000_000.0 / spanNs).roundToInt()
    }

    private fun drop(nowNs: Long) {
        while (times.isNotEmpty() && nowNs - times.first() > windowNs) times.removeFirst()
    }
}
