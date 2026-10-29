package tech.idct.clipra.core.model

import kotlinx.serialization.Serializable
import tech.idct.clipra.core.model.export.ExportSettings

/**
 * A Clipra project: a versioned JSON document (docs/architecture.md, section 3). All times are
 * microseconds on the project timeline. The document never holds paths to app caches: clips
 * refer to [media] by id, and proxies and thumbnails are looked up by that id.
 *
 * [layers] go from the bottom up: `layers[0]` is L1, and higher layers cover lower ones.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val format: Format = Format(),
    val layers: List<Layer> = listOf(Layer(id = "L1")),
    val media: List<MediaAsset> = emptyList(),
    val transitions: List<Transition> = emptyList(),
    val links: List<LinkGroup> = emptyList(),
    val transcripts: List<Transcript> = emptyList(),
    /** The options of the last export, offered again next time. */
    val exportSettings: ExportSettings? = null,
    val createdAtMillis: Long = 0,
    val version: Int = ProjectJson.SCHEMA_VERSION,
) {
    /** Where the last clip ends. */
    val durationUs: Long
        get() = layers.maxOfOrNull { layer -> layer.clips.maxOfOrNull { it.endUs } ?: 0L } ?: 0L

    fun layerIndexOf(layerId: String): Int = layers.indexOfFirst { it.id == layerId }

    fun asset(id: String): MediaAsset? = media.firstOrNull { it.id == id }

    fun findClip(clipId: String): Pair<Layer, Clip>? {
        for (layer in layers) {
            val clip = layer.clips.firstOrNull { it.id == clipId }
            if (clip != null) return layer to clip
        }
        return null
    }
}

/**
 * A generic layer: any content can go on it, and every layer plays sound. Layers only have
 * hide, mute and lock; everything else belongs to clips.
 */
@Serializable
data class Layer(
    val id: String,
    val hidden: Boolean = false,
    val muted: Boolean = false,
    val locked: Boolean = false,
    val clips: List<Clip> = emptyList(),
)

/** An imported photo, video or sound, identified by a hash of its content. */
@Serializable
data class MediaAsset(
    val id: String,
    val uri: String,
    val kind: MediaKind,
    val displayName: String = "",
    val durationUs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0,
    val hasAudio: Boolean = false,
) {
    /** The picture's size as shown, after the video's rotation; zero when unknown. */
    val displaySize: Size
        get() = if (rotation % 180 != 0) Size(height, width) else Size(width, height)
}

@Serializable
enum class MediaKind { Video, Photo, Audio }

/** Clips sharing a link group move and get cut together, such as a caption and its source. */
@Serializable
data class LinkGroup(val id: String, val clipIds: List<String>)

/** A transition on the cut at [cutTimeUs] of a layer, centred on it (see `Transitions`). */
@Serializable
data class Transition(
    val id: String,
    val layerId: String,
    val cutTimeUs: Long,
    val type: String,
    val durationUs: Long = 600_000,
    val easing: Easing = Easing.EaseInOut,
)

@Serializable
enum class Easing { Linear, EaseInOut, EaseOut }

/** The words Whisper heard in a clip, for captions and edit by text. */
@Serializable
data class Transcript(
    val clipId: String,
    val language: String = "",
    val words: List<Word> = emptyList(),
)

@Serializable
data class Word(
    val text: String,
    val startUs: Long,
    val endUs: Long,
    val confidence: Float = 1f,
    val kind: WordKind = WordKind.Word,
)

@Serializable
enum class WordKind { Word, Filler, Pause }
