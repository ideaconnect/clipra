package tech.idct.clipra.monetization.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.AdRequest
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.PreloadConfiguration
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdPreloader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tech.idct.clipra.monetization.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The Google Mobile Ads Next-Gen SDK with interstitial preloading (docs/product/monetization.md,
 * section 3). The SDK starts only when a free user with consent opens Export, never on app start.
 * Debug builds use Google's sample app and test ad unit, which only serve test ads.
 */
@Singleton
class NextGenAdsGateway @Inject constructor(@ApplicationContext private val context: Context) :
    AdsGateway {
    private val starting = Mutex()
    private var started = false
    private val unit = BuildConfig.AD_UNIT_INTERSTITIAL

    override suspend fun preload() {
        starting.withLock {
            if (!started) {
                // The SDK asks to be initialised off the main thread.
                withContext(Dispatchers.IO) {
                    MobileAds.initialize(
                        context,
                        InitializationConfig.Builder(BuildConfig.ADMOB_APP_ID).build(),
                    )
                }
                started = true
            }
        }
        if (InterstitialAdPreloader.getConfiguration(unit) == null) {
            InterstitialAdPreloader.start(
                unit,
                PreloadConfiguration(AdRequest.Builder(unit).build()),
            )
        }
    }

    override fun stop() {
        if (started) InterstitialAdPreloader.destroy(unit)
    }

    override suspend fun show(
        activity: Activity,
        timeoutMs: Long,
        stillWatching: () -> Boolean,
    ): Boolean {
        preload()
        val ad = withTimeoutOrNull(timeoutMs) {
            var polled: InterstitialAd? = InterstitialAdPreloader.pollAd(unit)
            while (polled == null) {
                delay(POLL_MS)
                polled = InterstitialAdPreloader.pollAd(unit)
            }
            polled
        } ?: return false
        return withContext(Dispatchers.Main) {
            if (!stillWatching()) return@withContext false
            suspendCancellableCoroutine { continuation ->
                ad.adEventCallback = object : InterstitialAdEventCallback {
                    override fun onAdDismissedFullScreenContent() {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onAdFailedToShowFullScreenContent(error: FullScreenContentError) {
                        Log.w(TAG, "Interstitial failed to show: ${error.message}")
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                ad.show(activity)
            }
        }
    }

    private companion object {
        const val TAG = "ExportAds"
        const val POLL_MS = 100L
    }
}
