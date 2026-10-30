package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.FaceBox
import tech.idct.clipra.core.model.FaceTracking
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.maskAt

/**
 * Follow face (#52): the clip's mask follows the face along [path] (clip time, smoothed by
 * `FaceTracking.track`) as keyframes, which stay editable. It keeps the mask's shape and
 * settings, from the one at [atUs] (timeline time); without a mask it starts an oval over
 * [addIfMissing], adding that effect if needed. A null [path] stops following, leaving the mask
 * where it is at [atUs]. One undo step.
 */
data class FollowFace(
    val clipId: String,
    val path: List<Pair<Long, FaceBox>>?,
    val atUs: Long,
    val addIfMissing: String? = null,
) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        if (!clip.takesFilters()) throw EditRejected(EditRejected.Reason.OutOfRange)
        val current = clip.maskAt(atUs - clip.startUs)
        if (path == null) {
            val still = current?.copy(followFace = false) ?: return project
            return project.replaceClip(clip.copy(mask = still, maskKeyframes = emptyList()))
        }
        if (path.isEmpty()) throw EditRejected(EditRejected.Reason.OutOfRange)
        val needed = addIfMissing?.takeIf { type -> clip.effects.none { it.type == type } }
        val withEffect = if (needed != null) {
            clip.withFilter(needed, Filters.DEFAULT_STRENGTH)
        } else {
            clip
        }
        val base = current ?: Mask(feather = DEFAULT_FEATHER, effect = addIfMissing)
        val keyframes = FaceTracking.keyframes(path, base)
        return project.replaceClip(
            withEffect.copy(mask = keyframes.first().value, maskKeyframes = keyframes),
        )
    }

    private companion object {
        const val DEFAULT_FEATHER = 0.04f
    }
}
