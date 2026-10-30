package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.maskKeyframed
import tech.idct.clipra.core.model.set

// Effect commands (#25, #29). The Filters board picks the first filter of the stack; the
// effect stack adds, orders, turns off and removes the rest.

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

/**
 * The clip with [type] as its first filter, in that filter's place in the stack (#29); null
 * takes the first filter away. A [type] further down the stack moves up, keeping its settings.
 */
internal fun Clip.withFilter(type: String?, strength: Float): Clip {
    val stack = effects.toMutableList()
    if (type == null) {
        val first = stack.indexOfFirst { Filters.isFilter(it.type) }
        if (first >= 0) stack.removeAt(first)
        return copy(effects = stack)
    }
    require(Filters.isFilter(type)) { "Unknown filter $type" }
    val existing = stack.firstOrNull { it.type == type }
    val first = stack.indexOfFirst { Filters.isFilter(it.type) }
    val filter = Effect(type, strength.coerceIn(0f, 1f), existing?.params ?: emptyMap())
    if (first >= 0 && stack[first].type != type) stack.removeAll { it.type == type }
    val at = stack.indexOfFirst { Filters.isFilter(it.type) }
    if (at < 0) stack.add(0, filter) else stack[at] = filter
    return copy(effects = stack)
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

/** The clip's stack, checked: a picture clip on an unlocked layer. */
private fun Project.stackOf(clipId: String): Clip {
    val (layer, clip) = clipWithLayer(clipId)
    layer.requireUnlocked()
    if (!clip.takesFilters()) throw EditRejected(EditRejected.Reason.OutOfRange)
    return clip
}

private fun Clip.requireEffect(index: Int) {
    if (index !in effects.indices) throw EditRejected(EditRejected.Reason.OutOfRange)
}

/**
 * Adds effect [type] at the top of the clip's stack (#29), at the default strength, so it
 * applies last. A type the stack already has is refused: each effect is there once.
 */
data class AddEffect(val clipId: String, val type: String) : EditCommand {
    override fun apply(project: Project): Project {
        val clip = project.stackOf(clipId)
        if (!Filters.isFilter(type) || clip.effects.any { it.type == type }) {
            throw EditRejected(EditRejected.Reason.OutOfRange)
        }
        return project.replaceClip(
            clip.copy(effects = clip.effects + Effect(type, Filters.DEFAULT_STRENGTH)),
        )
    }
}

/** Moves the effect at [from] to [to] in the clip's stack (#29), changing the order they apply. */
data class MoveEffect(val clipId: String, val from: Int, val to: Int) : EditCommand {
    override fun apply(project: Project): Project {
        val clip = project.stackOf(clipId)
        clip.requireEffect(from)
        clip.requireEffect(to)
        val stack = clip.effects.toMutableList()
        stack.add(to, stack.removeAt(from))
        return project.replaceClip(clip.copy(effects = stack))
    }
}

/** Turns the effect at [index] on or off (#29); off, it stays in the stack and draws nothing. */
data class SetEffectEnabled(val clipId: String, val index: Int, val enabled: Boolean) :
    EditCommand {
    override fun apply(project: Project): Project {
        val clip = project.stackOf(clipId)
        clip.requireEffect(index)
        val stack = clip.effects.toMutableList()
        stack[index] = stack[index].copy(enabled = enabled)
        return project.replaceClip(clip.copy(effects = stack))
    }
}

/** Sets the strength (0 to 1) of the effect at [index] (#29). */
data class SetEffectStrength(val clipId: String, val index: Int, val strength: Float) :
    EditCommand {
    override fun apply(project: Project): Project {
        val clip = project.stackOf(clipId)
        clip.requireEffect(index)
        val stack = clip.effects.toMutableList()
        stack[index] = stack[index].copy(strength = strength.coerceIn(0f, 1f))
        return project.replaceClip(clip.copy(effects = stack))
    }
}

/** Removes the effect at [index] from the clip's stack (#29). */
data class RemoveEffect(val clipId: String, val index: Int) : EditCommand {
    override fun apply(project: Project): Project {
        val clip = project.stackOf(clipId)
        clip.requireEffect(index)
        return project.replaceClip(
            clip.copy(effects = clip.effects.filterIndexed { i, _ -> i != index }),
        )
    }
}

/**
 * Paste effects (#29): gives the clip a copy of another clip's stack, in its order, replacing
 * its own. Effects Clipra doesn't know are left out.
 */
data class PasteEffects(val clipId: String, val effects: List<Effect>) : EditCommand {
    override fun apply(project: Project): Project {
        val clip = project.stackOf(clipId)
        val known = effects.filter { Filters.isFilter(it.type) }.distinctBy { it.type }
        return project.replaceClip(clip.copy(effects = known))
    }
}
