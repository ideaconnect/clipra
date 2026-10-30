package tech.idct.clipra.feature.editor.speed

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Speed
import tech.idct.clipra.core.model.SpeedCurve
import java.util.Locale

/** The Speed board (#22) on sunset.mp4, as the tablet's Speed tab shows it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SpeedScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sunset = Clip(
        id = "sunset",
        type = ClipType.Video,
        startUs = 0,
        durationUs = 9_200_000,
        source = MediaRef("sunset", 0, 9_200_000),
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(MediaAsset("sunset", "content://1", MediaKind.Video, "sunset.mp4")),
        layers = listOf(Layer("L1"), Layer("L2"), Layer("L3", clips = listOf(sunset))),
    )

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private val speeds = mutableListOf<Speed>()

    private fun show(speed: Speed = Speed(), locale: Locale = Locale.US) {
        Locale.setDefault(locale)
        compose.setContent {
            ClipraTheme {
                SpeedScreen(
                    project = project,
                    clip = sunset.copy(speed = speed),
                    actions = SpeedActions(onDone = {}, onSpeed = { speeds += it }),
                    preview = {},
                )
            }
        }
    }

    @Test
    fun constantSpeeds() {
        show()
        compose.onNodeWithText("sunset.mp4 · speed").assertExists()
        compose.onNodeWithText("Pitch is kept when speed changes.").assertExists()
        compose.onNodeWithText("1×").assertIsSelected()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithText("2×").performClick()
        compose.onNodeWithTag("ramp:hero").performClick()
        compose.onNodeWithTag("speed-reverse").performClick()
        assertEquals(
            listOf(Speed(rate = 2.0), Speed(ramp = "hero"), Speed(reverse = true)),
            speeds,
        )
    }

    @Test
    fun customRamp() {
        show(Speed(ramp = SpeedCurve.CUSTOM, reverse = true))
        compose.onNodeWithTag("ramp:custom").assertIsSelected()
        compose.onNodeWithTag("speed-custom").assertExists()
        // In the constant row and on the middle speed.
        compose.onAllNodesWithText("0.5×").assertCountEquals(2)
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    /** Every label fits in Polish (#76), with its decimal comma. */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun customRampInPolish() {
        show(Speed(ramp = SpeedCurve.CUSTOM), locale = Locale.forLanguageTag("pl-PL"))
        compose.onNodeWithText("0,25×").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
