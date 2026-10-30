package tech.idct.clipra.monetization.ads

import android.app.Activity

/**
 * The ads SDK, as Clipra uses it: one interstitial unit, preloaded. [NextGenAdsGateway] is the real
 * one; tests use a fake. Nothing here decides whether an ad may show; [ExportAds] does.
 */
interface AdsGateway {
    /** Starts the SDK (once) and keeps an interstitial loaded. Only for free users with consent. */
    suspend fun preload()

    /** Stops preloading, for a user who just bought Clipra Pro. */
    fun stop()

    /**
     * Shows the preloaded interstitial, waiting up to [timeoutMs] for one, and returns once the
     * user closed it: true if an ad showed. [stillWatching] is asked again right before showing.
     */
    suspend fun show(activity: Activity, timeoutMs: Long, stillWatching: () -> Boolean): Boolean
}
