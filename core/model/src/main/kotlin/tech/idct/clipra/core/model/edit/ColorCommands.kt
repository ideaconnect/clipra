package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ColorGrade
import tech.idct.clipra.core.model.Project

/** Grades a picture clip (#28); a neutral grade, or null, takes it away. */
data class SetColor(val clipId: String, val grade: ColorGrade?) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        if (!clip.takesFilters()) throw EditRejected(EditRejected.Reason.OutOfRange)
        return project.replaceClip(clip.copy(color = grade?.takeIf { !it.isNeutral }))
    }
}

/**
 * Paste to other clips (#28): gives every picture clip on an unlocked layer the grade of
 * [fromClipId], in one undo step.
 */
data class PasteColor(val fromClipId: String) : EditCommand {
    override fun apply(project: Project): Project {
        val (_, from) = project.clipWithLayer(fromClipId)
        val grade = from.color
        val pasted = { clip: Clip ->
            if (clip.id != fromClipId && clip.takesFilters()) clip.copy(color = grade) else clip
        }
        return project.copy(
            layers = project.layers.map { layer ->
                if (layer.locked) layer else layer.copy(clips = layer.clips.map(pasted))
            },
        )
    }

    companion object {
        /** The clips [PasteColor] from [fromClipId] would grade. */
        fun targets(project: Project, fromClipId: String): Int = project.layers
            .filter { !it.locked }
            .sumOf { layer -> layer.clips.count { it.id != fromClipId && it.takesFilters() } }
    }
}
