package tech.idct.clipra.feature.settings

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import java.util.Locale

/** About Clipra (#284), built from the design system: there is no board for it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class AboutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.001f),
    )

    private val calls = mutableListOf<String>()
    private val actions = AboutActions(
        onBack = { calls += "back" },
        onWebsite = { calls += "website" },
        onCoffee = { calls += "coffee" },
        onSponsor = { calls += "sponsor" },
        onReport = { calls += "report" },
    )

    private fun show(locale: Locale = Locale.US) {
        Locale.setDefault(locale)
        compose.setContent { ClipraTheme { AboutScreen(actions) } }
    }

    @Test
    fun aboutClipra() {
        show()
        compose.onNodeWithContentDescription("IDCT logo").assertExists()
        compose.onNodeWithText("Made by IDCT").assertExists()
        compose.onNodeWithText("IDCT Bartosz Pachołek").assertExists()
        compose.onNodeWithText("Visit idct.tech").assertExists()
        compose.onNodeWithText("Buy me a coffee").assertExists()
        compose.onNodeWithText("Sponsor on GitHub").assertExists()
        compose.onNodeWithText("Report a feature/bug").assertExists()
        compose.onNodeWithText("clipra@idct.tech").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)

        compose.onNodeWithTag("about-website").performScrollTo().performClick()
        compose.onNodeWithTag("about-coffee").performScrollTo().performClick()
        compose.onNodeWithTag("about-sponsor").performScrollTo().performClick()
        compose.onNodeWithTag("about-report").performScrollTo().performClick()
        compose.onNodeWithTag("back").performClick()
        assertEquals(listOf("website", "coffee", "sponsor", "report", "back"), calls)
    }

    /** The same in Polish, to see that every label fits (#76). */
    @Test
    @Config(qualifiers = "pl-w390dp-h844dp-xhdpi")
    fun aboutClipraInPolish() {
        show(locale = Locale.forLanguageTag("pl-PL"))
        compose.onNodeWithText("Stworzone przez IDCT").assertExists()
        compose.onNodeWithText("Postaw mi kawę").assertExists()
        compose.onNodeWithText("Wesprzyj na GitHubie").assertExists()
        compose.onNodeWithText("Zgłoś pomysł/błąd").assertExists()
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    /** The buttons open idct.tech, the two support pages and a new email, with the version. */
    @Test
    fun theButtonsOpenThePagesAndAnEmail() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName)
            .versionName = "1.2.3"
        compose.setContent { ClipraTheme { AboutDestination(onBack = {}) } }

        fun next(tag: String): Intent {
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            return shadowOf(app).nextStartedActivity
        }

        listOf(
            "about-website" to "https://idct.tech/",
            "about-coffee" to "https://buymeacoffee.com/idct",
            "about-sponsor" to "https://github.com/sponsors/ideaconnect?frequency=one-time",
        ).forEach { (tag, url) ->
            val page = next(tag)
            assertEquals(tag, Intent.ACTION_VIEW, page.action)
            assertEquals(tag, url, page.dataString)
        }

        val email = next("about-report")
        assertEquals(Intent.ACTION_SENDTO, email.action)
        assertEquals(
            "mailto:clipra@idct.tech?subject=Clipra%201.2.3%3A%20feature%20or%20bug",
            email.dataString,
        )
        assertEquals("Clipra 1.2.3: feature or bug", email.getStringExtra(Intent.EXTRA_SUBJECT))
    }
}
