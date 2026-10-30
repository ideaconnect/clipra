package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.LinkGroup
import tech.idct.clipra.core.model.Project

// Linked clips (a caption and its source, detached audio and its video) move and get cut
// together. These helpers find a clip's partners and move clips together.

/** The other clips in [clipId]'s link groups. */
fun Project.linkedPartners(clipId: String): Set<String> =
    links.filter { clipId in it.clipIds }.flatMap { it.clipIds }.toSet() - clipId

/**
 * [clip]'s detached sound, or the video a detached sound came from (#41): linked partners of
 * the same media that play in step with it (same start, length, media span and speed). A speed
 * change keeps them in step. Trims don't change what plays when, so a partner trimmed apart (a
 * J or L cut) stays in sync but no longer counts as in step.
 */
internal fun Project.inStep(clip: Clip): List<Clip> = linkedPartners(clip.id)
    .mapNotNull { findClip(it)?.second }
    .filter {
        (it.type == ClipType.Audio || it.type == ClipType.Video) && it.source != null &&
            it.source == clip.source && it.startUs == clip.startUs &&
            it.durationUs == clip.durationUs && it.speed == clip.speed
    }

/** Shifts [clipIds] by [deltaUs] on their own layers, refusing overlaps and locked layers. */
internal fun Project.shiftClips(clipIds: Set<String>, deltaUs: Long): Project {
    if (clipIds.isEmpty() || deltaUs == 0L) return this
    return copy(
        layers = layers.map { layer ->
            if (layer.clips.none { it.id in clipIds }) return@map layer
            layer.requireUnlocked()
            val shifted = layer.clips.map { clip ->
                if (clip.id !in clipIds) return@map clip
                val start = clip.startUs + deltaUs
                if (start < 0) throw EditRejected(EditRejected.Reason.OutOfRange)
                clip.copy(startUs = start)
            }.sortedBy { it.startUs }
            if (shifted.zipWithNext().any { (a, b) -> a.overlaps(b) }) {
                throw EditRejected(EditRejected.Reason.Overlap)
            }
            layer.copy(clips = shifted)
        },
    )
}

/** Removes clips everywhere, and from link groups; groups left with one clip are dropped. */
internal fun Project.removeClips(clipIds: Set<String>): Project = copy(
    layers = layers.map { layer ->
        if (layer.clips.any { it.id in clipIds }) layer.requireUnlocked()
        layer.copy(clips = layer.clips.filter { it.id !in clipIds })
    },
    links = links.map { it.copy(clipIds = it.clipIds - clipIds) }.filter { it.clipIds.size > 1 },
    transcripts = transcripts.filter { it.clipId !in clipIds },
)

internal fun Project.replaceClip(updated: Clip): Project = copy(
    layers = layers.map { layer ->
        layer.copy(clips = layer.clips.map { if (it.id == updated.id) updated else it })
    },
)

internal fun Project.addLink(groupId: String, clipIds: List<String>): Project =
    copy(links = links + LinkGroup(groupId, clipIds))
