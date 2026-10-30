package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.CaptionPosition
import tech.idct.clipra.core.model.CaptionSegments
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.CaptionText
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.LinkGroup
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Srt
import tech.idct.clipra.core.model.Word
import tech.idct.clipra.core.model.WordKind
import tech.idct.clipra.core.model.timelineAt
import kotlin.math.roundToLong

/**
 * Turns the transcripts of [sourceClipIds] into caption clips (#45), one undo step:
 * - words go from the source's media time to the timeline, leaving out what the source clip
 *   doesn't play, and fillers when the project's caption options say so;
 * - they are split into captions of about two lines;
 * - the captions go on a new layer at the top (or the top layer when it already holds only
 *   captions and the options say not to add one), each linked to its source clip so it moves,
 *   cuts and goes with it.
 *
 * Captions made earlier from the same sources are replaced.
 */
class CreateCaptions(
    private val sourceClipIds: List<String>,
    private val style: CaptionStyle = CaptionStyle.BoldPop,
    private val newId: () -> String = ::randomId,
) : EditCommand {
    override fun apply(project: Project): Project {
        val options = project.captionOptions
        val size = project.format.outputSize
        val maxChars = CaptionSegments.maxChars(size.width, size.height)
        val old = sourceClipIds.flatMap { id ->
            project.linkedPartners(id).filter {
                project.findClip(it)?.second?.type ==
                    ClipType.Caption
            }
        }.toSet()
        var edited = if (old.isEmpty()) project else project.removeClips(old)
        val captions = mutableListOf<Pair<String, List<Clip>>>()
        for (sourceId in sourceClipIds) {
            val source = edited.findClip(sourceId)?.second ?: continue
            val transcript = edited.transcripts.firstOrNull { it.clipId == sourceId } ?: continue
            val words = transcript.words
                .filter { !(options.removeFillers && it.kind == WordKind.Filler) }
                .mapNotNull { timelineWord(source, it) }
            val clips = CaptionSegments.split(words, maxChars).map { segment ->
                captionClip(segment, options.highlightWords)
            }
            captions += sourceId to clips.fitted()
        }
        val all = captions.flatMap { it.second }.sortedBy { it.startUs }
        if (all.isEmpty()) return edited
        val top = edited.layers.last()
        val reuse = !options.newLayer && top.clips.all { it.type == ClipType.Caption } &&
            !top.locked && all.none { c -> top.clips.any { it.overlaps(c) } }
        edited = if (reuse) {
            edited.updateLayer(top.id) { layer ->
                layer.copy(clips = (layer.clips + all).sortedBy { it.startUs })
            }
        } else {
            edited.copy(layers = edited.layers + Layer(newId(), clips = all))
        }
        val links = captions.filter { it.second.isNotEmpty() }.map { (sourceId, clips) ->
            LinkGroup(newId(), listOf(sourceId) + clips.map { it.id })
        }
        return edited.copy(links = edited.links + links)
    }

    /** [word] on the timeline, or null when [source] doesn't play it. */
    private fun timelineWord(source: Clip, word: Word): Word? {
        // At the clip's speed (#22); a reversed clip plays the word end first.
        val a = source.timelineAt(word.startUs) ?: return null
        val b = source.timelineAt(word.endUs) ?: return null
        val start = minOf(a, b)
        val end = maxOf(a, b)
        if (end <= source.startUs || start >= source.endUs) return null
        return word.copy(
            startUs = start.coerceAtLeast(source.startUs),
            endUs = end.coerceAtMost(source.endUs),
        )
    }

    private fun captionClip(segment: List<Word>, highlight: Boolean): Clip {
        val start = segment.first().startUs
        val end = maxOf(segment.last().endUs, start + MIN_CAPTION_US)
        return Clip(
            id = newId(),
            type = ClipType.Caption,
            startUs = start,
            durationUs = end - start,
            caption = CaptionSpec(
                words = segment.map {
                    it.copy(startUs = it.startUs - start, endUs = it.endUs - start)
                },
                style = style,
                highlight = highlight,
            ),
        )
    }

    /** Captions of one source end where the next starts, and close short gaps between them. */
    private fun List<Clip>.fitted(): List<Clip> = mapIndexed { i, clip ->
        val next = getOrNull(i + 1) ?: return@mapIndexed clip
        val gap = next.startUs - clip.endUs
        when {
            gap < 0 -> clip.copy(durationUs = next.startUs - clip.startUs)
            gap < BRIDGE_US -> clip.copy(durationUs = next.startUs - clip.startUs)
            else -> clip
        }
    }

    private companion object {
        const val MIN_CAPTION_US = 300_000L
        const val BRIDGE_US = 400_000L
    }
}

