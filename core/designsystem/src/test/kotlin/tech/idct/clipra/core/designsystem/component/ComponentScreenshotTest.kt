package tech.idct.clipra.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.R
import tech.idct.clipra.core.designsystem.gallery.ColorsSample
import tech.idct.clipra.core.designsystem.gallery.IconsSample
import tech.idct.clipra.core.designsystem.gallery.TypeSample
import tech.idct.clipra.core.designsystem.placeholder.Board
import tech.idct.clipra.core.designsystem.placeholder.BoardPlaceholder

/**
 * Screenshot tests of every component group against the references in src/test/screenshots.
 * Record with `./gradlew :core:designsystem:recordRoborazziDebug`, check with
 * `verifyRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class ComponentScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun buttons() = capture { ButtonsSample() }

    @Test
    fun selection() = capture { SelectionSample() }

    @Test
    fun cards() = capture { CardsSample() }

    @Test
    fun navigation() = capture { NavigationSample() }

    @Test
    fun type() = capture { TypeSample() }

    @Test
    fun colors() = capture { ColorsSample() }

    @Test
    fun icons() = capture { IconsSample() }

    @Test
    fun placeholder() = capture {
        BoardPlaceholder(
            board = Board(id = "Main", title = R.string.designsystem_gallery_title, route = Unit),
            links = listOf(
                Board(id = "NewProject", title = R.string.designsystem_back, route = Unit),
                Board(id = "Pro", title = R.string.designsystem_close, route = Unit),
            ),
            onOpen = {},
            onBack = {},
        )
    }

    private fun capture(content: @Composable () -> Unit) {
        compose.setContent {
            ClipraTheme {
                Box(
                    modifier = Modifier
                        .width(390.dp)
                        .background(ClipraTheme.colors.background),
                ) {
                    content()
                }
            }
        }
        compose.onRoot().captureRoboImage()
    }
}
