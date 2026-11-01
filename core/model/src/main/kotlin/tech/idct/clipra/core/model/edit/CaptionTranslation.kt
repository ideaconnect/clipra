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

/**
 * Captions go to the translator as whole sentences (#47). A caption often ends mid-sentence, and
 * a fragment translated alone reads wrong: "chociaż będziemy rozmawiać o" came back as
 * "Although we will talk about", capital and all. So consecutive captions are joined up to a
 * sentence's end ([group]), and each translated sentence is shared back over its captions by
 * their length ([split]).
 */
object CaptionSentences {
    /** At most this many captions go into one piece, so a long run without full stops stays short. */
    const val MAX_CAPTIONS = 6

    /** Runs of consecutive caption indices, each ending where a sentence ends. */
    fun group(texts: List<String>): List<IntRange> {
        val groups = mutableListOf<IntRange>()
        var start = 0
        texts.forEachIndexed { i, text ->
            val ends = text.trimEnd().trimEnd('"', '\'', ')', '”', '»').lastOrNull() in ENDS
            if (ends || i - start + 1 >= MAX_CAPTIONS || i == texts.lastIndex) {
                groups += start..i
                start = i + 1
            }
        }
        return groups
    }

    /**
     * [translation] split at word breaks into one piece per caption, each about as long, as a
     * share of the whole, as the caption it replaces ([lengths], in characters). Every piece
     * gets a word while there are words enough.
     */
    fun split(translation: String, lengths: List<Int>): List<String> {
        val words = translation.split(' ').filter { it.isNotBlank() }
        if (lengths.size <= 1) return listOf(words.joinToString(" "))
        if (words.size <= lengths.size) return lengths.indices.map { words.getOrElse(it) { "" } }
        val total = lengths.sum().coerceAtLeast(1)
        val chars = words.sumOf { it.length + 1 }
        val pieces = mutableListOf<String>()
        var next = 0
        var shared = 0
        for (i in lengths.indices) {
            val left = lengths.size - i - 1
            val last = if (left == 0) {
                words.size
            } else {
                shared += lengths[i]
                val goal = chars.toDouble() * shared / total
                var end = next
                var used = words.take(next).sumOf { it.length + 1 }
                // Take each word whose middle falls within the piece's share, leaving one for
                // each piece still to come.
                while (end < words.size - left &&
                    (end == next || used + (words[end].length + 1) / 2.0 <= goal)
                ) {
                    used += words[end].length + 1
                    end++
                }
                end
            }
            pieces += words.subList(next, last).joinToString(" ")
            next = last
        }
        return pieces
    }

    private val ENDS = setOf('.', '?', '!', '…')
}
