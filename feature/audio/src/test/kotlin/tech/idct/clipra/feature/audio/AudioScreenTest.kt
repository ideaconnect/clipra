package tech.idct.clipra.feature.audio

import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import tech.idct.clipra.core.engine.media.Waveform
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/** The Audio board for sunset.mp4's sound (design/screens/phone/Audio.png). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class AudioScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val clip = Clip(
        id = "sunset",
        type = ClipType.Video,
        startUs = 0,
        durationUs = 9_200_000,
        source = MediaRef("sunset", 0, 9_200_000),
        audio = AudioSettings(fadeInUs = 500_000, fadeOutUs = 1_000_000),
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(
            MediaAsset("sunset", "content://1", MediaKind.Video, "sunset.mp4", hasAudio = true),
        ),
        layers = listOf(Layer("L1", clips = listOf(clip))),
    )

    // Speech-like bursts: loud, then a pause.
    private val waveform = Waveform(
        ByteArray(460) { i ->
            (
                abs(sin(i / 7.0)) * if (i / 40 % 2 ==
                    0
                ) {
                    220
                } else {
                    20
                }
                ).toInt().toByte()
        },
    )

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    @Test
    fun volumeAndFades() {
        Locale.setDefault(Locale.US)
        val picked = mutableListOf<AudioSettings>()
        compose.setContent {
            ClipraTheme {
                AudioScreen(
                    state = AudioUiState(project, clip, waveform, loaded = true),
                    volume = 1f,
                    actions = AudioActions({}, {}, {}, { picked += it }),
                )
            }
        }
        compose.onNodeWithText("sunset.mp4 · clip audio").assertExists()
        compose.onNodeWithText("100%").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithText("0.5 s").performClick()
        compose.onNodeWithText("2.0 s").performClick()
        assertEquals(2_000_000L, picked.last().fadeInUs)
    }
}
