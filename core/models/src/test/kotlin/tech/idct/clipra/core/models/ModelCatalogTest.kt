package tech.idct.clipra.core.models

import android.text.format.Formatter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The manifest (#43): every URL is an official source pinned to a commit, every file has its
 * real size and SHA-256, and the sizes show in decimal units as the design's issue asks.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ModelCatalogTest {
    // Official sources only (docs/architecture.md, section 6), at a full commit hash: never
    // a branch such as main, a tag that can move, or another host.
    private val official = listOf(
        Regex("https://huggingface\\.co/ggerganov/whisper\\.cpp/resolve/[0-9a-f]{40}/[^/]+"),
        Regex("https://github\\.com/Rikorose/DeepFilterNet/raw/[0-9a-f]{40}/models/[^/]+"),
    )

    @Test
    fun everyDownloadIsAnOfficialSourcePinnedToACommit() {
        val downloads = ModelCatalog.all.mapNotNull { it.download }
        assertEquals(5, downloads.size)
        for (file in downloads) {
            assertTrue(file.url, official.any { it.matches(file.url) })
            assertTrue(file.url, Regex("[0-9a-f]{64}").matches(file.sha256))
            assertTrue(file.url, file.bytes > 0)
        }
    }

    @Test
    fun theCatalogueHasOneDefaultAndUniqueIds() {
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.toSet().size)
        assertEquals(listOf(ModelCatalog.whisperBase), ModelCatalog.all.filter { it.isDefault })
        assertEquals(listOf(ModelCatalog.rnnoise), ModelCatalog.all.filter { it.builtIn })
    }

    @Test
    fun sizesAreRealAndDecimal() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun shown(bytes: Long) = Formatter.formatShortFileSize(context, bytes).replace(' ', ' ')
        assertEquals("78 MB", shown(ModelCatalog.whisperTiny.download!!.bytes))
        assertEquals("148 MB", shown(ModelCatalog.whisperBase.download!!.bytes))
        assertEquals("488 MB", shown(ModelCatalog.whisperSmall.download!!.bytes))
        assertEquals("574 MB", shown(ModelCatalog.whisperLargeTurbo.download!!.bytes))
        // DeepFilterNet 3 downloads 8.0 MB and takes 8.6 MB unpacked.
        assertEquals("8.0 MB", shown(ModelCatalog.deepFilterNet.download!!.bytes))
        assertEquals(8_589_309L, ModelCatalog.deepFilterNet.installedBytes)
        assertEquals("8.6 MB", shown(ModelCatalog.deepFilterNet.installedBytes))
    }
}
