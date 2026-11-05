package tech.idct.clipra.ml.translate

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.math.roundToInt

/**
 * Marian on the phone (#47, ADR 0007): English to Polish with Mozilla's model, downloaded from
 * its registry and checked with SHA-256 the first time. Timings go to logcat under Marian.
 *
 * Each line is compared as a whole with what the model gives on x86_64, by chrF ([Chrf]). The
 * int8 products are computed differently on arm64, so a phone may pick another word now and then
 * ("jedzimy" for "jedziemy", #202). That costs a few points, while a broken path, an untranslated
 * line or another line's translation falls far below [Chrf.SAME_TRANSLATION].
 */
@RunWith(AndroidJUnit4::class)
class MarianTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.cacheDir, "marian-test").apply { mkdirs() }

    @Test
    fun translatesCaptionsFromEnglishToPolish() {
        val (model, vocab, shortlist) = FILES.map(::fetch)
        val lines = LINES.map { it.first }

        val loadStart = System.nanoTime()
        Marian.open(model, vocab, shortlist).use { marian ->
            Log.i(TAG, "loaded in ${(System.nanoTime() - loadStart) / 1_000_000} ms")
            val start = System.nanoTime()
            val polish = marian.translate(lines)
            Log.i(TAG, "${lines.size} lines in ${(System.nanoTime() - start) / 1_000_000} ms")

            assertEquals(lines.size, polish.size)
            polish.zip(LINES).forEach { (actual, line) ->
                assertSameTranslation(line.second, actual)
            }
        }
        // A second model in the same process, as a pair through English loads two.
        Marian.open(model, vocab, shortlist).use { marian ->
            assertSameTranslation(LINES[0].second, marian.translate(listOf(lines[0]))[0])
        }
    }

    private fun assertSameTranslation(expected: String, actual: String) {
        val score = Chrf.score(actual, expected)
        val shown = (score * 10).roundToInt() / 10.0
        Log.i(TAG, "chrF $shown: $actual")
        assertTrue(
            "\"$actual\" scores chrF $shown against \"$expected\"",
            score >= Chrf.SAME_TRANSLATION,
        )
    }

    /** The file of [url], unpacked, once its download matches [sha256]. */
    private fun fetch(file: Triple<String, String, String>): File {
        val (url, sha256, name) = file
        val unpacked = File(folder, name)
        if (unpacked.exists()) return unpacked
        val gz = File(folder, "$name.gz")
        URL(url).openStream().use { input -> gz.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(gz.readBytes())
        assertEquals(url, sha256, digest.joinToString("") { "%02x".format(it) })
        GZIPInputStream(gz.inputStream()).use { input ->
            unpacked.outputStream().use { input.copyTo(it) }
        }
        gz.delete()
        return unpacked
    }

    private companion object {
        const val TAG = "Marian"

        /**
         * Captions and what the model makes of them on x86_64 (the Pixel_3_API_29 emulator). These
         * are the model's own translations, mistakes included ("docieliśmy do Lizbonie"): the test
         * checks that Marian runs the model right, not how well the model translates.
         */
        val LINES = listOf(
            "We finally made it to Lisbon" to "W końcu docieliśmy do Lizbonie",
            "The light here is unreal" to "Światło tutaj jest nierealne",
            "Tomorrow we're taking the tram up to Alfama 🚋" to
                "Jutro jedziemy tramwajem do Alfamy 🚋",
        )
        const val BASE = "https://storage.googleapis.com/" +
            "moz-fx-translations-data--303e-prod-translations-data/" +
            "models/en-pl/retrain_hr_fix_names_YmTlfuUsSKCbUp75UXaUuw/exported/"
        val FILES = listOf(
            Triple(
                BASE + "model.enpl.intgemm.alphas.bin.gz",
                "80b549e5f4d1d1db3f66f850c71683c9449a259e6eac2abe3b8f04f0781088ed",
                "model.enpl.intgemm.alphas.bin",
            ),
            Triple(
                BASE + "vocab.enpl.spm.gz",
                "13b8f1dc8bf7a29d052008d78aa4429b1311f8fd0fcb63c5490fe1714b00606c",
                "vocab.enpl.spm",
            ),
            Triple(
                BASE + "lex.50.50.enpl.s2t.bin.gz",
                "6e7cbf38d9298877d9631a33a7bd3608326ba169be9a0fe9c7b2ee0c6c7e6d59",
                "lex.50.50.enpl.s2t.bin",
            ),
        )
    }
}
