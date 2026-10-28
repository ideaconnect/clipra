package tech.idct.clipra.feature.home

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.Resolution

/** New project (design/screens/phone/NewProject.png and its 16:9, 2×2, 4K state). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class NewProjectScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private fun show(format: Format, onChange: (Format) -> Unit = {}) {
        compose.setContent {
            ClipraTheme {
                NewProjectScreen(
                    format = format,
                    onFormatChange = onChange,
                    onCreate = {},
                    onBack = {},
                    fourKSupported = true,
                )
            }
        }
    }

    @Test
    fun defaults() {
        show(Format())
        compose.onNodeWithTag("output-size").assertTextEquals("1080 × 1920")
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun landscapeMosaic4k() {
        show(
            Format(
                ratio = AspectRatio.Landscape16x9,
                layout = Layout.Grid2x2,
                resolution = Resolution.UHD,
            ),
        )
        compose.onNodeWithTag("output-size").assertTextEquals("3840 × 2160")
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun choicesChangeTheFormat() {
        var changed: Format? = null
        show(Format()) { changed = it }
        compose.onNodeWithTag("ratio:21:9").performClick()
        assertEquals(AspectRatio.Cinema21x9, changed?.ratio)
        compose.onNodeWithTag("layout:Split3").performClick()
        assertEquals(Layout.Split3, changed?.layout)
        compose.onNodeWithTag("background:Yellow").performScrollTo().performClick()
        assertEquals(tech.idct.clipra.core.model.Background.Yellow, changed?.background)
    }
}
