package tech.idct.clipra.core.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** A project's frame: ratio, layout, resolution, frame rate and background (spec section 2). */
@Serializable
data class Format(
    val ratio: AspectRatio = AspectRatio.Portrait9x16,
    val layout: Layout = Layout.Single,
    val resolution: Resolution = Resolution.FHD,
    val frameRate: FrameRate = FrameRate.Fps30,
    val background: Background = Background.Black,
) {
    /**
     * The output size. The resolution is the short side in every ratio; the long side follows
     * the ratio, rounded to an even number (16:9 4K = 3840 × 2160, 21:9 FHD = 2520 × 1080).
     */
    val outputSize: Size
        get() {
            val p = resolution.shortSide
            val (w, h) = ratio.width to ratio.height
            return when {
                w > h -> Size(even(p.toDouble() * w / h), p)
                h > w -> Size(p, even(p.toDouble() * h / w))
                else -> Size(p, p)
            }
        }

    private fun even(value: Double): Int = (value / 2).roundToInt() * 2
}

@Serializable
data class Size(val width: Int, val height: Int)

@Serializable
enum class AspectRatio(val width: Int, val height: Int) {
    Portrait9x16(9, 16),
    Landscape16x9(16, 9),
    Square1x1(1, 1),
    Portrait4x5(4, 5),
    Landscape4x3(4, 3),
    Cinema21x9(21, 9),
    ;

    /** "9:16", "21:9". */
    val label: String get() = "$width:$height"
}

/** Mosaic (split-screen) layouts; [cells] regions that clips can be dropped into. */
@Serializable
enum class Layout(val cells: Int) {
    Single(1),
    Split2(2),
    Split3(3),
    Grid2x2(4),
    Grid3x3(9),
}

/** The resolution names of the design, with the short side in pixels. */
@Serializable
enum class Resolution(val shortSide: Int, val label: String) {
    HD(720, "HD"),
    FHD(1080, "FHD"),
    QHD(1440, "2K"),
    UHD(2160, "4K"),
}

@Serializable
enum class FrameRate(val fps: Int) {
    Fps24(24),
    Fps25(25),
    Fps30(30),
    Fps50(50),
    Fps60(60),
}

/** Fills empty space and is the default colour of text cards. Blur blurs the clip below. */
@Serializable
enum class Background {
    Black,
    White,
    Yellow,
    Blur,
}
