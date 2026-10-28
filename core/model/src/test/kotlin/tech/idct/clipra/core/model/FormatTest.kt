package tech.idct.clipra.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** The output size rule of spec section 2 for every ratio and resolution. */
class FormatTest {
    private fun size(ratio: AspectRatio, resolution: Resolution) =
        Format(ratio = ratio, resolution = resolution).outputSize

    @Test
    fun theResolutionIsTheShortSide() {
        val expected = mapOf(
            AspectRatio.Portrait9x16 to
                listOf(720 to 1280, 1080 to 1920, 1440 to 2560, 2160 to 3840),
            AspectRatio.Landscape16x9 to
                listOf(1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160),
            AspectRatio.Square1x1 to listOf(720 to 720, 1080 to 1080, 1440 to 1440, 2160 to 2160),
            AspectRatio.Portrait4x5 to listOf(720 to 900, 1080 to 1350, 1440 to 1800, 2160 to 2700),
            AspectRatio.Landscape4x3 to listOf(
                960 to 720,
                1440 to 1080,
                1920 to 1440,
                2880 to 2160,
            ),
            AspectRatio.Cinema21x9 to listOf(1680 to 720, 2520 to 1080, 3360 to 1440, 5040 to 2160),
        )
        for ((ratio, sizes) in expected) {
            Resolution.entries.zip(sizes).forEach { (resolution, wh) ->
                assertEquals(
                    "$ratio $resolution",
                    Size(wh.first, wh.second),
                    size(ratio, resolution),
                )
            }
        }
    }

    @Test
    fun theLongSideIsRoundedToAnEvenNumber() {
        for (ratio in AspectRatio.entries) {
            for (resolution in Resolution.entries) {
                val s = size(ratio, resolution)
                assertEquals("$ratio $resolution width", 0, s.width % 2)
                assertEquals("$ratio $resolution height", 0, s.height % 2)
            }
        }
    }

    @Test
    fun labels() {
        assertEquals("9:16", AspectRatio.Portrait9x16.label)
        assertEquals("21:9", AspectRatio.Cinema21x9.label)
        assertEquals("2K", Resolution.QHD.label)
        assertEquals("4K", Resolution.UHD.label)
    }
}
