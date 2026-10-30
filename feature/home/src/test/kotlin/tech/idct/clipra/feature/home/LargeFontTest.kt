package tech.idct.clipra.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.model.Format
import java.util.Locale

/** Home and New project with the system font at 200% (#75): the key actions stay on screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class LargeFontTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private fun show(content: @Composable () -> Unit) {
        Locale.setDefault(Locale.US)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ClipraTheme { content() }
            }
        }
    }

    @Test
    fun home() {
        show {
            HomeScreen(
                recent = emptyList(),
                nowMillis = 0,
                aiStatus = null,
                actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}),
                projectActions = ProjectActions({}, {}, {}),
            )
        }
        compose.onNodeWithText("New project").assertIsDisplayed()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun newProject() {
        show {
            NewProjectScreen(
                format = Format(),
                onFormatChange = {},
                onCreate = {},
                onBack = {},
                fourKSupported = true,
            )
        }
        compose.onNodeWithTag("create-project").assertIsDisplayed()
        compose.onNodeWithTag("output-size").assertIsDisplayed()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
