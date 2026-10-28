package tech.idct.clipra.feature.export

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import tech.idct.clipra.core.model.export.ExportSettings
import java.util.Locale

/** Saved to Gallery (design/screens/phone/ExportDone.png). */
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
    )

    @Test
    fun saved() {
        Locale.setDefault(Locale.US)
        compose.setContent {
            ClipraTheme {
                SavedScreen(
                    saved,
                    showPro = true,
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
