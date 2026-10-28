package tech.idct.clipra.feature.home

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.data.project.ProjectSummary
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.Resolution
import java.util.TimeZone

/** Home with the design's four projects (design/screens/phone/Main.png). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class HomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_761_675_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private val projects = listOf(
        summary(
            "1",
            "Lisbon weekend",
            AspectRatio.Portrait9x16,
            Layout.Single,
            Resolution.FHD,
            42,
            now - 2 * hour,
        ),
        summary(
            "2",
            "Product teaser",
            AspectRatio.Landscape16x9,
            Layout.Single,
            Resolution.UHD,
            78,
            now - 1 * day,
        ),
        summary(
            "3",
            "Morning run",
            AspectRatio.Square1x1,
            Layout.Single,
            Resolution.HD,
            25,
            now - 3 * day,
        ),
        summary(
            "4",
            "Unboxing split-screen",
            AspectRatio.Landscape16x9,
            Layout.Grid2x2,
            Resolution.QHD,
            125,
            now - 9 * day,
        ),
    )

    private val zone = TimeZone.getDefault()

    // "Edited … ago" depends on the local day.
    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    @Test
    fun home() = capture(projects)

    @Test
    fun homeWithoutProjects() = capture(emptyList())

    private fun capture(recent: List<ProjectSummary>) {
        compose.setContent {
            ClipraTheme {
                HomeScreen(
                    recent = recent,
                    nowMillis = now,
                    aiStatus = null,
                    actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}),
                    projectActions = ProjectActions({}, {}, {}),
                )
            }
        }
        compose.onRoot().captureRoboImage(
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
            ),
        )
    }

    private fun summary(
        id: String,
        name: String,
        ratio: AspectRatio,
        layout: Layout,
        resolution: Resolution,
        seconds: Long,
        updated: Long,
    ) = ProjectSummary(id, name, ratio, layout, resolution, seconds * 1_000_000, updated, null)
}
