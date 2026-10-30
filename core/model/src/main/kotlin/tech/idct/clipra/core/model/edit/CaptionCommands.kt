package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.CaptionPosition
import tech.idct.clipra.core.model.CaptionSegments
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.LinkGroup
import tech.idct.clipra.core.model.Project
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
