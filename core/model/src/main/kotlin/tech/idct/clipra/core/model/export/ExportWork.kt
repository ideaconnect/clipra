package tech.idct.clipra.core.model.export

import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Size
import kotlin.math.roundToInt

/**
 * How much rendering work each moment of [project] is (#183), so the export's time left can
 * follow work instead of time. Every frame is drawn and encoded (1). Each video on it is decoded
 * as well, from the original: as much more as its picture is bigger than the [output], and
 * faster or slower with its speed. Photos, text and adjustments are drawn from what was decoded
 * once, so they add little. Hidden layers add nothing.
 */
class ExportWork(project: Project, output: Size) {
    private val durationUs = project.durationUs.coerceAtLeast(1)
    private val steps = ((durationUs + STEP_US - 1) / STEP_US).toInt().coerceAtLeast(1)

    /** The work done by the end of each step, the last one 1. */
    private val done = FloatArray(steps)

    init {
        val outputPixels = (output.width.toLong() * output.height).coerceAtLeast(1)
        val layers = project.layers.filterNot { it.hidden }
        var total = 0.0
        for (step in 0 until steps) {
            val timeUs = step * STEP_US + STEP_US / 2
            var work = FRAME
            for (layer in layers) {
                val clip = layer.clips.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs }
                    ?: continue
                work += when (clip.type) {
                    ClipType.Video -> {
                        val asset = clip.source?.let { project.asset(it.mediaId) }
                        val pixels = asset?.let { it.width.toLong() * it.height } ?: 0L
                        val size = if (pixels > 0) pixels.toDouble() / outputPixels else 1.0
                        size.coerceIn(MIN_VIDEO, MAX_VIDEO) * clip.speed.rate.coerceIn(0.25, 4.0)
                    }

                    ClipType.Photo -> STILL

                    ClipType.Adjustment -> ADJUSTMENT

                    ClipType.Text, ClipType.TextCard, ClipType.Caption -> TEXT

                    ClipType.Audio -> 0.0
                }
            }
            total += work
            done[step] = total.toFloat()
        }
        for (step in 0 until steps) done[step] /= total.toFloat()
    }

    /** The share of the work done when the export's [progress] (its share of the time) is in. */
    fun share(progress: Float): Float {
        val at = progress.coerceIn(0f, 1f) * durationUs / STEP_US
        val step = at.toInt().coerceAtMost(steps - 1)
        val before = if (step == 0) 0f else done[step - 1]
        return before + (done[step] - before) * (at - step).coerceIn(0f, 1f)
    }

    private companion object {
        const val STEP_US = 100_000L
        const val FRAME = 1.0
        const val STILL = 0.1
        const val TEXT = 0.05
        const val ADJUSTMENT = 0.3
        const val MIN_VIDEO = 0.25
        const val MAX_VIDEO = 8.0
    }
}

/**
 * The seconds left of an export (#183): the work still to do ([ExportWork]) at the pace of the
 * work done so far. Replayed on an export of the demo project (4K video, photos and overlays, on
 * the emulator) this was within 27% of the truth from 30 s in, 9% at the median; time alone was
 * off by up to 113%. A pace over the last seconds was worse: a decoder starting at a clip change
 * held the frames for ten seconds, and the estimate shot up to hours.
 */
class TimeLeft(private val work: (progress: Float) -> Float) {
    /** The seconds left at [elapsedMs] of running with [progress] done; null until known. */
    fun update(elapsedMs: Long, progress: Float): Int? {
        if (progress < MIN_PROGRESS || elapsedMs <= 0) return null
        val done = work(progress).toDouble()
        if (done <= 0.0) return null
        return (elapsedMs * (1 - done) / done / 1000).roundToInt()
    }

    private companion object {
        const val MIN_PROGRESS = 0.02f
    }
}
