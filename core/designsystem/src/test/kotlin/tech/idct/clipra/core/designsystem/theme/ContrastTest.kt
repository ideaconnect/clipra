package tech.idct.clipra.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2.1 contrast of every text and background pair the components use: at least 4.5:1. */
class ContrastTest {
    private val c = ClipraDarkColors

    private val surfaces = listOf(
        "background" to c.background,
        "surface" to c.surface,
        "surfaceRaised" to c.surfaceRaised,
        "surfaceSunken" to c.surfaceSunken,
        "timelineBackground" to c.timelineBackground,
        "layerHeaderBackground" to c.layerHeaderBackground,
    )

    @Test
    fun textOnEverySurface() {
        val texts = listOf(
            "textPrimary" to c.textPrimary,
            "textSecondary" to c.textSecondary,
            "textSecondaryAlt" to c.textSecondaryAlt,
            "textTertiary" to c.textTertiary,
            "accent" to c.accent,
            "danger" to c.danger,
        )
        for ((textName, text) in texts) {
            for ((surfaceName, surface) in surfaces) {
                assertContrast("$textName on $surfaceName", text, surface)
            }
        }
    }

    @Test
    fun accentAndSelection() {
        assertContrast("onAccent on accent", c.onAccent, c.accent)
        assertContrast("onAccent on textPrimary (tabs)", c.onAccent, c.textPrimary)
        assertContrast(
            "textPrimary on accentSelectedBackground",
            c.textPrimary,
            c.accentSelectedBackground,
        )
        assertContrast("accent on accentSelectedBackground", c.accent, c.accentSelectedBackground)
        assertContrast(
            "textSecondary on accentSelectedBackground",
            c.textSecondary,
            c.accentSelectedBackground,
        )
        assertContrast("accent on accentChipBackground", c.accent, c.accentChipBackground)
        assertContrast("textPrimary on accentChipBackground", c.textPrimary, c.accentChipBackground)
    }

    @Test
    fun cards() {
        assertContrast("onDeviceText on onDeviceBackground", c.onDeviceText, c.onDeviceBackground)
        assertContrast(
            "textPrimary on onDeviceCardBackground",
            c.textPrimary,
            c.onDeviceCardBackground,
        )
        assertContrast(
            "onDeviceCardText on onDeviceCardBackground",
            c.onDeviceCardText,
            c.onDeviceCardBackground,
        )
        assertContrast("accent on onDeviceCardBackground", c.accent, c.onDeviceCardBackground)
        assertContrast("textPrimary on proCardBackground", c.textPrimary, c.proCardBackground)
        assertContrast("proCardText on proCardBackground", c.proCardText, c.proCardBackground)
        assertContrast("onAccent on accent (crown)", c.onAccent, c.accent)
    }

    @Test
    fun clipLabels() {
        val clips = listOf(
            "text" to c.clip.text,
            "photo" to c.clip.photo,
            "caption" to c.clip.caption,
            "music" to c.clip.music,
            "voice" to c.clip.voice,
        )
        for ((name, fill) in clips) {
            assertContrast("textPrimary on clip.$name", c.textPrimary, fill)
        }
        assertContrast("captionText on surface", c.clip.captionText, c.surface)
    }

    @Test
    fun adBadge() {
        assertContrast("badgeText on badgeBackground", c.ad.badgeText, c.ad.badgeBackground)
        assertContrast("textPrimary on ad background", c.textPrimary, c.ad.background)
    }

    private fun assertContrast(pair: String, text: Color, background: Color) {
        val ratio = contrast(text, background)
        assertTrue("$pair is %.2f:1, below 4.5:1".format(ratio), ratio >= 4.5)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
