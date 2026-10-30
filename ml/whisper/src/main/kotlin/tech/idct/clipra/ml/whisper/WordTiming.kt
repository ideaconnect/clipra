package tech.idct.clipra.ml.whisper

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Sharpens Whisper's word times at pauses (#44), where captions change and edit by text cuts.
 * Whisper's token timestamps land within a few tenths of a second; the sound itself shows where
 * a pause ends and starts. A word that starts near the end of a pause starts there, and a word
 * that ends near the start of a pause ends there.
 */
object WordTiming {
    /** A run of quiet, from [startUs] to [endUs]. */
    data class Pause(val startUs: Long, val endUs: Long)

    /**
     * The quiet stretches of [samples] at [sampleRate]: 10 ms frames under [aboveFloorDb] over
     * the recording's noise floor, for at least [minimumUs]. A recording whose speech is less
     * than [MIN_RANGE_DB] over its floor (loud noise) has no pauses to trust.
     */
    fun pauses(
        samples: FloatArray,
        sampleRate: Int,
        minimumUs: Long = MIN_PAUSE_US,
        aboveFloorDb: Float = ABOVE_FLOOR_DB,
    ): List<Pause> {
        val hop = sampleRate / FRAMES_PER_SECOND
        val frames = samples.size / hop
        if (frames == 0) return emptyList()
        val levels = FloatArray(frames) { f ->
            var sum = 0.0
            for (i in f * hop until (f + 1) * hop) sum += samples[i] * samples[i]
            (20 * log10(sqrt(sum / hop) + 1e-9)).toFloat()
        }
        // Quiet is relative to the recording: the floor is its quietest tenth.
        val floor = levels.sorted()[frames / 10]
        val speech = levels.sorted()[frames * 9 / 10]
        if (speech - floor < MIN_RANGE_DB) return emptyList()
        val threshold = floor + minOf(aboveFloorDb, (speech - floor) / 2)
        val minimumFrames = (minimumUs * FRAMES_PER_SECOND / 1_000_000).toInt()
        val pauses = mutableListOf<Pause>()
        var start = -1
        for (f in 0..frames) {
            val quiet = f < frames && levels[f] < threshold
            if (quiet && start < 0) start = f
            if (!quiet && start >= 0) {
                if (f - start >= minimumFrames) pauses += Pause(frameUs(start), frameUs(f))
                start = -1
            }
        }
        return pauses
    }

    /** [words] with their edges moved to the [pauses] within [reachUs] of them. */
    fun refine(
        words: List<Whisper.Word>,
        pauses: List<Pause>,
        reachUs: Long = REACH_US,
    ): List<Whisper.Word> {
        if (words.isEmpty() || pauses.isEmpty()) return words
        val starts = words.map { it.startUs }.toLongArray()
        val ends = words.map { it.endUs }.toLongArray()
        for (pause in pauses) {
            // Speech starts again where the pause ends.
            nearest(starts, pause.endUs, reachUs)?.let { i ->
                val floor = if (i > 0) ends[i - 1] else 0
                if (pause.endUs in floor until ends[i]) starts[i] = pause.endUs
            }
            // And stops where it starts.
            nearest(ends, pause.startUs, reachUs)?.let { i ->
                val ceiling = if (i < words.lastIndex) starts[i + 1] else Long.MAX_VALUE
                if (pause.startUs > starts[i] && pause.startUs <= ceiling) ends[i] = pause.startUs
            }
        }
        return words.mapIndexed { i, word -> word.copy(startUs = starts[i], endUs = ends[i]) }
    }

    private fun nearest(times: LongArray, target: Long, reachUs: Long): Int? {
        var best = -1
        var distance = Long.MAX_VALUE
        for (i in times.indices) {
            val d = abs(times[i] - target)
            if (d < distance) {
                best = i
                distance = d
            }
        }
        return best.takeIf { it >= 0 && distance <= reachUs }
    }

    private fun frameUs(frame: Int) = frame * 1_000_000L / FRAMES_PER_SECOND

    private const val FRAMES_PER_SECOND = 100

    /** Pauses as edit by text and captions see them: 150 ms of quiet or more. */
    const val MIN_PAUSE_US = 150_000L

    /** How far from Whisper's time a pause edge may be and still be the word's edge. */
    const val REACH_US = 400_000L

    private const val ABOVE_FLOOR_DB = 15f
    private const val MIN_RANGE_DB = 20f
}
