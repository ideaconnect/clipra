package tech.idct.clipra.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two parts of [EditorBenchmark]'s journey measured apart (#244), on the same project:
 * scrubbing the timeline, and playing the project. When `scrubAndPlay` misses the timeline
 * budget, these say which part it comes from. docs/release/performance.md, section 3, says how to
 * read them on Android 10 and 11, which report no `frameOverrunMs`.
 *
 * The compilation state is left as the install made it ([CompilationMode.Ignore]): on Android 13
 * and older without root, resetting it reinstalls the app, and the Budget project goes with its
 * data (#241).
 */
@OptIn(ExperimentalMacrobenchmarkApi::class)
@RunWith(AndroidJUnit4::class)
class EditorPartsBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    /** The timeline scrubbed back and forth, as in `scrubAndPlay`, with nothing played. */
    @Test
    fun scrub() = measure { scrubTimeline() }

    /** The project played for a moment twice, with no scrub. */
    @Test
    fun play() = measure {
        playBriefly()
        playBriefly()
    }

    private fun measure(block: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = {
            startActivityAndWait()
            passWelcome()
            openBudgetProject()
        },
        measureBlock = block,
    )

    private companion object {
        const val ITERATIONS = 5
    }
}
