package tech.idct.clipra.core.model

import kotlin.math.abs
import kotlin.math.max

/**
 * The person cutout's arithmetic (#50), shared by the mask cache and the compositor's shader
 * (`Shaders.CUTOUT` ports [alpha] line by line):
 * - the segmentation model gives each pixel a confidence that it belongs to a person;
 * - [smooth] steadies it from frame to frame, so edges don't flicker, while following motion;
 * - [alpha] turns it into how much of the pixel is kept.
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
}
