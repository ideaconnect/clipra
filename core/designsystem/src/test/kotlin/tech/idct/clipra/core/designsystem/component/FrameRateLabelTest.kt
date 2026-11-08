package tech.idct.clipra.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme

/**
 * The preview's frame rate label (#227) over the corner of a picture, against the references
 * in src/test/screenshots (`ComponentScreenshotTest` says how to record them).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class FrameRateLabelTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    @Test
    fun overAPicture() = capture(60)

    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun overAPictureInPolish() = capture(24)

    private fun capture(fps: Int) {
        compose.setContent {
            ClipraTheme {
                // A light picture, where the label is hardest to read.
                Box(
                    modifier = Modifier
                        .size(width = 135.dp, height = 240.dp)
                        .background(ClipraTheme.colors.clip.photo),
                ) {
                    FrameRateLabel(fps, Modifier.align(Alignment.TopStart))
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
