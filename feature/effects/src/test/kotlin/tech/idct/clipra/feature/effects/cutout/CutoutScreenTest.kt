package tech.idct.clipra.feature.effects.cutout

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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.model.Behind
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Cutout
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextColors
import java.util.Locale

/** The Cutout board on sunset.mp4 (L3), with the design's values (design/screens/phone/Cutout.png). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class CutoutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun clip(id: String) = Clip(
        id = id,
        type = ClipType.Video,
        startUs = 0,
        durationUs = 9_200_000,
        source = MediaRef(id, 0, 9_200_000),
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(
            MediaAsset("sunset", "content://1", MediaKind.Video, "sunset.mp4", 0, 1080, 1920),
        ),
        layers = listOf(
            Layer("L1", clips = listOf(clip("beach"))),
            Layer("L2", clips = listOf(clip("tram"))),
            Layer("L3", clips = listOf(clip("sunset").copy(cutout = Cutout()))),
        ),
    )

    private val picked = mutableListOf<Cutout>()
    private var removed = 0

    private fun show(
        cutout: Cutout = Cutout(),
        search: PersonSearch = PersonSearch.Found,
        locale: Locale = Locale.US,
    ) {
        Locale.setDefault(locale)
        val state = CutoutUiState(
            project = project,
            clip = project.findClip("sunset")!!.second.copy(cutout = cutout),
            loaded = true,
            search = search,
        )
        compose.setContent {
            ClipraTheme {
                CutoutScreen(
                    state = state,
                    cutout = cutout,
                    actions = CutoutActions(
                        onBack = {},
                        onLive = {},
                        onCutout = { picked += it },
                        onRemove = { removed++ },
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
    fun personFound() {
        show()
        compose.onNodeWithText("sunset.mp4 · L3 · AI person cutout").assertExists()
        compose.onNodeWithText("Person found").assertExists()
        compose.onNodeWithTag("behind:LayerBelow").assertIsSelected()
        compose.onNodeWithText("40").assertExists()
        compose.onNodeWithText("70").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("behind:Blur").performClick()
        compose.onNodeWithTag("cutout-remove").performClick()
        assertEquals(listOf(Cutout(Behind.Blur)), picked)
        assertEquals(1, removed)
    }

    @Test
    fun findingPeopleWithAColourBehind() {
        show(Cutout(Behind.Color, color = TextColors.BLUE), PersonSearch.Finding(0.4f))
        compose.onNodeWithText("Finding people 40%").assertExists()
        compose.onNodeWithTag("swatch:${TextColors.BLUE}").assertIsSelected()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
        compose.onNodeWithTag("swatch:${TextColors.WHITE}").performClick()
        assertEquals(TextColors.WHITE, picked.single().color)
    }

    @Test
    fun noPersonFound() {
        show(search = PersonSearch.NotFound)
        compose.onNodeWithText("No person found").assertExists()
    }

    /** Every label fits in Polish (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun personFoundInPolish() {
        show(Cutout(Behind.Color), locale = Locale.forLanguageTag("pl-PL"))
        compose.onNodeWithText("Znaleziono osobę").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
