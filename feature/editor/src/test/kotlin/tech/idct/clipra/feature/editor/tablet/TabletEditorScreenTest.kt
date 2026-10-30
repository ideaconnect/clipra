package tech.idct.clipra.feature.editor.tablet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.ColorGrade
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Resolution
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.feature.editor.EditorCallbacks
import tech.idct.clipra.feature.editor.EditorUiState
import tech.idct.clipra.feature.editor.timeline.LayerHeaderActions
import tech.idct.clipra.feature.editor.timeline.TimelineActions
import java.util.Locale

/** The tablet editor (#69) with the design's Lisbon weekend (design/screens/tablet). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class TabletEditorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sec = 1_000_000L

    private fun media(id: String, name: String, kind: MediaKind, seconds: Long = 60) = MediaAsset(
        id,
        "content://$id",
        kind,
        name,
        durationUs = seconds * sec,
        width = 1920,
        height = 1080,
        hasAudio = kind != MediaKind.Photo,
    )

    private fun clip(id: String, type: ClipType, start: Double, length: Double, media: String?) =
        Clip(
            id = id,
            type = type,
            startUs = (start * sec).toLong(),
            durationUs = (length * sec).toLong(),
            source = media?.let { MediaRef(it, 0, (length * sec).toLong()) },
        )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        format = Format(ratio = AspectRatio.Landscape16x9, resolution = Resolution.UHD),
        media = listOf(
            media("sunset", "sunset.mp4", MediaKind.Video, 9),
            media("tram", "tram.mp4", MediaKind.Video, 6),
            media("sintra", "sintra.mp4", MediaKind.Video, 12),
            media("stamp", "stamp.png", MediaKind.Photo),
            media("alfama", "alfama.mp4", MediaKind.Video, 21),
            media("voice", "voiceover.m4a", MediaKind.Audio, 9).copy(recorded = true),
            media("guitar", "lisbon-guitar.mp3", MediaKind.Audio, 42),
        ),
        layers = listOf(
            Layer("L1", clips = listOf(clip("guitar", ClipType.Audio, 0.0, 42.0, "guitar"))),
            Layer("L2", clips = listOf(clip("voice", ClipType.Audio, 16.0, 9.0, "voice"))),
            Layer(
                "L3",
                clips = listOf(
                    clip("sunset", ClipType.Video, 0.0, 9.2, "sunset").copy(
                        effects = listOf(Effect("cinematic"), Effect("mosaic")),
                    ),
                    clip("tram", ClipType.Video, 9.2, 4.0, "tram"),
                    clip("sintra", ClipType.Video, 18.0, 7.0, "sintra"),
                ),
            ),
            Layer(
                "L4",
                clips = listOf(
                    clip("stamp", ClipType.Photo, 1.0, 3.5, "stamp"),
                    clip("alfama", ClipType.Video, 5.0, 3.5, "alfama"),
                ),
            ),
            Layer(
                "L5",
                clips = listOf(
                    clip("day-one", ClipType.Text, 6.0, 3.0, null).copy(text = TextSpec("Day one")),
                ),
            ),
            Layer(
                "L6",
                clips = listOf(
                    clip("cap", ClipType.Caption, 6.0, 3.5, null)
                        .copy(text = TextSpec("We finally made it to Lisbon")),
                ),
            ),
        ),
    )

    private val calls = mutableListOf<String>()

    private val callbacks = EditorCallbacks(
        onClose = {}, onExport = { calls += "export" }, onTool = { calls += "tool:$it" },
        onPlayPause = {}, onUndo = {}, onRedo = {}, onPickVisual = { calls += "import" },
        onPickAudio = {}, onPickReplacement = {}, onSplit = { calls += "split" },
        onDuplicate = {}, onDelete = {}, onFreeze = {}, onExtract = { calls += "detach" },
        onLayers = {}, onCrop = {}, onDismissMessage = {},
        timeline = TimelineActions({}, {}, {}, { _, _, _ -> }, { _, _, _ -> }, {}),
    )

    private val actions = TabletEditorActions(
        onAddMedia = { calls += "add:$it" },
        onSeekCut = { calls += "cut:$it" },
        onSnap = { calls += "snap:$it" },
        onRipple = {},
        onAddLayer = {},
        onStabilize = { calls += "stabilize" },
        layers = LayerHeaderActions(
            onHidden = { id, hidden -> calls += "hidden:$id:$hidden" },
            onMuted = { _, _ -> },
            onLocked = { _, _ -> },
        ),
        inspector = InspectorActions(
            onTransform = { _, _ -> },
            onBlend = { _, _, _ -> },
            onColor = { _, grade -> calls += "color:${grade?.lut}" },
            onAutoColor = { calls += "auto" },
            onPasteColor = {},
            onAudio = { _, _ -> },
            onNormalize = { _, on -> calls += "normalize:$on" },
            onSpeed = { _, _ -> },
            onAnimation = { _, _ -> },
            effectName = { type -> type.replaceFirstChar { it.uppercase() } },
        ),
    )

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private var state by mutableStateOf(
        EditorUiState(
            project = project,
            loaded = true,
            selectedClipId = "sunset",
            playheadUs = 7_120_000,
            canUndo = true,
        ),
    )

    private fun show() {
        Locale.setDefault(Locale.US)
        compose.setContent {
            ClipraTheme {
                TabletEditorScreen(
                    state = state,
                    playing = false,
                    callbacks = callbacks,
                    actions = actions,
                    preview = {},
                )
            }
        }
    }

    @Test
    fun theEditorWithAClipSelected() {
        show()
        compose.onNodeWithText("Lisbon weekend").assertExists()
        compose.onNodeWithText("16:9 · 4K").assertExists()
        compose.onNodeWithTag("inspector:clip").assertExists()
        compose.onNodeWithText("Cinematic").assertExists()
        compose.onNodeWithTag("layer-header:L3").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("bin:tram.mp4").performClick()
        compose.onNodeWithTag("tool:Stabilize").performClick()
        compose.onNodeWithTag("snap").performClick()
        compose.onNodeWithTag("next-cut").performClick()
        compose.onNodeWithTag("layer-header:L6").assertExists()
        assertEquals(
            listOf("add:tram", "stabilize", "snap:false", "cut:true"),
            calls,
        )
    }

    @Test
    fun theColorTab() {
        state = state.copy(
            project = project.copy(
                layers = project.layers.map { layer ->
                    layer.copy(
                        clips = layer.clips.map {
                            if (it.id == "sunset") {
                                it.copy(color = ColorGrade(exposure = 10f, contrast = 18f))
                            } else {
                                it
                            }
                        },
                    )
                },
            ),
        )
        show()
        compose.onNodeWithText("Color").performClick()
        compose.onNodeWithText("Grade this clip").assertExists()
        compose.onNodeWithText("+18").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithText("Warm film").performClick()
        compose.onNodeWithTag("color-auto").performClick()
        assertEquals(listOf("color:builtin:warm_film", "auto"), calls)
    }

    @Test
    fun theAudioTab() {
        show()
        compose.onNode(hasText("Audio") and hasAnyAncestor(hasTestTag("inspector-tabs")))
            .performClick()
        compose.onNodeWithTag("audio-volume").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("audio-normalize").performClick()
        compose.onNodeWithTag("audio-detach").performClick()
        compose.onNodeWithTag("audio-cleanup").performClick()
        assertEquals(listOf("normalize:true", "detach", "tool:Audio"), calls)
    }

    @Test
    fun nothingSelected() {
        state = state.copy(selectedClipId = null)
        show()
        compose.onNodeWithText("Select a clip on the timeline to change it here.").assertExists()
        compose.onNodeWithTag("tool:Split").assertIsNotEnabled()
        compose.onNodeWithText("Gallery").performClick()
        compose.onNodeWithTag("open-gallery").performClick()
        assertEquals(listOf("import"), calls)
    }

    /** Every label fits in Polish (#76). */
    @Test
    @Config(qualifiers = "pl-w1280dp-h800dp-land-mdpi")
    fun theEditorInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        compose.setContent {
            ClipraTheme {
                TabletEditorScreen(
                    state = state,
                    playing = false,
                    callbacks = callbacks,
                    actions = actions,
                    preview = {},
                )
            }
        }
        // The rail and the media bin.
        compose.onAllNodesWithText("Multimedia").assertCountEquals(2)
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
