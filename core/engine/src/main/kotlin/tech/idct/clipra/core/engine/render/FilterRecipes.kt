@file:Suppress("ktlint:standard:argument-list-wrapping")

package tech.idct.clipra.core.engine.render

import tech.idct.clipra.core.model.BlendMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * A colour transform r' = m0 r + m1 g + m2 b + m3 (and so on for g and b), as CSS filter
 * functions define them (Filter Effects Module, "filter functions").
 */
internal class ColorMatrix(private val rows: FloatArray) {
    init {
        require(rows.size == 12)
    }

    /** This matrix, then [next]. */
    fun then(next: ColorMatrix): ColorMatrix {
        val out = FloatArray(12)
        for (row in 0 until 3) {
            for (col in 0 until 4) {
                var sum = if (col == 3) next.rows[row * 4 + 3] else 0f
                for (k in 0 until 3) sum += next.rows[row * 4 + k] * rows[k * 4 + col]
                out[row * 4 + col] = sum
            }
        }
        return ColorMatrix(out)
    }

    fun apply(r: Float, g: Float, b: Float): FloatArray = FloatArray(3) { row ->
        rows[row * 4] * r + rows[row * 4 + 1] * g + rows[row * 4 + 2] * b + rows[row * 4 + 3]
    }

    /** A column-major 4 × 4 for GLSL, applied to vec4(rgb, 1). */
    fun toGl(): FloatArray {
        val gl = FloatArray(16)
        for (row in 0 until 3) {
            for (col in 0 until 4) gl[col * 4 + row] = rows[row * 4 + col]
        }
        gl[15] = 1f
        return gl
    }

    companion object {
        val identity = linear(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        fun chain(vararg steps: ColorMatrix): ColorMatrix =
            steps.fold(identity) { acc, step -> acc.then(step) }

        fun saturate(s: Float) = linear(
            0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s,
            0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s,
            0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s,
        )

        fun grayscale(amount: Float): ColorMatrix {
            val a = 1 - amount
            return linear(
                0.2126f + 0.7874f * a, 0.7152f - 0.7152f * a, 0.0722f - 0.0722f * a,
                0.2126f - 0.2126f * a, 0.7152f + 0.2848f * a, 0.0722f - 0.0722f * a,
                0.2126f - 0.2126f * a, 0.7152f - 0.7152f * a, 0.0722f + 0.9278f * a,
            )
        }

        fun sepia(amount: Float): ColorMatrix {
            val a = 1 - amount
            return linear(
                0.393f + 0.607f * a, 0.769f - 0.769f * a, 0.189f - 0.189f * a,
                0.349f - 0.349f * a, 0.686f + 0.314f * a, 0.168f - 0.168f * a,
                0.272f - 0.272f * a, 0.534f - 0.534f * a, 0.131f + 0.869f * a,
            )
        }

        fun hueRotate(degrees: Float): ColorMatrix {
            val radians = Math.toRadians(degrees.toDouble())
            val c = cos(radians).toFloat()
            val s = sin(radians).toFloat()
            return linear(
                0.213f + c * 0.787f - s * 0.213f,
                0.715f - c * 0.715f - s * 0.715f,
                0.072f - c * 0.072f + s * 0.928f,
                0.213f - c * 0.213f + s * 0.143f,
                0.715f + c * 0.285f + s * 0.140f,
                0.072f - c * 0.072f - s * 0.283f,
                0.213f - c * 0.213f - s * 0.787f,
                0.715f - c * 0.715f + s * 0.715f,
                0.072f + c * 0.928f + s * 0.072f,
            )
        }

        fun brightness(b: Float) = linear(b, 0f, 0f, 0f, b, 0f, 0f, 0f, b)

        fun contrast(c: Float): ColorMatrix {
            val offset = 0.5f - 0.5f * c
            return ColorMatrix(
                floatArrayOf(c, 0f, 0f, offset, 0f, c, 0f, offset, 0f, 0f, c, offset),
            )
        }

        private fun linear(vararg m: Float) = ColorMatrix(
            floatArrayOf(m[0], m[1], m[2], 0f, m[3], m[4], m[5], 0f, m[6], m[7], m[8], 0f),
        )
    }
}

/** How a filter reads its input before the colour pass. */
internal enum class Sampling { Plain, Mosaic, RgbSplit, Glitch, Vhs }

/** A gradient stop: [position] 0 to 1 along the gradient, colour as straight RGBA, 0 to 1. */
internal data class Stop(
    val position: Float,
    val r: Float,
    val g: Float,
    val b: Float,
    val a: Float,
)

/** What a filter lays over the picture, and how it blends. */
internal sealed interface Overlay {
    val blend: BlendMode

