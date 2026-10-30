package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Cutout
import tech.idct.clipra.core.model.Project

/**
 * Cuts the people out of a video or photo clip (#50), or keys a colour out (#51); null takes the
 * cutout away.
 */
data class SetCutout(val clipId: String, val cutout: Cutout?) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        if (!clip.takesFilters()) {
            throw EditRejected(EditRejected.Reason.OutOfRange)
        }
        return project.replaceClip(
            clip.copy(
                cutout = cutout?.copy(
                    edgeRefine = cutout.edgeRefine.coerceIn(0f, 1f),
                    hairDetail = cutout.hairDetail.coerceIn(0f, 1f),
                    key = cutout.key?.let {
                        it.copy(
                            similarity = it.similarity.coerceIn(0f, 1f),
                            smoothness = it.smoothness.coerceIn(0f, 1f),
                            spill = it.spill.coerceIn(0f, 1f),
                        )
                    },
                ),
            ),
        )
    }
}
