package tech.idct.clipra.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start to the projects (#77; budget in docs/architecture.md, section 11), without and with
 * the baseline profile, so the profile's gain shows too. Run on real phones; the emulator only
 * shows that it works.
 *
 * Both reset the compilation state. On Android 13 and older without root, Macrobenchmark does
 * that by uninstalling and installing the app again, which deletes its data, the Budget project
 * of EditorBenchmark included (#241), so scripts/perf/budgets.py runs this after the editor.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartWithoutProfile() = start(CompilationMode.None())

    @Test
    fun coldStartWithProfile() =
        start(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun start(compilation: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        passWelcome()
    }

    private companion object {
        const val ITERATIONS = 10
    }
}
