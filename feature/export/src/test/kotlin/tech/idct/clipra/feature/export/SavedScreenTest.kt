package tech.idct.clipra.feature.export

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.engine.export.ExportState
import tech.idct.clipra.core.engine.export.SavedExtra
import tech.idct.clipra.core.model.export.ExportExtra
import tech.idct.clipra.core.model.export.ExportSettings
import java.util.Locale

/** Saved to Gallery (design/screens/phone/ExportDone.png), with its cover and project. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SavedScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val saved = ExportState.Done(
        projectId = "lisbon",
        uri = "content://media/external/video/media/42",
        fileName = "lisbon-weekend.mp4",
        sizeBytes = 64_300_000,
        durationUs = 42_000_000,
        settings = ExportSettings(),
        width = 1080,
        height = 1920,
        extras = listOf(
            SavedExtra(
                ExportExtra.Cover,
                "lisbon-weekend.jpg",
                "content://media/external/images/1",
            ),
            SavedExtra(
                ExportExtra.Project,
                "lisbon-weekend.otio",
                "content://media/external/file/2",
            ),
        ),
        captionsBurnedIn = true,
    )

    @Test
    fun saved() {
        Locale.setDefault(Locale.US)
        compose.setContent {
            ClipraTheme {
                SavedScreen(
                    saved,
                    showPro = true,
                    proPrice = "10,00 zł",
                    onShare = {},
                    onOpenGallery = {},
                    onPro = {},
                    onBackToEditing = {},
                    preview = {},
                )
            }
        }
        compose.onNodeWithText("lisbon-weekend.jpg · cover").assertExists()
        compose.onNodeWithText("H.264 · captions burned in").assertExists()
        compose.onRoot().captureRoboImage(
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
            ),
        )
    }

    /** The design state in Polish, to see that every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun savedInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        compose.setContent {
            ClipraTheme {
                SavedScreen(
                    saved,
                    showPro = true,
                    proPrice = "10,00 zł",
                    onShare = {},
                    onOpenGallery = {},
                    onPro = {},
                    onBackToEditing = {},
                    preview = {},
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
    fun shareOpensTheSystemShareSheetWithTheVideo() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.setContent {
            ClipraTheme {
                SavedScreen(
                    saved,
                    showPro = false,
                    proPrice = null,
                    onShare = { shareVideo(app, Uri.parse(saved.uri)) },
                    onOpenGallery = {},
                    onPro = {},
                    onBackToEditing = {},
                    preview = {},
                )
            }
        }
        compose.onNodeWithTag("share").performClick()
        val chooser = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("video/mp4", send.type)
        @Suppress("DEPRECATION")
        assertEquals(Uri.parse(saved.uri), send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }
}