    /** CSS linear-gradient at [degrees] (0 up, 90 right, 180 down). */
    data class Linear(val degrees: Float, val stops: List<Stop>, override val blend: BlendMode) :
        Overlay

    /**
     * CSS radial-gradient at ([x], [y]) in fractions of the picture, reaching its farthest
     * corner: an ellipse fitted to the picture, or a circle.
     */
    data class Radial(
        val x: Float,
        val y: Float,
        val ellipse: Boolean,
        val stops: List<Stop>,
        override val blend: BlendMode,
    ) : Overlay

    /** Thin cyan and magenta lines, 16 per picture height. */
    data object GlitchLines : Overlay {
        override val blend = BlendMode.Screen
    }

    /** Dark lines, 80 per picture height. */
    data object Scanlines : Overlay {
        override val blend = BlendMode.Normal
    }

    /** Film grain: noise per pixel that changes every frame. */
    data object Grain : Overlay {
        override val blend = BlendMode.Overlay
    }

    /** A grid of dark dots, 30 across the shorter side. */
    data object Halftone : Overlay {
        override val blend = BlendMode.Multiply
    }
}

/**
 * One filter for the filter shader: its CSS filter functions as one [matrix], how it samples,
 * the [overlay] on top, and for Blur the blur radius ([blur], a fraction of the shorter side at
 * full strength). The recipes follow design/canvas/Filters.dc.html.
 */
internal class FilterRecipe(
    val matrix: ColorMatrix = ColorMatrix.identity,
    val sampling: Sampling = Sampling.Plain,
    val overlay: Overlay? = null,
    val blur: Float = 0f,
)

internal object FilterRecipes {
    private fun rgba(r: Int, g: Int, b: Int, a: Float, at: Float) =
        Stop(at, r / 255f, g / 255f, b / 255f, a)

    private fun clear(at: Float) = Stop(at, 0f, 0f, 0f, 0f)

    private fun vignette(inner: Float, r: Int, g: Int, b: Int, a: Float) = Overlay.Radial(
        0.5f,
        0.5f,
        ellipse = true,
        stops = listOf(clear(inner), rgba(r, g, b, a, 1f)),
        blend = BlendMode.Normal,
    )

