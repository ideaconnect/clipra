package tech.idct.clipra.feature.editor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
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
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.feature.editor.timeline.TimelineActions
import tech.idct.clipra.feature.editor.voiceover.VoiceoverActions
import java.util.Locale
import kotlin.math.roundToInt

/** The phone editor with the design's layer stack (design/screens/phone/Editor.png). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class EditorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sec = 1_000_000L

    private fun media(id: String, name: String, kind: MediaKind) = MediaAsset(
        id,
        "content://$id",
        kind,
        name,
        durationUs = 60 * sec,
        width = 1080,
        height = 1920,
        hasAudio = kind == MediaKind.Video,
    )

    private fun clip(
        id: String,
        type: ClipType,
        start: Double,
        length: Double,
        media: String? = null,
    ) = Clip(
        id = id,
        type = type,
        startUs = (start * sec).toLong(),
        durationUs = (length * sec).toLong(),
        source = media?.let { MediaRef(it, 0, (length * sec).toLong()) },
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(
            media("guitar", "lisbon-guitar.mp3", MediaKind.Audio),
            // Recorded in Clipra (#41), so in the voice colours.
            media("voice", "voiceover.m4a", MediaKind.Audio).copy(recorded = true),
            media("sunset", "sunset.mp4", MediaKind.Video),
            media("tram", "tram.mp4", MediaKind.Video),
            media("stamp", "stamp.png", MediaKind.Photo),
            media("alfama", "alfama.mp4", MediaKind.Video),
        ),
        layers = listOf(
            Layer("L1", clips = listOf(clip("guitar", ClipType.Audio, 0.0, 42.0, "guitar"))),
            Layer("L2", clips = listOf(clip("voice", ClipType.Audio, 16.0, 10.0, "voice"))),
            Layer(
                "L3",
                clips = listOf(
                    clip("sunset", ClipType.Video, 0.0, 9.2, "sunset"),
                    clip("tram", ClipType.Video, 9.2, 4.0, "tram"),
                ),
            ),
            Layer(
                "L4",
                clips = listOf(
                    clip("stamp", ClipType.Photo, 0.0, 3.5, "stamp"),
                    clip("alfama", ClipType.Video, 5.0, 3.5, "alfama"),
                ),
            ),
            Layer(
                "L5",
                clips = listOf(
                    clip("day-one", ClipType.Text, 6.0, 3.0).copy(text = TextSpec("Day one")),
                ),
            ),
            Layer(
                "L6",
                clips = listOf(
                    clip(
                        "cap",
                        ClipType.Caption,
                        6.0,
                        3.5,
                    ).copy(text = TextSpec("We finally made it")),
                ),
            ),
        ),
    )

    private val noop = EditorCallbacks(
        onClose = {}, onExport = {}, onTool = {}, onPlayPause = {}, onUndo = {}, onRedo = {},
        onPickVisual = {}, onPickAudio = {}, onPickReplacement = {}, onSplit = {}, onDuplicate = {},
        onDelete = {
        }, onFreeze = {}, onExtract = {}, onLayers = {}, onCrop = {}, onDismissMessage = {},
        timeline = TimelineActions({}, {}, {}, { _, _, _ -> }, { _, _, _ -> }, {}),
    )

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    /** With the system font at 200% (#75), Export, play and the clip tools stay on screen. */
    @Test
    fun editorWithLargeFonts() {
        Locale.setDefault(Locale.US)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                ClipraTheme {
                    EditorScreen(
                        state = EditorUiState(
                            project = project,
                            loaded = true,
                            selectedClipId = "sunset",
                            playheadUs = 7_120_000,
                        ),
                        playing = false,
                        callbacks = noop,
                        preview = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("export").assertIsDisplayed()
        compose.onNodeWithTag("play").assertIsDisplayed()
        compose.onNodeWithTag("clip-tools").assertIsDisplayed()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun editorWithASelectedClip() {
        Locale.setDefault(Locale.US)
        compose.setContent {
            ClipraTheme {
                EditorScreen(
                    state = EditorUiState(
                        project = project,
                        loaded = true,
                        canUndo = true,
                        selectedClipId = "sunset",
                        playheadUs = 7_120_000,
                    ),
                    playing = false,
                    callbacks = noop,
                    preview = {},
                )
            }
        }
        compose.onNodeWithTag("clip-tools").assertExists()
        compose.onNodeWithTag("undo").assertIsEnabled()
        compose.onNodeWithTag("redo").assertIsNotEnabled()
        compose.onNodeWithTag("timecode").assertExists()
        // TalkBack: type, name, duration and layer, then "selected" from the state.
        compose.onNodeWithContentDescription("Video sunset.mp4, 9.2s, layer L3")
            .assertIsSelected()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    /** The design state in Polish, to see that every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun editorWithASelectedClipInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        compose.setContent {
            ClipraTheme {
                EditorScreen(
                    state = EditorUiState(
                        project = project,
                        loaded = true,
                        canUndo = true,
                        selectedClipId = "sunset",
                        playheadUs = 7_120_000,
                    ),
                    playing = false,
                    callbacks = noop,
                    preview = {},
                )
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun selectingAClipSwapsTheTools() {
        var selected by mutableStateOf<String?>(null)
        compose.setContent {
            ClipraTheme {
                EditorScreen(
                    state = EditorUiState(
                        project = project,
                        loaded = true,
                        selectedClipId = selected,
                    ),
                    playing = false,
                    callbacks = EditorCallbacks(
                        onClose = {}, onExport = {}, onTool = {}, onPlayPause = {}, onUndo = {},
                        onRedo = {}, onPickVisual = {}, onPickAudio = {}, onPickReplacement = {},
                        onSplit = {
                        }, onDuplicate = {}, onDelete = {}, onFreeze = {}, onExtract = {},
                        onLayers = {}, onCrop = {}, onDismissMessage = {},
                        timeline = TimelineActions({
                        }, {}, { selected = it }, { _, _, _ -> }, { _, _, _ -> }, {}),
                    ),
                    preview = {},
                )
            }
        }
        compose.onNodeWithTag("main-tools").assertExists()
        compose.onNodeWithTag("clip:sunset").performClick()
        assertEquals("sunset", selected)
        compose.onNodeWithTag("clip-tools").assertExists()
    }

    private fun showVoiceover(panel: VoiceoverState, actions: VoiceoverActions? = null) {
        compose.setContent {
            ClipraTheme {
                EditorScreen(
                    state = EditorUiState(
                        project = project,
                        loaded = true,
                        playheadUs = 16_000_000,
                        voiceover = panel,
                    ),
                    playing = panel.recording,
                    callbacks = actions?.let {
                        EditorCallbacks(
                            onClose = {}, onExport = {}, onTool = {}, onPlayPause = {},
                            onUndo = {}, onRedo = {}, onPickVisual = {}, onPickAudio = {},
                            onPickReplacement = {}, onSplit = {}, onDuplicate = {},
                            onDelete = {}, onFreeze = {}, onExtract = {}, onLayers = {},
                            onCrop = {}, onDismissMessage = {},
                            timeline = noop.timeline,
                            voiceover = it,
                        )
                    } ?: noop,
                    preview = {},
                )
            }
        }
    }

    /** A meter's worth of speech-like levels, in dBFS. */
    private val levels = List(40) { -42f + 30f * kotlin.math.abs(kotlin.math.sin(it * 0.45f)) }

    /** Voiceover (#41): Record voiceover from the Add menu opens the panel in place of the tools. */
    @Test
    fun addRecordsAVoiceover() {
        var opened = 0
        compose.setContent {
            ClipraTheme {
                EditorScreen(
                    state = EditorUiState(project = project, loaded = true),
                    playing = false,
                    callbacks = EditorCallbacks(
                        onClose = {}, onExport = {}, onTool = {}, onPlayPause = {}, onUndo = {},
                        onRedo = {}, onPickVisual = {}, onPickAudio = {}, onPickReplacement = {},
                        onSplit = {}, onDuplicate = {}, onDelete = {}, onFreeze = {},
                        onExtract = {}, onLayers = {}, onCrop = {}, onDismissMessage = {},
                        timeline = noop.timeline,
                        onVoiceover = { opened++ },
                    ),
                    preview = {},
                )
            }
        }
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithTag("add-voiceover").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun voiceoverReady() {
        Locale.setDefault(Locale.US)
        var recorded = 0
        var closed = 0
        showVoiceover(
            VoiceoverState(),
            VoiceoverActions(onRecord = { recorded++ }, onStop = {}, onDiscard = {}, onClose = {
                closed++
            }),
        )
        compose.onNodeWithTag("main-tools").assertDoesNotExist()
        compose.onNodeWithText(
            "Recording lands on the first free layer at the playhead. Cleanup runs offline " +
                "while you keep editing.",
        ).assertExists()
        // TalkBack calls the recorded voiceover by what it is.
        compose.onNodeWithContentDescription("Voiceover voiceover.m4a, 10.0s, layer L2")
            .assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
        compose.onNodeWithTag("voiceover-record").performClick()
        compose.onNodeWithTag("voiceover-close").performClick()
        assertEquals(1, recorded)
        assertEquals(1, closed)
    }

    @Test
    fun voiceoverRecording() {
        Locale.setDefault(Locale.US)
        var stopped = 0
        var discarded = 0
        showVoiceover(
            VoiceoverState(recording = true, elapsedUs = 4_200_000, levels = levels),
            VoiceoverActions(
                onRecord = {},
                onStop = { stopped++ },
                onDiscard = { discarded++ },
                onClose = {},
            ),
        )
        compose.onNodeWithTag("voiceover-time").assertTextEquals("00:04.20")
        compose.onNodeWithContentDescription("Level ${levels.last().roundToInt()} dB")
            .assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
        compose.onNodeWithTag("voiceover-stop").performClick()
        compose.onNodeWithTag("voiceover-discard").performClick()
        assertEquals(1, stopped)
        assertEquals(1, discarded)
    }

    /** Every voiceover label fits in Polish (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun voiceoverRecordingInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        showVoiceover(VoiceoverState(recording = true, elapsedUs = 4_200_000, levels = levels))
        compose.onNodeWithText("Zatrzymaj").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun voiceoverReadyInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        showVoiceover(VoiceoverState())
        compose.onNodeWithText("Nagraj lektora").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
