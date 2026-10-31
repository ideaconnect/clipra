package tech.idct.clipra.feature.editor.timeline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.edit.TrimClip

/**
 * Trimming the same clip twice: the second drag starts from where the first one left the
 * edge, not from where it was when the clip was selected.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class TimelineTrimTest {
    @get:Rule
    val compose = createComposeRule()

    private val sec = 1_000_000L

    @Test
    fun aSecondTrimStartsFromTheFirstOnesEdge() {
        // A 3 s card, then a gap, then a clip from 6 s.
        var project by mutableStateOf(
            Project(
                id = "trim",
                name = "Trim",
                layers = listOf(
                    Layer(
                        "L1",
                        clips = listOf(
                            Clip("card", ClipType.TextCard, 0, 3 * sec, text = TextSpec("Day one")),
                            Clip(
                                "later",
                                ClipType.TextCard,
                                6 * sec,
                                3 * sec,
                                text = TextSpec("Later"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val refused = mutableListOf<Long>()
        compose.setContent {
            ClipraTheme {
                Timeline(
                    project = project,
                    playheadUs = 0,
                    dpPerSecond = TimelineGeometry.DEFAULT_DP_PER_SECOND,
                    selectedClipId = "card",
                    missingMedia = emptySet(),
                    actions = TimelineActions(
                        onSeek = {},
                        onZoom = {},
                        onSelect = {},
                        onMove = { _, _, _ -> },
                        onTrim = { id, edge, to ->
                            project = try {
                                TrimClip(id, edge, to).apply(project)
                            } catch (_: Exception) {
                                refused += to
                                project
                            }
                        },
                        onAddMedia = {},
                    ),
                    snap = false,
                )
            }
        }
        fun card() = project.layers.single().clips.first { it.id == "card" }.durationUs
        fun drag(dp: Int) {
            compose.onNodeWithTag("trim-end").performTouchInput {
                val to = centerX + dp.dp.toPx()
                if (dp < 0) {
                    swipeLeft(startX = centerX, endX = to, durationMillis = 400)
                } else {
                    swipeRight(startX = centerX, endX = to, durationMillis = 400)
                }
            }
            compose.waitForIdle()
        }

        // The same drag twice to the left, then once to the right, into the gap: each moves
        // the edge by the same step from where the drag before left it.
        drag(-32)
        val first = card()
        drag(-32)
        val second = card()
        drag(32)
        val third = card()

        val step = 3 * sec - first
        assertTrue("shortened to $first", step > 200_000)
        assertEquals(step.toDouble(), (first - second).toDouble(), 100_000.0)
        assertTrue("refused: $refused", refused.isEmpty())
        assertEquals(first.toDouble(), third.toDouble(), 100_000.0)
    }
}
