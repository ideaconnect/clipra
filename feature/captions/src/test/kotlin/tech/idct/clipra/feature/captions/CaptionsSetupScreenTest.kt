package tech.idct.clipra.feature.captions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.models.AiModel
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelState
import tech.idct.clipra.core.models.ModelStatus
import java.util.Locale

/** The CaptionsSetup board (design/screens/phone/CaptionsSetup.png) with real model sizes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class CaptionsSetupScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private fun clip(id: String, media: String, type: ClipType = ClipType.Video) = Clip(
        id = id,
        type = type,
        startUs = 0,
        durationUs = 5_000_000,
        source = MediaRef(media, 0, 5_000_000),
    )

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(
            MediaAsset("talk", "content://1", MediaKind.Video, hasAudio = true),
            MediaAsset("view", "content://2", MediaKind.Video, hasAudio = false),
            MediaAsset("voice", "content://3", MediaKind.Audio, hasAudio = true),
        ),
        layers = listOf(
            Layer("L1", clips = listOf(clip("a", "talk"), clip("b", "view"))),
            Layer("L2", clips = listOf(clip("c", "talk"))),
            Layer("L3", clips = listOf(clip("d", "voice", ClipType.Audio))),
        ),
    )

    private val models = listOf(
        ModelState(ModelCatalog.whisperTiny, ModelStatus.Missing()),
        ModelState(ModelCatalog.whisperBase, ModelStatus.Installed(147_951_465)),
        ModelState(ModelCatalog.whisperSmall, ModelStatus.Missing()),
        ModelState(ModelCatalog.whisperLargeTurbo, ModelStatus.Missing()),
    )

    private val state = CaptionsSetupUiState(project = project, loaded = true, models = models)

    @Test
    fun setupAsDesigned() {
        Locale.setDefault(Locale.US)
        val generated = mutableListOf<Unit>()
        val downloads = mutableListOf<AiModel>()
        var current by mutableStateOf(state)
        compose.setContent {
            ClipraTheme {
                CaptionsSetupScreen(
                    current,
                    actions(
                        onGenerate = { generated += Unit },
                        onDownload = { downloads += it },
                        onModel = { current = current.copy(model = it) },
                    ),
                )
            }
        }
        compose.onNodeWithText("148 MB").assertExists()
        compose.onNodeWithText("Auto-detect").assertExists()
        compose.onNodeWithText("Generate captions").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("captions-generate").performClick()
        assertEquals(1, generated.size)

        // A model that is not on the phone is downloaded first.
        compose.onNodeWithTag("model:${ModelCatalog.whisperSmall.id}").performClick()
        compose.onNodeWithText("Download 488 MB and generate").assertExists()
        compose.onAllNodesWithText("Get", useUnmergedTree = true).assertCountEquals(3)

        current = current.copy(
            waitingForModel = true,
            models = models.map {
                if (it.model == ModelCatalog.whisperSmall) {
                    it.copy(status = ModelStatus.Downloading(243_800_984, 487_601_967))
                } else {
                    it
                }
            },
        )
        compose.onNodeWithText("Downloading the model… 50%").assertExists()
        compose.onNodeWithTag("captions-cancel").assertExists()

        current = current.copy(
            waitingForModel = false,
            model = ModelCatalog.whisperBase,
            transcription = TranscriptionState.Running(0.4f),
        )
        compose.onNodeWithText("Transcribing… 40%").assertExists()
        compose.onNodeWithTag("captions-generate").assertIsNotEnabled()
    }

    @Test
    fun polishSuggestsALargerModel() {
        Locale.setDefault(Locale.US)
        compose.setContent {
            ClipraTheme { CaptionsSetupScreen(state.copy(language = "pl"), actions()) }
        }
        compose.onNodeWithText("Polish").assertExists()
        compose.onNodeWithText("Small or larger is noticeably more accurate for Polish.")
            .assertExists()
    }

    @Test
    fun sourcesFollowListenTo() {
        assertEquals(listOf("a"), sources(project, ListenTo.MainClips).map { it.id })
        assertEquals(listOf("d"), sources(project, ListenTo.Voiceover).map { it.id })
        assertEquals(listOf("a", "c", "d"), sources(project, ListenTo.AllTracks).map { it.id })
        val muted = project.copy(
            layers = project.layers.mapIndexed { i, layer -> layer.copy(muted = i == 0) },
        )
        assertEquals(listOf("c"), sources(muted, ListenTo.MainClips).map { it.id })
    }

    private fun actions(
        onGenerate: () -> Unit = {},
        onDownload: (AiModel) -> Unit = {},
        onModel: (AiModel) -> Unit = {},
    ) = CaptionsSetupActions(
        onBack = {},
        onListenTo = {},
        onLanguage = {},
        onTranslate = {},
        onModel = onModel,
        onDownload = onDownload,
        onOptions = {},
        onGenerate = onGenerate,
        onCancel = {},
    )
}
