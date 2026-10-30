package tech.idct.clipra.feature.models

import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import tech.idct.clipra.core.models.AiModel
import tech.idct.clipra.core.models.DownloadFailure
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelState
import tech.idct.clipra.core.models.ModelStatus
import java.util.Locale

/** The AI models board (design/screens/phone/Models.png) with real sizes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class ModelsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private val downloaded = mutableListOf<AiModel>()
    private val removed = mutableListOf<AiModel>()
    private val cancelled = mutableListOf<AiModel>()
    private val actions = ModelsActions(
        onBack = {},
        onDownload = { downloaded += it },
        onCancel = { cancelled += it },
        onRemove = { removed += it },
        onWifiOnly = {},
        onUseAccelerator = {},
    )

    // As in the design: Base and DeepFilterNet 3 installed, with their real sizes on disk.
    private val designState = ModelsUiState(
        models = ModelCatalog.all.map { model ->
            ModelState(
                model,
                when (model) {
                    ModelCatalog.whisperBase, ModelCatalog.deepFilterNet ->
                        ModelStatus.Installed(model.installedBytes)

                    ModelCatalog.rnnoise -> ModelStatus.BuiltIn

                    else -> ModelStatus.Missing()
                },
            )
        },
    )

    @Test
    fun modelsWithTheirRealSizes() {
        Locale.setDefault(Locale.US)
        compose.setContent { ClipraTheme { ModelsScreen(designState, actions) } }
        // 147,951,465 + 8,589,309 bytes on disk.
        compose.onNodeWithText("157 MB").assertExists()
        compose.onNodeWithText("148 MB").assertExists()
        compose.onNodeWithText("78 MB").assertExists()
        compose.onNodeWithText("488 MB").assertExists()
        compose.onNodeWithText("574 MB").assertExists()
        compose.onNodeWithText("8.6 MB").assertExists()
        compose.onNodeWithTag("models-wifi-only").assertIsOn()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithContentDescription("Download Tiny").performClick()
        assertEquals(listOf(ModelCatalog.whisperTiny), downloaded)

        compose.onNodeWithContentDescription("Remove DeepFilterNet 3").performScrollTo()
            .performClick()
        compose.onNodeWithText("Remove DeepFilterNet 3?").assertExists()
        compose.onNodeWithTag("models-remove-confirm").performClick()
        assertEquals(listOf(ModelCatalog.deepFilterNet), removed)
    }

    /** The design state in Polish, to see that every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun modelsWithTheirRealSizesInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        compose.setContent { ClipraTheme { ModelsScreen(designState, actions) } }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun downloadsShowProgressWaitingAndFailures() {
        Locale.setDefault(Locale.US)
        val state = ModelsUiState(
            models = listOf(
                ModelState(
                    ModelCatalog.whisperTiny,
                    ModelStatus.Downloading(38_845_857, 77_691_713),
                ),
                ModelState(ModelCatalog.whisperBase, ModelStatus.Waiting(wifiOnly = true, 0)),
                ModelState(
                    ModelCatalog.whisperSmall,
                    ModelStatus.Missing(DownloadFailure.Corrupt),
                ),
                ModelState(
                    ModelCatalog.whisperLargeTurbo,
                    ModelStatus.Missing(DownloadFailure.Unavailable),
                ),
            ),
        )
        compose.setContent { ClipraTheme { ModelsScreen(state, actions) } }
        compose.onNodeWithText("50% of 78 MB").assertExists()
        compose.onNodeWithText("Waiting for Wi-Fi").assertExists()
        compose.onNodeWithText("Download failed its check").assertExists()
        compose.onNodeWithText("Source not available").assertExists()
        compose.onNodeWithText("0 B").assertExists()

        compose.onNodeWithContentDescription("Download Small").performClick()
        assertEquals(listOf(ModelCatalog.whisperSmall), downloaded)
        compose.onNodeWithTag("model:whisper-tiny").assertExists()
    }
}
