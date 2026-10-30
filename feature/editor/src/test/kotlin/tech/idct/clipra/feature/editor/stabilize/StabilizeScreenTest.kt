package tech.idct.clipra.feature.editor.stabilize

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isNotEnabled
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
import tech.idct.clipra.core.model.CameraStep
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Stabilization
import tech.idct.clipra.core.model.Stabilizing
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sin

/** The Stabilize sheet (#54) on a handheld walk through Alfama. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class StabilizeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val walk = Clip(
        id = "walk",
        type = ClipType.Video,
        startUs = 0,
        durationUs = 6_000_000,
        source = MediaRef("walk", 0, 6_000_000),
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(MediaAsset("walk", "content://1", MediaKind.Video, "alfama-walk.mp4")),
        layers = listOf(Layer("L1", clips = listOf(walk))),
    )

    /** Six seconds of shake at 30 frames a second. */
    private val steps = List(180) { i ->
        CameraStep(i * 33_333L, 0.004f * sin(i * 1.3f), 0.003f * sin(i * 0.8f), 0f, 1f)
    }

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private val changes = mutableListOf<Stabilization?>()
    private var cancelled = 0
    private var retried = 0

    private fun show(
        stabilization: Stabilization?,
        analysis: StabilizeAnalysis?,
        locale: Locale = Locale.US,
    ) {
        Locale.setDefault(locale)
        compose.setContent {
            ClipraTheme {
                StabilizeScreen(
                    project = project,
                    clip = walk.copy(stabilize = stabilization),
                    analysis = analysis,
                    actions = StabilizeActions(
                        onDone = {},
                        onStabilize = { changes += it },
                        onCancel = { cancelled++ },
                        onRetry = { retried++ },
                    ),
                    preview = {},
                )
            }
        }
    }

    @Test
    fun offUntilTurnedOn() {
        show(null, null)
        compose.onNodeWithText("alfama-walk.mp4 · stabilize").assertExists()
        compose.onNodeWithTag("stabilize-switch").assertIsOff()
        compose.onNode(hasTestTag("stabilize-strength") and hasAnyDescendant(isNotEnabled()))
            .assertExists()
        compose.onNodeWithTag("stabilize-crop").assertDoesNotExist()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("stabilize-switch").performClick()
        assertEquals(listOf(Stabilization()), changes)
    }

    @Test
    fun theCropFollowsTheStrength() {
        show(Stabilization(50), StabilizeAnalysis.Ready(steps, 16f / 9f))
        compose.onNodeWithTag("stabilize-switch").assertIsOn()
        val crop = Stabilizing.stabilize(steps, 50, 16f / 9f).crop
        compose.onNodeWithText("${(crop * 100).roundToInt()}%").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("stabilize-switch").performClick()
        assertEquals(listOf<Stabilization?>(null), changes)
    }

    @Test
    fun analysingCanBeCancelled() {
        show(Stabilization(50), StabilizeAnalysis.Working(0.45f))
        compose.onNodeWithText("Analysing the camera's motion · 45%").assertExists()
        compose.onNodeWithTag("stabilize-crop").assertDoesNotExist()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("stabilize-cancel").performClick()
        assertEquals(1, cancelled)
    }

    @Test
    fun aFailedAnalysisCanBeTriedAgain() {
        show(Stabilization(50), StabilizeAnalysis.Failed)
        compose.onNodeWithText("This clip couldn't be analysed.").assertExists()
        compose.onNodeWithTag("stabilize-retry").performClick()
        assertEquals(1, retried)
    }

    /** Every label fits in Polish (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun analysingInPolish() {
        show(
            Stabilization(80),
            StabilizeAnalysis.Working(0.7f),
            locale = Locale.forLanguageTag("pl-PL"),
        )
        compose.onNodeWithText("Analiza ruchu kamery · 70%").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
