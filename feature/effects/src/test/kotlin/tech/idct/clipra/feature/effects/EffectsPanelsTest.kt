package tech.idct.clipra.feature.effects

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
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.feature.effects.filters.FilterCategory
import tech.idct.clipra.feature.effects.stack.EffectStackActions
import tech.idct.clipra.feature.effects.stack.EffectStackUiState
import java.util.Locale

/**
 * The Effects panels of the tablet editor (#70, design/screens/tablet/TabletEffects.png): the
 * filters on the left (300 dp) and sunset.mp4's effect stack on the right (320 dp), Cinematic at
 * 80 then Mosaic, as on the board.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class EffectsPanelsTest {
    @get:Rule
    val compose = createComposeRule()

    private val sunset = Clip(
        "sunset",
        ClipType.Video,
        0,
        9_200_000,
        MediaRef("sunset", 0, 9_200_000),
        effects = listOf(
            Effect("cinematic", 0.8f),
            Effect("mosaic", 1f, mapOf(Filters.MOSAIC_SIZE to Filters.DEFAULT_MOSAIC_SIZE)),
        ),
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        format = Format(ratio = AspectRatio.Landscape16x9),
        media = listOf(
            MediaAsset(
                "sunset",
                "content://1",
                MediaKind.Video,
                "sunset.mp4",
                9_200_000,
                3840,
                2160,
            ),
        ),
        layers = listOf(Layer("L1"), Layer("L2"), Layer("L3", clips = listOf(sunset))),
    )

    private val picked = mutableListOf<Any?>()

    private val stack = EffectStackActions(
        onBack = {},
        onAdd = { picked += "add:$it" },
        onMove = { _, _ -> },
        onEnabled = { _, _ -> },
        onStrengthLive = { _, _ -> },
        onStrengthDone = {},
        onBlockSizeLive = {},
        onBlockSizeDone = {},
        onRemove = {},
        onCopy = { picked += "copy" },
        onPaste = {},
    )

    private fun show() {
        compose.setContent {
            ClipraTheme {
                Row(
                    Modifier
                        .height(600.dp)
                        .background(ClipraTheme.colors.background),
                ) {
                    FiltersLibrary(
                        category = FilterCategory.All,
                        selected = "cinematic",
                        thumbnails = emptyMap(),
                        enabled = true,
                        onCategory = { picked += it },
                        onPick = { picked += it },
                        modifier = Modifier
                            .width(300.dp)
                            .fillMaxHeight(),
                    )
                    Spacer(Modifier.weight(1f))
                    EffectStack(
                        state = EffectStackUiState(project, sunset, loaded = true),
                        effects = sunset.effects,
                        actions = stack,
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
    fun tabletEffects() {
        Locale.setDefault(Locale.US)
        show()
        compose.onNodeWithTag("filter:cinematic").assertIsSelected()
        compose.onNodeWithText("sunset.mp4").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("filter:noir").performClick()
        compose.onNodeWithTag("category:Looks").performClick()
        compose.onNodeWithText("Copy").performClick()
        assertEquals(listOf<Any?>("noir", FilterCategory.Looks, "copy"), picked)
    }

    /** The Polish board, to see every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w1280dp-h800dp-land-mdpi")
    fun tabletEffectsInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        show()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
