package tech.idct.clipra.core.designsystem

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import tech.idct.clipra.core.designsystem.theme.ClipraColors
import tech.idct.clipra.core.designsystem.theme.ClipraDarkColors
import tech.idct.clipra.core.designsystem.theme.ClipraDefaultTypography
import tech.idct.clipra.core.designsystem.theme.ClipraDimens
import tech.idct.clipra.core.designsystem.theme.ClipraShapes
import tech.idct.clipra.core.designsystem.theme.ClipraTypography

private val LocalClipraColors = staticCompositionLocalOf { ClipraDarkColors }
private val LocalClipraTypography = staticCompositionLocalOf { ClipraDefaultTypography }
private val LocalClipraShapes = staticCompositionLocalOf { ClipraShapes() }

/**
 * The Clipra theme: the colours, type and shapes of design/tokens.json. Screens read them through
 * [ClipraTheme.colors], [ClipraTheme.typography] and [ClipraTheme.shapes], never as raw values.
 * Material components get the same palette and fonts, and every interactive component keeps a
 * 48 dp touch area.
 */
@Composable
fun ClipraTheme(content: @Composable () -> Unit) {
    val colors = ClipraDarkColors
    val type = ClipraDefaultTypography
    CompositionLocalProvider(
        LocalClipraColors provides colors,
        LocalClipraTypography provides type,
        LocalClipraShapes provides ClipraShapes(),
        LocalMinimumInteractiveComponentSize provides ClipraDimens.TouchTarget,
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = colors.accent,
                onPrimary = colors.onAccent,
                primaryContainer = colors.accentSelectedBackground,
                onPrimaryContainer = colors.accent,
                secondary = colors.textSecondaryAlt,
                onSecondary = colors.onAccent,
                background = colors.background,
                onBackground = colors.textPrimary,
                surface = colors.background,
                onSurface = colors.textPrimary,
                surfaceVariant = colors.surfaceRaised,
                onSurfaceVariant = colors.textSecondary,
                surfaceContainerLowest = colors.background,
                surfaceContainerLow = colors.surfaceSunken,
                surfaceContainer = colors.surface,
                surfaceContainerHigh = colors.surfaceRaised,
                surfaceContainerHighest = colors.surfaceRaised,
                outline = colors.borderStrong,
                outlineVariant = colors.border,
                error = colors.danger,
                onError = colors.onAccent,
            ),
            typography = Typography(
                displayLarge = type.numberLarge,
                headlineLarge = type.heroTitle,
                headlineMedium = type.brand,
                headlineSmall = type.screenTitle,
                titleLarge = type.screenTitle,
                titleMedium = type.bodyStrong,
                titleSmall = type.headerTitle,
                bodyLarge = type.bodyStrong,
                bodyMedium = type.body,
                bodySmall = type.caption,
                labelLarge = type.button,
                labelMedium = type.chip,
                labelSmall = type.toolLabel,
            ),
            content = content,
        )
    }
}

/** Reads the current theme values. */
object ClipraTheme {
    val colors: ClipraColors
        @Composable @ReadOnlyComposable
        get() = LocalClipraColors.current

    val typography: ClipraTypography
        @Composable @ReadOnlyComposable
        get() = LocalClipraTypography.current

    val shapes: ClipraShapes
        @Composable @ReadOnlyComposable
        get() = LocalClipraShapes.current
}
