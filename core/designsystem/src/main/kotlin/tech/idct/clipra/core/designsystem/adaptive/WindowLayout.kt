package tech.idct.clipra.core.designsystem.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Which of the design's two layouts a window gets (#68): the phone boards (390 dp wide), or the
 * tablet's panes side by side (1280 × 800 dp). The tablet layout needs an expanded width in
 * landscape with room for the timeline, so a phone on its side and a tablet held upright keep
 * the phone layout. Screens switch as the window changes (split screen, foldables, resizing);
 * the activity handles those changes itself, so nothing on screen is lost.
 */
enum class WindowLayout {
    Phone,
    Tablet,
    ;

    companion object {
        /** The expanded width size class starts here. */
        const val TABLET_MIN_WIDTH_DP = 840

        /** Below this, a landscape window is too short for the preview over the timeline. */
        const val TABLET_MIN_HEIGHT_DP = 560

        /** The layout for a window [widthDp] × [heightDp]. */
        fun of(widthDp: Int, heightDp: Int): WindowLayout =
            if (widthDp >= TABLET_MIN_WIDTH_DP && heightDp >= TABLET_MIN_HEIGHT_DP &&
                widthDp > heightDp
            ) {
                Tablet
            } else {
                Phone
            }
    }
}

/** The layout of the window the screen is in; phone unless [ProvideWindowLayout] says else. */
val LocalWindowLayout = staticCompositionLocalOf { WindowLayout.Phone }

/** Gives [content] the layout for the current window, and a new one each time it changes. */
@Composable
fun ProvideWindowLayout(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val layout = WindowLayout.of(configuration.screenWidthDp, configuration.screenHeightDp)
    CompositionLocalProvider(LocalWindowLayout provides layout, content = content)
}
