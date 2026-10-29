package tech.idct.clipra.core.model

import kotlinx.serialization.Serializable

/**
 * One item on a layer. [startUs] and [durationUs] place it on the project timeline; [source]
 * says which part of a media asset it plays. Everything that changes how a clip looks or sounds
 * lives here, per clip: speed, transform, opacity, blend mode, effects, mask, colour, audio.
 */
@Serializable
data class Clip(
    val id: String,
    val type: ClipType,
    val startUs: Long,
    val durationUs: Long,
    val source: MediaRef? = null,
    val speed: Speed = Speed(),
    val transform: Keyframed<Transform> = Keyframed(Transform()),
    val opacity: Keyframed<Float> = Keyframed(1f),
    val blend: BlendMode = BlendMode.Normal,
    val effects: List<Effect> = emptyList(),
    val mask: Mask? = null,
    val color: ColorGrade? = null,
    val audio: AudioSettings = AudioSettings(),
    val text: TextSpec? = null,
    val animation: Animation? = null,
    val stabilize: Stabilization? = null,
) {
    val endUs: Long get() = startUs + durationUs

    fun contains(timeUs: Long): Boolean = timeUs in startUs until endUs
}

@Serializable
enum class ClipType { Video, Photo, TextCard, Text, Caption, Audio, Adjustment }

/** The part of [mediaId] a clip plays, in the asset's own time. */
@Serializable
data class MediaRef(val mediaId: String, val inPointUs: Long = 0, val outPointUs: Long)

@Serializable
data class Speed(val rate: Double = 1.0, val reverse: Boolean = false, val ramp: String? = null)

/**
 * Where a clip sits: [x] and [y] move its centre in fractions of the frame (0 = centred),
 * [scale] 1 fills or fits per [fit], [rotation] in degrees, crop in fractions of the source.
 */
@Serializable
data class Transform(
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 0f,
    val cropBottom: Float = 0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val fit: Fit = Fit.Fill,
)

@Serializable
enum class Fit { Fill, Fit }

/** A value that may change over the clip: [keyframes] are at times relative to the clip start. */
@Serializable
data class Keyframed<T>(val value: T, val keyframes: List<Keyframe<T>> = emptyList())

@Serializable
data class Keyframe<T>(val timeUs: Long, val value: T, val easing: Easing = Easing.EaseInOut)

/** The 16 blend modes of spec section 4. */
@Serializable
enum class BlendMode {
    Normal,
    Multiply,
    Screen,
    Overlay,
    SoftLight,
    HardLight,
    Darken,
    Lighten,
    Dodge,
    Burn,
    Add,
    Difference,
    Exclusion,
    Hue,
    Color,
    Luminosity,
}

/** A filter or stylized effect by id ("vivid", "mosaic"), with its strength and parameters. */
@Serializable
data class Effect(
    val type: String,
    val strength: Float = 0.8f,
    val params: Map<String, Float> = emptyMap(),
)

/**
 * Limits one of the clip's effects to a region of its picture (#27): inside a rectangle or an
 * oval, or outside it when inverted. It lives in the picture's own space, so it moves with the
 * clip.
 */
@Serializable
data class Mask(
    val shape: MaskShape = MaskShape.None,
    val invert: Boolean = false,
    /** How far the edge fades out, in fractions of the picture's shorter side. */
    val feather: Float = 0f,
    /** Rounding of a rectangle's corners, in fractions of the picture's shorter side. */
    val cornerRadius: Float = 0f,
    val followFace: Boolean = false,
    /** The centre, in fractions of the clip's picture from its top-left corner. */
    val x: Float = 0.5f,
    val y: Float = 0.4f,
    /** The size, in fractions of the picture's width and height. */
    val width: Float = 0.4f,
    val height: Float = 0.3f,
    /** Turned clockwise, in degrees. */
    val rotation: Float = 0f,
    /** The effect type the mask limits; null limits the clip's filter. */
    val effect: String? = null,
)

@Serializable
enum class MaskShape { None, Rectangle, Oval }

/** Colour adjustments, each from -100 to +100, and an optional LUT with its strength. */
@Serializable
data class ColorGrade(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val vignette: Float = 0f,
    val lut: String? = null,
    val lutStrength: Float = 1f,
)

/** Per-clip sound: volume 0 to 2 (200%), fades, and the loudness target in LUFS. */
@Serializable
data class AudioSettings(
    val volume: Float = 1f,
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
    val loudnessTarget: Int? = null,
    val noiseReduction: Float? = null,
    val voiceCleanup: Float? = null,
)

@Serializable
data class TextSpec(
    val text: String,
    val font: String = "Geist",
    val weight: Int = 700,
    val size: Float = 0.06f,
    val color: Long = 0xFFF4F3EF,
    val background: Long? = null,
    val align: TextAlign = TextAlign.Center,
    val effect: String? = null,
)

@Serializable
enum class TextAlign { Start, Center, End }

@Serializable
data class Animation(val enter: String? = null, val exit: String? = null, val loop: String? = null)

/** Video stabilization, strength 0 to 100 (spec section 3). */
@Serializable
data class Stabilization(val strength: Int = 50)
