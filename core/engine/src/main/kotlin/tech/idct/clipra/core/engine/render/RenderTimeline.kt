package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import tech.idct.clipra.core.model.Animation
import tech.idct.clipra.core.model.Animations
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.BlendMode
import tech.idct.clipra.core.model.CellRect
import tech.idct.clipra.core.model.ColorGrade
import tech.idct.clipra.core.model.CubeLut
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Keyframe
import tech.idct.clipra.core.model.Keyframed
import tech.idct.clipra.core.model.Keyframes
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.Speed
import tech.idct.clipra.core.model.SpeedCurve
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.at
import kotlin.math.roundToLong

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
    /**
     * How the clip's media plays (#22): its speed or ramp, forwards or reversed, over
     * [sourceSpanUs] of media from the source's in point (by default what its length plays at a
     * constant speed). Rate 0 holds the first frame (freeze).
     */
    val speed: Speed = Speed(),
    val sourceSpanUs: Long = (durationUs * speed.rate).roundToLong(),
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
    /** The colour grade (#28), before the filters, and its LUT read from its file. */
    val color: ColorGrade? = null,
    val lut: CubeLut? = null,
    /**
     * The clip's animation in, out and loop (#33), over [clipDurationUs] from
     * [keyframeOriginUs]: the whole clip's, which pieces of it keep. Text animates in its
     * renderer instead.
     */
    val animation: Animation? = null,
    val clipDurationUs: Long = durationUs,
    /** Voices this clip ducks under (#40). */
    val ducks: List<RenderDuck> = emptyList(),
    /** Keyframes (#21) of the transform, the opacity and the mask, in clip time. */
    val transformKeyframes: List<Keyframe<Transform>> = emptyList(),
    val opacityKeyframes: List<Keyframe<Float>> = emptyList(),
    val maskKeyframes: List<Keyframe<Mask>> = emptyList(),
    /** Where clip time starts: the clip's start, kept by the pieces a caption is drawn in. */
    val keyframeOriginUs: Long = startUs,
) {
    val endUs: Long get() = startUs + durationUs

    /** The transform and opacity of its animation (#33) at [timeUs], over [base]. */
    fun motionAt(timeUs: Long, base: Transform): Animations.Motion =
        Animations.motion(animation, base, timeUs - keyframeOriginUs, clipDurationUs)

    /** The transform at [timeUs] (timeline time), its keyframes applied. */
    fun transformAt(timeUs: Long): Transform =
        Keyframed(transform, transformKeyframes).at(timeUs - keyframeOriginUs, Keyframes::transform)

    /** The opacity at [timeUs] from its keyframes, before any dissolve in. */
    fun keyedOpacityAt(timeUs: Long): Float =
        Keyframed(opacity, opacityKeyframes).at(timeUs - keyframeOriginUs, Keyframes::opacity)

    /** The mask at [timeUs], its keyframes applied. */
    fun maskAt(timeUs: Long): Mask? =
        mask?.let { Keyframed(it, maskKeyframes).at(timeUs - keyframeOriginUs, Keyframes::mask) }

    fun isActiveAt(timeUs: Long): Boolean = timeUs in startUs until endUs

    /** The media offset from the source's in point that plays at [timeUs] (timeline time). */
    fun sourceOffsetAt(timeUs: Long): Long =
        SpeedCurve.sourceOffset(speed, sourceSpanUs, timeUs - startUs)

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

/**
 * A voice a clip ducks under (#40): where it speaks, in microseconds from the clip's start on the
 * timeline, and how far the clip goes down then.
 */
data class RenderDuck(val speech: List<LongRange>, val depthDb: Float)
