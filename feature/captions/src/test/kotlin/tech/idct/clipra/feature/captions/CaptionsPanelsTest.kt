package tech.idct.clipra.feature.captions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.LinkGroup
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Word
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelState
import tech.idct.clipra.core.models.ModelStatus
import java.util.Locale

/**
 * The Captions panels of the tablet editor (#70, design/screens/tablet/TabletCaptions.png): the
 * transcript on the left (300 dp) with the design's five captions, two words to check, the first
 * caption playing; and the inspector on the right (320 dp): their look, Bold pop at the bottom,
 * or Transcribe (#181, design/screens/states/TabletCaptions--transcribe-tab.png).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class CaptionsPanelsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun caption(id: String, from: Double, to: Double, text: String, unsure: Set<String>) =
        Clip(
            id = id,
            type = ClipType.Caption,
            startUs = (from * 1e6).toLong(),
            durationUs = ((to - from) * 1e6).toLong(),
            caption = CaptionSpec(
                words = text.split(" ").mapIndexed { i, word ->
                    Word(
                        word,
                        i * 300_000L,
                        i * 300_000L + 250_000,
                        if (word in unsure) 0.3f else 0.95f,
                    )
                },
            ),
        )

    private val captions = listOf(
        caption("c1", 6.0, 8.8, "We finally made it to Lisbon", emptySet()),
        caption("c2", 9.4, 11.6, "The light here is unreal", emptySet()),
        caption("c3", 12.1, 15.0, "Tomorrow we’re taking the tram up to Alfama", setOf("Alfama")),
        caption("c4", 15.6, 18.2, "and then the train out to Sintra", setOf("Sintra")),
        caption("c5", 18.9, 21.0, "if the weather holds", emptySet()),
    )

    private val sunset = Clip(
        id = "sunset",
        type = ClipType.Video,
        startUs = 0,
        durationUs = 21_000_000,
        source = MediaRef("sunset", 0, 21_000_000),
    )

    // The captions were made from sunset.mp4.
    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(MediaAsset("sunset", "content://1", MediaKind.Video, hasAudio = true)),
        layers = listOf(Layer("L1", clips = listOf(sunset)), Layer("L6", clips = captions)),
        links = listOf(LinkGroup("g", listOf("sunset") + captions.map { it.id })),
    )

    private val setup = CaptionsSetupUiState(
        project = project,
        loaded = true,
        models = listOf(
            ModelState(ModelCatalog.whisperTiny, ModelStatus.Installed(77_691_713)),
            ModelState(ModelCatalog.whisperBase, ModelStatus.Installed(147_951_465)),
            ModelState(ModelCatalog.whisperSmall, ModelStatus.Installed(487_601_967)),
            ModelState(ModelCatalog.whisperLargeTurbo, ModelStatus.Missing()),
        ),
        redoCaptioned = true,
    )

    private val picked = mutableListOf<Any>()

    private val actions = CaptionsEditActions(
        onDone = {},
        onStyle = { picked += it },
        onPosition = { picked += it },
        onScale = {},
        onScaleDone = {},
        onSeek = { picked += it },
        onEditByText = { picked += "edit-by-text" },
        onImportSrt = { picked += "import" },
    )

    private val setupActions = CaptionsSetupActions(
        onBack = {},
        onListenTo = {},
        onLanguage = { picked += it },
        onTranslate = { picked += it },
        onModel = { picked += it.id },
        onDownload = { picked += "download:${it.id}" },
        onOptions = {},
        onGenerate = { picked += "generate" },
        onCancel = { picked += "cancel" },
    )

    private fun show(state: CaptionsEditUiState, tab: CaptionsTab = CaptionsTab.Style) {
        compose.setContent {
            ClipraTheme {
                Row(
                    Modifier
                        .height(600.dp)
                        .background(ClipraTheme.colors.background),
                ) {
                    CaptionsTranscript(
                        state = state,
                        actions = actions,
                        onAutoCaptions = { picked += "auto" },
                        modifier = Modifier
                            .width(300.dp)
                            .fillMaxHeight(),
                    )
                    Spacer(Modifier.weight(1f))
                    CaptionsInspector(
                        tab = tab,
                        onTab = { picked += it },
                        state = state,
                        scale = null,
                        actions = actions,
                        setup = setup,
                        setupActions = setupActions,
                        modifier = Modifier
                            .width(320.dp)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    @Test
    fun tabletCaptions() {
        Locale.setDefault(Locale.US)
        show(CaptionsEditUiState(project, loaded = true, playheadUs = 8_300_000))
        compose.onNodeWithText("5 captions · 2 words to check").assertExists()
        compose.onNodeWithTag("style:BoldPop").assertIsSelected()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("caption:c2").performClick()
        compose.onNodeWithTag("style:Karaoke").performClick()
        compose.onNodeWithTag("captions-edit-by-text").performClick()
        assertEquals(listOf<Any>(9_400_000L, CaptionStyle.Karaoke, "edit-by-text"), picked)
    }

    /** No captions yet: Auto captions and .srt import, and no look to change. */
    @Test
    fun noCaptionsYet() {
        Locale.setDefault(Locale.US)
        show(CaptionsEditUiState(project.copy(layers = listOf(Layer("L1"))), loaded = true))
        compose.onNodeWithTag("captions-auto").performClick()
        compose.onNodeWithTag("captions-import-srt").performClick()
        assertEquals(listOf<Any>("auto", "import"), picked)
        compose.onNodeWithTag("style:BoldPop").assertDoesNotExist()
    }

    /** The Polish board, to see every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w1280dp-h800dp-land-mdpi")
    fun tabletCaptionsInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        show(CaptionsEditUiState(project, loaded = true, playheadUs = 8_300_000))
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    /** Transcribe makes the captions again with the model picked (#181). */
    @Test
    fun tabletTranscribe() {
        Locale.setDefault(Locale.US)
        show(
            CaptionsEditUiState(project, loaded = true, playheadUs = 8_300_000),
            CaptionsTab.Transcribe,
        )
        compose.onNodeWithText(
            "Replaces the 5 captions made from these clips, edits included",
        ).assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("model:${ModelCatalog.whisperSmall.id}").performClick()
        compose.onNodeWithText("Regenerate with Base").performClick()
        compose.onNodeWithText("Style").performClick()
        assertEquals(
            listOf<Any>(ModelCatalog.whisperSmall.id, "generate", CaptionsTab.Style),
            picked,
        )
    }

    @Test
    @Config(qualifiers = "pl-w1280dp-h800dp-land-mdpi")
    fun tabletTranscribeInPolish() {
        Locale.setDefault(Locale.forLanguageTag("pl-PL"))
        show(
            CaptionsEditUiState(project, loaded = true, playheadUs = 8_300_000),
            CaptionsTab.Transcribe,
        )
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
