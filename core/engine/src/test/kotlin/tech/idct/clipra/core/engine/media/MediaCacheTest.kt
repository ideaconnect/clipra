package tech.idct.clipra.core.engine.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Settings > Clear (#74): caches go, projects and models stay. */
class MediaCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun clearingTheCacheNeverTouchesProjectsOrModels() {
        // The app's layout: the cache in cacheDir/media, projects and models in filesDir.
        val cacheDir = folder.newFolder("cache")
        val filesDir = folder.newFolder("files")
        val cache = MediaCache(File(cacheDir, "media"))
        val project = File(filesDir, "projects/lisbon/project.json").write("{}")
        val model = File(filesDir, "models/whisper-base/ggml-base.bin").write("model")
        val export = File(cacheDir, "export-in-progress.mp4").write("video")
        cache.proxy("clip").write("proxy")
        cache.thumbnail("clip").write("thumb")
        cache.waveform("clip").write("peaks")
        cache.voiceRecorded("clip").write("voice")
        assertEquals(20L, cache.sizeBytes())
        val voiceVersion = cache.voiceVersion.value

        cache.clear()

        assertEquals(0L, cache.sizeBytes())
        assertTrue(project.exists() && model.exists() && export.exists())
        // Cleaned voices are made again: whoever shows one hears of the change.
        assertEquals(voiceVersion + 1, cache.voiceVersion.value)
    }

    private fun File.write(text: String): File {
        parentFile?.mkdirs()
        writeText(text)
        return this
    }
}
