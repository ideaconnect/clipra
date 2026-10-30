package tech.idct.clipra.feature.export

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.engine.export.ExportState
import tech.idct.clipra.core.model.export.ExportSettings
import java.util.Locale

/** Export finished (design/screens/states/ExportProgress--finished.png), before Ad 2. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class ExportProgressScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val done = ExportState.Done(
        projectId = "lisbon",
        uri = "content://media/external/video/media/42",
        fileName = "lisbon-weekend.mp4",
        sizeBytes = 64_300_000,
        durationUs = 42_000_000,
        settings = ExportSettings(),
        width = 1080,
        height = 1920,
    )

    @Test
    fun finishedBeforeAd2() {
        var seen = 0
        compose.setContent {
            ClipraTheme {
                ExportProgressScreen(
                    state = done,
                    onKeepEditing = {},
                    onPause = {},
                    onResume = {},
                    onCancel = {},
                    finished = true,
                    onSeeVideo = { seen++ },
                )
            }
        }
        compose.onNodeWithText("Export finished").assertExists()
        compose.onNodeWithText("Frame 1260 of 1260").assertExists()
        compose.onNodeWithText("A short ad plays first. Clipra Pro removes it.").assertExists()
        compose.onRoot().captureRoboImage(
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
            ),
        )
        compose.onNodeWithTag("see-video").performClick()
        assertEquals(1, seen)
    }

    /** The design state in Polish, to see that every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun finishedBeforeAd2InPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        var seen = 0
        compose.setContent {
            ClipraTheme {
                ExportProgressScreen(
                    state = done,
                    onKeepEditing = {},
                    onPause = {},
                    onResume = {},
                    onCancel = {},
                    finished = true,
                    onSeeVideo = { seen++ },
                )
            }
        }
        compose.onRoot().captureRoboImage(
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
            ),
        )
    }

    @Test
    fun withoutAd2ThereIsNoFinishedStep() {
        compose.setContent {
            ClipraTheme {
                ExportProgressScreen(
                    state = done,
                    onKeepEditing = {},
                    onPause = {},
                    onResume = {},
                    onCancel = {},
                    finished = false,
                )
            }
        }
        compose.onNodeWithTag("see-video").assertDoesNotExist()
    }
}
