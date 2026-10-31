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
private const val SKIP = "Skip downloads for now"
private const val NEW_PROJECT = "New project"
private val FIRST_SCREEN = Pattern.compile("$SKIP|$NEW_PROJECT")

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
    device.wait(Until.findObject(By.desc("Do not consent")), CONSENT_WAIT_MS)?.click()
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
    device.wait(Until.hasObject(By.text("Export")), WAIT_MS)
    device.waitForIdle()
}

/** From the projects, a new text card project, open in the editor. */
fun MacrobenchmarkScope.openTextCardProject() {
    device.wait(Until.findObject(By.text("Text card")), WAIT_MS)?.click()
    // The format comes first, then the card's text; Done leads to the editor.
    device.wait(Until.findObject(By.text("Create project")), WAIT_MS)?.click()
    device.wait(Until.findObject(By.text("Done")), WAIT_MS)?.click()
    device.wait(Until.hasObject(By.text("Export")), WAIT_MS)
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
    val play = device.findObject(By.desc("Play")) ?: return
    play.click()
    Thread.sleep(PLAY_MS)
    device.findObject(By.desc("Pause"))?.click()
}

/** Scrolls whatever scrolls on screen down and up again. */
fun MacrobenchmarkScope.scrollDownAndUp() {
    val list = device.findObject(By.scrollable(true)) ?: return
    list.fling(Direction.DOWN)
    device.waitForIdle()
    list.fling(Direction.UP)
    device.waitForIdle()
}
