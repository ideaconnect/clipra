package tech.idct.clipra.monetization

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

/**
 * The Pro and Pro--owned boards (design/screens/phone/Pro.png, states/Pro--owned.png) with the
 * price Google Play gives a user in Poland.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class ProScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private val clicks = mutableListOf<String>()
    private val actions = ProActions(
        onClose = { clicks += "close" },
        onBuy = { clicks += "buy" },
        onRestore = { clicks += "restore" },
        onBackToProjects = { clicks += "projects" },
        onAdPrivacy = { clicks += "privacy" },
    )

    private fun show(state: ProUiState) {
        compose.setContent { ClipraTheme { ProScreen(state, actions) } }
    }

    @Test
    fun proAsDesigned() {
        show(ProUiState(price = "10,00 zł", adPrivacyChoices = true))
        compose.onNodeWithText("Buy Pro · 10,00 zł").assertIsEnabled()
        compose.onNodeWithTag("pro-price").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
        compose.onNodeWithTag("pro-buy").performClick()
        compose.onNodeWithTag("pro-restore").performClick()
        compose.onNodeWithTag("pro-ad-privacy").performClick()
        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(listOf("buy", "restore", "privacy", "close"), clicks)
    }

    @Test
    fun owned() {
        show(ProUiState(owned = true, price = "10,00 zł", adPrivacyChoices = true))
        compose.onNodeWithText("You’re Pro").assertExists()
        compose.onNodeWithTag("pro-buy").assertDoesNotExist()
        compose.onNodeWithTag("pro-price").assertDoesNotExist()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
        compose.onNodeWithText("Back to projects").performClick()
        assertEquals(listOf("projects"), clicks)
    }

    @Test
    fun paymentPending() {
        show(ProUiState(price = "10,00 zł", pending = true, adPrivacyChoices = true))
        compose.onNodeWithTag("pro-status").assertExists()
        compose.onNodeWithTag("pro-buy").assertIsNotEnabled()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    fun adPrivacyChoicesOnlyWhereConsentNeedsThem() {
        show(ProUiState(price = "10,00 zł", adPrivacyChoices = false))
        compose.onNodeWithTag("pro-ad-privacy").assertDoesNotExist()
    }

    @Test
    fun withoutGooglePlayNoPriceIsMadeUp() {
        show(ProUiState(price = null, message = ProMessage.Unavailable))
        compose.onNodeWithText("Buy Pro").assertIsNotEnabled()
        compose.onNodeWithTag("pro-price").assertDoesNotExist()
        compose.onNodeWithText("Google Play isn’t available right now. Try again later.")
            .assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-mdpi")
    fun tabletAsDesigned() {
        show(ProUiState(price = "10,00 zł", adPrivacyChoices = true))
        compose.onNodeWithText("Buy Pro · 10,00 zł").assertIsEnabled()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
