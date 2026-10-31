package tech.idct.clipra.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The baseline profile (#77): the code of starting, the projects, a new project in the editor,
 * scrubbing and playing, compiled ahead of time when the app is installed. Generate it on a phone
 * or an emulator with Android 13 or later:
 *
 *     ./gradlew :benchmark:connectedBenchmarkAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.class=tech.idct.clipra.benchmark.BaselineProfileGenerator
 *
 * then copy the `*-startup-prof.txt` it reports over `app/src/main/baseline-prof.txt`
 * (docs/release/performance.md).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        passWelcome()
        scrollDownAndUp()
        openTextCardProject()
        scrubTimeline()
        playBriefly()
        device.pressBack()
    }
}
