package tech.idct.clipra.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.data.project.ProjectSummary
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.Resolution
import java.util.Locale
import java.util.TimeZone

/** The tablet home (#71) with the design's projects (design/screens/tablet/TabletHome.png). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class TabletHomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_761_675_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun summary(
        id: String,
        name: String,
        ratio: AspectRatio,
        layout: Layout,
        resolution: Resolution,
        seconds: Long,
        updated: Long,
    ) = ProjectSummary(
        id,
        name,
        ratio,
        layout,
        resolution,
        seconds * 1_000_000,
        updated,
        pictures[id].orEmpty(),
    )

    /** The design's pictures, by project. */
    private val pictures = mapOf(
        "1" to listOf("sunset"),
        "2" to listOf("sunset"),
        "3" to listOf("teaser"),
        "4" to listOf("run"),
        "5" to DesignPictures.tiles,
        "6" to listOf("studio"),
        "7" to listOf("hills"),
        "8" to listOf("nata"),
    )

    private val projects = listOf(
        summary(
            "1",
            "Lisbon weekend",
            AspectRatio.Landscape16x9,
            Layout.Single,
            Resolution.UHD,
            42,
            now - 2 * hour,
        ),
        summary(
            "2",
            "Lisbon weekend · Shorts",
            AspectRatio.Portrait9x16,
            Layout.Single,
            Resolution.FHD,
            42,
            now - 3 * hour,
        ),
        summary(
            "3",
            "Product teaser",
            AspectRatio.Landscape16x9,
            Layout.Single,
            Resolution.UHD,
            78,
            now - day,
        ),
        summary(
            "4",
            "Morning run",
            AspectRatio.Square1x1,
            Layout.Single,
            Resolution.HD,
            25,
            now - 3 * day,
        ),
        summary(
            "5",
            "Unboxing split-screen",
            AspectRatio.Landscape16x9,
            Layout.Grid2x2,
            Resolution.QHD,
            125,
            now - 8 * day,
        ),
        summary(
            "6",
            "Podcast clip",
            AspectRatio.Portrait4x5,
            Layout.Single,
            Resolution.FHD,
            58,
            now - 9 * day,
        ),
        summary(
            "7",
            "Sintra from above",
            AspectRatio.Cinema21x9,
            Layout.Single,
            Resolution.UHD,
            92,
            now - 15 * day,
        ),
        summary(
            "8",
            "Pastel de nata reel",
            AspectRatio.Portrait9x16,
            Layout.Single,
            Resolution.FHD,
            33,
            now - 22 * day,
        ),
    )

    private val calls = mutableListOf<String>()

    private val zone = TimeZone.getDefault()

    // "Edited … ago" depends on the local day.
    @Before
    fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    private fun show(format: Format, pro: ProOffer, recent: List<ProjectSummary> = projects) {
        compose.setContent {
            ClipraTheme {
                CompositionLocalProvider(LocalProjectPictures provides DesignPictures) {
                    TabletHomeScreen(
                        recent = recent,
                        nowMillis = now,
                        aiStatus = null,
                        pro = pro,
                        actions = HomeActions(
                            onNewProject = {},
                            onOpenProject = { calls += "open:$it" },
                            onAutoCaptions = { calls += "captions" },
                            onTextCard = {},
                            onMosaic = { calls += "mosaic" },
                            onModels = { calls += "models" },
                            onSettings = {},
                            onPro = { calls += "pro" },
                        ),
                        projectActions = ProjectActions({}, {}, {}),
                        panel = NewProjectPanel(
                            format = format,
                            onFormatChange = { calls += "format:${it.ratio.label}" },
                            onCreate = { calls += "create" },
                            fourKSupported = true,
                            onTextCard = { calls += "text-card" },
                            onImport = { calls += "import" },
                        ),
                    )
                }
            }
        }
    }

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    @Test
    fun projectsBesideNewProject() {
        Locale.setDefault(Locale.US)
        show(
            Format(ratio = AspectRatio.Landscape16x9, resolution = Resolution.UHD),
            ProOffer(price = "PLN 10.00"),
        )
        compose.onNodeWithText("Go Pro · PLN 10.00").assertExists()
        compose.onNodeWithText("3840 × 2160").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithText("Import from Gallery").performClick()
        compose.onNodeWithText("Text card").performClick()
        compose.onNodeWithText("Mosaic layout").performClick()
        compose.onNodeWithTag("ratio:1:1").performClick()
        compose.onNodeWithTag("create-project").performClick()
        compose.onNodeWithTag("go-pro").performClick()
        assertEquals(
            listOf("import", "text-card", "mosaic", "format:1:1", "create", "pro"),
            calls,
        )
    }

    @Test
    fun searchFindsByName() {
        Locale.setDefault(Locale.US)
        show(Format(), ProOffer(owned = true))
        compose.onNodeWithText("Clipra Pro").assertExists()
        compose.onNodeWithTag("search").performTextInput("lisbon")
        compose.onAllNodesWithTag("project:1").assertCountEquals(1)
        compose.onAllNodesWithTag("project:3").assertCountEquals(0)
        compose.onNodeWithTag("search").performTextInput("xyz")
        compose.onNodeWithText("No project has that in its name.").assertExists()
    }

    /** Every label fits in Polish (#76), with the price as Google Play formats it there. */
    @Test
    @Config(qualifiers = "pl-w1280dp-h800dp-land-mdpi")
    fun theHomeInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        show(Format(layout = Layout.Split3), ProOffer(price = "10,00 zł"))
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
