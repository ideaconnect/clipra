package tech.idct.clipra.monetization.ads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every rule of docs/product/monetization.md, section 1, that the gate decides. */
class AdGateTest {
    private val free =
        AdConditions(pro = false, canRequestAds = true, online = true, watching = true)
    private val gate = AdGate().apply { exportStarted() }

    @Test
    fun aFreeUserWatchingTheExportGetsBothAds() {
        assertNull(gate.check(AdSlot.Start, free))
        gate.used(AdSlot.Start)
        gate.startClosed(atMs = 5_000)
        gate.exportEnded(succeeded = true, atMs = 60_000, watching = true)
        assertNull(gate.check(AdSlot.Finish, free))
    }

    @Test
    fun proUsersNeverSeeAds() {
        val pro = free.copy(pro = true)
        assertEquals(AdSkip.Pro, gate.check(AdSlot.Start, pro))
        gate.exportEnded(succeeded = true, atMs = 60_000, watching = true)
        assertEquals(AdSkip.Pro, gate.check(AdSlot.Finish, pro))
    }

    @Test
    fun noAdWithoutConsent() {
        assertEquals(AdSkip.NoConsent, gate.check(AdSlot.Start, free.copy(canRequestAds = false)))
    }

    @Test
    fun offlineSkipsTheSlot() {
        assertEquals(AdSkip.Offline, gate.check(AdSlot.Start, free.copy(online = false)))
    }

    @Test
    fun neverWhileEditing() {
        // Keep editing, Home or another app: Ad 1 is not shown later.
        assertEquals(AdSkip.NotWatching, gate.check(AdSlot.Start, free.copy(watching = false)))
        // Away when the export finished: the notification opens the video without Ad 2, even if
        // the progress screen is open again by then.
        gate.exportEnded(succeeded = true, atMs = 60_000, watching = false)
        assertEquals(AdSkip.NotWatching, gate.check(AdSlot.Finish, free))
    }

    @Test
    fun ad2OnlyAfterASuccessfulExport() {
        assertEquals(AdSkip.NotSucceeded, gate.check(AdSlot.Finish, free))
        gate.exportEnded(succeeded = false, atMs = 60_000, watching = true)
        assertEquals(AdSkip.NotSucceeded, gate.check(AdSlot.Finish, free))
    }

    @Test
    fun neverBackToBack() {
        gate.used(AdSlot.Start)
        gate.startClosed(atMs = 40_000)
        // A short export: it finished 29.9 s after Ad 1 closed.
        gate.exportEnded(succeeded = true, atMs = 69_900, watching = true)
        assertEquals(AdSkip.TooSoon, gate.check(AdSlot.Finish, free))
    }

    @Test
    fun thirtySecondsAfterAd1IsEnough() {
        gate.used(AdSlot.Start)
        gate.startClosed(atMs = 40_000)
        gate.exportEnded(succeeded = true, atMs = 70_000, watching = true)
        assertNull(gate.check(AdSlot.Finish, free))
    }

    @Test
    fun ad1StillOpenWhenTheExportFinished() {
        gate.used(AdSlot.Start)
        gate.exportEnded(succeeded = true, atMs = 20_000, watching = true)
        gate.startClosed(atMs = 25_000)
        assertEquals(AdSkip.TooSoon, gate.check(AdSlot.Finish, free))
    }

    @Test
    fun withoutAd1Ad2CanShow() {
        gate.used(AdSlot.Start)
        gate.exportEnded(succeeded = true, atMs = 5_000, watching = true)
        assertNull(gate.check(AdSlot.Finish, free))
    }

    @Test
    fun atMostTwoPerExport() {
        gate.used(AdSlot.Start)
        assertEquals(AdSkip.AlreadyUsed, gate.check(AdSlot.Start, free))
        gate.exportEnded(succeeded = true, atMs = 60_000, watching = true)
        gate.used(AdSlot.Finish)
        assertEquals(AdSkip.AlreadyUsed, gate.check(AdSlot.Finish, free))

        // The next export gets its own two chances.
        gate.exportStarted()
        assertNull(gate.check(AdSlot.Start, free))
        assertEquals(AdSkip.NotSucceeded, gate.check(AdSlot.Finish, free))
    }
}
