package tech.idct.clipra.feature.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import tech.idct.clipra.core.model.Animation
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextTemplates
import java.util.Locale

/**
 * The Text panels of the tablet editor (#70, design/screens/tablet/TabletText.png): the library
 * on the left (300 dp, as the media bin) and the inspector on the right (320 dp), for "Day two"
 * on a black card with the Retro effect, typed in and coming in by Typewriter.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class TextPanelsTest {
    @get:Rule
    val compose = createComposeRule()

    private val card = TextTemplates.builtIn.first { it.id == "card" }.spec
        .copy(text = "DAY TWO", effect = "retro")

    private val clip = Clip(
        "day-two",
        ClipType.TextCard,
        12_000_000,
        3_000_000,
        text = card,
        animation = Animation(enter = "typewriter", exit = "fade"),
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        format = Format(ratio = AspectRatio.Landscape16x9),
        layers = listOf(Layer("L1"), Layer("L2"), Layer("L3", clips = listOf(clip))),
    )

    private val picked = mutableListOf<Any?>()

    private val library = TextLibraryActions(
        onAdd = { picked += "add" },
        onTemplate = { picked += it.id },
        onSave = { picked += "save" },
        onDelete = { picked += "delete:$it" },
        onEffect = { picked += it },
    )

    private fun show(selected: Clip?) {
        compose.setContent {
            ClipraTheme {
                Row(
                    Modifier
                        .height(600.dp)
                        .background(ClipraTheme.colors.background),
                ) {
                    TextLibrary(
                        spec = selected?.text,
                        templates = TextTemplates.builtIn,
                        actions = if (selected == null) {
                            TextLibraryActions(
                                library.onAdd,
                                library.onTemplate,
                                null,
                                library.onDelete,
                                library.onEffect,
                            )
                        } else {
                            library
                        },
                        modifier = Modifier
                            .width(300.dp)
                            .fillMaxHeight(),
                    )
                    Spacer(Modifier.weight(1f))
                    if (selected != null) {
                        TextInspector(
                            project = project,
                            clip = selected,
                            draft = selected.text!!.text,
                            actions = TextActions(
                                onBack = {},
                                onDraft = {},
                                onSpec = { picked += it },
                                onAnimation = { picked += it },
                            ),
                            modifier = Modifier
                                .width(320.dp)
                                .fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    @Test
    fun undoneWordsStayUndone() {
        val commits = mutableListOf<Pair<String, String>>()
        var words by mutableStateOf("Clipra")
        var selected by mutableStateOf(true)
        lateinit var draft: MutableState<String>
        compose.setContent {
            if (selected) draft = rememberWords("title", words) { id, it -> commits += id to it }
        }
        fun step(change: () -> Unit) {
            compose.runOnIdle(change)
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        // Typed, and committed once typing stops.
        step { draft.value = "Clipra demo" }
        assertEquals("title" to "Clipra demo", commits.last())
        step { words = "Clipra demo" }

        // Undo puts the old words back: the draft takes them, and leaving keeps the undo.
        step { words = "Clipra" }
        assertEquals("Clipra", draft.value)
        step { selected = false }
        assertEquals("title" to "Clipra", commits.last())
    }

    @Test
    fun tabletText() {
        Locale.setDefault(Locale.US)
        show(clip)
        compose.onNodeWithTag("template:card").assertIsSelected()
        compose.onNodeWithText("L3 · 3.0 s").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("template:title").performClick()
        compose.onNodeWithTag("add-text").performClick()
        assertEquals(listOf<Any?>("title", "add"), picked)
    }

    /** With no text selected, a template makes new text and there is no look to save. */
    @Test
    fun nothingSelected() {
        Locale.setDefault(Locale.US)
        show(null)
        compose.onNodeWithTag("save-template").assertDoesNotExist()
        compose.onNodeWithTag("template:lower_third").performClick()
        assertEquals(listOf<Any?>("lower_third"), picked)
        compose.onNodeWithText(
            "Select a text clip to change its look, or tap a template to add text in it.",
        ).assertExists()
    }

    /** The Polish board, to see every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w1280dp-h800dp-land-mdpi")
    fun tabletTextInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        show(clip)
        compose.onNodeWithText("L3 · 3,0 s").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
