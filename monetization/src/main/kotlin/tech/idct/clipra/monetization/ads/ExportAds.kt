package tech.idct.clipra.monetization.ads

import android.app.Activity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.idct.clipra.monetization.Entitlement

/**
 * The two export ads (#63, docs/product/monetization.md, section 1): Ad 1 once the render has
 * started, Ad 2 when a finished export is opened from the progress screen. [AdGate] decides, the
 * SDK shows. The export never waits for an ad: it renders behind both.
 */
class ExportAds(
    private val ads: AdsGateway,
    private val consent: AdConsent,
    private val entitlement: Entitlement,
    private val online: () -> Boolean,
    private val clock: () -> Long,
    scope: CoroutineScope,
) {
    private val gate = AdGate()
    private val lock = Mutex()

    init {
        // A user who just bought Clipra Pro stops loading ads.
        scope.launch { entitlement.isPro.filter { it }.collect { ads.stop() } }
    }

    /** Export opened: preload Ad 1, for free users with consent only (rules 8, 9 and 10). */
    suspend fun prepare() {
        if (mayRequest()) ads.preload()
    }

    /** The user tapped Export to Gallery and the render started. */
    suspend fun exportStarted() = lock.withLock { gate.exportStarted() }

    /** The render finished or failed; [watching] is whether the progress screen was in front. */
    suspend fun exportEnded(succeeded: Boolean, watching: Boolean) = lock.withLock {
        gate.exportEnded(succeeded, clock(), watching)
    }

    /** Whether opening the finished video would show Ad 2, so the screen can say so. */
    suspend fun finishAdExpected(): Boolean = lock.withLock {
        gate.check(AdSlot.Finish, conditions(watching = true)) == null
    }

    /**
     * Shows [slot]'s ad if the rules allow it and one is ready within 2 s, and returns when it
     * closed; null when an ad showed, else why not. Each slot gets one chance per export.
     */
    suspend fun show(slot: AdSlot, activity: Activity, watching: () -> Boolean): AdSkip? {
        val skip = lock.withLock {
            gate.check(slot, conditions(watching())).also { gate.used(slot) }
        }
        if (skip != null) return skip
        val shown = ads.show(activity, AdGate.LOAD_TIMEOUT_MS, watching)
        if (!shown) return AdSkip.NotLoaded
        if (slot == AdSlot.Start) lock.withLock { gate.startClosed(clock()) }
        return null
    }

    private suspend fun mayRequest(): Boolean = !entitlement.isPro.first() && consent.canRequestAds

    private suspend fun conditions(watching: Boolean) = AdConditions(
        pro = entitlement.isPro.first(),
        canRequestAds = consent.canRequestAds,
        online = online(),
        watching = watching,
    )
}
