package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextTemplate

/**
 * Gives a text clip [template]'s look (#34) in one step: its font, weight, size, colours,
 * alignment and effect, and for words over a video its place. The clip keeps its own words, and
 * everything stays editable. A text card keeps a card colour; words over a video have none.
 */
data class ApplyTextTemplate(val clipId: String, val template: TextTemplate) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        val spec = clip.text
        if (spec == null || (clip.type != ClipType.Text && clip.type != ClipType.TextCard)) {
            throw EditRejected(EditRejected.Reason.OutOfRange)
        }
        val card = clip.type == ClipType.TextCard
        val look = template.spec.copy(
            text = spec.text,
            background = if (card) template.spec.background ?: spec.background else null,
        )
        // A place only for words over a video, and only when nothing animates where they are.
        val y = template.y?.takeIf { !card && clip.transform.keyframes.isEmpty() }
        val transform = y?.let { clip.transform.copy(value = clip.transform.value.copy(y = it)) }
        return project.replaceClip(clip.copy(text = look, transform = transform ?: clip.transform))
    }
}