/**
 * Restyles every caption clip (#45, CaptionsEdit): any of [style], [position], [scale]
 * (0.5 to 2), [highlight] and [maxTwoLines] that is set. One undo step.
 */
data class SetCaptionLook(
    val style: CaptionStyle? = null,
    val position: CaptionPosition? = null,
    val scale: Float? = null,
    val highlight: Boolean? = null,
    val maxTwoLines: Boolean? = null,
) : EditCommand {
    override fun apply(project: Project): Project = project.copy(
        layers = project.layers.map { layer ->
            if (layer.locked) return@map layer
            layer.copy(
                clips = layer.clips.map { clip ->
                    val caption = clip.caption ?: return@map clip
                    clip.copy(
                        caption = caption.copy(
                            style = style ?: caption.style,
                            position = position ?: caption.position,
                            scale = (scale ?: caption.scale).coerceIn(MIN_SCALE, MAX_SCALE),
                            highlight = highlight ?: caption.highlight,
                            maxTwoLines = maxTwoLines ?: caption.maxTwoLines,
                        ),
                    )
                },
            )
        },
    )

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2f
    }
}

/** Retypes the word at [index] of caption [clipId] (#46); a caption left with no words goes. */
data class EditCaptionWord(val clipId: String, val index: Int, val text: String) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        val caption = clip.caption ?: throw EditRejected(EditRejected.Reason.OutOfRange)
        if (index !in caption.words.indices) throw EditRejected(EditRejected.Reason.OutOfRange)
        val words = CaptionText.retype(caption.words, index, text)
        if (words.isEmpty()) return project.removeClips(setOf(clipId))
        return project.replaceClip(clip.copy(caption = caption.copy(words = words)))
    }
}

/**
 * Splits caption [clipId] where the word at [index] starts (#46): the second caption starts
 * with that word, and stays linked to the same source.
 */
class SplitCaption(
    private val clipId: String,
    private val index: Int,
    private val newId: () -> String = ::randomId,
) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        val caption = clip.caption ?: throw EditRejected(EditRejected.Reason.OutOfRange)
        val words = caption.words
        if (index !in 1 until words.size) throw EditRejected(EditRejected.Reason.OutOfRange)
        val cut = words[index].startUs
        if (cut < MIN_CLIP_US || clip.durationUs - cut < MIN_CLIP_US) {
            throw EditRejected(EditRejected.Reason.OutOfRange)
        }
        val left = clip.copy(
            durationUs = cut,
            caption = caption.copy(
                words = words.take(index).map { it.copy(endUs = minOf(it.endUs, cut)) },
            ),
        )
        val right = clip.copy(
            id = newId(),
            startUs = clip.startUs + cut,
            durationUs = clip.durationUs - cut,
            caption = caption.copy(
                words = words.drop(index).map {
                    it.copy(startUs = it.startUs - cut, endUs = it.endUs - cut)
                },
            ),
        )
        val edited = project.replaceClip(left).updateLayer(layer.id) { it.withClip(right) }
        return edited.copy(
            links = edited.links.map { group ->
                if (clipId in group.clipIds) {
                    group.copy(clipIds = group.clipIds + right.id)
                } else {
                    group
                }
            },
        )
    }
}

