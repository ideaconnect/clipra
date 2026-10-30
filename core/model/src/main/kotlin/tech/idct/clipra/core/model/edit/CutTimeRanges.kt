package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Project

/**
 * Edit by text (#48): removes [ranges] of the timeline from every unlocked layer and closes
 * the gaps, so everything after a cut (captions, music, overlays) moves up with it and stays
 * in sync. A clip across a cut is split around it; one partly inside loses that part. Caption
 * words follow their clip, and split parts keep their links and transcripts. One undo step.
 */
class CutTimeRanges(
    private val ranges: List<LongRange>,
    private val newId: () -> String = ::randomId,
) : EditCommand {
    override fun apply(project: Project): Project {
        // From the last cut back, so earlier times stay where they are.
        return ranges.filter { it.last > it.first }.sortedByDescending { it.first }
            .fold(project) { edited, range -> cut(edited, range.first, range.last) }
    }

    private fun cut(project: Project, from: Long, to: Long): Project {
        val length = to - from
        var edited = project
        val gone = mutableSetOf<String>()
        val parts = mutableListOf<Pair<String, String>>() // left id to new right id
        val layers = edited.layers.map { layer ->
            if (layer.locked) return@map layer
            val clips = layer.clips.flatMap { clip ->
                when {
                    clip.endUs <= from -> listOf(clip)

                    clip.startUs >= to -> listOf(clip.copy(startUs = clip.startUs - length))

                    clip.startUs >= from && clip.endUs <= to -> {
                        gone += clip.id
                        emptyList()
                    }

                    clip.startUs < from && clip.endUs > to -> {
                        val right = clip.after(to, newId()).copy(startUs = from)
                        parts += clip.id to right.id
                        listOf(clip.before(from), right)
                    }

                    clip.startUs < from -> listOf(clip.before(from))

                    else -> listOf(clip.after(to, clip.id).copy(startUs = from))
                }
            }
            layer.copy(clips = clips)
        }
        edited = edited.copy(layers = layers)
        // A caption whose words were all cut has nothing left to show.
        gone += layers.flatMap { it.clips }
            .filter { it.caption != null && it.caption.words.isEmpty() }
            .map { it.id }
        if (gone.isNotEmpty()) edited = edited.removeClips(gone)
        // A split part goes with its partners and keeps its transcript (media time).
        for ((left, right) in parts) {
            edited = edited.copy(
                links = edited.links.map { group ->
                    if (left in
                        group.clipIds
                    ) {
                        group.copy(clipIds = group.clipIds + right)
                    } else {
                        group
                    }
                },
                transcripts = edited.transcripts + edited.transcripts
                    .filter { it.clipId == left }
                    .map { it.copy(clipId = right) },
            )
        }
        return edited
    }

    /** The part of this clip before [atUs] on the timeline, at its speed (#22). */
    private fun Clip.before(atUs: Long): Clip {
        val cut = atUs - startUs
        return played(0, cut).copy(
            caption = caption?.let { c ->
                c.copy(
                    words = c.words.filter {
                        it.startUs < cut
                    }.map { it.copy(endUs = minOf(it.endUs, cut)) },
                )
            },
        )
    }

    /** The part of this clip from [atUs] on, as [id]; its start is still to be placed. */
    private fun Clip.after(atUs: Long, id: String): Clip {
        val cut = atUs - startUs
        // Its keyframes (#21) keep their places in the picture's time.
        return played(cut, durationUs).withKeyframesShifted(-cut).copy(
            id = id,
            caption = caption?.let { c ->
                c.copy(
                    words = c.words.filter { it.endUs > cut }.map {
                        it.copy(
                            startUs = (it.startUs - cut).coerceAtLeast(0),
                            endUs = it.endUs - cut,
                        )
                    },
                )
            },
        )
    }
}
