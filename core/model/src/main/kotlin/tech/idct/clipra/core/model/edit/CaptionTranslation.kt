package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.CaptionText
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Project

// Captions in more languages (#47): a translation is a copy of the captions on its own layer,
// in step with them; the language chips show one language's layers and hide the others'.

/** The captions as heard (not translations), in time order. */
fun Project.originalCaptions(): List<Clip> = layers.flatMap { it.clips }
    .filter { it.type == ClipType.Caption && it.caption?.language.isNullOrEmpty() }
    .sortedBy { it.startUs }

/**
 * The language the captions were heard in: their sources' transcripts' (the most common, when
 * they differ), or null when no transcript says, as for captions imported from an .srt file.
 */
fun Project.spokenCaptionLanguage(): String? {
    val languages = originalCaptions().mapNotNull { caption ->
        val sources = linkedPartners(caption.id)
        transcripts.firstOrNull { it.clipId in sources }?.language?.takeIf { it.isNotEmpty() }
    }
    return languages.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}

/** The translations' languages, in the order they were made; the captions as heard come first. */
fun Project.translatedCaptionLanguages(): List<String> = layers.flatMap { it.clips }
    .mapNotNull { it.caption?.language?.takeIf { language -> language.isNotEmpty() } }
    .distinct()

/**
 * The language shown: "" for the captions as heard, a translation's language, or null when no
 * caption layer is visible.
 */
fun Project.shownCaptionLanguage(): String? = layers.firstOrNull { layer ->
    !layer.hidden && layer.clips.any { it.type == ClipType.Caption }
}?.clips?.firstOrNull { it.type == ClipType.Caption }?.caption?.language

/** The captions in [language] ("" for the captions as heard), in time order. */
fun Project.captionsIn(language: String): List<Clip> = layers.flatMap { it.clips }
    .filter { it.type == ClipType.Caption && it.caption?.language.orEmpty() == language }
    .sortedBy { it.startUs }

/**
 * Adds the captions in [language] (#47), one undo step: a copy of each caption as heard with
 * its translation from [texts] (by caption id), timed like it and its words sharing that time
 * by length, on a new layer at the top, linked to the same source so it moves and gets cut
 * with it. An earlier translation into [language] is replaced, and the new one is shown.
 */
class TranslateCaptions(
    private val language: String,
    private val texts: Map<String, String>,
    private val newId: () -> String = ::randomId,
) : EditCommand {
    override fun apply(project: Project): Project {
        require(language.isNotEmpty()) { "A translation needs its language" }
        val old = project.captionsIn(language).map { it.id }.toSet()
        val oldLayers = project.layers.filter { layer -> layer.clips.any { it.id in old } }
            .map { it.id }.toSet()
        var edited = project.removeClips(old)
        edited = edited.copy(
            layers = edited.layers.filterNot { it.id in oldLayers && it.clips.isEmpty() },
        )
        val copies = edited.originalCaptions().mapNotNull { caption ->
            val spec = caption.caption ?: return@mapNotNull null
            val text = texts[caption.id]?.trim().orEmpty()
            val words = CaptionText.words(text, 0, caption.durationUs)
            if (words.isEmpty()) return@mapNotNull null
            caption.id to caption.copy(
                id = newId(),
                caption = spec.copy(words = words, language = language),
            )
        }
        if (copies.isEmpty()) throw EditRejected(EditRejected.Reason.OutOfRange)
        val copyOf = copies.toMap().mapValues { it.value.id }
        edited = edited.copy(
            layers = edited.layers + Layer(newId(), clips = copies.map { it.second }),
            links = edited.links.map { group ->
                val added = group.clipIds.mapNotNull { copyOf[it] }
                if (added.isEmpty()) group else group.copy(clipIds = group.clipIds + added)
            },
        )
        return ShowCaptionLanguage(language).apply(edited)
    }
}

/**
 * Shows the captions in [language] ("" for the captions as heard) and hides the other
 * languages' caption layers (#47). Layers with anything besides captions stay as they are.
 */
data class ShowCaptionLanguage(val language: String) : EditCommand {
    override fun apply(project: Project): Project = project.copy(
        layers = project.layers.map { layer ->
            val captions = layer.clips.filter { it.type == ClipType.Caption }
            if (captions.isEmpty() || captions.size != layer.clips.size) return@map layer
            layer.copy(hidden = captions.none { it.caption?.language.orEmpty() == language })
        },
    )
}
