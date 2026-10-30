package tech.idct.clipra.monetization.ads

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import tech.idct.clipra.monetization.Entitlement

/** What the consent allows: whether ads may be requested and whether to offer privacy options. */
data class ConsentState(val canRequestAds: Boolean, val privacyOptionsRequired: Boolean)

/**
 * Ad consent (#62, docs/product/monetization.md, rule 9). Free users are asked once they reach
 * their projects, where the law requires it, before any ad request; Pro users never are. The
 * answer UMP stored stays valid offline and across starts.
 */
class AdConsent(private val gateway: ConsentGateway, private val entitlement: Entitlement) {
    private val stateFlow = MutableStateFlow(current())
    val state: StateFlow<ConsentState> = stateFlow.asStateFlow()

    /** Whether an ad may be requested now; checked right before every request. */
    val canRequestAds: Boolean get() = gateway.canRequestAds

    /** Updates the consent information and shows the form when required. */
    suspend fun gather(activity: Activity) {
        if (!entitlement.isPro.first()) {
            // Even when the update fails, the consent stored in an earlier run still applies.
            if (gateway.update(activity)) gateway.showFormIfRequired(activity)
        }
        stateFlow.value = current()
    }

    /** "Ad privacy choices": the user changes their consent; the next ad request uses it. */
    suspend fun showPrivacyOptions(activity: Activity) {
        gateway.showPrivacyOptions(activity)
        stateFlow.value = current()
    }

    private fun current() = ConsentState(
        canRequestAds = gateway.canRequestAds,
        privacyOptionsRequired = gateway.privacyOptionsRequired,
    )
}
