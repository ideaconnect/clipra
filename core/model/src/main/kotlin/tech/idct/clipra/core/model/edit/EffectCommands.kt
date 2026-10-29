package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Project

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
