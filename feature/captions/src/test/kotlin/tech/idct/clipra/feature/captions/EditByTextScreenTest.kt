package tech.idct.clipra.feature.captions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Token
import tech.idct.clipra.core.model.Transcript
import tech.idct.clipra.core.model.Word
import tech.idct.clipra.core.model.WordKind
import java.util.Locale
import kotlin.math.roundToLong

/** The Transcript board (design/screens/phone/Transcript.png) with the design's speech. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class EditByTextScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private fun w(text: String, at: Double, d: Double, filler: Boolean = false) = Word(
        text,
        (at * 1e6).roundToLong(),
        ((at + d) * 1e6).roundToLong(),
        kind = if (filler) WordKind.Filler else WordKind.Word,
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(
            MediaAsset("v", "content://1", MediaKind.Video, "sunset.mp4", hasAudio = true),
            MediaAsset("a", "content://2", MediaKind.Audio, "voiceover.m4a", hasAudio = true),
        ),
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    Clip("sunset", ClipType.Video, 0, 42_000_000, MediaRef("v", 0, 42_000_000)),
                ),
            ),
            Layer(
                "L2",
                clips = listOf(
                    Clip(
                        "voice",
                        ClipType.Audio,
                        12_000_000,
                        10_000_000,
                        MediaRef("a", 12_000_000, 22_000_000),
                    ),
                ),
            ),
        ),
        transcripts = listOf(
            Transcript(
                "sunset",
                "en",
                listOf(
                    w("um", 5.5, 0.4, true), w("We", 6.0, 0.4), w("finally", 6.4, 0.5),
                    w("made", 6.9, 0.4), w("it", 7.3, 0.3), w("to", 7.6, 0.3),
                    w("Lisbon.", 7.9, 0.9), w("The", 9.4, 0.3), w("light", 9.7, 0.4),
                    w("here", 10.1, 0.3), w("is,", 10.4, 0.2), w("uh,", 10.6, 0.3, true),
                    w("unreal.", 10.9, 0.7),
                ),
            ),
            Transcript(
                "voice",
                "en",
                listOf(
                    w("Tomorrow", 12.1, 0.5), w("we’re", 12.6, 0.3), w("taking", 12.9, 0.4),
                    w(
                        "the",
                        13.3,
                        0.2,
                    ),
                    w("tram", 13.5, 0.4), w("up", 13.9, 0.3), w("to", 14.2, 0.2),
                    w("Alfama,", 14.4, 0.6), w("and", 15.6, 0.2), w("then", 15.8, 0.4),
                    w("um", 16.2, 0.4, true), w("the", 16.6, 0.2), w("train", 16.8, 0.4),
                    w("out", 17.2, 0.3), w("to", 17.5, 0.2), w("Sintra,", 17.7, 0.5),
                    w("if", 18.9, 0.2), w("the", 19.1, 0.2), w("weather", 19.3, 0.5),
                    w("holds,", 19.8, 0.6), w("you know.", 20.6, 0.4, true),
                ),
            ),
        ),
    )

    @Test
    fun editByTextAsDesigned() {
        Locale.setDefault(Locale.US)
        var state by mutableStateOf(
            EditByTextUiState(project, loaded = true, playheadUs = 7_900_000),
        )
        val tapped = mutableListOf<Token>()
        compose.setContent {
            ClipraTheme {
                EditByTextScreen(
                    state = state,
                    playing = false,
                    actions = EditByTextActions(
                        onBack = {},
                        onDone = {},
                        onFillers = {
                            state = state.copy(choices = state.choices.copy(fillers = it))
                        },
                        onPauses = {
                            state = state.copy(choices = state.choices.copy(pauses = it))
                        },
                        onThreshold = {},
                        onToken = { tapped += it },
                        onRestoreAll = {},
                        onPlay = {},
                        onAutoCaptions = {},
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
        compose.onNodeWithText("0:38.6").assertExists()
        compose.onNodeWithText("7 cuts · 3.4 s shorter").assertExists()
        compose.onNodeWithText("4 found · um, uh, you know").assertExists()
        compose.onNodeWithText("3 longer than 0.5 s").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithText("Lisbon.").performClick()
        assertEquals("Lisbon.", tapped.single().text)
        compose.onNodeWithTag("cut-fillers").performClick()
        compose.onNodeWithText("3 cuts · 1.9 s shorter").assertExists()
        compose.onNodeWithTag("cut-pauses").performClick()
        compose.onNodeWithText("No cuts yet").assertExists()
    }

    /** The design state in Polish, to see that every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun editByTextAsDesignedInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        var state by mutableStateOf(
            EditByTextUiState(project, loaded = true, playheadUs = 7_900_000),
        )
        val tapped = mutableListOf<Token>()
        compose.setContent {
            ClipraTheme {
                EditByTextScreen(
                    state = state,
                    playing = false,
                    actions = EditByTextActions(
                        onBack = {},
                        onDone = {},
                        onFillers = {
                            state = state.copy(choices = state.choices.copy(fillers = it))
                        },
                        onPauses = {
                            state = state.copy(choices = state.choices.copy(pauses = it))
                        },
                        onThreshold = {},
                        onToken = { tapped += it },
                        onRestoreAll = {},
                        onPlay = {},
                        onAutoCaptions = {},
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
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun withoutATranscriptItOffersAutoCaptions() {
        var opened = 0
        compose.setContent {
            ClipraTheme {
                EditByTextScreen(
                    state = EditByTextUiState(
                        project.copy(transcripts = emptyList()),
                        loaded = true,
                    ),
                    playing = false,
                    actions = EditByTextActions(
                        {}, {}, {}, {}, {}, {}, {}, {}, onAutoCaptions = { opened++ },
                    ),
                )
            }
        }
        compose.onNodeWithTag("text-auto-captions").performClick()
        assertEquals(1, opened)
    }
}
