package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project

/**
 * Adds an adjustment clip (#23): on the top layer when it is unlocked and free for the clip's
 * time, otherwise on a new layer on top, so it grades every layer below it.
 */
data class AddAdjustment(val clip: Clip, val newLayerId: String) : EditCommand {
    override fun apply(project: Project): Project {
        if (clip.type != ClipType.Adjustment) throw EditRejected(EditRejected.Reason.OutOfRange)
        val top = project.layers.lastOrNull()
        val target = if (top != null && !top.locked && top.clips.none { it.overlaps(clip) }) {
            LayerTarget.Existing(top.id)
        } else {
            LayerTarget.NewOnTop(newLayerId)
        }
        return AddClip(clip, target).apply(project)
    }
}
