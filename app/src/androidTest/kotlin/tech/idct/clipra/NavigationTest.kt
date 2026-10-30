package tech.idct.clipra

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import tech.idct.clipra.navigation.AllBoards

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    // Export progress asks for notifications; the system dialog would cover the app.
    @get:Rule(order = 0)
    val notifications = object : ExternalResource() {
        override fun before() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.uiAutomation.grantRuntimePermission(
                    instrumentation.targetContext.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun everyPhoneBoardOpensFromTheScreenIndex() {
        // First run: onboarding leads to Home (a later run starts there), and Home (debug build)
        // to the screen index.
        if (compose.onAllNodesWithTag("onboarding-skip").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithTag("onboarding-skip").performClick()
        }
        compose.onNodeWithTag("home").performScrollToNode(hasTestTag("link:BoardIndex"))
        compose.onNodeWithTag("link:BoardIndex").performClick()

        for (board in AllBoards.phone) {
            compose.onNodeWithTag("index").performScrollToNode(hasTestTag("index:${board.id}"))
            compose.onNodeWithTag("index:${board.id}").performClick()
            // A built screen is tagged screen:<id>; one still to build, placeholder:<id>.
            compose.onNode(
                hasTestTag("screen:${board.id}") or hasTestTag("placeholder:${board.id}"),
            )
                .assertIsDisplayed()
            compose.activity.onBackPressedDispatcher.let { dispatcher ->
                compose.runOnUiThread { dispatcher.onBackPressed() }
            }
        }
    }
}
