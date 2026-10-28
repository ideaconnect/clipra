package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap

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

data class RenderLayer(val clips: List<RenderClip>, val hidden: Boolean = false)

/**
 * One clip of a layer. [fadeInUs] dissolves the clip in over the clip it overlaps on the same
 * layer (a transition across a cut).
 */
data class RenderClip(
    val id: String,
    val startUs: Long,
    val durationUs: Long,
    val source: RenderSource,
    val placement: Placement = Placement.Fill,
    val blend: BlendMode = BlendMode.Normal,
    val opacity: Float = 1f,
    val fadeInUs: Long = 0,
) {
    val endUs: Long get() = startUs + durationUs

    fun isActiveAt(timeUs: Long): Boolean = timeUs in startUs until endUs

    /** Opacity at [timeUs], including the dissolve in. */
    fun opacityAt(timeUs: Long): Float {
        if (fadeInUs <= 0) return opacity
        val progress = ((timeUs - startUs).toFloat() / fadeInUs).coerceIn(0f, 1f)
        return opacity * progress
    }
}

sealed interface RenderSource {
    /** A video file; [inPointUs] is where the clip starts in the file. */
    data class Video(val path: String, val inPointUs: Long = 0) : RenderSource

    /** A still picture: a photo, or a text card rendered to a bitmap. */
    data class Image(val bitmap: Bitmap) : RenderSource
}

/** Where a clip sits in the frame. */
sealed interface Placement {
    /** Covers the whole frame, cropping what doesn't fit. */
    data object Fill : Placement

    /** Fits inside a rectangle given in fractions of the frame, from the top-left corner. */
    data class Box(val left: Float, val top: Float, val width: Float, val height: Float) : Placement
}

/** The spike's blend modes; the full set of 16 comes with M2-blend. */
enum class BlendMode { Normal, Multiply, Screen }
