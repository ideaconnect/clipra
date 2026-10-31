package tech.idct.clipra.feature.effects.reframe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Reframe
import java.util.Locale

/** The Reframe board (design/screens/phone/Reframe.png): Lisbon weekend, 16:9 to 9:16. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class ReframeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        format = Format(ratio = AspectRatio.Landscape16x9),
    )

    /** A sunset sky with a sun and a figure on the right, as on the board. */
    private fun scene(width: Int, height: Int, shift: Float): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            val sky = Paint().apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    height.toFloat(),
                    intArrayOf(0xFF23305C.toInt(), 0xFF6B4D7A.toInt(), 0xFFD9785A.toInt()),
                    null,
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), sky)
            val sun = Paint().apply { color = 0xFFFFE6A8.toInt() }
            canvas.drawCircle(width * (0.75f - shift), height * 0.25f, height * 0.09f, sun)
            val person = Paint().apply { color = 0xFF2F4A7A.toInt() }
            canvas.drawCircle(width * (0.64f - shift), height * 1.05f, height * 0.3f, person)
            val head = Paint().apply { color = 0xFFC98B6B.toInt() }
            canvas.drawCircle(width * (0.64f - shift), height * 0.5f, height * 0.1f, head)
        }

    private val state = ReframeUiState(
        project = project,
        loaded = true,
        ratios = Reframe.ratios(project),
        ratio = AspectRatio.Portrait9x16,
        source = scene(320, 180, 0f),
        window = Reframe.Box(0.485f, 0f, 0.8f, 1f),
        result = scene(320, 180, 0.14f).let { Bitmap.createBitmap(it, 105, 0, 101, 180) },
    )

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private val picked = mutableListOf<Any>()

    private fun show(state: ReframeUiState) {
        var shown by mutableStateOf(state)
        compose.setContent {
            ClipraTheme {
                ReframeScreen(
                    state = shown,
                    playheadUs = 7_120_000,
                    actions = ReframeActions(
                        onBack = {},
                        onRatio = {
                            picked += it
                            shown = shown.copy(ratio = it)
                        },
                        onFollow = {
                            picked += it
                            shown = shown.copy(follow = it)
                        },
                        onMotion = {},
                        onKeepInside = {
                            picked += it
                            shown = shown.copy(keepInside = it)
                        },
                        onCreate = { picked += "create" },
                        onCancel = { picked += "cancel" },
                    ),
                )
            }
        }
    }

    @Test
    fun reframe() {
        Locale.setDefault(Locale.US)
        show(state)
        compose.onNodeWithText("Lisbon weekend · 16:9 → 9:16").assertExists()
        compose.onNodeWithText("Source 16:9 · 00:07.12").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithText("1:1").performClick()
        compose.onNodeWithText("1:1").assertIsSelected()
        compose.onNodeWithText("Create 1:1 copy").assertExists()
        compose.onNodeWithText("Face").performClick()
        compose.onNodeWithTag("reframe-keep-inside").performClick()
        compose.onNodeWithTag("reframe-create").performClick()
        assertEquals(
            listOf(AspectRatio.Square1x1, Reframe.Follow.Face, false, "create"),
            picked,
        )
    }

    /** The design state in Polish, to see that every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun reframeInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        show(state)
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    /** While every shot is tracked, a progress bar and Cancel stand in for Create. */
    @Test
    fun tracking() {
        Locale.setDefault(Locale.US)
        show(state.copy(creating = 0.42f))
        compose.onNodeWithText("Tracking every shot · 42%").assertExists()
        compose.onNodeWithTag("reframe-create").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), picked)
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
