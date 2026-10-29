package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec

// Text commands (#31). A text clip with a background colour is a text card, a full-frame card;
// one without is text over the layers below.

/**
 * Adds text clip [clip] on the first unlocked layer above L1 with room at its time, or on a
 * new layer on top, so it shows over the video.
 */
data class AddText(val clip: Clip, private val newLayerId: () -> String = ::randomId) :
    EditCommand {
    override fun apply(project: Project): Project {
        require(clip.type == ClipType.Text || clip.type == ClipType.TextCard)
        val free = project.layers.drop(1).firstOrNull { layer ->
            !layer.locked && layer.clips.none { it.overlaps(clip) }
        }
        val target = free?.let { LayerTarget.Existing(it.id) } ?: LayerTarget.NewOnTop(newLayerId())
        return AddClip(clip, target).apply(project)
    }
}

/** Changes a text clip's words and style; a background colour makes it a card. */
data class SetText(val clipId: String, val spec: TextSpec) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        if (clip.type != ClipType.Text && clip.type != ClipType.TextCard) {
            throw EditRejected(EditRejected.Reason.OutOfRange)
        }
        val type = if (spec.background != null) ClipType.TextCard else ClipType.Text
        return project.replaceClip(clip.copy(type = type, text = spec))
    }
}
