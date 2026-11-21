package tech.idct.clipra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * The Play store graphics in `src/main/play/listings/<language>/graphics` (#282): every language
 * has its own eight phone, eight 7-inch and eight 10-inch tablet screenshots, each a PNG
 * without transparency within Play Console's limits for its device type.
 */
class StoreGraphicsTest {
    private val listings = File("src/main/play/listings")
    private val languages = listOf("en-US", "pl-PL")

    @Test
    fun everyLanguageHasEightScreenshotsPerDeviceType() {
        for (language in languages) {
            for (type in TYPES) {
                val names = screenshots(language, type.folder).map { it.name }
                assertEquals("$language ${type.folder}", EXPECTED_NAMES, names)
            }
        }
    }

    @Test
    fun screenshotsFitPlayLimits() {
        for (language in languages) {
            for (type in TYPES) {
                for (file in screenshots(language, type.folder)) {
                    val image = ImageIO.read(file)
                    val name = "$language/${type.folder}/${file.name}"
                    val short = minOf(image.width, image.height)
                    val long = maxOf(image.width, image.height)
                    assertTrue("$name has transparency", !image.colorModel.hasAlpha())
                    assertTrue("$name is ${file.length()} bytes", file.length() <= MAX_BYTES)
                    assertTrue("$name: $short px is below ${type.minSide}", short >= type.minSide)
                    assertTrue("$name: $long px is above ${type.maxSide}", long <= type.maxSide)
                    // Play shows 16:9 and 9:16 whole; the phone limit is twice as long as wide.
                    assertTrue("$name is not 16:9 or 9:16", long * 9 == short * 16)
                }
            }
        }
    }

    @Test
    fun featureGraphicsAndIconHavePlaySizes() {
        for (language in languages) {
            val path = "$language/graphics/feature-graphic/feature-graphic.png"
            val graphic = ImageIO.read(File(listings, path))
            assertEquals("$language feature graphic width", 1024, graphic.width)
            assertEquals("$language feature graphic height", 500, graphic.height)
        }
        val icon = ImageIO.read(File(listings, "en-US/graphics/icon/icon.png"))
        assertEquals(512, icon.width)
        assertEquals(512, icon.height)
    }

    private fun screenshots(language: String, folder: String): List<File> =
        File(listings, "$language/graphics/$folder").listFiles().orEmpty()
            .filter { it.isFile }
            .sortedBy { it.name }

    /** A Play screenshot slot: its folder (Gradle Play Publisher's names) and side limits. */
    private class ScreenshotType(val folder: String, val minSide: Int, val maxSide: Int)

    private companion object {
        val TYPES = listOf(
            ScreenshotType("phone-screenshots", minSide = 320, maxSide = 3840),
            ScreenshotType("tablet-screenshots", minSide = 320, maxSide = 3840),
            ScreenshotType("large-tablet-screenshots", minSide = 1080, maxSide = 7680),
        )
        val EXPECTED_NAMES = (1..8).map { "%02d.png".format(it) }
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}
