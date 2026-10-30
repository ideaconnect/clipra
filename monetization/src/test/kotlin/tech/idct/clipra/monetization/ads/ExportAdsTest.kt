package tech.idct.clipra.monetization.ads

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import tech.idct.clipra.monetization.Entitlement

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExportAdsTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).get()
    private val ads = FakeAdsGateway()
    private val pro = MutableStateFlow(false)
    private var consentAllowed = true
    private val consentGateway = object : ConsentGateway {
        override suspend fun update(activity: Activity) = true

        override suspend fun showFormIfRequired(activity: Activity) = Unit

        override suspend fun showPrivacyOptions(activity: Activity) = Unit

        override val canRequestAds get() = consentAllowed
        override val privacyOptionsRequired = true
    }
    private val entitlement = object : Entitlement {
        override val isPro = pro
    }
    private var now = 0L
    private val watching = { true }

    private fun TestScope.exportAds() = ExportAds(
        ads = ads,
        consent = AdConsent(consentGateway, entitlement),
        entitlement = entitlement,
        online = { true },
        clock = { now },
        scope = backgroundScope,
    )

    @Test
    fun bothAdsShowAroundALongExport() = runTest {
        val exportAds = exportAds()
        exportAds.prepare()
        assertTrue(ads.preloaded)

        exportAds.exportStarted()
        now = 1_000
        assertNull(exportAds.show(AdSlot.Start, activity, watching))
        // Ad 1 closed at 6 s; the export finished at 50 s.
        assertEquals(6_000, ads.closedAt)
        now = 50_000
        exportAds.exportEnded(succeeded = true, watching = true)
        assertTrue(exportAds.finishAdExpected())
        assertNull(exportAds.show(AdSlot.Finish, activity, watching))
        assertEquals(2, ads.shown)
    }

    @Test
    fun aShortExportSkipsAd2() = runTest {
        val exportAds = exportAds()
        exportAds.exportStarted()
        assertNull(exportAds.show(AdSlot.Start, activity, watching))
        now = 20_000
        exportAds.exportEnded(succeeded = true, watching = true)
        assertFalse(exportAds.finishAdExpected())
        assertEquals(AdSkip.TooSoon, exportAds.show(AdSlot.Finish, activity, watching))
        assertEquals(1, ads.shown)
    }

    @Test
    fun anAdThatIsNotReadyIn2sIsSkippedForGood() = runTest {
        val exportAds = exportAds()
        exportAds.exportStarted()
        ads.ready = false
        assertEquals(AdSkip.NotLoaded, exportAds.show(AdSlot.Start, activity, watching))
        assertEquals(AdGate.LOAD_TIMEOUT_MS, ads.lastTimeout)
        ads.ready = true
        assertEquals(AdSkip.AlreadyUsed, exportAds.show(AdSlot.Start, activity, watching))
    }

    @Test
    fun proUsersNeverRequestAds() = runTest {
        pro.value = true
        val exportAds = exportAds()
        exportAds.prepare()
        exportAds.exportStarted()
        assertEquals(AdSkip.Pro, exportAds.show(AdSlot.Start, activity, watching))
        assertFalse(ads.preloaded)
        assertEquals(0, ads.shown)
    }

    @Test
    fun withoutConsentNothingLoads() = runTest {
        consentAllowed = false
        val exportAds = exportAds()
        exportAds.prepare()
        exportAds.exportStarted()
        assertFalse(ads.preloaded)
        assertEquals(AdSkip.NoConsent, exportAds.show(AdSlot.Start, activity, watching))
    }

    @Test
    fun buyingProStopsPreloading() = runTest {
        exportAds().prepare()
        runCurrent()
        assertTrue(ads.preloaded)
        pro.value = true
        runCurrent()
        assertFalse(ads.preloaded)
    }

    /** An ad SDK whose ads take 5 s to watch. */
    private inner class FakeAdsGateway : AdsGateway {
        var preloaded = false
        var ready = true
        var shown = 0
        var closedAt = 0L
        var lastTimeout = 0L

        override suspend fun preload() {
            preloaded = true
        }

        override fun stop() {
            preloaded = false
        }

        override suspend fun show(
            activity: Activity,
            timeoutMs: Long,
            stillWatching: () -> Boolean,
        ): Boolean {
            lastTimeout = timeoutMs
            if (!ready || !stillWatching()) return false
            shown++
            now += 5_000
            closedAt = now
            return true
        }
    }
}
