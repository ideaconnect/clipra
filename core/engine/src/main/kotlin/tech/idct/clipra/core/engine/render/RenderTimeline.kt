package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.BlendMode
import tech.idct.clipra.core.model.CellRect
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.Transform

/**
 * What the layer compositor draws: the output format and the layers, L1 (bottom) first. Each
 * layer holds clips on the project timeline; times are microseconds. A time without a clip on a
 * layer is a gap, and the layers below show through it.
 */
data class RenderTimeline(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val durationUs: Long,
    val background: Int,
    val layers: List<RenderLayer>,
)

/**
 * A hidden layer draws nothing; a muted layer adds nothing to the sound. [transitions] join
 * neighbouring clips across their cuts.
 */
data class RenderLayer(
    val clips: List<RenderClip>,
    val hidden: Boolean = false,
    val muted: Boolean = false,
    val transitions: List<RenderTransition> = emptyList(),
)

/**
 * A transition from clip [fromClipId] to [toClipId] over [startUs] to [startUs] +
 * [durationUs]: the outgoing clip plays on past its end and the incoming one starts early.
 * [region] (fractions of the frame, from the top-left) keeps it to part of the frame; null is
 * the whole frame.
 */
data class RenderTransition(
    val fromClipId: String,
    val toClipId: String,
    val startUs: Long,
    val durationUs: Long,
    val type: String,
    val easing: Easing = Easing.EaseInOut,
    val region: Region? = null,
) {
    data class Region(val left: Float, val top: Float, val width: Float, val height: Float)

    fun isActiveAt(timeUs: Long): Boolean = timeUs in startUs until startUs + durationUs
}

/**
 * One clip of a layer. [transform] places it in the frame (see `ClipGeometry`); [fadeInUs]
 * dissolves the clip in over the clip it overlaps on the same layer (a transition across a cut).
 */
data class RenderClip(
    val id: String,
    val startUs: Long,
    val durationUs: Long,
    val source: RenderSource,
    val transform: Transform = Transform(),
    val blend: BlendMode = BlendMode.Normal,
    val opacity: Float = 1f,
    val fadeInUs: Long = 0,
    /** Source time per timeline time: 1 plays normally, 0 holds the first frame (freeze). */
    val rate: Double = 1.0,
    /** Filters, in order (#25); effects the compositor doesn't draw yet are skipped. */
    val effects: List<Effect> = emptyList(),
    /** Limits one of the effects to a region of the picture (#27). */
    val mask: Mask? = null,
    /** The clip's volume and fades (#36). */
    val audio: AudioSettings = AudioSettings(),
    /**
     * The cleaned voice (#39) to play instead of the source's own sound: mono, 48 kHz and
     * lined up with the source from its start.
     */
    val voicePath: String? = null,
    /** The mosaic cell (#17) the clip is laid out in and cut to; null for the whole frame. */
    val cell: CellRect? = null,
) {
    val endUs: Long get() = startUs + durationUs

    fun isActiveAt(timeUs: Long): Boolean = timeUs in startUs until endUs

    /** Opacity at [timeUs], including the dissolve in, starting from [base]. */
    fun opacityAt(timeUs: Long, base: Float = opacity): Float {
        if (fadeInUs <= 0) return base
        val progress = ((timeUs - startUs).toFloat() / fadeInUs).coerceIn(0f, 1f)
        return base * progress
    }
}

sealed interface RenderSource {
    /**
     * A video file or content URI; [inPointUs] is where the clip starts in the file, and
     * [durationUs] how long the file is (times past it hold the last frame).
     */
    data class Video(
        val path: String,
        val inPointUs: Long = 0,
        val durationUs: Long = Long.MAX_VALUE,
        /** Whether the file has a sound track; silent videos add nothing to the mix. */
        val hasAudio: Boolean = true,
    ) : RenderSource

    /** A still picture: a photo, or a text card rendered to a bitmap. */
    data class Image(val bitmap: Bitmap) : RenderSource

    /**
     * A picture drawn when the compositor first needs it, such as one state of a caption (#45):
     * only what is on screen takes memory.
     */
    class Drawn(val draw: () -> Bitmap) : RenderSource

    /** Sound only (music, voiceover): no picture. */
    data class Audio(val path: String, val inPointUs: Long = 0) : RenderSource
}
