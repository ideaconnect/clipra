package tech.idct.clipra.core.model.export

import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.Project

/**
 * A clip that an export leaves out because its media can't be read (#212): the file was moved
 * or deleted, or Clipra lost access to it. [layer] counts from 1 (L1 is the bottom layer);
 * [fileName] is the media's name as imported, empty when unknown.
 */
data class LeftOutClip(
    val clipId: String,
    val type: ClipType,
    val layer: Int,
    val startUs: Long,
    val endUs: Long,
    val mediaId: String,
    val fileName: String = "",
)

/**
 * Which clips an export leaves out (#212). The render skips a clip whose media resolves to
 * nothing, so the video just has a hole there: no picture, no sound, and no transition next to
 * it. Before the export starts, the user is told which clips those are.
 */
object LeftOut {
    /**
     * The clips of [project] that would add a picture or a sound to the video but whose media
     * [readable] says can't be read, in time order, then by layer. [readable] is asked once per
     * media, however many clips play it; a clip whose media isn't in the project at all counts
     * too. A clip that adds nothing doesn't count: a photo on a hidden layer, a sound on a muted
     * one, a video on a layer that is both (or hidden, when the video has no sound).
     */
    fun clips(project: Project, readable: (MediaAsset) -> Boolean): List<LeftOutClip> {
        val answers = mutableMapOf<String, Boolean>()
        return project.layers.flatMapIndexed { index, layer ->
            layer.clips.mapNotNull { clip ->
                val source = clip.source ?: return@mapNotNull null
                val asset = project.asset(source.mediaId)
                if (!adds(layer, clip, asset)) return@mapNotNull null
                val ok = asset != null && answers.getOrPut(asset.id) { readable(asset) }
                if (ok) return@mapNotNull null
                LeftOutClip(
                    clipId = clip.id,
                    type = clip.type,
                    layer = index + 1,
                    startUs = clip.startUs,
                    endUs = clip.endUs,
                    mediaId = source.mediaId,
                    fileName = asset?.displayName.orEmpty(),
                )
            }
        }.sortedWith(compareBy({ it.startUs }, { it.layer }))
    }

    /** Whether [clip] adds a picture or a sound to the video, as [layer] is set. */
    private fun adds(layer: Layer, clip: Clip, asset: MediaAsset?): Boolean = when (clip.type) {
        ClipType.Photo -> !layer.hidden
        ClipType.Audio -> !layer.muted
        ClipType.Video -> !layer.hidden || (!layer.muted && asset?.hasAudio != false)
        else -> false
    }
}
