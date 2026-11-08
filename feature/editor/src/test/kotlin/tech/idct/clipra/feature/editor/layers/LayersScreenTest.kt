package tech.idct.clipra.feature.editor.layers

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.model.BlendMode
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.Fit
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Keyframe
import tech.idct.clipra.core.model.Keyframed
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.feature.editor.LayerActions
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

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
    private lateinit var view: View

    private fun show(
        tab: LayerTab,
        cropping: Boolean = false,
        locale: Locale = Locale.US,
        project: Project = this.project,
    ) {
        Locale.setDefault(locale)
        compose.setContent {
            view = LocalView.current
            var shown by remember { mutableStateOf(tab) }
            ClipraTheme {
                LayersScreen(
                    state = LayersUiState(
                        project = project,
                        clip = alfama,
                        playheadUs = 7_120_000,
                        tab = shown,
                        cropping = cropping,
                    ),
                    actions = LayersActions(
                        onDone = {},
                        onTab = { shown = it },
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

    /**
     * The Galaxy S9 of #230 (360 × 740 dp): with the number keyboard open, the preview gives up
     * height and the position fields stay in view while they are typed in.
     */
    @Test
    @Config(qualifiers = "pl-w360dp-h740dp-xxhdpi")
    fun theKeyboardLeavesTheFieldsInView() {
        show(LayerTab.Transform, locale = Locale.forLanguageTag("pl-PL"))
        systemBars(keyboard = false)
        val previewHeight = compose.onNodeWithTag("layers-preview").fetchSemanticsNode()
            .size.height
        compose.onNodeWithContentDescription("Pozycja X").performClick()
        systemBars(keyboard = true)
        compose.onNodeWithContentDescription("Pozycja X").assertIsFocused()
        assertFullyShown("transform-x")
        assertFullyShown("transform-y")
        assertFullyShown("transform-scale")
        assertFullyShown("layer-tabs")
        val smaller = compose.onNodeWithTag("layers-preview").fetchSemanticsNode().size.height
        assertTrue("preview $smaller px, was $previewHeight px", smaller < previewHeight)
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    /**
     * #230: in a mosaic the cell picker pushes the fields down, so the keyboard covers the row
     * being typed in; the whole field scrolls back into view, label and unit too.
     */
    @Test
    @Config(qualifiers = "pl-w360dp-h740dp-xxhdpi")
    fun theFieldBeingTypedInScrollsIntoView() {
        val mosaic = project.copy(format = Format(layout = Layout.Grid2x2))
        show(LayerTab.Transform, locale = Locale.forLanguageTag("pl-PL"), project = mosaic)
        systemBars(keyboard = false)
        compose.onNodeWithTag("transform-cells").assertExists()
        assertFullyShown("transform-rotation")
        compose.onNodeWithContentDescription("Obrót").performClick()
        systemBars(keyboard = true)
        compose.onNodeWithContentDescription("Obrót").assertIsFocused()
        assertFullyShown("transform-rotation")
    }

    /** #230: a Blend tab scrolled down to the opacity opens Transform at its top. */
    @Test
    @Config(qualifiers = "pl-w360dp-h740dp-xxhdpi")
    fun eachTabKeepsItsOwnScrollPosition() {
        show(LayerTab.Blend, locale = Locale.forLanguageTag("pl-PL"))
        systemBars(keyboard = false)
        compose.onNodeWithTag("opacity").performScrollTo()
        assertFullyShown("opacity")
        // The tab really scrolled: its first row of modes went up out of view.
        assertFalse(isFullyShown("blend:Normal"))

        compose.onNodeWithText("Transformacja").performClick()
        assertFullyShown("transform-x")
        assertFullyShown("transform-y")

        // And Blend is still where it was left.
        compose.onNodeWithText("Mieszanie").performClick()
        assertFullyShown("opacity")
        assertFalse(isFullyShown("blend:Normal"))
    }

    /**
     * The S9's status and navigation bars (24 and 48 dp), and with [keyboard] its number
     * keyboard, about 250 dp above the navigation bar.
     */
    private fun systemBars(keyboard: Boolean) {
        compose.runOnIdle {
            val density = view.resources.displayMetrics.density
            fun px(dp: Int) = (dp * density).roundToInt()
            val ime = WindowInsetsCompat.Type.ime()
            val bottom = if (keyboard) px(48 + 250) else px(48)
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px(24), 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, px(48)))
                .setInsets(ime, Insets.of(0, 0, 0, if (keyboard) bottom else 0))
                .setVisible(ime, keyboard)
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
            keyboardTop = (view.height - bottom).toFloat()
        }
        compose.waitForIdle()
    }

    /** Where the keyboard, or without it the navigation bar, starts. */
    private var keyboardTop = Float.MAX_VALUE

    /** All of [tag] is on screen, none of it cut off by its scrolling tab or the keyboard. */
    private fun isFullyShown(tag: String): Boolean {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        return node.size.height > 0 &&
            abs(node.boundsInRoot.height - node.size.height) < 1f &&
            node.boundsInRoot.bottom <= keyboardTop
    }

    private fun assertFullyShown(tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        assertTrue(
            "$tag: ${node.size.height} px tall, shown at ${node.boundsInRoot}, " +
                "keyboard from $keyboardTop px",
            isFullyShown(tag),
        )
    }

    @Test
    fun onAKeyframeTheButtonDeletesItAndOffersTheEasing() {
        // alfama starts at 5 s; a keyframe at 2.12 s into it is at the playhead (7.12 s).
        val keyed = alfama.copy(
            transform = alfama.transform.copy(
                keyframes = listOf(Keyframe(2_120_000, alfama.transform.value, Easing.Linear)),
            ),
        )
        var toggled = 0
        val easings = mutableListOf<Easing>()
        compose.setContent {
            ClipraTheme {
                LayersScreen(
                    state = LayersUiState(
                        project = project,
                        clip = keyed,
                        playheadUs = 7_120_000,
                        tab = LayerTab.Transform,
                        cropping = false,
                    ),
                    actions = LayersActions(
                        onDone = {},
                        onTab = {},
                        onCrop = {},
                        onTransformLive = {},
                        onTransform = {},
                        onBlend = {},
                        onOpacityLive = {},
                        onOpacityDone = {},
                        onSelectLayer = {},
                        layers = LayerActions({ _, _ ->
                        }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}),
                        onKeyframe = { toggled++ },
                        onEasing = { easings += it },
                    ),
                    preview = {},
                )
            }
        }
        compose.onNodeWithText("Delete keyframe at 00:07.12").assertExists()
        compose.onNodeWithTag("layers-keyframe").performClick()
        compose.onNodeWithText("Ease in-out").performClick()
        assertEquals(1, toggled)
        assertEquals(listOf(Easing.EaseInOut), easings)
    }
}
