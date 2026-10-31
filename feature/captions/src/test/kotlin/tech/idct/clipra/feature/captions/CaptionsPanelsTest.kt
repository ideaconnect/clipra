package tech.idct.clipra.feature.captions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Word
import java.util.Locale

/**
 * The Captions panels of the tablet editor (#70, design/screens/tablet/TabletCaptions.png): the
 * transcript on the left (300 dp) with the design's five captions, two words to check, the first
 * caption playing; and their look on the right (320 dp), Bold pop at the bottom.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class CaptionsPanelsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun caption(id: String, from: Double, to: Double, text: String, unsure: Set<String>) =
        Clip(
            id = id,
            type = ClipType.Caption,
            startUs = (from * 1e6).toLong(),
            durationUs = ((to - from) * 1e6).toLong(),
            caption = CaptionSpec(
                words = text.split(" ").mapIndexed { i, word ->
                    Word(
                        word,
                        i * 300_000L,
                        i * 300_000L + 250_000,
                        if (word in unsure) 0.3f else 0.95f,
                    )
                },
            ),
        )

    private val captions = listOf(
        caption("c1", 6.0, 8.8, "We finally made it to Lisbon", emptySet()),
        caption("c2", 9.4, 11.6, "The light here is unreal", emptySet()),
        caption("c3", 12.1, 15.0, "Tomorrow we’re taking the tram up to Alfama", setOf("Alfama")),
        caption("c4", 15.6, 18.2, "and then the train out to Sintra", setOf("Sintra")),
        caption("c5", 18.9, 21.0, "if the weather holds", emptySet()),
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        layers = listOf(Layer("L1"), Layer("L6", clips = captions)),
    )

    private val picked = mutableListOf<Any>()

    private val actions = CaptionsEditActions(
        onDone = {},
        onStyle = { picked += it },
        onPosition = { picked += it },
        onScale = {},
        onScaleDone = {},
        onSeek = { picked += it },
        onEditByText = { picked += "edit-by-text" },
        onImportSrt = { picked += "import" },
    )

    private fun show(state: CaptionsEditUiState) {
        compose.setContent {
            ClipraTheme {
                Row(
                    Modifier
                        .height(600.dp)
                        .background(ClipraTheme.colors.background),
                ) {
                    CaptionsTranscript(
                        state = state,
                        actions = actions,
                        onAutoCaptions = { picked += "auto" },
                        modifier = Modifier
                            .width(300.dp)
                            .fillMaxHeight(),
                    )
                    Spacer(Modifier.weight(1f))
                    CaptionsLookInspector(
                        state = state,
                        scale = null,
                        actions = actions,
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    @Test
    fun tabletCaptions() {
        Locale.setDefault(Locale.US)
        show(CaptionsEditUiState(project, loaded = true, playheadUs = 8_300_000))
        compose.onNodeWithText("5 captions · 2 words to check").assertExists()
        compose.onNodeWithTag("style:BoldPop").assertIsSelected()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("caption:c2").performClick()
        compose.onNodeWithTag("style:Karaoke").performClick()
        compose.onNodeWithTag("captions-edit-by-text").performClick()
        assertEquals(listOf<Any>(9_400_000L, CaptionStyle.Karaoke, "edit-by-text"), picked)
    }

    /** No captions yet: Auto captions and .srt import, and no look to change. */
    @Test
    fun noCaptionsYet() {
        Locale.setDefault(Locale.US)
        show(CaptionsEditUiState(project.copy(layers = listOf(Layer("L1"))), loaded = true))
        compose.onNodeWithTag("captions-auto").performClick()
        compose.onNodeWithTag("captions-import-srt").performClick()
        assertEquals(listOf<Any>("auto", "import"), picked)
        compose.onNodeWithTag("style:BoldPop").assertDoesNotExist()
    }

    /** The Polish board, to see every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w1280dp-h800dp-land-mdpi")
    fun tabletCaptionsInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        show(CaptionsEditUiState(project, loaded = true, playheadUs = 8_300_000))
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
