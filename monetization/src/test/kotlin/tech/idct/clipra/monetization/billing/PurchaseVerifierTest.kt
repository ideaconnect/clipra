package tech.idct.clipra.monetization.billing

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PurchaseVerifierTest {
    private val play = PlayKey()

    @Test
    fun acceptsWhatGooglePlaySigned() {
        val verifier = PurchaseVerifier(play.publicKey, trustUnsigned = false)
        assertTrue(verifier.verify(play.purchase("token")))
    }

    @Test
    fun rejectsChangedDataAndOtherSignatures() {
        val verifier = PurchaseVerifier(play.publicKey, trustUnsigned = true)
        val purchase = play.purchase("token")
        assertFalse(verifier.verify(purchase.copy(json = purchase.json.replace("token", "other"))))
        assertFalse(verifier.verify(purchase.copy(signature = PlayKey().sign(purchase.json))))
        assertFalse(verifier.verify(purchase.copy(signature = "not base64!")))
        assertFalse(verifier.verify(purchase.copy(signature = "")))
    }

    @Test
    fun withoutAKeyOnlyDebugBuildsTrustPurchases() {
        val purchase = play.purchase("token").copy(signature = "")
        assertTrue(PurchaseVerifier("", trustUnsigned = true).verify(purchase))
        assertFalse(PurchaseVerifier("", trustUnsigned = false).verify(purchase))
    }

    @Test
    fun revealsTheKeyTheBuildMasked() {
        // What monetization/build.gradle.kts does with clipraPlayLicenseKey.
        val mask = PurchaseVerifier.KEY_MASK.toByteArray()
        val bytes = play.publicKey.toByteArray()
        val masked = ByteArray(bytes.size) { i ->
            (bytes[i].toInt() xor mask[i % mask.size].toInt()).toByte()
        }
        val stored = Base64.getEncoder().encodeToString(masked)
        assertFalse(stored.contains(play.publicKey.take(16)))
        assertEquals(play.publicKey, PurchaseVerifier.reveal(stored))
        assertEquals("", PurchaseVerifier.reveal(""))
    }
}
