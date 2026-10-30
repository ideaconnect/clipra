package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Stabilization

/** Steadies a video clip (#54) at a strength from 0 to 100; null turns stabilization off. */
data class SetStabilize(val clipId: String, val stabilization: Stabilization?) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        if (clip.type != ClipType.Video) {
            throw EditRejected(EditRejected.Reason.OutOfRange)
        }
        return project.replaceClip(
            clip.copy(
                stabilize = stabilization?.copy(strength = stabilization.strength.coerceIn(0, 100)),
            ),
        )
    }
}
