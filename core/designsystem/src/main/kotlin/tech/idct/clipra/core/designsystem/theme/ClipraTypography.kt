package tech.idct.clipra.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import tech.idct.clipra.core.designsystem.R
import tech.idct.clipra.core.fonts.R as FontsR

/**
 * Geist for UI text, Geist Mono for measurements (timecodes, sizes, resolutions, LUFS) and
 * Bricolage Grotesque for titles, the brand and big numbers. All three are bundled variable
 * fonts (OFL); see design/design-system.md, "Type".
 */
object ClipraFonts {
    val Geist: FontFamily =
        FontFamily(listOf(400, 500, 600, 700).map { variable(FontsR.font.geist, it) })

    val GeistMono: FontFamily =
        FontFamily(listOf(400, 500, 600, 700).map { variable(FontsR.font.geist_mono, it) })

    /**
     * Bricolage Grotesque has an optical size axis that defaults to 96. Browsers set it to the
     * font size, as the design was drawn, so every display style gets a family for its size.
     */
    fun display(size: Float): FontFamily = FontFamily(
        listOf(700, 800).map {
            variable(
                FontsR.font.bricolage_grotesque,
                it,
                FontVariation.Setting("opsz", size.coerceIn(12f, 96f)),
                FontVariation.width(100f),
            )
        },
    )

    private fun variable(resId: Int, weight: Int, vararg extra: FontVariation.Setting): Font = Font(
        resId = resId,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight), *extra),
    )
}

/** The text styles of design/tokens.json ("type"), plus the big numbers of the design system. */
@Immutable
data class ClipraTypography(
    val heroTitle: TextStyle,
    val screenTitle: TextStyle,
    val cardTitle: TextStyle,
    val sectionTitle: TextStyle,
    val brand: TextStyle,
    val headerTitle: TextStyle,
    val sectionLabel: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val caption: TextStyle,
    val label: TextStyle,
    val button: TextStyle,
    val primaryButton: TextStyle,
    val chip: TextStyle,
    val toolLabel: TextStyle,
    val navLabel: TextStyle,
    val meta: TextStyle,
    val metaStrong: TextStyle,
    val clipLabel: TextStyle,
    val clipTitle: TextStyle,
    val numberLarge: TextStyle,
    val numberMedium: TextStyle,
)

private fun display(size: Int, weight: Int) = TextStyle(
    fontFamily = ClipraFonts.display(size.toFloat()),
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = (size * 1.15f).sp,
)

private fun ui(size: Int, weight: Int, lineHeight: Float = 1.35f) = TextStyle(
    fontFamily = ClipraFonts.Geist,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = (size * lineHeight).sp,
)

private fun mono(size: Int, weight: Int) = TextStyle(
    fontFamily = ClipraFonts.GeistMono,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = (size * 1.3f).sp,
)

val ClipraDefaultTypography = ClipraTypography(
    heroTitle = display(34, 800),
    screenTitle = display(22, 700),
    cardTitle = display(24, 800),
    sectionTitle = ui(17, 700),
    brand = display(26, 800),
    headerTitle = ui(15, 600),
    sectionLabel = ui(13, 600),
    body = ui(14, 500),
    bodyStrong = ui(15, 600),
    caption = ui(12, 400),
    label = ui(13, 500),
    button = ui(15, 700),
    primaryButton = ui(16, 700),
    chip = ui(13, 600),
    toolLabel = ui(11, 500),
    navLabel = ui(12, 600),
    meta = mono(11, 400),
    metaStrong = mono(15, 600),
    clipLabel = mono(10, 600),
    clipTitle = ui(10, 600),
    numberLarge = display(56, 800),
    numberMedium = display(32, 800),
)
