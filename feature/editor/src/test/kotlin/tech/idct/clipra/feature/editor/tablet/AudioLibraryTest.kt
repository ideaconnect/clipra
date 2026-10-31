package tech.idct.clipra.feature.editor.tablet

import androidx.compose.foundation.background
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
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.VoiceCleanup
import java.util.Locale

/**
 * The tablet's Audio library (#70, design/screens/tablet/TabletAudio.png): the cleaned-up
 * voiceover selected, the guitar, and the sound of sunset.mp4 and sintra.mp4; a silent video
 * isn't listed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class AudioLibraryTest {
    @get:Rule
    val compose = createComposeRule()

    private fun asset(id: String, name: String, kind: MediaKind, recorded: Boolean = false) =
        MediaAsset(
            id,
            "content://$id",
            kind,
            name,
            42_000_000,
            hasAudio = true,
            recorded = recorded,
        )

    private fun clip(id: String, type: ClipType, start: Long, length: Long) =
        Clip(id, type, start, length, MediaRef(id, 0, length))

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(
            asset("voice", "voiceover.m4a", MediaKind.Audio, recorded = true),
            asset("guitar", "lisbon-guitar.mp3", MediaKind.Audio),
            asset("sunset", "sunset.mp4", MediaKind.Video),
            asset("sintra", "sintra.mp4", MediaKind.Video),
            asset("stamp", "stamp.mp4", MediaKind.Video).copy(hasAudio = false),
        ),
        layers = listOf(
            Layer("L1", clips = listOf(clip("guitar", ClipType.Audio, 0, 42_000_000))),
            Layer(
                "L2",
                clips = listOf(
                    clip("voice", ClipType.Audio, 14_000_000, 9_000_000).copy(
                        audio = AudioSettings(voiceCleanup = VoiceCleanup()),
                    ),
                ),
            ),
            Layer(
                "L3",
                clips = listOf(
                    clip("sunset", ClipType.Video, 0, 9_000_000),
                    clip("sintra", ClipType.Video, 16_000_000, 7_000_000),
                ),
            ),
            Layer("L4", clips = listOf(clip("stamp", ClipType.Video, 0, 3_000_000))),
        ),
    )

    private val picked = mutableListOf<String>()

    private fun show() {
        compose.setContent {
            ClipraTheme {
                AudioLibrary(
                    project = project,
                    selectedClipId = "voice",
                    onSelect = { picked += it },
                    onImport = { picked += "import" },
                    onRecord = { picked += "record" },
                    modifier = Modifier
                        .width(300.dp)
                        .height(600.dp)
                        .background(ClipraTheme.colors.background),
                )
            }
        }
    }

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    @Test
    fun tabletAudioLibrary() {
        Locale.setDefault(Locale.US)
        show()
        compose.onNodeWithTag("sound:voice").assertIsSelected()
        compose.onNodeWithText("Voice · 0:09 · recorded").assertExists()
        compose.onNodeWithText("sunset.mp4 audio").assertExists()
        compose.onNodeWithTag("sound:stamp").assertDoesNotExist()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("sound:guitar").performClick()
        compose.onNodeWithTag("import-sound").performClick()
        compose.onNodeWithTag("record-voiceover").performClick()
        assertEquals(listOf("guitar", "import", "record"), picked)
    }

    /** The Polish board, to see every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w1280dp-h800dp-land-mdpi")
    fun tabletAudioLibraryInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        show()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