    private val recipes: Map<String, FilterRecipe> = with(ColorMatrix) {
        mapOf(
            "vivid" to FilterRecipe(chain(saturate(1.6f), contrast(1.1f))),
            "warm" to FilterRecipe(chain(sepia(0.3f), saturate(1.4f), hueRotate(-12f))),
            "cool" to FilterRecipe(
                chain(sepia(0.35f), hueRotate(170f), saturate(1.2f), brightness(1.05f)),
            ),
            "cinematic" to FilterRecipe(
                chain(contrast(1.2f), saturate(1.3f)),
                overlay = Overlay.Linear(
                    180f,
                    listOf(rgba(0, 150, 160, 0.6f, 0f), rgba(255, 140, 60, 0.4f, 1f)),
                    BlendMode.SoftLight,
                ),
            ),
            "matte" to FilterRecipe(chain(contrast(0.78f), brightness(1.12f), saturate(0.85f))),
            "mono" to FilterRecipe(grayscale(1f)),
            "noir" to FilterRecipe(
                chain(grayscale(1f), contrast(1.7f), brightness(0.9f)),
                overlay = vignette(0.4f, 0, 0, 0, 0.8f),
            ),
            "sepia" to FilterRecipe(sepia(0.9f)),
            "vintage" to FilterRecipe(
                chain(sepia(0.5f), contrast(0.9f), brightness(1.05f), saturate(1.2f)),
                overlay = vignette(0.45f, 40, 20, 0, 0.65f),
            ),
            "film" to FilterRecipe(
                chain(sepia(0.18f), contrast(1.08f), saturate(0.9f)),
                overlay = Overlay.Grain,
            ),
            "night" to FilterRecipe(
                chain(brightness(0.55f), sepia(1f), hueRotate(180f), saturate(1.6f)),
            ),
            "neon" to FilterRecipe(chain(hueRotate(250f), saturate(2f), contrast(1.15f))),
            "pastel" to FilterRecipe(chain(saturate(0.6f), brightness(1.2f), contrast(0.85f))),
            "mosaic" to FilterRecipe(sampling = Sampling.Mosaic),
            "blur" to FilterRecipe(blur = 0.025f),
            "glitch" to FilterRecipe(
                saturate(1.3f),
                sampling = Sampling.Glitch,
                overlay = Overlay.GlitchLines,
            ),
            "vhs" to FilterRecipe(
                chain(saturate(1.4f), contrast(1.1f)),
                sampling = Sampling.Vhs,
                overlay = Overlay.Scanlines,
            ),
            "grain" to FilterRecipe(contrast(1.05f), overlay = Overlay.Grain),
            "vignette" to FilterRecipe(overlay = vignette(0.4f, 0, 0, 0, 0.8f)),
            "rgb-split" to FilterRecipe(
                saturate(1.2f),
                sampling = Sampling.RgbSplit,
                overlay = Overlay.Linear(
                    90f,
                    listOf(
                        rgba(255, 0, 80, 0.45f, 0f),
                        clear(0.35f),
                        clear(0.65f),
                        rgba(0, 190, 255, 0.45f, 1f),
                    ),
                    BlendMode.Screen,
                ),
            ),
            "light-leak" to FilterRecipe(
                brightness(1.05f),
                overlay = Overlay.Radial(
                    0.85f,
                    0.15f,
                    ellipse = false,
                    stops = listOf(
                        rgba(255, 140, 60, 0.95f, 0f),
                        rgba(255, 80, 120, 0.4f, 0.35f),
                        clear(0.65f),
                    ),
                    blend = BlendMode.Screen,
                ),
            ),
            "halftone" to FilterRecipe(
                chain(grayscale(0.3f), contrast(1.2f)),
                overlay = Overlay.Halftone,
            ),
            "duotone" to FilterRecipe(
                chain(grayscale(1f), contrast(1.2f)),
                overlay = Overlay.Linear(
                    135f,
                    listOf(rgba(0x5B, 0x2A, 0x86, 1f, 0f), rgba(0xFF, 0x8A, 0x5B, 1f, 1f)),
                    BlendMode.Color,
                ),
            ),
            "soft-glow" to FilterRecipe(
                chain(brightness(1.12f), saturate(1.15f), contrast(0.95f)),
                overlay = Overlay.Radial(
                    0.65f,
                    0.4f,
                    ellipse = false,
                    stops = listOf(rgba(255, 230, 180, 0.6f, 0f), clear(0.6f)),
                    blend = BlendMode.Screen,
                ),
            ),
        )
    }

    /** The recipe of filter [type], or null for an effect that isn't a filter. */
    fun of(type: String): FilterRecipe? = recipes[type]

    val types: Set<String> get() = recipes.keys
}
