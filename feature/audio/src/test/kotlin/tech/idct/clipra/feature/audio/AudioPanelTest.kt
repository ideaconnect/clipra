package tech.idct.clipra.feature.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.engine.media.Loudness
import tech.idct.clipra.core.engine.media.Waveform
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.LoudnessTargets
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.VoiceCleanup
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

/**
 * The Audio inspector of the tablet editor (#70, design/screens/tablet/TabletAudio.png): the
 * recorded voiceover on L2, normalized to -14 LUFS and cleaned up, in the 320 dp pane.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class AudioPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private val voiceover = Clip(
        id = "vo",
        type = ClipType.Audio,
        startUs = 14_400_000,
        durationUs = 9_000_000,
        source = MediaRef("voice", 0, 9_000_000),
        audio = AudioSettings(
            loudnessTarget = LoudnessTargets.SOCIAL,
            measuredLufs = -22.8f,
            truePeakDb = -3.1f,
            voiceCleanup = VoiceCleanup(),
        ),
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(
            MediaAsset(
                "voice",
                "/files/recordings/voiceover.m4a",
                MediaKind.Audio,
                "voiceover.m4a",
                9_000_000,
                hasAudio = true,
                recorded = true,
            ),
        ),
        layers = listOf(Layer("L1"), Layer("L2", clips = listOf(voiceover))),
    )

    // Speech-like bursts: loud, then a pause.
    private val waveform = Waveform(
        ByteArray(460) { i ->
            (abs(sin(i / 7.0)) * if (i / 40 % 2 == 0) 220 else 20).toInt().toByte()
        },
    )

    private fun show() {
        compose.setContent {
            ClipraTheme {
                AudioInspector(
                    state = AudioUiState(
                        project,
                        voiceover,
                        waveform,
                        loaded = true,
                        measured = Loudness.Measurement(-22.8f, -3.1f),
                        measuring = false,
                    ),
                    clip = voiceover,
                    editing = AudioEditing(
                        volume = 1f,
                        strength = null,
                        noise = null,
                        duck = null,
                        actions = AudioActions({}, {}, {}, {}, {}),
                    ),
                    modifier = Modifier
                        .width(320.dp)
                        .height(800.dp)
                        .background(ClipraTheme.colors.background),
                )
            }
        }
    }

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    @Test
    fun tabletAudio() {
        Locale.setDefault(Locale.US)
        show()
        compose.onNodeWithText("voiceover.m4a").assertExists()
        compose.onNodeWithText("Clip on layer L2").assertExists()
        compose.onNodeWithText("-14.0 LUFS").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    /** The Polish board, to see every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w1280dp-h800dp-land-mdpi")
    fun tabletAudioInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        show()
        compose.onNodeWithText("Klip na warstwie L2").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
