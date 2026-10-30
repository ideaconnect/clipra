package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.Keyframed
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.keyframeAt
import tech.idct.clipra.core.model.maskAt
import tech.idct.clipra.core.model.maskKeyframed
import tech.idct.clipra.core.model.movedKeyframe
import tech.idct.clipra.core.model.opacityAt
import tech.idct.clipra.core.model.shifted
import tech.idct.clipra.core.model.transformAt
import tech.idct.clipra.core.model.withKeyframe
import tech.idct.clipra.core.model.withoutKeyframe

/** What a keyframe holds: the clip's place and opacity (Layers board), or its mask (Masks). */
enum class KeyframeTarget { Transform, Mask }

/**
 * The keyframe button (#21): at [atUs] (timeline time), adds a keyframe holding the clip's values
 * there, or deletes the one already there. [KeyframeTarget.Transform] keys the transform and the
 * opacity together; [KeyframeTarget.Mask] the mask.
 */
data class ToggleKeyframe(val clipId: String, val atUs: Long, val target: KeyframeTarget) :
    EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        val time = atUs - clip.startUs
        if (time !in 0..clip.durationUs) throw EditRejected(EditRejected.Reason.OutOfRange)
        val updated = when (target) {
            KeyframeTarget.Transform -> {
                val there = clip.transform.keyframeAt(time) != null ||
                    clip.opacity.keyframeAt(time) != null
                if (there) {
                    clip.copy(
                        transform = clip.transform.withoutKeyframe(time),
                        opacity = clip.opacity.withoutKeyframe(time),
                    )
                } else {
                    clip.copy(
                        transform = clip.transform.withKeyframe(time, clip.transformAt(time)),
                        opacity = clip.opacity.withKeyframe(time, clip.opacityAt(time)),
                    )
                }
            }

            KeyframeTarget.Mask -> {
                val keyed = clip.maskKeyframed ?: throw EditRejected(EditRejected.Reason.OutOfRange)
                val toggled = if (keyed.keyframeAt(time) != null) {
                    keyed.withoutKeyframe(time)
                } else {
                    keyed.withKeyframe(time, clip.maskAt(time)!!)
                }
                clip.withMask(toggled)
            }
        }
        return project.replaceClip(updated)
    }
}

/** Moves the keyframes at [fromUs] to [toUs] (clip time, kept within the clip). */
data class MoveKeyframe(val clipId: String, val fromUs: Long, val toUs: Long) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        val to = toUs.coerceIn(0, clip.durationUs)
        val mask = clip.maskKeyframed?.movedKeyframe(fromUs, to)
        val moved = clip.copy(
            transform = clip.transform.movedKeyframe(fromUs, to),
            opacity = clip.opacity.movedKeyframe(fromUs, to),
        )
        return project.replaceClip(if (mask == null) moved else moved.withMask(mask))
    }
}

/** How the move from the keyframes at [atUs] (clip time) to the next ones goes. */
data class SetKeyframeEasing(val clipId: String, val atUs: Long, val easing: Easing) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        fun <T> Keyframed<T>.eased() =
            keyframeAt(atUs)?.let { withKeyframe(it.timeUs, it.value, easing) } ?: this
        val mask = clip.maskKeyframed?.eased()
        val eased = clip.copy(transform = clip.transform.eased(), opacity = clip.opacity.eased())
        return project.replaceClip(if (mask == null) eased else eased.withMask(mask))
    }
}

private fun Clip.withMask(keyed: Keyframed<Mask>) =
    copy(mask = keyed.value, maskKeyframes = keyed.keyframes)

/** Every keyframe [byUs] later in clip time: a trim or split keeps them on the timeline. */
internal fun Clip.withKeyframesShifted(byUs: Long): Clip = copy(
    transform = transform.shifted(byUs),
    opacity = opacity.shifted(byUs),
    maskKeyframes = maskKeyframes.map { it.copy(timeUs = it.timeUs + byUs) },
)
