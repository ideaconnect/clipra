package tech.idct.clipra.benchmark

import android.util.Log
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** The app under test. */
const val PACKAGE = "tech.idct.clipra"

/** The name of the project the editor is measured on. */
const val BUDGET_PROJECT = "Budget"

/**
 * The instrumentation argument that makes the Budget project required (#241): with
 * `budgetProject=required`, EditorBenchmark fails on a phone without one instead of measuring a
 * new text card project. scripts/perf/budgets.py passes it on phones.
 */
const val BUDGET_PROJECT_ARGUMENT = "budgetProject"

/** The logcat tag that says which project the editor was measured on (#241). */
const val BUDGET_PROJECT_TAG = "BudgetProject"

private const val WAIT_MS = 10_000L

/** The ad consent form loads over the network after the first start. */
private const val CONSENT_WAIT_MS = 15_000L
private const val PLAY_MS = 2_000L

// The app's labels in English and Polish, the languages it ships, so the journeys work on phones
// set to either (#197). Each one is the string resource named next to it.
private val SKIP = label("Skip downloads for now", "Pomiń pobieranie") // models_onboarding_skip
private val NEW_PROJECT = label("New project", "Nowy projekt") // home_title_new_project
private val TEXT_CARD = label("Text card", "Karta tekstowa") // home_quick_text
private val CREATE_PROJECT = label("Create project", "Utwórz projekt") // home_new_create
private val DONE = label("Done", "Gotowe") // text_done
private val EXPORT = label("Export", "Eksportuj") // editor_export
private val PLAY = label("Play", "Odtwórz") // editor_play
private val PAUSE = label("Pause", "Wstrzymaj") // editor_pause
private val FIRST_SCREEN = Pattern.compile("${SKIP.pattern()}|${NEW_PROJECT.pattern()}")

// The timeline's labels: each layer's chip in the gutter (L1 is the bottom layer) and the
// ruler's times ("00:04"; the transport's timecode has hundredths, "00:04.20").
private val LAYER_CHIP = Pattern.compile("L\\d+")
private val RULER_TIME = Pattern.compile("\\d{2}:\\d{2}")

/** Google's consent form for the test ads is in English on Polish phones too. */
private const val DO_NOT_CONSENT = "Do not consent"

private fun label(english: String, polish: String): Pattern =
    Pattern.compile("${Pattern.quote(english)}|${Pattern.quote(polish)}")

/**
 * Past the first start's welcome (#73), if it shows: the downloads are skipped, so nothing goes
 * over the network while measuring. In the EEA and the UK the ad consent form (#64) follows; it
 * is declined, once, like the welcome.
 */
fun MacrobenchmarkScope.passWelcome() {
    device.wait(Until.hasObject(By.text(FIRST_SCREEN)), WAIT_MS)
    val skip = device.findObject(By.text(SKIP)) ?: return
    skip.click()
    device.wait(Until.hasObject(By.text(NEW_PROJECT)), WAIT_MS)
    device.wait(Until.findObject(By.desc(DO_NOT_CONSENT)), CONSENT_WAIT_MS)?.click()
    device.wait(Until.hasObject(By.text(NEW_PROJECT)), WAIT_MS)
}

/**
 * From the projects, the project named [BUDGET_PROJECT] in the editor: on a phone, six layers of
 * its own footage, made by hand once (docs/release/performance.md). Without one, a new text card
 * project, so the journey works on any device, or a failure with [BUDGET_PROJECT_ARGUMENT] set to
 * `required`. Either way the [BUDGET_PROJECT_TAG] log says which project it was.
 */
fun MacrobenchmarkScope.openBudgetProject() {
    device.wait(Until.hasObject(By.text(NEW_PROJECT)), WAIT_MS)
    // The projects can show up after the screen's title.
    val budget = device.wait(Until.findObject(By.text(BUDGET_PROJECT)), WAIT_MS)
    if (budget == null) {
        if (budgetProjectRequired()) {
            val message = "No project named $BUDGET_PROJECT on Home " +
                "($BUDGET_PROJECT_ARGUMENT=required): make it as docs/release/performance.md, " +
                "section 3, says"
            Log.e(BUDGET_PROJECT_TAG, message)
            error(message)
        }
        Log.w(
            BUDGET_PROJECT_TAG,
            "No project named $BUDGET_PROJECT on Home: measuring a new text card project",
        )
        openTextCardProject()
        return
    }
    Log.i(BUDGET_PROJECT_TAG, "Measuring the project named $BUDGET_PROJECT")
    budget.click()
    device.wait(Until.hasObject(By.text(EXPORT)), WAIT_MS)
    device.waitForIdle()
}

/** From the projects, a new text card project, open in the editor. */
fun MacrobenchmarkScope.openTextCardProject() {
    device.wait(Until.findObject(By.text(TEXT_CARD)), WAIT_MS)?.click()
    // The format comes first, then the card's text; Done leads to the editor.
    device.wait(Until.findObject(By.text(CREATE_PROJECT)), WAIT_MS)?.click()
    device.wait(Until.findObject(By.text(DONE)), WAIT_MS)?.click()
    device.wait(Until.hasObject(By.text(EXPORT)), WAIT_MS)
    device.waitForIdle()
}

private fun budgetProjectRequired(): Boolean =
    InstrumentationRegistry.getArguments().getString(BUDGET_PROJECT_ARGUMENT) == "required"

/**
 * Scrubs the timeline back and forth, as when looking for a cut: along a layer whose chip is on
 * screen, else along the ruler. With six layers a phone shows only the top ones (#241).
 */
fun MacrobenchmarkScope.scrubTimeline() {
    val width = device.displayWidth
    val label = onScreen(LAYER_CHIP) ?: onScreen(RULER_TIME)
    val y = checkNotNull(label) { "The editor's timeline is not on screen" }.centerY()
    repeat(3) {
        device.swipe((width * 0.85).toInt(), y, (width * 0.35).toInt(), y, 40)
        device.waitForIdle()
        device.swipe((width * 0.35).toInt(), y, (width * 0.85).toInt(), y, 40)
        device.waitForIdle()
    }
}

/** Where the first text matching [text] shows, if one does. */
private fun MacrobenchmarkScope.onScreen(text: Pattern) =
    device.findObjects(By.text(text)).map { it.visibleBounds }.firstOrNull { !it.isEmpty }

/** Plays the project for a moment and stops it. */
fun MacrobenchmarkScope.playBriefly() {
    val play = device.findObject(By.desc(PLAY)) ?: return
    play.click()
    Thread.sleep(PLAY_MS)
    device.findObject(By.desc(PAUSE))?.click()
}

/** Scrolls whatever scrolls on screen down and up again. */
fun MacrobenchmarkScope.scrollDownAndUp() {
    val list = device.findObject(By.scrollable(true)) ?: return
    list.fling(Direction.DOWN)
    device.waitForIdle()
    list.fling(Direction.UP)
    device.waitForIdle()
}
