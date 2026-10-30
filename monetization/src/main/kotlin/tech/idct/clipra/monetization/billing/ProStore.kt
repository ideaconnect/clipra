package tech.idct.clipra.monetization.billing

import android.app.Activity
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.idct.clipra.monetization.Entitlement
import java.util.concurrent.atomic.AtomicBoolean

/** What a restore found. */
enum class RestoreResult { Owned, NotOwned, Unavailable }

/** What the store tells the Pro screen about a purchase it started. */
enum class PurchaseEvent { Granted, Pending, Failed }

/**
 * Clipra Pro (docs/product/monetization.md, section 2): sells it, keeps the entitlement and
 * exposes it as [isPro].
 *
 * - A purchase counts once Google Play reports it purchased and its signature checks out; Pro is
 *   granted at once and the purchase acknowledged (retried on every refresh until it succeeds).
 * - A pending payment grants nothing until it completes.
 * - [refresh] runs on every start and on Restore: Google Play's list of purchases is the truth,
 *   so a refunded or revoked purchase removes Pro. When Play cannot be reached the cached
 *   entitlement stays, so Pro users see no ads offline.
 */
class ProStore(
    private val gateway: BillingGateway,
    private val verifier: PurchaseVerifier,
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) : Entitlement {
    override val isPro: Flow<Boolean> =
        dataStore.data.map { it[PRO_OWNED] ?: false }.distinctUntilChanged()

    private val priceState = MutableStateFlow<String?>(null)

    /** Clipra Pro's price as Google Play formats it for this user; null until Play answers. */
    val price: StateFlow<String?> = priceState.asStateFlow()

    private val pendingState = MutableStateFlow(false)

    /** A purchase is waiting for its payment, such as cash at a shop. */
    val pending: StateFlow<Boolean> = pendingState.asStateFlow()

    private val eventFlow = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PurchaseEvent> = eventFlow.asSharedFlow()

    private val started = AtomicBoolean(false)
    private val applying = Mutex()

    /** Listens for purchases and restores Pro from Google Play; called once, on app start. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { gateway.updates.collect { onUpdate(it) } }
        scope.launch {
            refresh()
            loadPrice()
        }
    }

    /** Asks Google Play which purchases the account owns and grants or revokes Pro to match. */
    suspend fun refresh(): RestoreResult {
        val purchases = gateway.queryPurchases() ?: return RestoreResult.Unavailable
        return if (apply(
                purchases,
                complete = true,
            )
        ) {
            RestoreResult.Owned
        } else {
            RestoreResult.NotOwned
        }
    }

    suspend fun loadPrice(): String? {
        priceState.value?.let { return it }
        return gateway.proPrice()?.also { priceState.value = it }
    }

    /** Opens Google Play's purchase sheet; the purchase itself arrives through the listener. */
    suspend fun buy(activity: Activity): LaunchResult {
        val result = gateway.launchPurchase(activity)
        if (result == LaunchResult.AlreadyOwned) refresh()
        return result
    }

    private suspend fun onUpdate(update: PurchaseUpdate) {
        when (update) {
            is PurchaseUpdate.Purchases -> {
                if (update.purchases.none { PRO_PRODUCT_ID in it.products }) return
                val event = when {
                    apply(update.purchases, complete = false) -> PurchaseEvent.Granted
                    pendingState.value -> PurchaseEvent.Pending
                    else -> PurchaseEvent.Failed
                }
                eventFlow.emit(event)
            }

            PurchaseUpdate.AlreadyOwned -> refresh()

            PurchaseUpdate.Cancelled -> Unit

            is PurchaseUpdate.Failed -> eventFlow.emit(PurchaseEvent.Failed)
        }
    }

    /**
     * Grants Pro for a verified purchase and acknowledges it. [complete] means [purchases] is
     * everything the account owns, so no verified purchase revokes Pro; an update from the
     * listener only lists what changed. Returns whether Pro is owned.
     */
    private suspend fun apply(purchases: List<StorePurchase>, complete: Boolean): Boolean =
        applying.withLock {
            val pro = purchases.filter { PRO_PRODUCT_ID in it.products }
            val owned = pro.filter { it.state == PurchaseState.Purchased && verifier.verify(it) }
            if (pro.any { it.state == PurchaseState.Purchased && it !in owned }) {
                Log.w(TAG, "Ignoring a Clipra Pro purchase whose signature does not check out")
            }
            val pending = pro.any { it.state == PurchaseState.Pending }
            when {
                owned.isNotEmpty() -> {
                    setOwned(true)
                    pendingState.value = false
                    owned.filterNot { it.acknowledged }.forEach { gateway.acknowledge(it.token) }
                }

                complete -> {
                    setOwned(false)
                    pendingState.value = pending
                }

                pending -> pendingState.value = true
            }
            owned.isNotEmpty()
        }

    private suspend fun setOwned(owned: Boolean) {
        dataStore.edit { it[PRO_OWNED] = owned }
    }

    private companion object {
        const val TAG = "ProStore"
        val PRO_OWNED = booleanPreferencesKey("pro_owned")
    }
}
