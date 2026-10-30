package tech.idct.clipra.monetization.ads

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import tech.idct.clipra.monetization.Entitlement

/** Rule 9 of docs/product/monetization.md: consent first where required, never for Pro users. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AdConsentTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).get()
    private val gateway = FakeConsentGateway()
    private val pro = MutableStateFlow(false)
    private val entitlement = object : Entitlement {
        override val isPro = pro
    }

    @Test
    fun aFreeUserInTheEeaAnswersTheFormBeforeAnyAd() = runTest {
        val consent = AdConsent(gateway, entitlement)
        assertFalse(consent.canRequestAds)

        consent.gather(activity)
        assertEquals(listOf("update", "form"), gateway.calls)
        assertTrue(consent.canRequestAds)
        assertEquals(
            ConsentState(canRequestAds = true, privacyOptionsRequired = true),
            consent.state.value,
        )
    }

    @Test
    fun proUsersAreNeverAsked() = runTest {
        pro.value = true
        val consent = AdConsent(gateway, entitlement)
        consent.gather(activity)
        assertTrue(gateway.calls.isEmpty())
        assertFalse(consent.canRequestAds)
    }

    @Test
    fun withoutAnUpdateTheStoredConsentStillApplies() = runTest {
        gateway.updateWorks = false
        gateway.stored = true
        val consent = AdConsent(gateway, entitlement)
        consent.gather(activity)
        assertEquals(listOf("update"), gateway.calls)
        assertTrue(consent.state.value.canRequestAds)
    }

    @Test
    fun changingConsentTakesEffectOnTheNextRequest() = runTest {
        val consent = AdConsent(gateway, entitlement)
        consent.gather(activity)
        assertTrue(consent.canRequestAds)

        gateway.answer = false
        consent.showPrivacyOptions(activity)
        assertEquals("privacy", gateway.calls.last())
        assertFalse(consent.canRequestAds)
        assertFalse(consent.state.value.canRequestAds)
    }
}

/** UMP for a device in the EEA: the form asks, the user agrees unless [answer] says otherwise. */
private class FakeConsentGateway : ConsentGateway {
    val calls = mutableListOf<String>()
    var updateWorks = true
    var answer = true
    var stored = false
    private var required = false

    override suspend fun update(activity: Activity): Boolean {
        calls += "update"
        if (updateWorks) required = true
        return updateWorks
    }

    override suspend fun showFormIfRequired(activity: Activity) {
        calls += "form"
        stored = answer
    }

    override suspend fun showPrivacyOptions(activity: Activity) {
        calls += "privacy"
        stored = answer
    }

    override val canRequestAds: Boolean get() = stored
    override val privacyOptionsRequired: Boolean get() = required
}
