package tech.idct.clipra

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.checks.TouchTargetSizeCheck
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import com.google.android.apps.common.testing.accessibility.framework.uielement.ViewHierarchyElement
import org.hamcrest.Description
import org.hamcrest.TypeSafeMatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.data.project.ProjectSummary
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Resolution
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.feature.editor.EditorCallbacks
import tech.idct.clipra.feature.editor.EditorScreen
import tech.idct.clipra.feature.editor.EditorUiState
import tech.idct.clipra.feature.editor.timeline.TimelineActions
import tech.idct.clipra.feature.export.EncoderSupport
import tech.idct.clipra.feature.export.ExportAdsLine
import tech.idct.clipra.feature.export.ExportScreen
import tech.idct.clipra.feature.home.HomeActions
import tech.idct.clipra.feature.home.HomeScreen
import tech.idct.clipra.feature.home.NewProjectScreen
import tech.idct.clipra.feature.home.ProjectActions
import tech.idct.clipra.monetization.ProActions
import tech.idct.clipra.monetization.ProScreen
import tech.idct.clipra.monetization.ProUiState

/**
 * The accessibility pass (#75): the Accessibility Test Framework, which Accessibility Scanner
 * runs, checks the main flows' screens on the device (touch targets, labels, contrast) and
 * fails on any error.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * Timeline clips sit in 28 dp lanes so six layers fit on a phone (design-system.md,
     * Accessibility); each is also in the Layers list with a full-size row. Their size is the
     * one finding let through, and only for them.
     */
    private val timelineClips = object : TypeSafeMatcher<AccessibilityViewCheckResult>() {
        override fun describeTo(description: Description) {
            description.appendText("a touch target inside a timeline clip")
        }

        override fun matchesSafely(result: AccessibilityViewCheckResult): Boolean =
            result.accessibilityHierarchyCheck == TouchTargetSizeCheck::class.java &&
                result.element?.let(::holdsClip) == true

        /** The clip's node, or the one Compose wraps around it with its gestures. */
        private fun holdsClip(element: ViewHierarchyElement): Boolean =
            element.contentDescription?.toString()?.contains(", layer L") == true ||
                (0 until element.childViewCount).any { holdsClip(element.getChildView(it)) }
    }

    @Before
    fun checks() = compose.enableAccessibilityChecks(
        AccessibilityValidator().setSuppressingResultMatcher(timelineClips),
    )

    private val sec = 1_000_000L

    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        format = Format(ratio = AspectRatio.Landscape16x9),
        media = listOf(
            MediaAsset("sunset", "content://sunset", MediaKind.Video, "sunset.mp4", 9 * sec),
            MediaAsset("guitar", "content://guitar", MediaKind.Audio, "guitar.mp3", 42 * sec),
        ),
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    Clip("guitar", ClipType.Audio, 0, 42 * sec, MediaRef("guitar", 0, 42 * sec)),
                ),
            ),
            Layer(
                "L2",
                clips = listOf(
                    Clip("sunset", ClipType.Video, 0, 9 * sec, MediaRef("sunset", 0, 9 * sec)),
                ),
            ),
            Layer(
                "L3",
                clips = listOf(
                    Clip("title", ClipType.Text, 2 * sec, 3 * sec, text = TextSpec("Day one")),
                ),
            ),
        ),
    )

    private fun check(content: @Composable () -> Unit) {
        compose.setContent { ClipraTheme { content() } }
        compose.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun home() = check {
        HomeScreen(
            recent = listOf(
                ProjectSummary(
                    "lisbon",
                    "Lisbon weekend",
                    AspectRatio.Landscape16x9,
                    Layout.Single,
                    Resolution.FHD,
                    42 * sec,
                    0,
                ),
            ),
            nowMillis = 3_600_000,
            aiStatus = null,
            actions = HomeActions({}, {}, {}, {}, {}, {}, {}, {}),
            projectActions = ProjectActions({}, {}, {}),
        )
    }

    @Test
    fun newProject() = check {
        NewProjectScreen(
            format = Format(),
            onFormatChange = {},
            onCreate = {},
            onBack = {},
            fourKSupported = true,
        )
    }

    @Test
    fun editorWithAClipSelected() = check {
        EditorScreen(
            state = EditorUiState(
                project = project,
                loaded = true,
                selectedClipId = "sunset",
                playheadUs = 3 * sec,
            ),
            playing = false,
            callbacks = EditorCallbacks(
                onClose = {}, onExport = {}, onTool = {}, onPlayPause = {}, onUndo = {},
                onRedo = {}, onPickVisual = {}, onPickAudio = {}, onPickReplacement = {},
                onSplit = {}, onDuplicate = {}, onDelete = {}, onFreeze = {}, onExtract = {},
                onLayers = {}, onCrop = {}, onDismissMessage = {},
                timeline = TimelineActions({}, {}, {}, { _, _, _ -> }, { _, _, _ -> }, {}),
            ),
            preview = {},
        )
    }

    @Test
    fun export() = check {
        ExportScreen(
            project = project,
            settings = ExportSettings(),
            support = EncoderSupport(),
            onChange = {},
            onExport = {},
            onBack = {},
            ads = ExportAdsLine(proPrice = "10,00 zł", onPro = {}),
        )
    }

    @Test
    fun clipraPro() = check {
        ProScreen(
            state = ProUiState(price = "10,00 zł"),
            actions = ProActions({}, {}, {}, {}, {}),
        )
    }
}
