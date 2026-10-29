package tech.idct.clipra.core.model

import kotlin.math.pow

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

    /** Progress 0 to 1 through a transition, shaped by [easing]. */
    fun ease(easing: Easing, linear: Float): Float {
        val p = linear.coerceIn(0f, 1f)
        return when (easing) {
            Easing.Linear -> p
            Easing.EaseInOut -> if (p < 0.5f) 4 * p * p * p else 1 - (-2 * p + 2).pow(3) / 2
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
