package tech.idct.clipra.monetization.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/** The one product Clipra sells: Clipra Pro, a non-consumable one-time product. */
const val PRO_PRODUCT_ID = "clipra_pro"

/** A purchase as Google Play reports it, with what Clipra needs to check and keep it. */
data class StorePurchase(
    val products: List<String>,
    val state: PurchaseState,
    val token: String,
    val acknowledged: Boolean,
    /** The purchase data Google Play signed. */
    val json: String,
    /** Google Play's signature of [json], base64. */
    val signature: String,
)

enum class PurchaseState { Purchased, Pending, Unknown }

/** What the purchases listener reports after a purchase flow or a change in the background. */
sealed interface PurchaseUpdate {
    data class Purchases(val purchases: List<StorePurchase>) : PurchaseUpdate

    data object Cancelled : PurchaseUpdate

    data object AlreadyOwned : PurchaseUpdate

    data class Failed(val responseCode: Int) : PurchaseUpdate
}

enum class LaunchResult { Started, AlreadyOwned, Cancelled, Unavailable, Failed }

/**
 * Google Play Billing, as Clipra uses it. [PlayBillingGateway] is the real one; tests use a fake.
 * Every call that needs Play returns null or [LaunchResult.Unavailable] when Play cannot be
 * reached, never throws.
 */
interface BillingGateway {
    /** Purchases that changed, from the purchase sheet or later (a pending payment completing). */
    val updates: Flow<PurchaseUpdate>

    /** Every one-time purchase the Google account owns or has pending; null if Play is unavailable. */
    suspend fun queryPurchases(): List<StorePurchase>?

    /** The formatted price of [PRO_PRODUCT_ID] for this user, such as "10,00 zł". */
    suspend fun proPrice(): String?

    /** Opens the Google Play purchase sheet for Clipra Pro; the result arrives in [updates]. */
    suspend fun launchPurchase(activity: Activity): LaunchResult

    /** Acknowledges a purchase; Google Play refunds purchases left unacknowledged for 3 days. */
    suspend fun acknowledge(token: String): Boolean
}
