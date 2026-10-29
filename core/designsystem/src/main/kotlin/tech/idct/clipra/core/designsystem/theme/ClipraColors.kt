package tech.idct.clipra.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** The colours of design/tokens.json. Clipra is dark only, so there is one palette. */
@Immutable
data class ClipraColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val timelineBackground: Color,
    val layerHeaderBackground: Color,
    val border: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textSecondaryAlt: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    val accent: Color,
    val accentHover: Color,
    val onAccent: Color,
    val onAccentMuted: Color,
    val accentSelectedBackground: Color,
    val accentSelectedBorder: Color,
    val accentChipBackground: Color,
    val proCardBackground: Color,
    val proCardBorder: Color,
    val proCardText: Color,
    val onDeviceBackground: Color,
    val onDeviceText: Color,
    val onDeviceCardBackground: Color,
    val onDeviceCardBorder: Color,
    val onDeviceCardText: Color,
    val danger: Color,
    val cutBand: Color,
    val fillerUnderline: Color,
    val lowConfidenceUnderline: Color,
    val clip: ClipColors,
    val feature: FeatureColors,
    val ad: AdColors,
    /** The custom colour swatch (Text board): a colour wheel. */
    val colorWheel: List<Color> = listOf(
        Color(0xFFFF5A36),
        Color(0xFFFFD23F),
        Color(0xFF3BD17A),
        Color(0xFF2FB8FF),
        Color(0xFF7A5AF8),
        Color(0xFFFF4FD8),
        Color(0xFFFF5A36),
    ),
)

/** Timeline clip colours: they tell the content type apart. */
@Immutable
data class ClipColors(
    val text: Color,
    val photo: Color,
    val caption: Color,
    val captionText: Color,
    val music: Color,
    val musicWave: Color,
    val voice: Color,
    val voiceWave: Color,
    val adjustmentStripeA: Color,
    val adjustmentStripeB: Color,
    val adjustmentBorder: Color,
    val textCardBorder: Color,
    val beatMarker: Color,
)

/** Icon colours of the quick actions on Home (Main board): one per feature. */
@Immutable
data class FeatureColors(val captions: Color, val text: Color, val mosaic: Color)

/** The ad slots: black, with a light "Ad" badge. */
@Immutable
data class AdColors(val background: Color, val badgeBackground: Color, val badgeText: Color)

val ClipraDarkColors = ClipraColors(
    background = Color(0xFF0D0D0F),
    surface = Color(0xFF17171A),
    surfaceRaised = Color(0xFF1F1F23),
    surfaceSunken = Color(0xFF131316),
    timelineBackground = Color(0xFF111114),
    layerHeaderBackground = Color(0xFF141417),
    border = Color(0xFF2A2A30),
    borderSubtle = Color(0xFF1F1F23),
    borderStrong = Color(0xFF3A3A40),
    textPrimary = Color(0xFFF4F3EF),
    textSecondary = Color(0xFFA6A5AD),
    textSecondaryAlt = Color(0xFFB4B3BB),
    textTertiary = Color(0xFF8C8B94),
    textDisabled = Color(0xFF6E6D76),
    accent = Color(0xFFFFD23F),
    accentHover = Color(0xFFFFE27A),
    onAccent = Color(0xFF141210),
    // Secondary text on the accent, such as the New project card's line on Home.
    onAccentMuted = Color(0xFF3B3418),
    accentSelectedBackground = Color(0xFF221F12),
    accentSelectedBorder = Color(0xFF5A4C16),
    accentChipBackground = Color(0xFF2B2716),
    proCardBackground = Color(0xFF1B1910),
    proCardBorder = Color(0xFF4A3F14),
    proCardText = Color(0xFFC9C2A2),
    onDeviceBackground = Color(0xFF1F2A1F),
    onDeviceText = Color(0xFF8FE388),
    onDeviceCardBackground = Color(0xFF131A13),
    onDeviceCardBorder = Color(0xFF22331F),
    onDeviceCardText = Color(0xFFB4C4B0),
    danger = Color(0xFFFF8A70),
    // rgba(255, 90, 54, 0.2)
    cutBand = Color(0x33FF5A36),
    fillerUnderline = Color(0xFFFFB547),
    lowConfidenceUnderline = Color(0xFFFF9A6B),
    clip = ClipColors(
        text = Color(0xFFA8431A),
        photo = Color(0xFF5B45C4),
        caption = Color(0xFF1F4FC2),
        captionText = Color(0xFF7FA6FF),
        music = Color(0xFF0E4F49),
        musicWave = Color(0xFF5EE0C8),
        voice = Color(0xFF5A2238),
        voiceWave = Color(0xFFFF9CC2),
        adjustmentStripeA = Color(0xFF3A3552),
        adjustmentStripeB = Color(0xFF2E2A40),
        adjustmentBorder = Color(0xFF6E5FA8),
        textCardBorder = Color(0xFF3A3A40),
        // rgba(255, 210, 63, 0.95)
        beatMarker = Color(0xF2FFD23F),
    ),
    feature = FeatureColors(
        captions = Color(0xFF7FA6FF),
        text = Color(0xFFFF9A6B),
        mosaic = Color(0xFFB9A6FF),
    ),
    ad = AdColors(
        background = Color(0xFF000000),
        badgeBackground = Color(0xFFF4F3EF),
        badgeText = Color(0xFF000000),
    ),
)
