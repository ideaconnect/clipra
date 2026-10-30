package tech.idct.clipra.feature.effects.color

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
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.ColorGrade
import tech.idct.clipra.core.model.HslShift
import tech.idct.clipra.core.model.HueRange
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Luts
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import java.util.Locale

/** The Color board on sunset.mp4 (L3), with the design's values (design/screens/phone/Color.png). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class ColorScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val design = ColorGrade(
        exposure = 10f,
        contrast = 18f,
        saturation = 12f,
        temperature = 30f,
        vignette = 30f,
        hsl = mapOf(HueRange.Oranges to HslShift(hue = -8f, saturation = 12f, luminance = 5f)),
    )

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
            Layer("L2", clips = listOf(clip("tram"), clip("sintra").copy(startUs = 9_200_000))),
            Layer("L3", clips = listOf(clip("sunset").copy(color = design))),
            Layer("L4", clips = listOf(clip("alfama"))),
        ),
    )

    private val luts = listOf(
        LutChoice(
            Luts.builtInId("teal_orange"),
            null,
            listOf(0xFF1E5060.toInt(), 0xFFE0A070.toInt()),
        ),
        LutChoice("imported:a1", "Warm print.cube", listOf(0xFF6B3A2A.toInt(), 0xFFF3B064.toInt())),
        LutChoice("imported:b2", "Night city.cube", listOf(0xFF0B1020.toInt(), 0xFF3D6FD8.toInt())),
    )

    private val grades = mutableListOf<ColorGrade>()
    private var pasted = 0

    private fun show(tab: ColorTab, grade: ColorGrade = design, locale: Locale = Locale.US) {
        Locale.setDefault(locale)
        val state = ColorUiState(
            project = project,
            clip = project.findClip("sunset")!!.second,
            loaded = true,
            luts = luts,
        )
        compose.setContent {
            ClipraTheme {
                ColorScreen(
                    state = state,
                    grade = grade,
                    split = 0.5f,
                    actions = ColorActions(
                        onBack = {},
                        onLive = {},
                        onGrade = { grades += it },
                        onDone = {},
                        onSplit = {},
                        onAuto = {},
                        onReset = {},
                        onPaste = { pasted++ },
                        onImport = {},
                    ),
                    preview = {},
                    initialTab = tab,
                )
            }
        }
    }

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    @Test
    fun adjust() {
        show(ColorTab.Adjust)
        compose.onNodeWithText("sunset.mp4 · L3").assertExists()
        compose.onNodeWithText("+18").assertExists()
        compose.onNodeWithText("Before").assertExists()
        // Four other video clips take the grade.
        compose.onNodeWithText("Paste to 4 clips").performClick()
        assertEquals(1, pasted)
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun hsl() {
        show(ColorTab.Hsl)
        compose.onNodeWithTag("hue:Oranges").assertIsSelected()
        compose.onNodeWithText(
            "Adjusting oranges only, for example skin tones or sky",
        ).assertExists()
        compose.onNodeWithText("-8").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun lut() {
        show(ColorTab.Lut, design.copy(lut = "imported:a1", lutStrength = 0.7f))
        compose.onNodeWithTag("lut:imported:a1").assertIsSelected()
        compose.onNodeWithText("Teal and orange").assertExists()
        compose.onNodeWithText("70%").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
        compose.onNodeWithTag("lut:none").performClick()
        assertEquals(null, grades.last().lut)
    }

    /** Every label fits in Polish (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun adjustInPolish() {
        show(ColorTab.Adjust, locale = Locale.forLanguageTag("pl-PL"))
        compose.onNodeWithText("Wklej do 4 klipów").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
