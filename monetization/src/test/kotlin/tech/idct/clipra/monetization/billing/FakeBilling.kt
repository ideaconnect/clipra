package tech.idct.clipra.monetization.billing

import android.app.Activity
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/** Google Play as the tests need it: owned purchases, a price, and what was acknowledged. */
class FakeBillingGateway : BillingGateway {
    val updateFlow = MutableSharedFlow<PurchaseUpdate>(extraBufferCapacity = 8)
    override val updates: Flow<PurchaseUpdate> = updateFlow

    /** What Google Play lists for the account; null when Play cannot be reached. */
    var owned: List<StorePurchase>? = emptyList()
    var price: String? = "10,00 zł"
    var launch = LaunchResult.Started
    var acknowledgeWorks = true
    val acknowledged = mutableListOf<String>()
    var priceQueries = 0

    override suspend fun queryPurchases(): List<StorePurchase>? = owned

    override suspend fun proPrice(): String? {
        priceQueries++
        return price
    }

    override suspend fun launchPurchase(activity: Activity): LaunchResult = launch

    override suspend fun acknowledge(token: String): Boolean {
        if (!acknowledgeWorks) return false
        acknowledged += token
        owned = owned?.map { if (it.token == token) it.copy(acknowledged = true) else it }
        return true
    }
}

/** An in-memory DataStore, like the app's settings store. */
class MemoryDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

/** A Play-like signing key: purchases are signed with SHA1withRSA, as Google Play signs them. */
class PlayKey {
    private val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        .generateKeyPair()

    /** The public key as Play Console shows it: base64 of the X.509 encoding. */
    val publicKey: String = Base64.getEncoder().encodeToString(pair.public.encoded)

    fun sign(json: String): String {
        val signature = Signature.getInstance("SHA1withRSA")
        signature.initSign(pair.private)
        signature.update(json.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    fun purchase(
        token: String,
        state: PurchaseState = PurchaseState.Purchased,
        acknowledged: Boolean = false,
        product: String = PRO_PRODUCT_ID,
    ): StorePurchase {
        val json = """{"productId":"$product","purchaseToken":"$token","purchaseState":"$state"}"""
        return StorePurchase(
            products = listOf(product),
            state = state,
            token = token,
            acknowledged = acknowledged,
            json = json,
            signature = sign(json),
        )
    }
}
