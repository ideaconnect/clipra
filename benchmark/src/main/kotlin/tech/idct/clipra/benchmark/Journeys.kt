package tech.idct.clipra.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** The app under test. */
const val PACKAGE = "tech.idct.clipra"

/** The name of the project the editor is measured on. */
const val BUDGET_PROJECT = "Budget"

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
 * project, so the journey works on any device.
 */
fun MacrobenchmarkScope.openBudgetProject() {
    device.wait(Until.hasObject(By.text(NEW_PROJECT)), WAIT_MS)
    val budget = device.findObject(By.text(BUDGET_PROJECT))
    if (budget == null) {
        openTextCardProject()
        return
    }
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

/** Scrubs the timeline back and forth along the bottom layer, as when looking for a cut. */
fun MacrobenchmarkScope.scrubTimeline() {
    val width = device.displayWidth
    val y = device.findObject(By.text("L1"))?.visibleBounds?.centerY()
        ?: (device.displayHeight * 0.57).toInt()
    repeat(3) {
        device.swipe((width * 0.85).toInt(), y, (width * 0.35).toInt(), y, 40)
        device.waitForIdle()
        device.swipe((width * 0.35).toInt(), y, (width * 0.85).toInt(), y, 40)
        device.waitForIdle()
    }
}

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
