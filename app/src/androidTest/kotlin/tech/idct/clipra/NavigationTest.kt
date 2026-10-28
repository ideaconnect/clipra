package tech.idct.clipra

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.navigation.AllBoards

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun everyPhoneBoardOpensFromTheScreenIndex() {
        // First run: onboarding leads to Home, and Home (debug build) to the screen index.
        compose.onNodeWithTag("link:Main").performClick()
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
