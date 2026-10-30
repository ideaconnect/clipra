package tech.idct.clipra.monetization.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import tech.idct.clipra.monetization.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The UMP SDK, Google's certified consent platform (docs/product/monetization.md, rule 9).
 * Debug builds test as a device in the EEA, so the form shows on emulators.
 */
@Singleton
class UmpConsentGateway @Inject constructor(@ApplicationContext private val context: Context) :
    ConsentGateway {
    private val info: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)

    override suspend fun update(activity: Activity): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            info.requestConsentInfoUpdate(
                activity,
                parameters(),
                { if (continuation.isActive) continuation.resume(true) },
                { error ->
                    log("Consent update failed", error)
                    if (continuation.isActive) continuation.resume(false)
                },
            )
        }
    }

    override suspend fun showFormIfRequired(activity: Activity) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                error?.let { log("Consent form failed", it) }
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    override suspend fun showPrivacyOptions(activity: Activity) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
                error?.let { log("Privacy options form failed", it) }
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    override val canRequestAds: Boolean get() = info.canRequestAds()

    override val privacyOptionsRequired: Boolean
        get() = info.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

    private fun parameters(): ConsentRequestParameters {
        val builder = ConsentRequestParameters.Builder()
            .setAdMobAppId(BuildConfig.ADMOB_APP_ID)
            // Clipra is not for children (docs/product/monetization.md, section 4).
            .setTagForUnderAgeOfConsent(false)
        if (BuildConfig.DEBUG) {
            builder.setConsentDebugSettings(
                ConsentDebugSettings.Builder(context)
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                    .setForceTesting(true)
                    .build(),
            )
        }
        return builder.build()
    }

    private fun log(message: String, error: FormError) {
        Log.w(TAG, "$message: ${error.errorCode} ${error.message}")
    }

    private companion object {
        const val TAG = "AdConsent"
    }
}
