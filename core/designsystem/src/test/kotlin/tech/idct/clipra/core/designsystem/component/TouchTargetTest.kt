package tech.idct.clipra.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens

/** Every interactive component keeps at least a 44 dp touch area (design-system.md). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w400dp-h1200dp")
class TouchTargetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun interactiveComponentsAreAtLeast44dp() {
        compose.setContent {
            ClipraTheme {
                Column {
                    PrimaryButton(text = "Go", onClick = {}, modifier = Modifier.testTag("primary"))
                    SecondaryButton(
                        text = "Go",
                        onClick = {},
                        modifier = Modifier.testTag("secondary"),
                    )
                    TextAction(text = "Go", onClick = {}, modifier = Modifier.testTag("text"))
                    IconAction(
                        icon = ClipraIcons.Undo,
                        contentDescription = "Undo",
                        onClick = {},
                        modifier = Modifier.testTag("icon"),
                    )
                    ClipraChip(
                        label = "4K",
                        selected = false,
                        onClick = {},
                        modifier = Modifier.testTag("chip"),
                    )
                    ClipraSwitch(
                        checked = true,
                        onCheckedChange = {},
                        modifier = Modifier.testTag("switch"),
                    )
                    ClipraSwitch(
                        checked = true,
                        onCheckedChange = {},
                        compact = true,
                        modifier = Modifier.testTag("compact"),
                    )
                    SwitchRow(
                        title = "On",
                        checked = true,
                        onCheckedChange = {},
                        modifier = Modifier.testTag("row"),
                    )
                    ChoiceCard(
                        title = "9:16",
                        selected = true,
                        onClick = {},
                        modifier = Modifier.testTag("card"),
                    )
                    SegmentedControl(
                        segments = listOf(Segment("24"), Segment("30")),
                        selectedIndex = 0,
                        onSelect = {},
                        modifier = Modifier.testTag("segments"),
                    )
                }
            }
        }
        val tags = listOf(
            "primary", "secondary", "text", "icon", "chip", "switch", "compact", "row", "card",
        )
        // Touch bounds: what a finger can hit, including Compose's extension of small targets.
        for (tag in tags) {
            val touch = compose.onNodeWithTag(tag).fetchSemanticsNode().touchBoundsInRoot
            val (width, height) = with(compose.density) {
                touch.width.toDp() to touch.height.toDp()
            }
            assertTrue(
                "$tag touch area is $width × $height",
                width >= ClipraDimens.TouchTarget && height >= ClipraDimens.TouchTarget,
            )
        }
        // 44 dp segments inside 4 dp of container padding.
        compose.onNodeWithTag("segments").assertHeightIsAtLeast(ClipraDimens.SegmentHeight + 8.dp)
    }
}
