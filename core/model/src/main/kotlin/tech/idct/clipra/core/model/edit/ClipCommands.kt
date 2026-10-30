package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.BlendMode
import tech.idct.clipra.core.model.CaptionOptions
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Speed
import tech.idct.clipra.core.model.Transcript
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.VoiceCleanup
import java.util.UUID
import kotlin.math.roundToLong

// Clip commands (#15). New clips, layers and link groups get ids from [newId], so tests can
// predict them; the editor uses random ids.

/** The shortest a clip can get by trimming or splitting: about one frame at 30 fps. */
const val MIN_CLIP_US: Long = 33_334

/** A random id for clips, layers and link groups. */
fun randomId(): String = UUID.randomUUID().toString()

/** Which edge a trim moves. */
enum class Edge { Start, End }

/** The source position [timeUs] (on the timeline) plays in a clip. */
private fun Clip.sourceOffset(timeUs: Long): Long = ((timeUs - startUs) * speed.rate).roundToLong()

/**
 * Splits a clip at [atUs] into two, and its linked partners with it. The right parts get new ids
 * and a new link group of their own.
 */
class SplitClip(
    private val clipId: String,
    private val atUs: Long,
    private val newId: () -> String = ::randomId,
) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        if (atUs - clip.startUs < MIN_CLIP_US || clip.endUs - atUs < MIN_CLIP_US) {
            throw EditRejected(EditRejected.Reason.OutOfRange)
        }
        val partners = project.linkedPartners(clipId)
            .mapNotNull { project.findClip(it)?.second }
            .filter { atUs - it.startUs >= MIN_CLIP_US && it.endUs - atUs >= MIN_CLIP_US }
        var edited = project
        val rightIds = mutableListOf<String>()
        for (piece in listOf(clip) + partners) {
            val pieceLayer = project.clipWithLayer(piece.id).first.requireUnlocked()
            val (left, right) = piece.splitAt(atUs, newId())
            edited = edited.replaceClip(left).updateLayer(pieceLayer.id) { it.withClip(right) }
            rightIds += right.id
        }
        return if (rightIds.size > 1) edited.addLink(newId(), rightIds) else edited
    }

    private fun Clip.splitAt(atUs: Long, rightId: String): Pair<Clip, Clip> {
        val offset = sourceOffset(atUs)
        val left = copy(
            durationUs = atUs - startUs,
            source = source?.copy(outPointUs = source.inPointUs + offset),
        )
        val right = copy(
            id = rightId,
            startUs = atUs,
            durationUs = endUs - atUs,
            source = source?.copy(inPointUs = source.inPointUs + offset),
        )
        return left to right
    }
}

/**
 * Deletes a clip and its linked partners. With [ripple] (on by default in the editor), the clips
 * after it on its layer close the gap, and their linked partners move with them.
 */
data class DeleteClip(val clipId: String, val ripple: Boolean = true) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        val removed = project.removeClips(project.linkedPartners(clipId) + clipId)
        if (!ripple) return removed
        val later = removed.layer(layer.id).clips.filter {
            it.startUs >= clip.endUs
        }.map { it.id }.toSet()
        val withPartners = later + later.flatMap { removed.linkedPartners(it) }
        return removed.shiftClips(withPartners, -clip.durationUs)
    }
}

/**
 * Duplicates a clip right after itself on its layer, or, when that space is taken, at the same
 * time on a new layer above the top one.
 */
class DuplicateClip(private val clipId: String, private val newId: () -> String = ::randomId) :
    EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        val after = clip.copy(id = newId(), startUs = clip.endUs)
        return try {
            project.updateLayer(layer.id) { it.withClip(after) }
        } catch (_: EditRejected) {
            AddClip(
                after.copy(startUs = clip.startUs),
                LayerTarget.NewOnTop(newId()),
            ).apply(project)
        }
    }
}

/**
 * Moves a clip's start or end edge to [toUs] on the timeline, taking or giving back source media.
 * A video or sound clip can't reach past its media; no clip gets shorter than [MIN_CLIP_US] or
 * overlaps its neighbours.
 */
