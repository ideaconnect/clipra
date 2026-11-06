package tech.idct.clipra.feature.export

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.export.LeftOutClip
import java.util.Locale

/**
 * The question before an export that would leave clips out (#212), with the issue's three: a
 * video whose original is gone (only its proxy is left), the photo overlay and the music.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class LeftOutDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private val clips = listOf(
        LeftOutClip("music", ClipType.Audio, 3, 0, 50_000_000, "fado", "fado-night.mp3"),
        LeftOutClip("stamp", ClipType.Photo, 2, 6_000_000, 11_500_000, "stamp", "stamp.png"),
        LeftOutClip("alfama", ClipType.Video, 1, 19_900_000, 23_500_000, "alfama", "alfama.mp4"),
    )

    private var answers = mutableListOf<String>()

    private fun show(find: Boolean = true) {
        compose.setContent {
            ClipraTheme {
                LeftOutDialog(
                    clips = clips,
                    onExportAnyway = { answers += "export" },
                    onCancel = { answers += "cancel" },
                    onFind = if (find) ({ answers += "find" }) else null,
                )
            }
        }
    }

    @Test
    fun eachClipSaysWhatWhereAndWhen() {
        Locale.setDefault(Locale.US)
        show()

        compose.onNodeWithText("Some media is missing").assertIsDisplayed()
        compose.onNodeWithText("Sound · L3 · 0:00-0:50").assertIsDisplayed()
        compose.onNodeWithText("Photo · L2 · 0:06-0:12").assertIsDisplayed()
        compose.onNodeWithText("Video · L1 · 0:19-0:24").assertIsDisplayed()
        compose.onNodeWithText("alfama.mp4").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("left-out-find").performClick()
        compose.onNodeWithTag("left-out-cancel").performClick()
        compose.onNodeWithTag("left-out-export").performClick()
        assertEquals(listOf("find", "cancel", "export"), answers)
    }

    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun eachClipSaysWhatWhereAndWhenInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        show()

        compose.onNodeWithText("Brakuje plików").assertIsDisplayed()
        compose.onNodeWithText("Dźwięk · L3 · 0:00-0:50").assertIsDisplayed()
        compose.onNodeWithText("Eksportuj mimo to").assertIsDisplayed()
        compose.onNode(isDialog()).captureRoboImage(roborazziOptions = options)
    }

    /** Without an editor to go back to, there is no Find files. */
    @Test
    fun findFilesOnlyWhereTheEditorCanFindThem() {
        show(find = false)

        compose.onNodeWithTag("left-out-find").assertDoesNotExist()
        compose.onNodeWithTag("left-out-cancel").assertIsDisplayed()
    }
}
