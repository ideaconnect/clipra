package tech.idct.clipra.core.model

import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.roundToInt

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
    /** A caption clip's words and look (#45). */
    val caption: CaptionSpec? = null,
    val animation: Animation? = null,
    val stabilize: Stabilization? = null,
    /**
     * The mosaic cell the clip is drawn in (#17), numbered as `Cells` numbers them; null for the
     * whole frame. It is ignored in a Single layout.
     */
    val cell: Int? = null,
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

/**
 * Per-clip sound: volume 0 to 2 (200%), fades, the loudness target in LUFS and AI voice
 * cleanup.
 */
@Serializable
data class AudioSettings(
    val volume: Float = 1f,
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
    /** Normalize to this many LUFS (#37), or null to leave the level alone. */
    val loudnessTarget: Int? = null,
    /** The clip's own integrated loudness (LUFS) and true peak (dBTP), as measured. */
    val measuredLufs: Float? = null,
    val truePeakDb: Float? = null,
    val noiseReduction: Float? = null,
    /** AI voice cleanup (#39), or null to keep the sound as recorded. */
    val voiceCleanup: VoiceCleanup? = null,
)

/** Which network cleans up a voice (#39): DeepFilterNet 3 (downloaded) or RNNoise (built in). */
@Serializable
enum class VoiceEngine { DeepFilterNet, RNNoise }

/**
 * AI voice cleanup (#39): the [engine] and the [strength], 0 to 1. Full strength removes all
 * the noise the network finds; lower strengths keep some of it (see [noisyShare]).
 */
@Serializable
data class VoiceCleanup(
    val engine: VoiceEngine = VoiceEngine.DeepFilterNet,
    val strength: Float = DEFAULT_STRENGTH,
) {
    /** The strength in whole percent, as the slider shows it and the cache keys it. */
    val percent: Int get() = (strength * 100).roundToInt()

    /**
     * How much of the recorded sound is mixed back in: none at full strength, and an
     * attenuation limit of 40 dB times the strength below it (70% keeps noise 28 dB down).
     */
    val noisyShare: Float
        get() = if (percent >= 100) 0f else 10.0.pow(-2.0 * percent / 100).toFloat()

    companion object {
        const val DEFAULT_STRENGTH = 0.7f
    }
}

/** Normalization (#37): the gain in dB that takes the clip to its target, or 0. */
val AudioSettings.normalizeDb: Float
    get() {
        val target = loudnessTarget ?: return 0f
        val measured = measuredLufs ?: return 0f
        return target - measured
    }

/** The loudness targets of spec section 11, in LUFS. */
object LoudnessTargets {
    const val SOCIAL = -14
    const val PODCAST = -16
    const val BROADCAST = -23

    /** The ceiling for true peaks, in dBTP. */
    const val PEAK_CEILING_DB = -1f
}

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

/** The colours Text offers for cards and letters (Text board), as ARGB. */
object TextColors {
    const val BLACK = 0xFF000000
    const val WHITE = 0xFFFFFFFF
    const val YELLOW = 0xFFFFD23F
    const val RED = 0xFFE5482E
    const val BLUE = 0xFF2F4FE0
    const val GREEN = 0xFF17795A

    val palette = listOf(BLACK, WHITE, YELLOW, RED, BLUE, GREEN)
}

/** The font families text clips can use, all bundled (OFL). */
object TextFonts {
    const val BRICOLAGE = "Bricolage Grotesque"
    const val GEIST = "Geist"
    const val GEIST_MONO = "Geist Mono"

    val all = listOf(BRICOLAGE, GEIST, GEIST_MONO)
}
