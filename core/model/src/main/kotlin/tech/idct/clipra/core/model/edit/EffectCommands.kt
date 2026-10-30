package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.maskKeyframed
import tech.idct.clipra.core.model.set

// Effect commands (#25). A clip has at most one filter; picking another replaces it.

/**
 * Gives a clip the filter [type] at [strength] (0 to 1), or removes its filter when [type] is
 * null ("None"). Picking the filter it already has keeps that filter's parameters.
 */
data class SetFilter(val clipId: String, val type: String?, val strength: Float) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        return project.replaceClip(clip.withFilter(type, strength))
    }
}

/**
 * Gives every video and photo clip the filter [type] at [strength] ("Apply to all"). Clips on
 * locked layers keep theirs.
 */
data class ApplyFilterToAll(val type: String?, val strength: Float) : EditCommand {
    override fun apply(project: Project): Project = project.copy(
        layers = project.layers.map { layer ->
            if (layer.locked) {
                layer
            } else {
                layer.copy(
                    clips = layer.clips.map { clip ->
                        if (clip.takesFilters()) clip.withFilter(type, strength) else clip
                    },
                )
            }
        },
    )
}

/** Clips with a picture of their own: videos and photos. */
fun Clip.takesFilters(): Boolean = type == ClipType.Video || type == ClipType.Photo

private fun Clip.withFilter(type: String?, strength: Float): Clip {
    val current = effects.firstOrNull { Filters.isFilter(it.type) }
    val others = effects.filterNot { Filters.isFilter(it.type) }
    if (type == null) return copy(effects = others)
    require(Filters.isFilter(type)) { "Unknown filter $type" }
    val params = if (current?.type == type) current.params else emptyMap()
    val filter = Effect(type, strength.coerceIn(0f, 1f), params)
    return copy(effects = listOf(filter) + others)
}

/**
 * Sets a clip's mask (#27). A mask limits an effect, so when the clip has none of type
 * [addIfMissing] yet, that filter is added at the default strength in the same step.
 */
data class SetMask(
    val clipId: String,
    val mask: Mask?,
    val addIfMissing: String? = null,
    /** On a mask with keyframes, the timeline time whose keyframe this sets (#21). */
    val atUs: Long? = null,
) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        val needed = addIfMissing?.takeIf { type -> clip.effects.none { it.type == type } }
        val withEffect = if (needed != null) {
            clip.withFilter(needed, Filters.DEFAULT_STRENGTH)
        } else {
            clip
        }
        val keyed = clip.maskKeyframed
        // Removing the mask removes its keyframes; an animated mask keeps animating.
        val updated = when {
            mask == null -> withEffect.copy(mask = null, maskKeyframes = emptyList())

            atUs == null || keyed == null || keyed.keyframes.isEmpty() ->
                withEffect.copy(mask = mask)

            else -> {
                val set = keyed.set(atUs - clip.startUs, mask)
                withEffect.copy(mask = set.value, maskKeyframes = set.keyframes)
            }
        }
        return project.replaceClip(updated)
    }
}

/** Sets parameter [key] of the clip's effect [type], such as Mosaic's block size. */
data class SetEffectParam(val clipId: String, val type: String, val key: String, val value: Float) :
    EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        if (clip.effects.none {
                it.type == type
            }
        ) {
            throw EditRejected(EditRejected.Reason.OutOfRange)
        }
        val effects = clip.effects.map {
            if (it.type == type) it.copy(params = it.params + (key to value)) else it
        }
        return project.replaceClip(clip.copy(effects = effects))
    }
}
