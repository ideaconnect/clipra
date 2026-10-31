package tech.idct.clipra.core.data.project

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.Project
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectAutosaverTest {
    private fun project(name: String) = Project(id = "p", name = name)

    private class Recorder {
        val saves = mutableListOf<Pair<Long, String>>()
    }

    private fun TestScope.autosaver(recorder: Recorder) = ProjectAutosaver(
        scope = backgroundScope,
        save = { recorder.saves += testScheduler.currentTime to it.name },
        nowMs = { testScheduler.currentTime },
    )

    @Test
    fun savesOnceEditsPause() = runTest {
        val recorder = Recorder()
        val saver = autosaver(recorder)
        saver.submit(project("a"))
        saver.submit(project("b"))
        advanceTimeBy(299)
        runCurrent()
        assertTrue(recorder.saves.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(300L to "b"), recorder.saves)
    }

    @Test
    fun continuousEditsAreSavedAtLeastEverySecond() = runTest {
        val recorder = Recorder()
        val saver = autosaver(recorder)
        // An edit every 100 ms for 5 s: the pause rule alone would never save.
        var lastEdit = ""
        for (i in 0 until 50) {
            lastEdit = "edit $i"
            saver.submit(project(lastEdit))
            advanceTimeBy(100)
            runCurrent()
        }
        val times = recorder.saves.map { it.first }
        assertTrue("saves: $times", times.size >= 4)
        (listOf(0L) + times).zipWithNext().forEach { (a, b) ->
            assertTrue("gap ${b - a} ms", b - a <= 1_000)
        }
        // After the last edit, the pause saves it.
        advanceTimeBy(400)
        runCurrent()
        assertEquals(lastEdit, recorder.saves.last().second)
    }

    @Test
    fun flushSavesTheLatestEditAtOnce() = runTest {
        val recorder = Recorder()
        val saver = autosaver(recorder)
        saver.submit(project("now"))
        saver.flush()
        assertEquals(listOf(0L to "now"), recorder.saves)
        // Nothing left to save when the pause comes.
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, recorder.saves.size)
    }

    /** A full phone: the save fails, nothing ends, and the edit is saved once there is room. */
    @Test
    fun aFailedSaveIsTriedAgainUntilItWorks() = runTest {
        val saved = mutableListOf<String>()
        var full = true
        val saver = ProjectAutosaver(
            scope = backgroundScope,
            save = {
                if (full) throw IOException("No space left on device")
                saved += it.name
            },
            nowMs = { testScheduler.currentTime },
        )
        saver.submit(project("edit"))
        advanceTimeBy(301)
        runCurrent()
        assertTrue(saver.failing.value)
        saver.flush()
        assertTrue(saved.isEmpty())

        // Room again: the next retry saves it without a new edit.
        full = false
        advanceTimeBy(5_400)
        runCurrent()
        assertEquals(listOf("edit"), saved)
        assertFalse(saver.failing.value)
    }
}
