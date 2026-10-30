package tech.idct.clipra.feature.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
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
import tech.idct.clipra.core.models.ModelCatalog
import java.util.Locale

/**
 * Onboarding (design/screens/phone/Onboarding.png and states/Onboarding--small-checked.png)
 * with the real download sizes: 156 MB for Whisper Base and DeepFilterNet 3, not the design's 150.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class OnboardingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private var started = 0
    private var skipped = 0

    /** The screen with its choices kept here, as the view model keeps them. */
    private fun show(
        initial: OnboardingUiState = OnboardingUiState(),
        locale: Locale = Locale.US,
    ) {
        Locale.setDefault(locale)
        compose.setContent {
            var state by remember { mutableStateOf(initial) }
            ClipraTheme {
                OnboardingScreen(
                    state = state,
                    actions = OnboardingActions(
                        onToggle = { model ->
                            state = state.copy(
                                choices = state.choices.map {
                                    if (it.model == model) it.copy(selected = !it.selected) else it
                                },
                            )
                        },
                        onStart = { started++ },
                        onSkip = { skipped++ },
                    ),
                )
            }
        }
    }

    @Test
    fun onboardingAsDesigned() {
        show()
        compose.onNodeWithTag("onboarding:whisper-base").assertIsOn()
        compose.onNodeWithTag("onboarding:deepfilternet3").assertIsOn()
        compose.onNodeWithTag("onboarding:whisper-small").assertIsOff()
        // 147,951,465 + 7,983,136 bytes; Small alone is 487,601,967.
        compose.onNodeWithText("148 MB").assertExists()
        compose.onNodeWithText("8.0 MB").assertExists()
        compose.onNodeWithText("488 MB").assertExists()
        compose.onNodeWithTag("onboarding-total")
            .assertTextEquals("Downloads 156 MB over Wi-Fi. Change this any time in AI models.")
        compose.onRoot().captureRoboImage(roborazziOptions = options)
        compose.onNodeWithTag("onboarding-start").performClick()
        compose.onNodeWithTag("onboarding-skip").performClick()
        assertEquals(1, started)
        assertEquals(1, skipped)
    }

    /** The design state in Polish, to see that every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun onboardingAsDesignedInPolish() {
        show(locale = Locale.forLanguageTag("pl-PL"))
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun theTotalFollowsTheChoices() {
        show()
        compose.onNodeWithTag("onboarding:whisper-small").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding:whisper-small").assertIsOn()
        compose.onNodeWithTag("onboarding-total")
            .assertTextEquals("Downloads 644 MB over Wi-Fi. Change this any time in AI models.")
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        listOf("whisper-base", "deepfilternet3", "whisper-small").forEach {
            compose.onNodeWithTag("onboarding:$it").performScrollTo().performClick()
        }
        compose.onNodeWithTag("onboarding-total")
            .assertTextEquals("Nothing to download now. Get models any time in AI models.")
    }

    @Test
    fun withoutWifiOnlyTheTotalSaysSo() {
        show(OnboardingUiState(wifiOnly = false))
        compose.onNodeWithTag("onboarding-total")
            .assertTextEquals("Downloads 156 MB. Change this any time in AI models.")
    }

    @Test
    fun installedModelsAreNotDownloadedAgain() {
        val state = OnboardingUiState(
            choices = OnboardingChoices.defaults.map {
                if (it.model == ModelCatalog.whisperBase) it.copy(installed = true) else it
            },
        )
        assertEquals(ModelCatalog.deepFilterNet.download!!.bytes, state.downloadBytes)
        show(state)
        compose.onNodeWithText("Installed").assertExists()
        compose.onNodeWithTag("onboarding-total")
            .assertTextEquals("Downloads 8.0 MB over Wi-Fi. Change this any time in AI models.")
    }
}
