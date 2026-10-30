package tech.idct.clipra.core.model

import kotlin.math.abs
import kotlin.math.max

/**
 * The cutout's arithmetic (#50, #51), shared by the mask cache and the compositor's shader
 * (`Shaders.CUTOUT` ports [alpha], [keyAlpha] and [despill] line by line):
 * - the segmentation model gives each pixel a confidence that it belongs to a person;
 * - [smooth] steadies it from frame to frame, so edges don't flicker, while following motion;
 * - [alpha] turns it into how much of the pixel is kept;
 * - a chroma key instead keeps a pixel by how far its colour is from the key's ([keyAlpha]) and
 *   takes the key's tint off what stays ([despill]).
 */
object CutoutMath {
    /** The side of the square masks, as the selfie segmenter outputs them. */
    const val MASK_SIZE = 256

    /**
     * How much of a pixel is kept for the model's confidence [m] (0 to 1). The firm edge sits a
     * little inside the model's 50 % line and narrows as [edgeRefine] goes up (a soft 0.3 either
     * side at 0, a crisp 0.08 at 1). The soft fringe, where the model is unsure (hair, fingers,
     * motion blur), is kept partly see-through by [hairDetail].
     */
    fun alpha(m: Float, edgeRefine: Float, hairDetail: Float): Float {
        val center = 0.5f + 0.15f * edgeRefine
        val width = 0.3f - 0.22f * edgeRefine
        val firm = smoothstep(center - width, center + width, m)
        val soft = smoothstep(FRINGE_FROM, FRINGE_TO, m)
        return max(firm, soft * hairDetail)
    }

    /**
     * This frame's confidence, steadied by the last frame's: small changes (the model wavering
     * on a still edge) mostly keep the last value, large ones (real motion) take the new one at
     * once. [previous] and [current] are masks of the same size; [out] may be [previous].
     */
    fun smooth(previous: FloatArray, current: FloatArray, out: FloatArray) {
        for (i in current.indices) {
            val change = abs(current[i] - previous[i])
            val keep = KEEP * (1f - change / MOTION).coerceIn(0f, 1f)
            out[i] = current[i] + keep * (previous[i] - current[i])
        }
    }

    /**
     * The colour [r], [g], [b] (0 to 1) as BT.709 chroma (blue and red difference, each about
     * -0.5 to 0.5): brightness left out, so the shadows and highlights of a green screen stay
     * close to its colour.
     */
    fun chroma(r: Float, g: Float, b: Float): FloatArray {
        val y = LUMA_R * r + LUMA_G * g + LUMA_B * b
        return floatArrayOf((b - y) * CB_SCALE, (r - y) * CR_SCALE)
    }

    /** How far apart two colours' chroma are. */
    fun chromaDistance(r: Float, g: Float, b: Float, key: FloatArray): Float {
        val c = chroma(r, g, b)
        val db = c[0] - key[0]
        val dr = c[1] - key[1]
        return kotlin.math.sqrt(db * db + dr * dr)
    }

    /**
     * How much of a pixel a chroma key (#51) keeps, from its chroma's [distance] to the key's:
     * nothing within the similarity, all of it past the soft edge after it.
     */
    fun keyAlpha(distance: Float, similarity: Float, smoothness: Float): Float {
        val near = similarity * KEY_REACH
        return smoothstep(near, near + KEY_SOFT_MIN + smoothness * KEY_SOFT, distance)
    }

    /**
     * What is left of the key's tint on a kept pixel: up to [spill] of it is taken off, by moving
     * the colour towards its own grey, most where the colour is nearest the key.
     */
    fun despill(
        rgb: FloatArray,
        distance: Float,
        similarity: Float,
        smoothness: Float,
        spill: Float,
    ): FloatArray {
        val near = similarity * KEY_REACH
        val tint = 1f - smoothstep(near, near + smoothness * KEY_SOFT + SPILL_REACH, distance)
        val amount = spill * tint
        val y = LUMA_R * rgb[0] + LUMA_G * rgb[1] + LUMA_B * rgb[2]
        return FloatArray(3) { rgb[it] + (y - rgb[it]) * amount }
    }

    /**
     * The colour a chroma key should take out of a picture ([pixels], ARGB, [width] × [height]):
     * the commonest strong colour around its edges, where a green or blue screen shows behind
     * the subject; null when the edges have no strong colour.
     */
    fun guessKey(pixels: IntArray, width: Int, height: Int): Long? {
        val band = maxOf(1, minOf(width, height) / BORDER)
        val buckets = Array(HUES) { LongArray(4) }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x >= band && x < width - band && y >= band && y < height - band) continue
                val p = pixels[y * width + x]
                val r = p shr 16 and 0xFF
                val g = p shr 8 and 0xFF
                val b = p and 0xFF
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                if (max - min < STRONG) continue
                val hue = when (max) {
                    r -> 60f * ((g - b).toFloat() / (max - min)).mod(6f)
                    g -> 60f * ((b - r).toFloat() / (max - min) + 2)
                    else -> 60f * ((r - g).toFloat() / (max - min) + 4)
                }
                val bucket = buckets[(hue / 360f * HUES).toInt().coerceIn(0, HUES - 1)]
                bucket[0]++
                bucket[1] += r.toLong()
                bucket[2] += g.toLong()
                bucket[3] += b.toLong()
            }
        }
        val best = buckets.maxBy { it[0] }
        if (best[0] == 0L) return null
        val n = best[0]
        return 0xFF000000 or (best[1] / n shl 16) or (best[2] / n shl 8) or (best[3] / n)
    }

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** The share of the last frame kept where nothing moves. */
    private const val KEEP = 0.7f

    /** A change this large (in confidence) is motion, not wavering, and is taken at once. */
    private const val MOTION = 0.35f

    private const val FRINGE_FROM = 0.08f
    private const val FRINGE_TO = 0.6f

    const val LUMA_R = 0.2126f
    const val LUMA_G = 0.7152f
    const val LUMA_B = 0.0722f

    /** BT.709's chroma scales: Cb and Cr each span about -0.5 to 0.5. */
    const val CB_SCALE = 0.5389f
    const val CR_SCALE = 0.6350f

    /** Similarity 1 keys out every colour within this chroma distance of the key. */
    const val KEY_REACH = 0.4f

    /** The soft edge after it: at least this, up to this plus [KEY_SOFT] at Smoothness 1. */
    const val KEY_SOFT_MIN = 0.01f
    const val KEY_SOFT = 0.25f

    /** How far past the edge the key's tint is still taken off. */
    const val SPILL_REACH = 0.12f

    /** The edges [guessKey] looks at: a tenth of the short side. */
    private const val BORDER = 10

    /** Colours with a spread (max minus min of 0 to 255) under this are too grey to key. */
    private const val STRONG = 60

    /** Hue buckets of 15 degrees. */
    private const val HUES = 24
}
