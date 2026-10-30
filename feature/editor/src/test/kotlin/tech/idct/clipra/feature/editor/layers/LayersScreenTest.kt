package tech.idct.clipra.feature.editor.layers

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.model.BlendMode
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Fit
import tech.idct.clipra.core.model.Keyframed
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.feature.editor.LayerActions
import java.util.Locale

/** The Layers board with the design's stack: alfama.mp4 on L4 (design/screens/phone/Layers.png). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class LayersScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sec = 1_000_000L

    // Scaled into the upper right, as in the design.
    private val alfama = Clip(
        id = "alfama",
        type = ClipType.Video,
        startUs = 5 * sec,
        durationUs = 3_600_000,
        source = MediaRef("alfama", 0, 3_600_000),
        transform = Keyframed(Transform(x = 0.19f, y = -0.13f, scale = 0.42f, fit = Fit.Fit)),
        opacity = Keyframed(0.85f),
        blend = BlendMode.Screen,
    )

    private fun clip(id: String, type: ClipType, start: Double, length: Double) = Clip(
        id = id,
        type = type,
        startUs = (start * sec).toLong(),
        durationUs = (length * sec).toLong(),
        source = MediaRef(id, 0, (length * sec).toLong()),
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(
            MediaAsset("guitar", "content://1", MediaKind.Audio, "lisbon-guitar.mp3"),
            MediaAsset("voice", "content://2", MediaKind.Audio, "voiceover.m4a"),
            MediaAsset("sunset", "content://3", MediaKind.Video, "sunset.mp4"),
            MediaAsset(
                "alfama",
                "content://4",
                MediaKind.Video,
                "alfama.mp4",
                width = 800,
                height = 1100,
                hasAudio = true,
            ),
        ),
        layers = listOf(
            Layer("L1", clips = listOf(clip("guitar", ClipType.Audio, 0.0, 42.0))),
            Layer("L2", clips = listOf(clip("voice", ClipType.Audio, 12.1, 10.0))),
            Layer("L3", clips = listOf(clip("sunset", ClipType.Video, 0.0, 9.2))),
            Layer("L4", clips = listOf(alfama)),
            Layer(
                "L5",
                clips = listOf(
                    clip("day-one", ClipType.Text, 6.0, 3.0).copy(
                        source = null,
                        text = TextSpec("Day one"),
                    ),
                ),
            ),
            Layer(
                "L6",
                clips = listOf(
                    clip("cap", ClipType.Caption, 6.0, 2.8).copy(
                        source = null,
                        text = TextSpec("We finally made it to Lisbon"),
                    ),
                ),
            ),
        ),
    )

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private val committed = mutableListOf<Transform>()
    private val blends = mutableListOf<BlendMode>()

    private fun show(tab: LayerTab, cropping: Boolean = false, locale: Locale = Locale.US) {
        Locale.setDefault(locale)
        compose.setContent {
            ClipraTheme {
                LayersScreen(
                    state = LayersUiState(
                        project = project,
                        clip = alfama,
                        playheadUs = 7_120_000,
                        tab = tab,
                        cropping = cropping,
                    ),
                    actions = LayersActions(
                        onDone = {},
                        onTab = {},
                        onCrop = {},
                        onTransformLive = {},
                        onTransform = { committed += it },
                        onBlend = { blends += it },
                        onOpacityLive = {},
                        onOpacityDone = {},
                        onSelectLayer = {},
                        layers = LayerActions({ _, _ ->
                        }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}),
                    ),
                    preview = {},
                )
            }
        }
    }

    @Test
    fun blendTab() {
        show(LayerTab.Blend)
        compose.onNodeWithText("alfama.mp4 · layer L4").assertExists()
        compose.onNodeWithText("Video + audio · 00:05.00 - 00:08.60").assertExists()
        compose.onNodeWithTag("blend:Screen").assertIsSelected()
        compose.onNodeWithTag("blend-chip").assertExists()
        compose.onNodeWithText("Screen · 85%").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
        compose.onNodeWithTag("blend:Multiply").performClick()
        assertEquals(listOf(BlendMode.Multiply), blends)
    }

    @Test
    fun transformTab() {
        show(LayerTab.Transform)
        // 0.19 of 1080 px and -0.13 of 1920 px from the centre.
        compose.onNodeWithText("205").assertExists()
        compose.onNodeWithText("-250").assertExists()
        compose.onNodeWithText("42").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("transform-flip").performClick()
        assertTrue(committed.last().flipHorizontal)
        compose.onNodeWithTag("transform-fill").performClick()
        assertEquals(Transform(fit = Fit.Fill), committed.last())
    }

    /** The design state in Polish, to see that every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun transformTabInPolish() {
        show(LayerTab.Transform, locale = Locale.forLanguageTag("pl-PL"))
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun typingAScaleCommitsIt() {
        show(LayerTab.Transform)
        val field = compose.onNodeWithTag("transform-scale")
        // The text field inside the labelled row.
        compose.onNodeWithText("42").performClick()
        compose.onNodeWithText("42").performTextReplacement("150")
        compose.onNodeWithText("150").performImeAction()
        field.assertExists()
        assertEquals(1.5f, committed.last().scale, 1e-4f)
    }

    @Test
    fun layersTab() {
        show(LayerTab.Layers)
        compose.onNodeWithTag("layer:L4").assertExists()
        compose.onNodeWithText("Empty here").assertExists()
        compose.onNodeWithText("Sound from 12.1 s").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun cropModeShowsTheHint() {
        show(LayerTab.Transform, cropping = true)
        compose.onNodeWithText("Drag the corners to crop").assertExists()
        compose.onNodeWithTag("transform-crop").assertIsSelected()
    }
}
