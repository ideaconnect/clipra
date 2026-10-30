package tech.idct.clipra.monetization.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Play Billing Library 9 with automatic service reconnection and pending one-time purchases
 * (docs/product/monetization.md, section 2). One client for the whole app.
 */
@Singleton
class PlayBillingGateway @Inject constructor(@ApplicationContext context: Context) :
    BillingGateway {
    private val updateFlow = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 16)
    override val updates: Flow<PurchaseUpdate> = updateFlow

    private val client = BillingClient.newBuilder(context)
        .setListener { result, purchases -> updateFlow.tryEmit(update(result, purchases)) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private val connecting = Mutex()
    private val loading = Mutex()
    private var details: ProductDetails? = null

    override suspend fun queryPurchases(): List<StorePurchase>? {
        if (!connected()) return null
        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        val result = client.queryPurchasesAsync(params)
        if (result.billingResult.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "Purchases query failed: ${result.billingResult.debugMessage}")
            return null
        }
        return result.purchasesList.map { it.toStore() }
    }

    override suspend fun proPrice(): String? =
        proDetails()?.oneTimePurchaseOfferDetails?.formattedPrice

    override suspend fun launchPurchase(activity: Activity): LaunchResult {
        val product = proDetails() ?: return LaunchResult.Unavailable
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product)
        product.oneTimePurchaseOfferDetails?.offerToken?.let { productParams.setOfferToken(it) }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams.build()))
            .build()
        // The purchase sheet must be launched from the main thread.
        val result = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params) }
        return when (result.responseCode) {
            BillingResponseCode.OK -> LaunchResult.Started

            BillingResponseCode.USER_CANCELED -> LaunchResult.Cancelled

            BillingResponseCode.ITEM_ALREADY_OWNED -> LaunchResult.AlreadyOwned

            in UNAVAILABLE -> LaunchResult.Unavailable

            else -> {
                Log.w(TAG, "Purchase sheet failed: ${result.debugMessage}")
                LaunchResult.Failed
            }
        }
    }

    override suspend fun acknowledge(token: String): Boolean {
        if (!connected()) return false
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
        val result = client.acknowledgePurchase(params)
        if (result.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "Acknowledgement failed: ${result.debugMessage}")
        }
        return result.responseCode == BillingResponseCode.OK
    }

    /** Clipra Pro's details, loaded once; they carry the price and the offer to buy. */
    private suspend fun proDetails(): ProductDetails? = loading.withLock {
        details?.let { return it }
        if (!connected()) return null
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRO_PRODUCT_ID)
            .setProductType(ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        val result = client.queryProductDetails(params)
        if (result.billingResult.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "Product query failed: ${result.billingResult.debugMessage}")
            return null
        }
        result.productDetailsList.orEmpty()
            .firstOrNull { it.productId == PRO_PRODUCT_ID }
            ?.also { details = it }
    }

    /**
     * Connects once; afterwards the library reconnects by itself when a call finds the service
     * disconnected. False when Google Play is missing, signed out or does not answer in time.
     */
    private suspend fun connected(): Boolean = connecting.withLock {
        if (client.isReady) return true
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                client.startConnection(
                    object : BillingClientStateListener {
                        override fun onBillingSetupFinished(result: BillingResult) {
                            if (result.responseCode != BillingResponseCode.OK) {
                                Log.w(
                                    TAG,
                                    "Google Play Billing unavailable: ${result.debugMessage}",
                                )
                            }
                            if (continuation.isActive) {
                                continuation.resume(result.responseCode == BillingResponseCode.OK)
                            }
                        }

                        override fun onBillingServiceDisconnected() {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                )
            }
        }
        connected == true
    }

    private fun update(result: BillingResult, purchases: List<Purchase>?): PurchaseUpdate =
        when (result.responseCode) {
            BillingResponseCode.OK -> PurchaseUpdate.Purchases(
                purchases.orEmpty().map {
                    it.toStore()
                },
            )

            BillingResponseCode.USER_CANCELED -> PurchaseUpdate.Cancelled

            BillingResponseCode.ITEM_ALREADY_OWNED -> PurchaseUpdate.AlreadyOwned

            else -> PurchaseUpdate.Failed(result.responseCode)
        }

    private fun Purchase.toStore() = StorePurchase(
        products = products,
        state = when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.Purchased
            Purchase.PurchaseState.PENDING -> PurchaseState.Pending
            else -> PurchaseState.Unknown
        },
        token = purchaseToken,
        acknowledged = isAcknowledged,
        json = originalJson,
        signature = signature,
    )

    private companion object {
        const val TAG = "PlayBilling"
        const val CONNECT_TIMEOUT_MS = 10_000L
        val UNAVAILABLE = setOf(
            BillingResponseCode.SERVICE_DISCONNECTED,
            BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingResponseCode.BILLING_UNAVAILABLE,
            BillingResponseCode.SERVICE_TIMEOUT,
            BillingResponseCode.NETWORK_ERROR,
        )
    }
}
