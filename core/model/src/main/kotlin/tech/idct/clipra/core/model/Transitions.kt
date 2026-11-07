package tech.idct.clipra.core.model

/**
 * The 24 transitions of spec section 7 (#26), by id: 8 basic, 9 motion and 7 stylized. A
 * transition sits on a cut, centred on it: the outgoing clip plays on past the cut and the
 * incoming one starts early, each for half the duration.
 */
object Transitions {
    val basic = listOf(
        "dissolve",
        "fade-black",
        "fade-white",
        "wipe-left",
        "wipe-down",
        "clock",
        "iris",
        "split",
    )

    val motion = listOf(
        "slide-left",
        "slide-up",
        "push",
        "zoom-in",
        "zoom-out",
        "whip-pan",
        "spin",
        "shake",
        "cube",
    )

    val stylized = listOf("glitch", "flash", "blur", "pixelate", "light-leak", "film-burn", "ink")

    val all = basic + motion + stylized

    const val DEFAULT_DURATION_US = 600_000L
    const val MIN_DURATION_US = 100_000L
    const val MAX_DURATION_US = 2_000_000L

    fun isTransition(type: String): Boolean = type in all

    /**
     * Progress 0 to 1 through a transition, shaped by [easing]. Ease in-out is smoothstep, as
     * gentle as CSS's ease-in-out: a cubic curve barely moves in its first and last thirds, so a
     * 0.6 s dissolve showed as a hard cut with a few blended frames.
     */
    fun ease(easing: Easing, linear: Float): Float {
        val p = linear.coerceIn(0f, 1f)
        return when (easing) {
            Easing.Linear -> p
            Easing.EaseInOut -> p * p * (3 - 2 * p)
            Easing.EaseOut -> 1 - (1 - p) * (1 - p)
        }
    }
}

/** A cut on a layer: [from] ends where [to] starts, with no gap. */
data class Cut(val layerId: String, val from: Clip, val to: Clip) {
    val timeUs: Long get() = from.endUs

    /** The longest transition that fits: half of it in each clip. */
    val maxTransitionUs: Long get() = minOf(from.durationUs, to.durationUs) * 2
}

/** Clips that can take transitions: those with a picture of their own. */
private val Clip.hasPicture: Boolean get() = type != ClipType.Audio && type != ClipType.Caption

/** Every cut between two picture clips, layer by layer, in time order. */
fun Project.cuts(): List<Cut> = layers.flatMap { layer ->
    val clips = layer.clips.filter { it.hasPicture }.sortedBy { it.startUs }
    clips.zipWithNext().filter { (a, b) -> a.endUs == b.startUs }.map { (a, b) ->
        Cut(layer.id, a, b)
    }
}

/** The cut where [clipId] ends, if the next clip on its layer starts right there. */
fun Project.cutAfter(clipId: String): Cut? = cuts().firstOrNull { it.from.id == clipId }

/** The transition on [cut], if it has one. */
fun Project.transitionOn(cut: Cut): Transition? =
    transitions.firstOrNull { it.layerId == cut.layerId && it.cutTimeUs == cut.timeUs }

/**
 * This project, edited from [before], with every transition kept on its cut (#218). A
 * transition follows its two clips when the edit moves their cut (a ripple, a speed change),
 * and stays on the part of the outgoing clip that still ends there when that clip is split. It
 * goes when the two clips no longer meet, so it never comes back on other clips that meet at
 * its old time later. A transition the edit put on a cut stays there. Each one is fitted to
 * its clips, as `SetTransition` fits it.
 */
internal fun Project.keepTransitionsOnTheirCuts(before: Project): Project {
    if (transitions.isEmpty()) return this
    val cutsBefore = before.cuts()
    val cutsNow = cuts()
    val clipsBefore = before.layers.flatMap { layer -> layer.clips.map { it.id } }.toSet()
    val kept = transitions.mapNotNull { transition ->
        val old = before.transitions.firstOrNull { it.id == transition.id }
        val placedByEdit = old == null ||
            old.layerId != transition.layerId ||
            old.cutTimeUs != transition.cutTimeUs
        val cut = if (placedByEdit) {
            cutsNow.at(transition.layerId, transition.cutTimeUs)
        } else {
            // One already off its cut before the edit (saved by an older version) goes too.
            cutsBefore.at(transition.layerId, transition.cutTimeUs)?.let { was ->
                cutsNow.firstOrNull { it.from.id == was.from.id && it.to.id == was.to.id }
                    ?: cutsNow.firstOrNull {
                        it.to.id == was.to.id &&
                            it.from.id !in clipsBefore &&
                            it.from.isPartOf(was.from)
                    }
            }
        }
        cut?.let {
            val fitted = transition.copy(
                layerId = it.layerId,
                cutTimeUs = it.timeUs,
                durationUs = transition.durationUs.coerceAtMost(it.maxTransitionUs),
            )
            fitted to placedByEdit
        }
    }
    // One cut takes one transition, the edit's own first.
    val taken = kept.filter { it.second }.map { it.first.layerId to it.first.cutTimeUs }.toSet()
    val onCuts = kept
        .filter { (transition, placedByEdit) ->
            placedByEdit || (transition.layerId to transition.cutTimeUs) !in taken
        }
        .map { it.first }
        .distinctBy { it.layerId to it.cutTimeUs }
    return if (onCuts == transitions) this else copy(transitions = onCuts)
}

private fun List<Cut>.at(layerId: String, timeUs: Long): Cut? =
    firstOrNull { it.layerId == layerId && it.timeUs == timeUs }

/** Whether this clip, new after an edit, is a part of [clip], as a split's right part is. */
private fun Clip.isPartOf(clip: Clip): Boolean =
    type == clip.type && source?.mediaId == clip.source?.mediaId