data class TrimClip(val clipId: String, val edge: Edge, val toUs: Long) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        val start = if (edge == Edge.Start) toUs else clip.startUs
        val end = if (edge == Edge.End) toUs else clip.endUs
        if (start < 0 ||
            end - start < MIN_CLIP_US
        ) {
            throw EditRejected(EditRejected.Reason.OutOfRange)
        }
        val source = clip.source
        val asset = source?.let { project.asset(it.mediaId) }
        val trimmed = if (source == null || asset == null || asset.kind == MediaKind.Photo) {
            // A still has no source length to respect.
            clip.copy(startUs = start, durationUs = end - start)
        } else {
            val inPoint = source.inPointUs + clip.sourceOffset(start)
            val outPoint = source.inPointUs + clip.sourceOffset(end)
            if (inPoint < 0 || outPoint > asset.durationUs) {
                throw EditRejected(EditRejected.Reason.OutOfRange)
            }
            clip.copy(
                startUs = start,
                durationUs = end - start,
                source = source.copy(inPointUs = inPoint, outPointUs = outPoint),
            )
        }
        return project.updateLayer(layer.id) { it.withClip(trimmed) }
    }
}

/**
 * Holds the frame at [atUs] for [durationUs]: splits the clip there and inserts a still of that
 * frame (speed 0), pushing the rest of the layer later.
 */
class FreezeFrame(
    private val clipId: String,
    private val atUs: Long,
    private val durationUs: Long = 2_000_000,
    private val newId: () -> String = ::randomId,
) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        if (clip.type != ClipType.Video || !clip.contains(atUs)) {
            throw EditRejected(EditRejected.Reason.OutOfRange)
        }
        val frame = (clip.source?.inPointUs ?: 0) + clip.sourceOffset(atUs)
        val later = layer.clips.filter { it.startUs > atUs }.map { it.id }.toSet()
        var edited = project.shiftClips(
            later + later.flatMap {
                project.linkedPartners(it)
            },
            durationUs,
        )
        val rightId = newId()
        val splitsHere = atUs - clip.startUs >= MIN_CLIP_US && clip.endUs - atUs >= MIN_CLIP_US
        if (splitsHere) {
            val left = clip.copy(
                durationUs = atUs - clip.startUs,
                source = clip.source?.copy(outPointUs = frame),
            )
            val right = clip.copy(
                id = rightId,
                startUs = atUs + durationUs,
                durationUs = clip.endUs - atUs,
                source = clip.source?.copy(inPointUs = frame),
            )
            edited = edited.replaceClip(left).updateLayer(layer.id) { it.withClip(right) }
        }
        val still = clip.copy(
            id = newId(),
            startUs = atUs,
            durationUs = durationUs,
            source = clip.source?.copy(inPointUs = frame, outPointUs = frame),
            speed = Speed(rate = 0.0),
        )
        return edited.updateLayer(layer.id) { it.withClip(still) }
    }
}

/**
 * Detaches a video clip's sound onto its own audio clip, linked to the video, on the first free
 * layer above it (or a new layer on top). The video clip goes silent.
 */
class ExtractAudio(private val clipId: String, private val newId: () -> String = ::randomId) :
    EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        if (clip.type != ClipType.Video || clip.source == null) {
            throw EditRejected(EditRejected.Reason.OutOfRange)
        }
        val audio = clip.copy(
            id = newId(),
            type = ClipType.Audio,
            effects = emptyList(),
            mask = null,
            color = null,
            stabilize = null,
        )
        val above = project.layers.drop(project.layerIndexOf(layer.id) + 1)
        val free = above.firstOrNull { candidate ->
            !candidate.locked && candidate.clips.none { it.overlaps(audio) }
        }
        val target = free?.let { LayerTarget.Existing(it.id) } ?: LayerTarget.NewOnTop(newId())
        val silent = clip.copy(audio = clip.audio.copy(volume = 0f))
        return AddClip(audio, target).apply(project.replaceClip(silent))
            .addLink(newId(), listOf(clip.id, audio.id))
    }
}

