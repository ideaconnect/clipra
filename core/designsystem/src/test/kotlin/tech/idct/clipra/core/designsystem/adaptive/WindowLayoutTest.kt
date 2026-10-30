package tech.idct.clipra.core.designsystem.adaptive

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Phone or tablet layout by window size (#68), switching without losing what is on screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WindowLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theTabletLayoutIsForWideLandscapeWindows() {
        // The design's tablet board, and a tablet in split screen beside another app.
        assertEquals(WindowLayout.Tablet, WindowLayout.of(1280, 800))
        assertEquals(WindowLayout.Tablet, WindowLayout.of(840, 600))
        // A phone, upright and on its side; a tablet held upright; half a tablet.
        assertEquals(WindowLayout.Phone, WindowLayout.of(390, 844))
        assertEquals(WindowLayout.Phone, WindowLayout.of(915, 412))
        assertEquals(WindowLayout.Phone, WindowLayout.of(800, 1280))
        assertEquals(WindowLayout.Phone, WindowLayout.of(640, 800))
        // An unfolded foldable, upright and on its side.
        assertEquals(WindowLayout.Phone, WindowLayout.of(673, 841))
        assertEquals(WindowLayout.Tablet, WindowLayout.of(841, 673))
    }

    @Test
    fun resizingSwitchesTheLayoutAndKeepsState() {
        var wide by mutableStateOf(false)
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = if (wide) 1280 else 390
                screenHeightDp = if (wide) 800 else 844
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                ProvideWindowLayout {
                    var taps by remember { mutableIntStateOf(0) }
                    Text(
                        text = "${LocalWindowLayout.current} $taps",
                        modifier = Modifier
                            .clickable { taps++ }
                            .testTag("screen"),
                    )
                }
            }
        }
        compose.onNodeWithTag("screen").performClick()
        compose.onNodeWithText("Phone 1").assertExists()
        wide = true
        compose.onNodeWithText("Tablet 1").assertExists()
        wide = false
        compose.onNodeWithText("Phone 1").assertExists()
    }
}
