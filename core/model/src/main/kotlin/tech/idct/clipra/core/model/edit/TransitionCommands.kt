package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Cut
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transition
import tech.idct.clipra.core.model.Transitions
import tech.idct.clipra.core.model.cuts

// Transition commands (#26). A transition belongs to a cut: the layer and the time where one
// clip ends and the next begins. Moving either clip away from the cut leaves it unused.

/**
 * Puts transition [type] on the cut at [cutTimeUs] of [layerId], replacing the one there, or
 * removes it when [type] is null ("None"). The duration is kept between 0.1 and 2 s and within
 * the clips (half of it in each).
 */
data class SetTransition(
    val layerId: String,
    val cutTimeUs: Long,
    val type: String?,
    val durationUs: Long = Transitions.DEFAULT_DURATION_US,
    val easing: Easing = Easing.EaseInOut,
    private val newId: () -> String = ::randomId,
) : EditCommand {
    override fun apply(project: Project): Project {
        project.layer(layerId).requireUnlocked()
        val cut = project.cuts().firstOrNull { it.layerId == layerId && it.timeUs == cutTimeUs }
            ?: throw EditRejected(EditRejected.Reason.OutOfRange)
        return project.withTransition(cut, type, durationUs, easing, newId)
    }
}

/** Puts transition [type] on every cut of the unlocked layers, or removes them all (null). */
data class ApplyTransitionToAll(
    val type: String?,
    val durationUs: Long = Transitions.DEFAULT_DURATION_US,
    val easing: Easing = Easing.EaseInOut,
    private val newId: () -> String = ::randomId,
) : EditCommand {
    override fun apply(project: Project): Project {
        val unlocked = project.layers.filterNot { it.locked }.map { it.id }.toSet()
        return project.cuts().filter { it.layerId in unlocked }.fold(project) { edited, cut ->
            edited.withTransition(cut, type, durationUs, easing, newId)
        }
    }
}

private fun Project.withTransition(
    cut: Cut,
    type: String?,
    durationUs: Long,
    easing: Easing,
    newId: () -> String,
): Project {
    val others = transitions.filterNot {
        it.layerId == cut.layerId && it.cutTimeUs == cut.timeUs
    }
    if (type == null) return copy(transitions = others)
    require(Transitions.isTransition(type)) { "Unknown transition $type" }
    val existing = transitions.firstOrNull {
        it.layerId == cut.layerId && it.cutTimeUs == cut.timeUs
    }
    val duration = durationUs
        .coerceIn(Transitions.MIN_DURATION_US, Transitions.MAX_DURATION_US)
        .coerceAtMost(cut.maxTransitionUs)
    val transition = Transition(
        id = existing?.id ?: newId(),
        layerId = cut.layerId,
        cutTimeUs = cut.timeUs,
        type = type,
        durationUs = duration,
        easing = easing,
    )
    return copy(transitions = others + transition)
}
