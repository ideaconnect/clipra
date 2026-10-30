package tech.idct.clipra.feature.editor.animate

import androidx.compose.ui.test.assertIsSelected
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
import tech.idct.clipra.core.model.Animation
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec
import java.util.Locale

/** The Animate board (#33) on sunset.mp4, and on a text card. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class AnimateScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sunset = Clip(
        id = "sunset",
        type = ClipType.Video,
        startUs = 0,
        durationUs = 9_200_000,
        source = MediaRef("sunset", 0, 9_200_000),
        animation = Animation(enter = "zoom_in", loop = "ken_burns"),
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(MediaAsset("sunset", "content://1", MediaKind.Video, "sunset.mp4")),
        layers = listOf(Layer("L1", clips = listOf(sunset))),
    )

    private val picked = mutableListOf<Animation>()

    private fun show(clip: Clip, locale: Locale = Locale.US) {
        Locale.setDefault(locale)
        compose.setContent {
            ClipraTheme {
                AnimateScreen(
                    project = project.copy(layers = listOf(Layer("L1", clips = listOf(clip)))),
                    clip = clip,
                    actions = AnimateActions(onDone = {}, onAnimation = { picked += it }),
                    preview = {},
                )
            }
        }
    }

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    @Test
    fun aClipComesInGoesOutAndLoops() {
        show(sunset)
        compose.onNodeWithText("sunset.mp4 · animation").assertExists()
        compose.onNodeWithTag("animate-in:zoom_in").assertIsSelected()
        compose.onNodeWithTag("animate-out:none").assertIsSelected()
        compose.onNodeWithTag("animate-loop:ken_burns").assertIsSelected()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("animate-out:fade").performClick()
        assertEquals(Animation(enter = "zoom_in", exit = "fade", loop = "ken_burns"), picked.last())
        compose.onNodeWithTag("animate-in:none").performClick()
        assertEquals(Animation(loop = "ken_burns"), picked.last())
    }

    @Test
    fun textHasItsOwnAnimationsAndNoLoop() {
        val card = Clip(
            "title",
            ClipType.TextCard,
            0,
            3_000_000,
            text = TextSpec("Day two"),
            animation = Animation(enter = "typewriter"),
        )
        show(card)
        compose.onNodeWithTag("animate-in:typewriter").assertIsSelected()
        compose.onNodeWithTag("animate-loop").assertDoesNotExist()
    }

    /** Every label fits in Polish (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun aClipInPolish() {
        show(sunset, Locale.forLanguageTag("pl-PL"))
        compose.onNodeWithText("Przybliżenie").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
