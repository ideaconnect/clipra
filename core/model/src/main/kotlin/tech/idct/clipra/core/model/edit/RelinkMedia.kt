package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.SpeedCurve

/**
 * Points the project's media [mediaId], whose file went missing, at [asset]: a file picked to
 * stand for it (#216). This works on the media, not on a clip, so one step brings back every
 * clip of it, on every layer.
 *
 * The same content (the same fingerprint, so the same id) changes only the link: every clip
 * comes back with its in and out points, speed and effects, and nothing else changes. Other
 * content takes the missing media's place in every clip that played it: each clip keeps its
 * place, speed and effects, and its in point where [asset] is long enough (see [fit]); the
 * missing media leaves the project.
 */
data class RelinkMedia(val mediaId: String, val asset: MediaAsset) : EditCommand {
    override fun apply(project: Project): Project {
        val missing = project.asset(mediaId) ?: throw EditRejected(EditRejected.Reason.ClipNotFound)
        if (asset.kind != missing.kind) throw EditRejected(EditRejected.Reason.WrongKind)
        if (asset.id == mediaId) {
            val relinked = missing.copy(
                uri = asset.uri,
                // The same content has the same size; a project from before #216 learns it now.
                sizeBytes = missing.sizeBytes.takeIf { it > 0 } ?: asset.sizeBytes,
                takenAtMillis = missing.takenAtMillis.takeIf { it > 0 } ?: asset.takenAtMillis,
            )
            return project.copy(
                media = project.media.map {
                    if (it.id ==
                        mediaId
                    ) {
                        relinked
                    } else {
                        it
                    }
                },
            )
        }
        // The picked file may be in the project already; then its clips share that media.
        val known = project.media.any { it.id == asset.id }
        val media = project.media.mapNotNull { existing ->
            when {
                existing.id != mediaId -> existing
                known -> null
                else -> asset
            }
        }
        val layers = project.layers.map { layer ->
            layer.copy(
                clips = layer.clips.map { clip ->
                    if (clip.source?.mediaId == mediaId) fit(clip, asset) else clip
                },
            )
        }
        return project.copy(media = media, layers = layers)
    }

    companion object {
        /**
         * [clip] playing [asset] instead of its media, in the same place, at the same speed and
         * with the same effects. Where [asset] is long enough for the clip's out point, it plays
         * the same part; where it is long enough for the part's length only, the part moves back
         * just enough to end with [asset]; where it is shorter still, the clip plays all of it
         * and gets shorter. A photo has no part to fit.
         */
        fun fit(clip: Clip, asset: MediaAsset): Clip {
            val source = clip.source ?: return clip
            val same = source.copy(mediaId = asset.id)
            if (asset.kind == MediaKind.Photo) return clip.copy(source = same)
            val length = asset.durationUs
            val span = source.outPointUs - source.inPointUs
            return when {
                source.outPointUs <= length -> clip.copy(source = same)

                span <= length ->
                    clip.copy(source = same.copy(inPointUs = length - span, outPointUs = length))

                // A held frame (speed 0) keeps its length; anything else plays all of [asset].
                clip.speed.ramp == null && clip.speed.rate <= 0 ->
                    clip.copy(source = MediaRef(asset.id, 0, length))

                else -> clip.copy(
                    source = MediaRef(asset.id, 0, length),
                    durationUs = minOf(
                        clip.durationUs,
                        maxOf(MIN_CLIP_US, SpeedCurve.timelineDuration(clip.speed, length)),
                    ),
                )
            }
        }

        /**
         * How many clips of [mediaId] in [project] would play another part than their own if
         * [asset] took its place, and how many clips it has.
         */
        fun refitted(project: Project, mediaId: String, asset: MediaAsset): Pair<Int, Int> {
            val clips = project.layers.flatMap { it.clips }.filter { it.source?.mediaId == mediaId }
            val moved = clips.count { clip ->
                val fitted = fit(clip, asset)
                fitted.source?.inPointUs != clip.source?.inPointUs ||
                    fitted.durationUs != clip.durationUs
            }
            return moved to clips.size
        }
    }
}
