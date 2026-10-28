package tech.idct.clipra.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The Clipra theme. Clipra is dark only. Until the design system issue (#5) brings the colours,
 * type and shapes from design/tokens.json, this is Material 3's dark scheme.
 */
@Composable
fun ClipraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
