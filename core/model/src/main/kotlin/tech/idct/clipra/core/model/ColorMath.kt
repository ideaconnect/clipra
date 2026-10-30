package tech.idct.clipra.core.model

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What a [ColorGrade] does to one pixel (#28), in the order the grade shader does it: exposure,
 * white balance (temperature and tint), contrast, saturation, the HSL ranges, the LUT at its
 * strength, then the vignette. Colours are 0 to 1 as stored (sRGB). The shader in `core:engine`
 * is a line-by-line port, and the golden test holds the two together.
 */
object ColorMath {
    /** The colour [rgb] at ([u], [v]) of the picture (0 to 1 from a corner) once graded. */
    fun apply(rgb: FloatArray, u: Float, v: Float, grade: ColorGrade, lut: CubeLut?): FloatArray {
        var c = rgb.copyOf()
        // Exposure in stops, as light: a sRGB value scales by 2^(stops / 2.2).
        val gain = 2f.pow(grade.exposure / 100f / GAMMA)
        c = FloatArray(3) { c[it] * gain }
        // White balance: warmer adds red and takes blue; tint takes green toward magenta.
        val t = grade.temperature / 100f
        val tint = grade.tint / 100f
        c[0] *= 1 + TEMPERATURE * t
        c[2] *= 1 - TEMPERATURE * t
        c[1] *= 1 - TINT * tint
        val k = 1 + CONTRAST * grade.contrast / 100f
        c = FloatArray(3) { (c[it] - 0.5f) * k + 0.5f }
        val s = 1 + grade.saturation / 100f
        val luma = luma(c)
        c = FloatArray(3) { (luma + (c[it] - luma) * s).coerceIn(0f, 1f) }
        if (grade.hsl.values.any { !it.isNeutral }) c = hsl(c, shifts(grade))
        if (lut != null && grade.lutStrength > 0f) {
            val looked = lut.sample(c)
            c = FloatArray(3) { c[it] + (looked[it] - c[it]) * grade.lutStrength }
        }
        val vignette = grade.vignette / 100f
        if (vignette != 0f) {
            val dx = (u - 0.5f) * 2
            val dy = (v - 0.5f) * 2
            val f = smoothstep(0.3f, 1f, sqrt(dx * dx + dy * dy) / sqrt(2f))
            c = if (vignette > 0) {
                FloatArray(3) { c[it] * (1 - VIGNETTE_DARK * vignette * f) }
            } else {
                FloatArray(3) { c[it] + (1 - c[it]) * -vignette * VIGNETTE_LIGHT * f }
            }
        }
        return FloatArray(3) { c[it].coerceIn(0f, 1f) }
    }

    /** The eight ranges' shifts as hue, saturation and luminance (-1 to 1), in range order. */
    fun shifts(grade: ColorGrade): FloatArray = FloatArray(HueRange.entries.size * 3).also {
        HueRange.entries.forEachIndexed { i, range ->
            val shift = grade.hsl[range] ?: return@forEachIndexed
            it[i * 3] = shift.hue / 100f
            it[i * 3 + 1] = shift.saturation / 100f
            it[i * 3 + 2] = shift.luminance / 100f
        }
    }

    /**
     * The HSL ranges: a colour's hue falls between two range centres and takes their shifts in
     * proportion; greys (little chroma) barely move.
     */
    private fun hsl(c: FloatArray, shifts: FloatArray): FloatArray {
        val max = maxOf(c[0], c[1], c[2])
        val min = minOf(c[0], c[1], c[2])
        val chroma = max - min
        if (chroma <= 0f) return c
        val l = (max + min) / 2
        val s = chroma / (1 - abs(2 * l - 1)).coerceAtLeast(1e-6f)
        val h = when (max) {
            c[0] -> 60 * mod((c[1] - c[2]) / chroma, 6f)
            c[1] -> 60 * ((c[2] - c[0]) / chroma + 2)
            else -> 60 * ((c[0] - c[1]) / chroma + 4)
        }
        val ranges = HueRange.entries
        var i = ranges.size - 1
        for (j in ranges.indices) if (h >= ranges[j].degrees) i = j
        val next = (i + 1) % ranges.size
        val end = if (next == 0) 360f else ranges[next].degrees
        val t = (h - ranges[i].degrees) / (end - ranges[i].degrees)
        val f = (chroma * CHROMA_WEIGHT).coerceIn(0f, 1f)
        fun shift(n: Int) = (shifts[i * 3 + n] * (1 - t) + shifts[next * 3 + n] * t) * f
        val hue = mod(h + shift(0) * HUE_DEGREES, 360f)
        val sat = (s * (1 + shift(1))).coerceIn(0f, 1f)
        val lum = (l * (1 + LUMINANCE * shift(2))).coerceIn(0f, 1f)
        return hslToRgb(hue, sat, lum)
    }

    /** The shader's HSL to RGB: hue in degrees, saturation and lightness 0 to 1. */
    fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        val offsets = floatArrayOf(0f, 4f, 2f)
        return FloatArray(3) {
            val k = (abs(mod(h / 60 + offsets[it], 6f) - 3) - 1).coerceIn(0f, 1f)
            l + s * (k - 0.5f) * (1 - abs(2 * l - 1))
        }
    }

    fun luma(c: FloatArray): Float = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    private fun mod(x: Float, y: Float): Float = x - y * floor(x / y)

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** log2, for Auto. */
    internal fun log2(x: Float): Float = (ln(x.toDouble()) / ln(2.0)).toFloat()

    const val GAMMA = 2.2f
    const val TEMPERATURE = 0.2f
    const val TINT = 0.15f
    const val CONTRAST = 0.8f
    const val HUE_DEGREES = 30f
    const val LUMINANCE = 0.5f
    const val CHROMA_WEIGHT = 4f
    const val VIGNETTE_DARK = 0.75f
    const val VIGNETTE_LIGHT = 0.5f
}

