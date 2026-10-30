package tech.idct.clipra.feature.captions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
import tech.idct.clipra.core.model.CaptionPosition
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.LinkGroup
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transcript
import tech.idct.clipra.core.model.Word
import tech.idct.clipra.core.models.ModelCatalog
import java.util.Locale

/** The CaptionsEdit board (design/screens/phone/CaptionsEdit.png) with the design's captions. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class CaptionsEditScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

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
        layers = listOf(Layer("L1"), Layer("L2", clips = captions)),
        links = listOf(LinkGroup("g", listOf("src") + captions.map { it.id })),
        transcripts = listOf(
            Transcript("src", "en", model = ModelCatalog.whisperBase.id, detected = true),
        ),
    )

    @Test
    fun captionsAsDesigned() {
        Locale.setDefault(Locale.US)
        val styles = mutableListOf<CaptionStyle>()
        val positions = mutableListOf<CaptionPosition>()
        val seeks = mutableListOf<Long>()
        compose.setContent {
            ClipraTheme {
                CaptionsEditScreen(
                    state = CaptionsEditUiState(project, loaded = true, playheadUs = 8_300_000),
                    scale = null,
                    actions = CaptionsEditActions(
                        onDone = {},
                        onStyle = { styles += it },
                        onPosition = { positions += it },
                        onScale = {},
                        onScaleDone = {},
                        onSeek = { seeks += it },
                        onEditByText = {},
                    ),
                    preview = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(ClipraTheme.colors.surfaceRaised),
                        )
                    },
                )
            }
        }
        compose.onNodeWithText("Whisper Base · English (detected)").assertExists()
        compose.onNodeWithText("5 captions").assertExists()
        compose.onNodeWithText("2 words to check").assertExists()
        compose.onNodeWithText("22 pt").assertExists()
        compose.onNodeWithText("00:06.0 → 00:08.8").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("style:Karaoke").performClick()
        compose.onNodeWithText("Top").performClick()
        compose.onNodeWithTag("caption:c3").performClick()
        assertEquals(listOf(CaptionStyle.Karaoke), styles)
        assertEquals(listOf(CaptionPosition.Top), positions)
        assertEquals(listOf(12_100_000L), seeks)
    }
}
