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

/**
 * Marian on the phone (#47, ADR 0007): English to Polish with Mozilla's model, downloaded from
 * its registry and checked with SHA-256 the first time. Timings go to logcat under Marian.
 */
@RunWith(AndroidJUnit4::class)
class MarianTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = File(context.cacheDir, "marian-test").apply { mkdirs() }

    @Test
    fun translatesCaptionsFromEnglishToPolish() {
        val (model, vocab, shortlist) = FILES.map(::fetch)

        val loadStart = System.nanoTime()
        Marian.open(model, vocab, shortlist).use { marian ->
            Log.i(TAG, "loaded in ${(System.nanoTime() - loadStart) / 1_000_000} ms")
            val lines = listOf(
                "We finally made it to Lisbon",
                "The light here is unreal",
                "Tomorrow we're taking the tram up to Alfama 🚋",
            )
            val start = System.nanoTime()
            val polish = marian.translate(lines)
            Log.i(TAG, "${lines.size} lines in ${(System.nanoTime() - start) / 1_000_000} ms")
            polish.forEach { Log.i(TAG, it) }

            assertEquals(lines.size, polish.size)
            assertTrue(polish[0], "Lizbon" in polish[0])
            assertTrue(polish[1], "świat" in polish[1].lowercase())
            assertTrue(polish[2], "Alfam" in polish[2])
        }
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
