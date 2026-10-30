package tech.idct.clipra.feature.effects.stack

import androidx.compose.ui.test.assertIsNotEnabled
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
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.MaskShape
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import java.util.Locale

/** The effect stack on sunset.mp4: Cinematic, then Mosaic in an oval (TabletEffects.png). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class EffectStackScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val stack = listOf(
        Effect("cinematic", 0.8f),
        Effect("mosaic", 0.8f, mapOf("size" to 0.05f)),
    )

    private val clip = Clip(
        id = "sunset",
        type = ClipType.Video,
        startUs = 0,
        durationUs = 9_200_000,
        source = MediaRef("sunset", 0, 9_200_000),
        effects = stack,
        mask = Mask(shape = MaskShape.Oval, effect = "mosaic"),
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(MediaAsset("sunset", "content://1", MediaKind.Video, "sunset.mp4")),
        layers = listOf(Layer("L1", clips = listOf(clip))),
    )

    private val moves = mutableListOf<Pair<Int, Int>>()
    private val toggles = mutableListOf<Pair<Int, Boolean>>()
    private val removed = mutableListOf<Int>()
    private val added = mutableListOf<String>()

    private fun show(
        effects: List<Effect> = stack,
        adding: Boolean = false,
        copied: List<Effect>? = null,
        locale: Locale = Locale.US,
    ) {
        Locale.setDefault(locale)
        val shown = clip.copy(effects = effects)
        val state = EffectStackUiState(
            project = project.copy(layers = listOf(Layer("L1", clips = listOf(shown)))),
            clip = shown,
            loaded = true,
            copied = copied,
        )
        compose.setContent {
            ClipraTheme {
                EffectStackScreen(
                    state = state,
                    effects = effects,
                    actions = EffectStackActions(
                        onBack = {},
                        onAdd = { added += it },
                        onMove = { from, to -> moves += from to to },
                        onEnabled = { index, on -> toggles += index to on },
                        onStrengthLive = { _, _ -> },
                        onStrengthDone = {},
                        onBlockSizeLive = {},
                        onBlockSizeDone = {},
                        onRemove = { removed += it },
                        onCopy = {},
                        onPaste = {},
                    ),
                    preview = {},
                    initialAdding = adding,
                )
            }
        }
    }

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    @Test
    fun twoEffects() {
        show()
        compose.onNodeWithText("sunset.mp4 · 2 effects").assertExists()
        compose.onNodeWithText("Look: Cinematic").assertExists()
        compose.onNodeWithText("masked").assertExists()
        // Nothing copied yet.
        compose.onNodeWithTag("stack-paste").assertIsNotEnabled()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("effect-down:cinematic").performClick()
        compose.onNodeWithTag("effect-up:mosaic").performClick()
        compose.onNodeWithTag("effect-on:mosaic").performClick()
        compose.onNodeWithTag("effect-remove:cinematic").performClick()
        assertEquals(listOf(0 to 1, 1 to 0), moves)
        assertEquals(listOf(1 to false), toggles)
        assertEquals(listOf(0), removed)
    }

    @Test
    fun addingAnEffect() {
        show(effects = stack.take(1), adding = true, copied = stack)
        compose.onNodeWithTag("stack-picker").assertExists()
        // The clip's own effects aren't offered again.
        compose.onNodeWithTag("add:cinematic").assertDoesNotExist()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
        compose.onNodeWithTag("add:grain").performClick()
        assertEquals(listOf("grain"), added)
    }

    @Test
    fun empty() {
        show(effects = emptyList())
        compose.onNodeWithTag("stack-empty").assertExists()
        compose.onNodeWithTag("stack-copy").assertIsNotEnabled()
    }

    /** Every label fits in Polish (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun twoEffectsInPolish() {
        show(locale = Locale.forLanguageTag("pl-PL"))
        compose.onNodeWithText("sunset.mp4 · 2 efekty").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
