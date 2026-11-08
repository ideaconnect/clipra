package tech.idct.clipra.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor's frames while the timeline is scrubbed and the project plays (#77; the timeline
 * budget is 60 fps with six layers on proxies, docs/architecture.md, section 11). It measures the
 * project named Budget when the phone has one, and a new text card project otherwise; with the
 * instrumentation argument `budgetProject=required` it fails instead (#241).
 *
 * The compilation state is left as the install made it ([CompilationMode.Ignore]): on Android 13
 * and older without root, Macrobenchmark resets it by uninstalling and installing the app again,
 * which deletes the Budget project (#241). StartupBenchmark covers compilation.
 */
@OptIn(ExperimentalMacrobenchmarkApi::class)
@RunWith(AndroidJUnit4::class)
class EditorBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scrubAndPlay() = rule.measureRepeated(
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
    ) {
        scrubTimeline()
        playBriefly()
    }

    private companion object {
        const val ITERATIONS = 5
    }
}
