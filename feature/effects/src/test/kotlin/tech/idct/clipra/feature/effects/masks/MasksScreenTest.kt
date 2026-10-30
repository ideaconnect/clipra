package tech.idct.clipra.feature.effects.masks

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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

/** The Masks board: Mosaic inside an oval over the face (design/screens/phone/Masks.png). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class MasksScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val oval = Mask(
        shape = MaskShape.Oval,
        x = 0.5f,
        y = 0.57f,
        width = 0.37f,
        height = 0.27f,
        feather = 0.04f,
        effect = "mosaic",
    )

    private fun clip(mask: Mask) = Clip(
        id = "sunset",
        type = ClipType.Video,
        startUs = 0,
        durationUs = 9_200_000,
        source = MediaRef("sunset", 0, 9_200_000),
        effects = listOf(Effect("mosaic", 0.8f)),
        mask = mask,
    )

    private fun project(mask: Mask) = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(
            MediaAsset("sunset", "content://1", MediaKind.Video, "sunset.mp4", 0, 1080, 1920),
        ),
        layers = listOf(Layer("L1", clips = listOf(clip(mask)))),
    )

    private val shapes = mutableListOf<MaskShape>()
    private val masks = mutableListOf<Mask>()

    private fun show(mask: Mask, locale: Locale = Locale.US) {
        Locale.setDefault(locale)
        val project = project(mask)
        compose.setContent {
            ClipraTheme {
                MasksScreen(
                    state = MasksUiState(project, project.layers[0].clips[0], loaded = true),
                    mask = mask,
                    blockSize = 0.08f,
                    actions = MasksActions(
                        onBack = {},
                        onShape = { shapes += it },
                        onMaskLive = {},
                        onMask = { masks += it },
                        onMaskDone = {},
                        onBlockSizeLive = {},
                        onBlockSizeDone = {},
                    ),
                    preview = {},
                )
            }
        }
    }

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    @Test
    fun ovalOverTheFace() {
        show(oval)
        compose.onNodeWithText("Mosaic effect · sunset.mp4").assertExists()
        compose.onNodeWithText("Inside oval · feather 43").assertExists()
        compose.onNodeWithTag("shape:Oval").assertIsSelected()
        compose.onNodeWithTag("mask-block-size").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("shape:Rectangle").performClick()
        assertEquals(listOf(MaskShape.Rectangle), shapes)
        compose.onNodeWithTag("mask-invert").performClick()
        assertTrue(masks.last().invert)
    }

    /** The design state in Polish, to see that every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun ovalOverTheFaceInPolish() {
        show(oval, locale = Locale.forLanguageTag("pl-PL"))
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun rectangleInverted() {
        show(oval.copy(shape = MaskShape.Rectangle, invert = true, cornerRadius = 0.05f))
        compose.onNodeWithText("Outside rectangle · feather 43").assertExists()
        compose.onNodeWithTag("mask-radius").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