/**
 * Swaps a clip's media for [mediaId], keeping its place and length; a shorter video or sound
 * shortens the clip to fit.
 */
data class ReplaceMedia(val clipId: String, val mediaId: String) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        val asset = project.asset(mediaId) ?: throw EditRejected(EditRejected.Reason.ClipNotFound)
        val type = when (asset.kind) {
            MediaKind.Video -> ClipType.Video
            MediaKind.Photo -> ClipType.Photo
            MediaKind.Audio -> ClipType.Audio
        }
        val length = if (asset.kind ==
            MediaKind.Photo
        ) {
            clip.durationUs
        } else {
            minOf(clip.durationUs, asset.durationUs)
        }
        val replaced = clip.copy(
            type = type,
            durationUs = length,
            source = MediaRef(
                mediaId,
                inPointUs = 0,
                outPointUs = if (asset.kind ==
                    MediaKind.Photo
                ) {
                    0
                } else {
                    length
                },
            ),
            speed = Speed(),
        )
        return project.replaceClip(replaced)
    }
}

/** Sets where a clip sits in the frame (#19); keyframes come with #21. */
data class SetTransform(val clipId: String, val transform: Transform) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        return project.replaceClip(clip.copy(transform = clip.transform.copy(value = transform)))
    }
}

/** Sets how a clip blends with the layers below it and its opacity, 0 to 1 (#20). */
data class SetBlend(val clipId: String, val blend: BlendMode, val opacity: Float) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        return project.replaceClip(
            clip.copy(blend = blend, opacity = clip.opacity.copy(value = opacity.coerceIn(0f, 1f))),
        )
    }
}

/**
 * "Apply to all clips with speech" (#39): gives every video clip with sound on an unlocked layer
 * [cleanup], or turns it off with null; one undo step. Sounds from the music picker are left
 * alone.
 */
data class ApplyVoiceCleanupToAll(val cleanup: VoiceCleanup?) : EditCommand {
    override fun apply(project: Project): Project {
        val fitted = cleanup?.let { it.copy(strength = it.strength.coerceIn(0f, 1f)) }
        return project.copy(
            layers = project.layers.map { layer ->
                if (layer.locked) return@map layer
                layer.copy(
                    clips = layer.clips.map { clip ->
                        val asset = clip.source?.let { project.asset(it.mediaId) }
                        if (clip.type == ClipType.Video && asset?.hasAudio == true) {
                            clip.copy(audio = clip.audio.copy(voiceCleanup = fitted))
                        } else {
                            clip
                        }
                    },
                )
            },
        )
    }
}

/** Sets a clip's volume (0 to 2), fades and other sound settings (#36). */
data class SetAudio(val clipId: String, val audio: AudioSettings) : EditCommand {
    override fun apply(project: Project): Project {
        val (layer, clip) = project.clipWithLayer(clipId)
        layer.requireUnlocked()
        val fitted = audio.copy(
            volume = audio.volume.coerceIn(0f, 2f),
            fadeInUs = audio.fadeInUs.coerceIn(0, clip.durationUs),
            fadeOutUs = audio.fadeOutUs.coerceIn(0, clip.durationUs),
            voiceCleanup = audio.voiceCleanup?.let {
                it.copy(strength = it.strength.coerceIn(0f, 1f))
            },
        )
        return project.replaceClip(clip.copy(audio = fitted))
    }
}

/**
 * Stores Auto captions' result (#44): [transcripts] replace the ones of the same clips, and
 * [options] become the project's caption options. One undo step.
 */
data class SetTranscripts(val transcripts: List<Transcript>, val options: CaptionOptions) :
    EditCommand {
    override fun apply(project: Project): Project {
        val clips = transcripts.map { it.clipId }.toSet()
        return project.copy(
            transcripts = project.transcripts.filter { it.clipId !in clips } + transcripts,
            captionOptions = options,
        )
    }
}
