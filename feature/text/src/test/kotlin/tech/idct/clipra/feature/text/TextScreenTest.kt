package tech.idct.clipra.feature.text

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextAlign
import tech.idct.clipra.core.model.TextColors
import tech.idct.clipra.core.model.TextFonts
import tech.idct.clipra.core.model.TextSpec
import java.util.Locale

/** The Text board: "Day two" on a black card (design/screens/phone/Text.png). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class TextScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val card = TextSpec(
        "Day two",
        font = TextFonts.BRICOLAGE,
        weight = 800,
        size = 0.08f,
        color = TextColors.WHITE,
        background = TextColors.BLACK,
    )

    private fun project(spec: TextSpec) = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        layers = listOf(
            Layer("L1"),
            Layer("L2"),
            Layer(
                "L3",
                clips = listOf(
                    Clip(
                        "title",
                        if (spec.background != null) ClipType.TextCard else ClipType.Text,
                        11_000_000,
                        3_000_000,
                        text = spec,
                    ),
                ),
            ),
        ),
    )

    private val specs = mutableListOf<TextSpec>()

    private fun show(spec: TextSpec, locale: Locale = Locale.US) {
        Locale.setDefault(locale)
        val project = project(spec)
        compose.setContent {
            ClipraTheme {
                TextScreen(
                    state = TextUiState(project, project.layers[2].clips[0], loaded = true),
                    draft = spec.text,
                    actions = TextActions(onBack = {}, onDraft = {}, onSpec = { specs += it }),
                    preview = {},
                )
            }
        }
    }

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    @Test
    fun textCard() {
        show(card)
        compose.onNodeWithText("Text card").assertExists()
        compose.onNodeWithText("Layer L3 · 00:11.00 - 00:14.00").assertExists()
        compose.onAllNodesWithContentDescription("Black")[0].assertIsSelected()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onAllNodesWithContentDescription("Yellow")[0].performClick()
        assertEquals(TextColors.YELLOW, specs.last().background)
        compose.onNodeWithTag("text-align").performClick()
        assertEquals(TextAlign.End, specs.last().align)
    }

    /** The 12 text effects (#32), drawn by the clip's renderer; Retro as in the design. */
    @Test
    fun textEffects() {
        show(card.copy(text = "Day two", effect = "retro"))
        compose.onNodeWithTag("effect:retro").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("text-effects").captureRoboImage(roborazziOptions = options)
        compose.onNodeWithTag("effect:neon").performClick()
        assertEquals("neon", specs.last().effect)
        compose.onNodeWithTag("effect:none").performClick()
        assertEquals(null, specs.last().effect)
    }

    /** The design state in Polish, to see that every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun textCardInPolish() {
        show(card, locale = Locale.forLanguageTag("pl-PL"))
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun textOverVideo() {
        show(card.copy(background = null))
        compose.onAllNodesWithText("Text")[0].assertExists()
        compose.onNodeWithContentDescription("No card, text over the video").assertIsSelected()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