/** Joins caption [clipId] with the next caption on its layer (#46), keeping every word's time. */
data class MergeCaptions(val clipId: String) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        val caption = clip.caption ?: throw EditRejected(EditRejected.Reason.OutOfRange)
        val next = layer.clips.filter { it.startUs >= clip.endUs }.minByOrNull { it.startUs }
        val nextCaption = next?.caption ?: throw EditRejected(EditRejected.Reason.OutOfRange)
        val offset = next.startUs - clip.startUs
        val merged = clip.copy(
            durationUs = next.endUs - clip.startUs,
            caption = caption.copy(
                words = caption.words + nextCaption.words.map {
                    it.copy(startUs = it.startUs + offset, endUs = it.endUs + offset)
                },
            ),
        )
        return project.removeClips(setOf(next.id)).replaceClip(merged)
    }
}

/**
 * Replaces [find] with [replacement] in every caption (#46), ignoring case; a match may run
 * over several words. Captions left with no words go. One undo step.
 */
data class ReplaceInCaptions(val find: String, val replacement: String) : EditCommand {
    override fun apply(project: Project): Project {
        var edited = project
        val emptied = mutableSetOf<String>()
        for (layer in project.layers) {
            for (clip in layer.clips) {
                val caption = clip.caption ?: continue
                val words = CaptionText.replace(caption.words, find, replacement) ?: continue
                layer.requireUnlocked()
                if (words.isEmpty()) {
                    emptied += clip.id
                } else {
                    edited = edited.replaceClip(clip.copy(caption = caption.copy(words = words)))
                }
            }
        }
        return if (emptied.isEmpty()) edited else edited.removeClips(emptied)
    }
}

/**
 * Puts the captions of an .srt file in place of the project's captions (#46), one undo step:
 * on the layer that held them when it is free, or on a new layer at the top. They take the look
 * the captions had, and each cue's words share its time by length. Overlapping cues end where
 * the next begins.
 */
class ImportCaptions(
    private val cues: List<Srt.Cue>,
    private val newId: () -> String = ::randomId,
) : EditCommand {
    override fun apply(project: Project): Project {
        val existing = project.layers.flatMap { layer ->
            layer.clips.filter { it.type == ClipType.Caption }.map { layer to it }
        }
        val look = existing.firstNotNullOfOrNull { it.second.caption } ?: CaptionSpec(emptyList())
        val sorted = cues.sortedBy { it.startUs }
        val clips = sorted.mapIndexedNotNull { i, cue ->
            val end = minOf(cue.endUs, sorted.getOrNull(i + 1)?.startUs ?: Long.MAX_VALUE)
            val duration = end - cue.startUs
            if (cue.startUs < 0 || duration < MIN_CLIP_US) return@mapIndexedNotNull null
            val words = CaptionText.words(cue.text, 0, duration)
            if (words.isEmpty()) return@mapIndexedNotNull null
            Clip(
                id = newId(),
                type = ClipType.Caption,
                startUs = cue.startUs,
                durationUs = duration,
                caption = look.copy(words = words),
            )
        }
        if (clips.isEmpty()) throw EditRejected(EditRejected.Reason.OutOfRange)
        val edited = project.removeClips(existing.map { it.second.id }.toSet())
        val homeId = existing.firstOrNull()?.first?.id
        val home = edited.layers.find { it.id == homeId }
        val fits = home != null && !home.locked &&
            clips.none { c -> home.clips.any { it.overlaps(c) } }
        return if (fits) {
            edited.updateLayer(home!!.id) { layer ->
                layer.copy(clips = (layer.clips + clips).sortedBy { it.startUs })
            }
        } else {
            edited.copy(layers = edited.layers + Layer(newId(), clips = clips))
        }
    }
}
