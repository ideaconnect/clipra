package tech.idct.clipra.feature.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
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
import tech.idct.clipra.core.model.VoiceEngine
import tech.idct.clipra.core.models.ModelStatus
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
                    actions = AudioActions({}, {}, {}, { picked += it }, {}),
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

    /** Normalized to -14 LUFS: the readout shows the clip before and after, the design's numbers. */
    @Test
    fun normalizeLoudness() {
        Locale.setDefault(Locale.US)
        val targets = mutableListOf<Int?>()
        val normalized = clip.copy(
            audio = clip.audio.copy(
                loudnessTarget = LoudnessTargets.SOCIAL,
                measuredLufs = -22.8f,
                truePeakDb = -3.1f,
            ),
        )
        compose.setContent {
            ClipraTheme {
                AudioScreen(
                    state = AudioUiState(
                        project,
                        normalized,
                        waveform,
                        loaded = true,
                        measured = Loudness.Measurement(-22.8f, -3.1f),
                        measuring = false,
                    ),
                    volume = 1f,
                    actions = AudioActions({}, {}, {}, {}, { targets += it }),
                )
            }
        }
        compose.onNodeWithText("-14.0 LUFS").assertExists()
        compose.onNodeWithText("peak -1.0 dBTP").assertExists()
        compose.onNodeWithTag("audio-normalize").assertIsOn()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithText("Before").performClick()
        compose.onNodeWithText("-22.8 LUFS").assertExists()
        compose.onNodeWithText("peak -3.1 dBTP").assertExists()

        compose.onNodeWithTag("audio-target-23").performScrollTo().performClick()
        compose.onNodeWithTag("audio-normalize").performClick()
        assertEquals(listOf(LoudnessTargets.BROADCAST, null), targets)
    }

    /** Until the clip is measured, normalization waits; a silent clip says why it cannot. */
    @Test
    fun normalizeWaitsForTheMeasurement() {
        Locale.setDefault(Locale.US)
        var state by mutableStateOf(AudioUiState(project, clip, waveform, loaded = true))
        val targets = mutableListOf<Int?>()
        compose.setContent {
            ClipraTheme {
                AudioScreen(state, 1f, AudioActions({}, {}, {}, {}, { targets += it }))
            }
        }
        compose.onNodeWithText("Measuring…").assertExists()
        compose.onNodeWithTag("audio-normalize").assertIsNotEnabled()
        state = state.copy(measuring = false)
        compose.onNodeWithText("No sound").assertExists()
        compose.onNodeWithText("This clip has no sound to measure").assertExists()
        assertEquals(emptyList<Int?>(), targets)
    }

    /** AI voice cleanup (#39) with DeepFilterNet 3 at 70%, then the states around it. */
    @Test
    fun voiceCleanup() {
        Locale.setDefault(Locale.US)
        val engines = mutableListOf<VoiceEngine>()
        val switches = mutableListOf<Boolean>()
        var applied = 0
        val cleaned = clip.copy(
            audio = clip.audio.copy(voiceCleanup = VoiceCleanup(VoiceEngine.DeepFilterNet, 0.7f)),
        )
        var state by mutableStateOf(
            AudioUiState(
                project,
                cleaned,
                waveform,
                loaded = true,
                measured = Loudness.Measurement(-20f, -4f),
                measuring = false,
                voice = VoiceUiState(deepFilterNet = ModelStatus.Installed(8_589_309)),
            ),
        )
        compose.setContent {
            ClipraTheme {
                AudioScreen(
                    state,
                    1f,
                    AudioActions(
                        onBack = {},
                        onVolume = {},
                        onVolumeDone = {},
                        onAudio = {},
                        onLoudnessTarget = {},
                        onVoice = { switches += it },
                        onEngine = { engines += it },
                        onApplyToAll = { applied++ },
                    ),
                )
            }
        }
        compose.onNodeWithTag("audio-apply-all").performScrollTo()
        compose.onNodeWithText("Best quality · 8.6 MB").assertExists()
        compose.onNodeWithText("Cuts wind, traffic and room noise").assertExists()
        compose.onNodeWithText("70").assertExists()
        compose.onNodeWithTag("audio-voice-switch").assertIsOn()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("audio-engine-rnnoise").performClick()
        compose.onNodeWithTag("audio-voice-switch").performClick()
        compose.onNodeWithTag("audio-apply-all").performClick()
        assertEquals(listOf(VoiceEngine.RNNoise), engines)
        assertEquals(listOf(false), switches)
        assertEquals(1, applied)

        state = state.copy(voice = state.voice.copy(cleaning = 0.45f))
        compose.onNodeWithText("Cleaning up the voice… 45%").assertExists()
        state = state.copy(
            voice = VoiceUiState(deepFilterNet = ModelStatus.Downloading(3_991_568, 7_983_136)),
        )
        compose.onNodeWithText("Downloading 50%").assertExists()
        state = state.copy(voice = VoiceUiState(deepFilterNet = ModelStatus.Missing()))
        compose.onNodeWithText("Download · 8.0 MB").assertExists()
    }

    @Test
    fun levelsAfterFollowVolumeAndNormalization() {
        val measured = Loudness.Measurement(-22.8f, -3.1f)
        val plain = AudioSettings(volume = 0.5f)
        val half = levelsAfter(measured, plain)!!
        assertEquals(-28.8f, half.lufs, 0.05f)
        assertEquals(-9.1f, half.peakDb, 0.05f)
        // Normalizing moves loudness to the target; the limiter holds the peak at -1 dBTP.
        val podcast = plain.copy(
            volume = 1f,
            loudnessTarget = LoudnessTargets.PODCAST,
            measuredLufs = -22.8f,
        )
        assertEquals(Levels(-16f, -1f), levelsAfter(measured, podcast))
        assertEquals(null, levelsAfter(measured, plain.copy(volume = 0f)))
        assertEquals(null, levelsAfter(null, plain))
    }
}
