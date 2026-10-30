package tech.idct.clipra.feature.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
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
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.model.export.VideoCodec
import tech.idct.clipra.monetization.billing.RestoreResult
import java.util.Locale

/** Settings (#74), built from the design system: there is no board for it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private val calls = mutableListOf<String>()
    private val defaults = mutableListOf<ExportSettings>()
    private val actions = SettingsActions(
        onBack = { calls += "back" },
        onPro = { calls += "pro" },
        onRestore = { calls += "restore" },
        onExportDefaults = { defaults += it },
        onClearCache = { calls += "clear" },
        onModels = { calls += "models" },
        onAdPrivacy = { calls += "privacy" },
        onLicenses = { calls += "licenses" },
    )

    // A free user in Poland on a phone without an AV1 encoder.
    private val free = SettingsUiState(
        proPrice = "10,00 zł",
        codecs = setOf(VideoCodec.H264, VideoCodec.HEVC),
        cacheBytes = 1_240_000_000,
        modelBytes = 156_540_774,
        adPrivacyChoices = true,
        version = "0.1.0 (1)",
    )

    private fun show(state: SettingsUiState, locale: Locale = Locale.US) {
        Locale.setDefault(locale)
        compose.setContent { ClipraTheme { SettingsScreen(state, actions) } }
    }

    @Test
    fun settingsForAFreeUser() {
        show(free)
        compose.onNodeWithText("No ads when you export · 10,00 zł, one payment").assertExists()
        compose.onNodeWithText("1.2 GB").assertExists()
        compose.onNodeWithText("157 MB").assertExists()
        compose.onNodeWithText("AV1").assertIsNotEnabled()
        compose.onNodeWithText("H.264").assertIsSelected()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("settings-pro").performClick()
        compose.onNodeWithTag("settings-restore").performClick()
        compose.onNodeWithText("HEVC").performScrollTo().performClick()
        compose.onNodeWithTag("settings-models").performScrollTo().performClick()
        compose.onNodeWithTag("settings-ad-privacy").performScrollTo().performClick()
        compose.onNodeWithTag("settings-licenses").performScrollTo().performClick()
        assertEquals(listOf("pro", "restore", "models", "privacy", "licenses"), calls)
        assertEquals(listOf(ExportSettings(codec = VideoCodec.HEVC)), defaults)
    }

    /** The design state in Polish, to see that every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun settingsForAFreeUserInPolish() {
        show(free, locale = Locale.forLanguageTag("pl-PL"))
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun clearingAsksFirst() {
        show(free)
        compose.onNodeWithTag("settings-clear").performScrollTo().performClick()
        compose.onNodeWithText("Clear proxies and caches?").assertExists()
        compose.onNodeWithTag("settings-clear-confirm").performClick()
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun noClearingDuringAnExport() {
        show(free.copy(exporting = true))
        compose.onNodeWithTag("settings-clear").performScrollTo().performClick()
        compose.onNodeWithTag("settings-clear-confirm").assertDoesNotExist()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun settingsForAProUser() {
        show(
            free.copy(
                pro = true,
                restore = RestoreResult.Owned,
                adPrivacyChoices = false,
            ),
        )
        compose.onNodeWithText("You’re Pro").assertExists()
        compose.onNodeWithText("Clipra Pro is restored.").assertExists()
        compose.onNodeWithTag("settings-ad-privacy").assertDoesNotExist()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
