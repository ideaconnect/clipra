package tech.idct.clipra.core.engine.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.MediaKind
import java.io.File

/**
 * Importing a 4K clip (#16): the asset is ready at once, preview reads the original until the
 * 720p proxy exists, then the proxy.
 */
@RunWith(AndroidJUnit4::class)
class MediaImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext

    private fun copyAsset(name: String): File {
        val file = File(context.cacheDir, "import-test/$name").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open("import/$name").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    @Test
    fun a4kClipIsEditableAtOnceAndGetsA720pProxy() = runBlocking {
        val source = Uri.fromFile(copyAsset("uhd.mp4"))
        val cache =
            MediaCache(File(context.cacheDir, "import-test/cache").apply { deleteRecursively() })

        val asset = MediaInspector.inspect(context, source)
        assertEquals(MediaKind.Video, asset.kind)
        assertEquals(3840, asset.width)
        assertEquals(2160, asset.height)
        assertTrue("duration ${asset.durationUs}", asset.durationUs in 900_000..1_100_000)
        assertTrue(asset.hasAudio)
        assertEquals(64, asset.id.length)
        // The same file always gets the same id.
        assertEquals(asset.id, MediaInspector.fingerprint(context, source))

        assertTrue(ProxyGenerator.needsProxy(asset))
        val preview = ProjectMediaResolver(context, cache, forExport = false, maxImageSide = 1080)
        // Before the proxy: preview plays the original.
        assertEquals(source.toString(), preview.source(asset))

        assertNotNull(Thumbnails.thumbnail(context, asset, cache))
        ProxyGenerator.generate(context, source, cache.proxy(asset.id))

        assertEquals(cache.proxy(asset.id).path, preview.source(asset))
        val export = ProjectMediaResolver(context, cache, forExport = true, maxImageSide = 3840)
        assertEquals(source.toString(), export.source(asset))
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(cache.proxy(asset.id).path)
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
            )!!.toInt()
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
            )!!.toInt()
            assertEquals(720, minOf(width, height))
            assertEquals(1280, maxOf(width, height))
            assertEquals(
                "yes",
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO),
            )
        }
        assertNotNull(Thumbnails.filmstripFrame(context, asset, cache, 0))
    }

    @Test
    fun missingMediaResolvesToNothing() {
        val gone = MediaInspector.inspect(context, Uri.fromFile(copyAsset("uhd.mp4")))
            .copy(
                id = "gone",
                uri = Uri.fromFile(File(context.cacheDir, "no-such-file.mp4")).toString(),
            )
        val cache = MediaCache(File(context.cacheDir, "import-test/empty"))
        val resolver = ProjectMediaResolver(context, cache, forExport = false, maxImageSide = 1080)
        assertEquals(null, resolver.source(gone))
        assertFalse(ProjectMediaResolver.isAvailable(context, gone.uri))
    }
}
