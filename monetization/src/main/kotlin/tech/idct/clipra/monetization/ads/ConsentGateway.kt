package tech.idct.clipra.monetization.ads

import android.app.Activity

/**
 * Google's User Messaging Platform, as Clipra uses it. [UmpConsentGateway] is the real one; tests
 * use a fake. Every call needs an activity in the foreground and never throws.
 */
interface ConsentGateway {
    /** Asks UMP whether consent is needed where the user is; false if it could not answer. */
    suspend fun update(activity: Activity): Boolean

    /** Shows the consent form when the law requires one and it has not been answered. */
    suspend fun showFormIfRequired(activity: Activity)

    /** The privacy options form, where the user changes their choice ("Ad privacy choices"). */
    suspend fun showPrivacyOptions(activity: Activity)

    /** Whether ads may be requested, from the last consent UMP stored (also from earlier runs). */
    val canRequestAds: Boolean

    /** Whether the user must be able to reach the privacy options form (EEA, UK and similar). */
    val privacyOptionsRequired: Boolean
}
