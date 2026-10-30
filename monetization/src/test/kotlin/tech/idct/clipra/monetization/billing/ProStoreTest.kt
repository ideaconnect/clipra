package tech.idct.clipra.monetization.billing

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** The entitlement rules of docs/product/monetization.md, section 2, against a fake Google Play. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProStoreTest {
    private val play = PlayKey()
    private val gateway = FakeBillingGateway()
    private val dataStore = MemoryDataStore()

    private fun TestScope.store() = ProStore(
        gateway = gateway,
        verifier = PurchaseVerifier(play.publicKey, trustUnsigned = false),
        dataStore = dataStore,
        scope = backgroundScope,
    )

    @Test
    fun aPurchaseGrantsProAndIsAcknowledged() = runTest {
        val store = store()
        store.start()
        runCurrent()
        assertFalse(store.isPro.first())

        val events = mutableListOf<PurchaseEvent>()
        backgroundScope.launch { store.events.collect { events += it } }
        runCurrent()
        val purchase = play.purchase("token-1")
        gateway.owned = listOf(purchase)
        gateway.updateFlow.emit(PurchaseUpdate.Purchases(listOf(purchase)))
        runCurrent()

        assertTrue(store.isPro.first())
        assertEquals(listOf("token-1"), gateway.acknowledged)
        assertEquals(listOf(PurchaseEvent.Granted), events)
        // Acknowledged once: the next start finds it acknowledged.
        assertEquals(RestoreResult.Owned, store.refresh())
        assertEquals(listOf("token-1"), gateway.acknowledged)
    }

    @Test
    fun aPendingPaymentGrantsNothingUntilItCompletes() = runTest {
        val store = store()
        store.start()
        runCurrent()
        val events = mutableListOf<PurchaseEvent>()
        backgroundScope.launch { store.events.collect { events += it } }
        runCurrent()

        gateway.updateFlow.emit(
            PurchaseUpdate.Purchases(listOf(play.purchase("cash", PurchaseState.Pending))),
        )
        runCurrent()
        assertFalse(store.isPro.first())
        assertTrue(store.pending.value)
        assertTrue(gateway.acknowledged.isEmpty())

        gateway.updateFlow.emit(PurchaseUpdate.Purchases(listOf(play.purchase("cash"))))
        runCurrent()
        assertTrue(store.isPro.first())
        assertFalse(store.pending.value)
        assertEquals(listOf(PurchaseEvent.Pending, PurchaseEvent.Granted), events)
    }

    @Test
    fun restoreOnStartGrantsProOnANewDevice() = runTest {
        gateway.owned = listOf(play.purchase("token-1", acknowledged = true))
        val store = store()
        store.start()
        runCurrent()
        assertTrue(store.isPro.first())
        assertEquals("10,00 zł", store.price.value)
        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test
    fun aRefundRemovesProOnTheNextStart() = runTest {
        gateway.owned = listOf(play.purchase("token-1", acknowledged = true))
        val store = store()
        assertEquals(RestoreResult.Owned, store.refresh())

        gateway.owned = emptyList()
        assertEquals(RestoreResult.NotOwned, store.refresh())
        assertFalse(store.isPro.first())
    }

    @Test
    fun withoutGooglePlayTheCachedEntitlementStays() = runTest {
        gateway.owned = listOf(play.purchase("token-1", acknowledged = true))
        val store = store()
        store.refresh()

        gateway.owned = null
        gateway.price = null
        assertEquals(RestoreResult.Unavailable, store.refresh())
        assertTrue(store.isPro.first())

        // A store started later (the next cold start, offline) still reads Pro from the cache.
        val offline = store()
        offline.start()
        runCurrent()
        assertTrue(offline.isPro.first())
        assertEquals(null, offline.price.value)
    }

    @Test
    fun aListenerUpdateNeverRevokes() = runTest {
        gateway.owned = listOf(play.purchase("token-1", acknowledged = true))
        val store = store()
        store.start()
        runCurrent()
        // The listener reports only what changed, here another product.
        gateway.updateFlow.emit(
            PurchaseUpdate.Purchases(listOf(play.purchase("other", product = "something_else"))),
        )
        runCurrent()
        assertTrue(store.isPro.first())
    }

    @Test
    fun aForgedPurchaseIsIgnored() = runTest {
        val forged = play.purchase("token-1").let { it.copy(signature = PlayKey().sign(it.json)) }
        gateway.owned = listOf(forged)
        val store = store()
        assertEquals(RestoreResult.NotOwned, store.refresh())
        assertFalse(store.isPro.first())
        assertTrue(gateway.acknowledged.isEmpty())
    }

    @Test
    fun otherProductsDoNotGrantPro() = runTest {
        gateway.owned = listOf(play.purchase("token-1", product = "something_else"))
        assertEquals(RestoreResult.NotOwned, store().refresh())
    }

    @Test
    fun aFailedAcknowledgementIsRetriedOnTheNextRefresh() = runTest {
        gateway.owned = listOf(play.purchase("token-1"))
        gateway.acknowledgeWorks = false
        val store = store()
        store.refresh()
        assertTrue(store.isPro.first())
        assertTrue(gateway.acknowledged.isEmpty())

        gateway.acknowledgeWorks = true
        store.refresh()
        assertEquals(listOf("token-1"), gateway.acknowledged)
    }

    @Test
    fun buyingWhatIsAlreadyOwnedRestoresIt() = runTest {
        gateway.owned = listOf(play.purchase("token-1", acknowledged = true))
        gateway.launch = LaunchResult.AlreadyOwned
        val store = store()
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        assertEquals(LaunchResult.AlreadyOwned, store.buy(activity))
        assertTrue(store.isPro.first())
    }

    @Test
    fun aFailedPurchaseIsReported() = runTest {
        val store = store()
        store.start()
        runCurrent()
        val events = mutableListOf<PurchaseEvent>()
        backgroundScope.launch { store.events.collect { events += it } }
        runCurrent()
        gateway.updateFlow.emit(PurchaseUpdate.Cancelled)
        gateway.updateFlow.emit(PurchaseUpdate.Failed(responseCode = 6))
        runCurrent()
        assertEquals(listOf(PurchaseEvent.Failed), events)
        assertFalse(store.isPro.first())
    }

    @Test
    fun thePriceIsLoadedOnce() = runTest {
        val store = store()
        assertEquals("10,00 zł", store.loadPrice())
        assertEquals("10,00 zł", store.loadPrice())
        assertEquals(1, gateway.priceQueries)
    }
}
