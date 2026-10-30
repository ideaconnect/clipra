package tech.idct.clipra.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.adaptive.LocalWindowLayout
import tech.idct.clipra.core.designsystem.adaptive.WindowLayout

/** A tool screen's layout (#70): stacked on a phone, side by side on a tablet. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class ToolScaffoldTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(layout: WindowLayout) {
        compose.setContent {
            ClipraTheme {
                CompositionLocalProvider(LocalWindowLayout provides layout) {
                    ToolScaffold(
                        header = { Text("Header", Modifier.height(52.dp).testTag("header")) },
                        preview = { Box(Modifier.fillMaxSize().testTag("preview")) },
                        controls = { fill -> Box(fill.fillMaxWidth().testTag("controls")) },
                        previewHeight = 300.dp,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    @Test
    fun onAPhoneTheControlsAreUnderThePreview() {
        show(WindowLayout.Phone)
        val preview = compose.onNodeWithTag("preview").getBoundsInRoot()
        val controls = compose.onNodeWithTag("controls").getBoundsInRoot()
        assertEquals(300.dp, preview.bottom - preview.top)
        assertEquals(preview.bottom, controls.top)
        assertEquals(1280.dp, controls.right)
    }

    @Test
    fun onATabletTheControlsAreBesideThePreview() {
        show(WindowLayout.Tablet)
        val preview = compose.onNodeWithTag("preview").getBoundsInRoot()
        val controls = compose.onNodeWithTag("controls").getBoundsInRoot()
        assertTrue(preview.right <= controls.left)
        assertEquals(420.dp, controls.right - controls.left)
        assertEquals(52.dp, controls.top)
    }

    @Test
    fun readableWidthKeepsToTheMiddleOnATablet() {
        compose.setContent {
            ClipraTheme {
                CompositionLocalProvider(LocalWindowLayout provides WindowLayout.Tablet) {
                    ReadableWidth { Box(Modifier.fillMaxSize().testTag("content")) }
                }
            }
        }
        val content = compose.onNodeWithTag("content").getBoundsInRoot()
        assertEquals(720.dp, content.right - content.left)
        assertEquals(280.dp, content.left)
    }
}
