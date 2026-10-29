package tech.idct.clipra.feature.effects.transitions

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transition
import tech.idct.clipra.core.model.Transitions
import tech.idct.clipra.core.model.cutAfter
import java.util.Locale

/** The Transitions board with Whip pan on the cut (design/screens/phone/Transitions.png). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class TransitionsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun video(id: String, start: Long) =
        Clip(id, ClipType.Video, start, 4_000_000, MediaRef(id, 0, 4_000_000))

    private val transition = Transition("t", "L1", 4_000_000, "whip-pan", 600_000)

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(
            MediaAsset("sunset", "content://1", MediaKind.Video, "sunset.mp4"),
            MediaAsset("tram", "content://2", MediaKind.Video, "tram.mp4"),
        ),
        layers = listOf(
            Layer("L1", clips = listOf(video("sunset", 0), video("tram", 4_000_000))),
        ),
        transitions = listOf(transition),
    )

    // Stand-ins for the live thumbnails, which need GL: half sky, half tram.
    private val thumbnails = (listOf("") + Transitions.all).associateWith {
        val bitmap = Bitmap.createBitmap(124, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(0f, 0f, 62f, 96f, Paint().apply { color = 0xFF6B4D7A.toInt() })
        canvas.drawRect(62f, 0f, 124f, 96f, Paint().apply { color = 0xFF2F6FA8.toInt() })
        bitmap
    }

    private val picked = mutableListOf<String?>()
    private val easings = mutableListOf<Easing>()

    private fun show(category: TransitionCategory = TransitionCategory.All) {
        Locale.setDefault(Locale.US)
        compose.setContent {
            ClipraTheme {
                TransitionsScreen(
                    state = TransitionsUiState(
                        project = project,
                        cut = project.cutAfter("sunset"),
                        transition = transition,
                        loaded = true,
                        category = category,
                        thumbnails = thumbnails,
                    ),
                    durationUs = 600_000,
                    actions = TransitionsActions(
                        onBack = {},
                        onCategory = {},
                        onPick = { picked += it },
                        onDuration = {},
                        onDurationDone = {},
                        onEasing = { easings += it },
                        onPlay = {},
                        onApplyToAll = {},
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
    fun allTransitions() {
        show()
        compose.onNodeWithText("sunset.mp4 → tram.mp4").assertExists()
        compose.onNodeWithText("0.6 s · midpoint").assertExists()
        compose.onNodeWithTag("transition:whip-pan").assertIsSelected()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("transition:iris").performClick()
        compose.onNodeWithTag("transition:none").performClick()
        assertEquals(listOf("iris", null), picked)
        compose.onNodeWithText("Linear").performClick()
        assertEquals(listOf(Easing.Linear), easings)
    }

    @Test
    fun motion() {
        show(TransitionCategory.Motion)
        compose.onNodeWithTag("transition:cube").assertExists()
        compose.onNodeWithTag("transition:dissolve").assertDoesNotExist()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
