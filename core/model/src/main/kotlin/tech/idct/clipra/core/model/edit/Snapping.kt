package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Project
import kotlin.math.abs

/**
 * Snapping while dragging (on by default): a moved edge lands on the playhead, a cut (any clip's
 * start or end) or a beat when it comes within a threshold of one.
 */
object Snapping {
    /** Where edges can snap to, leaving out the dragged clip and its linked partners. */
    fun targets(
        project: Project,
        playheadUs: Long,
        excludeClipId: String? = null,
        beatsUs: List<Long> = emptyList(),
    ): List<Long> {
        val excluded = excludeClipId?.let { project.linkedPartners(it) + it }.orEmpty()
        val cuts = project.layers.flatMap { layer ->
            layer.clips.filter { it.id !in excluded }.flatMap { listOf(it.startUs, it.endUs) }
        }
        return (listOf(0L, playheadUs) + cuts + beatsUs).distinct().sorted()
    }

    /** The nearest target within [thresholdUs] of [timeUs], or [timeUs] itself. */
    fun snap(timeUs: Long, targets: List<Long>, thresholdUs: Long): Long {
        val nearest = targets.minByOrNull { abs(it - timeUs) } ?: return timeUs
        return if (abs(nearest - timeUs) <= thresholdUs) nearest else timeUs
    }

    /**
     * Snaps a clip being moved: whichever of its start or end is nearer a target decides, and the
     * clip keeps its length.
     */
    fun snapMove(startUs: Long, durationUs: Long, targets: List<Long>, thresholdUs: Long): Long {
        val endUs = startUs + durationUs
        val start = snap(startUs, targets, thresholdUs)
        val end = snap(endUs, targets, thresholdUs)
        val startShift = if (start != startUs) abs(start - startUs) else null
        val endShift = if (end != endUs) abs(end - endUs) else null
        return when {
            startShift == null && endShift == null -> startUs
            endShift == null -> start
            startShift != null && startShift <= endShift -> start
            else -> end - durationUs
        }
    }
}