/**
 * What Auto looks at in a frame (#28): the average of each channel, the average lightness, the
 * darkest and lightest 2 percent and how colourful it is, all 0 to 1.
 */
data class ColorStats(
    val red: Float,
    val green: Float,
    val blue: Float,
    val luma: Float,
    val low: Float,
    val high: Float,
    val chroma: Float,
) {
    companion object {
        /** The statistics of ARGB [pixels]. */
        fun of(pixels: IntArray): ColorStats {
            val histogram = IntArray(256)
            var r = 0.0
            var g = 0.0
            var b = 0.0
            var chroma = 0.0
            for (pixel in pixels) {
                val red = (pixel shr 16 and 0xFF) / 255f
                val green = (pixel shr 8 and 0xFF) / 255f
                val blue = (pixel and 0xFF) / 255f
                r += red
                g += green
                b += blue
                chroma += maxOf(red, green, blue) - minOf(red, green, blue)
                histogram[(ColorMath.luma(floatArrayOf(red, green, blue)) * 255).toInt()]++
            }
            val n = pixels.size.coerceAtLeast(1)
            fun percentile(p: Float): Float {
                var seen = 0
                for (i in histogram.indices) {
                    seen += histogram[i]
                    if (seen >= p * n) return i / 255f
                }
                return 1f
            }
            val mean = FloatArray(3) { (listOf(r, g, b)[it] / n).toFloat() }
            return ColorStats(
                red = mean[0],
                green = mean[1],
                blue = mean[2],
                luma = ColorMath.luma(mean),
                low = percentile(0.02f),
                high = percentile(0.98f),
                chroma = (chroma / n).toFloat(),
            )
        }
    }
}

/**
 * Auto (#28): a grade that brings a frame toward a balanced exposure, a full range of tones and
 * neutral greys, correcting casts by half so the look of the footage stays.
 */
object AutoColor {
    fun grade(stats: ColorStats): ColorGrade {
        val luma = stats.luma.coerceAtLeast(0.02f)
        // Stops that bring the average to mid-grey, as the exposure slider counts them.
        val exposure = (100 * ColorMath.GAMMA * ColorMath.log2(TARGET_LUMA / luma))
            .coerceIn(-MAX_EXPOSURE, MAX_EXPOSURE)
        val spread = (stats.high - stats.low).coerceAtLeast(0.05f)
        val contrast = ((TARGET_SPREAD / spread - 1) / ColorMath.CONTRAST * 100)
            .coerceIn(-MAX_CONTRAST, MAX_CONTRAST)
        // Grey world: equal red and blue, and green between them.
        val warmBlue = stats.red + stats.blue
        val temperature = if (warmBlue <= 0f) {
            0f
        } else {
            (CAST * (stats.blue - stats.red) / (ColorMath.TEMPERATURE * warmBlue) * 100)
                .coerceIn(-MAX_CAST, MAX_CAST)
        }
        val tint = if (stats.green <= 0f) {
            0f
        } else {
            (CAST * (1 - warmBlue / 2 / stats.green) / ColorMath.TINT * 100)
                .coerceIn(-MAX_CAST, MAX_CAST)
        }
        val saturation = if (stats.chroma < DULL) DULL_BOOST else 0f
        return ColorGrade(
            exposure = exposure.round(),
            contrast = contrast.round(),
            saturation = saturation,
            temperature = temperature.round(),
            tint = tint.round(),
        )
    }

    private fun Float.round(): Float = kotlin.math.round(this)

    private const val TARGET_LUMA = 0.45f
    private const val TARGET_SPREAD = 0.85f
    private const val CAST = 0.5f
    private const val MAX_EXPOSURE = 60f
    private const val MAX_CONTRAST = 40f
    private const val MAX_CAST = 50f
    private const val DULL = 0.12f
    private const val DULL_BOOST = 10f
}
