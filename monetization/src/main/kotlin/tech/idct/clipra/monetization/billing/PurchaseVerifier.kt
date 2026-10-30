package tech.idct.clipra.monetization.billing

import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Checks Google Play's signature of a purchase against the app's Play public key (Play Console,
 * Monetization setup), on the device: Clipra has no backend (docs/product/monetization.md,
 * "Verification").
 *
 * Without a key, purchases pass only when [trustUnsigned] is set (debug builds); release builds
 * cannot be built without the key.
 */
class PurchaseVerifier(base64Key: String, private val trustUnsigned: Boolean) {
    private val key: PublicKey? = base64Key.takeIf { it.isNotBlank() }?.let {
        val spec = X509EncodedKeySpec(Base64.getDecoder().decode(it))
        KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    fun verify(purchase: StorePurchase): Boolean {
        val key = key ?: return trustUnsigned
        return try {
            val signature = Signature.getInstance("SHA1withRSA")
            signature.initVerify(key)
            signature.update(purchase.json.toByteArray(Charsets.UTF_8))
            signature.verify(Base64.getDecoder().decode(purchase.signature))
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Purchase signature check failed", e)
            false
        } catch (e: IllegalArgumentException) {
            // Not base64.
            false
        }
    }

    companion object {
        private const val TAG = "PurchaseVerifier"

        /**
         * The key as the build stores it: XOR-ed with a mask and base64-encoded, so the plain key
         * is not one string search away in the APK. The build applies the same mask.
         */
        fun reveal(obfuscated: String): String {
            if (obfuscated.isEmpty()) return ""
            val bytes = Base64.getDecoder().decode(obfuscated)
            val mask = KEY_MASK.toByteArray(Charsets.UTF_8)
            return String(
                ByteArray(bytes.size) { i ->
                    (bytes[i].toInt() xor mask[i % mask.size].toInt()).toByte()
                },
                Charsets.UTF_8,
            )
        }

        /** Keep in sync with monetization/build.gradle.kts. */
        const val KEY_MASK = "tech.idct.clipra.pro"
    }
}
