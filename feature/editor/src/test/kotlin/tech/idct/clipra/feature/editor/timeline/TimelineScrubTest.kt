package tech.idct.clipra.feature.editor.timeline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
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

/**
 * The timeline says when a scrub starts and ends (#244), the fling after the swipe included,
 * so the preview can follow it more cheaply meanwhile.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class TimelineScrubTest {
    @get:Rule
    val compose = createComposeRule()

    private val sec = 1_000_000L

    private val project = Project(
        id = "scrub",
        name = "Scrub",
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    Clip("card", ClipType.TextCard, 0, 30 * sec, text = TextSpec("Day one")),
                ),
            ),
        ),
    )

    @Test
    fun aSwipeIsOneScrubFromTouchToTheEndOfItsFling() {
        var playhead by mutableStateOf(10 * sec)
        val events = mutableListOf<String>()
        compose.setContent {
            ClipraTheme {
                Timeline(
                    project = project,
                    playheadUs = playhead,
                    dpPerSecond = TimelineGeometry.DEFAULT_DP_PER_SECOND,
                    selectedClipId = null,
                    missingMedia = emptySet(),
                    actions = actions(
                        onSeek = {
                            playhead = it
                            if (events.lastOrNull() != "seek") events += "seek"
                        },
                        onScrubbing = { events += if (it) "start" else "end" },
                    ),
                )
            }
        }
        compose.waitForIdle()
        assertTrue("before any touch: $events", events.isEmpty())

        compose.onNodeWithTag("timeline").performTouchInput {
            swipeLeft(startX = centerX + 100f, endX = centerX - 100f, durationMillis = 100)
        }
        compose.waitForIdle()

        // The first move may come before the start: the preview seeks a frame later anyway.
        assertEquals(listOf("start", "end"), events.filter { it != "seek" })
        assertEquals("seek", events[events.indexOf("start") + 1])
        assertTrue("the playhead moved on: $playhead", playhead > 10 * sec)
    }

    @Test
    fun aScrubCutShortByTheTimelineGoingAwayEnds() {
        var shown by mutableStateOf(true)
        val events = mutableListOf<Boolean>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ClipraTheme {
                if (shown) {
                    Timeline(
                        project = project,
                        playheadUs = 10 * sec,
                        dpPerSecond = TimelineGeometry.DEFAULT_DP_PER_SECOND,
                        selectedClipId = null,
                        missingMedia = emptySet(),
                        actions = actions(onScrubbing = { events += it }),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("timeline").performTouchInput {
            down(center)
            moveBy(Offset(-80f, 0f))
            moveBy(Offset(-80f, 0f))
        }
        compose.mainClock.advanceTimeBy(100)
        assertEquals(listOf(true), events)

        shown = false
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(listOf(true, false), events)
    }

    private fun actions(onSeek: (Long) -> Unit = {}, onScrubbing: (Boolean) -> Unit) =
        TimelineActions(
            onSeek = onSeek,
            onZoom = {},
            onSelect = {},
            onMove = { _, _, _ -> },
            onTrim = { _, _, _ -> },
            onAddMedia = {},
            onScrubbing = onScrubbing,
        )
}
