package tech.idct.clipra.core.engine.media

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.MediaKind
import java.io.File

/**
 * Voiceover (#41): a recording lands in app storage as an .m4a and imports as a recorded sound
 * of the length recorded; a discarded one leaves nothing behind.
 */
@RunWith(AndroidJUnit4::class)
class VoiceRecorderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val recorder = VoiceRecorder(context)

    @Before
    fun grantMicrophone() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
    }

    @Test
    fun aRecordingImportsAsARecordedSound() = runBlocking {
        val recording = recorder.start()
        Thread.sleep(1_500)
        val level = recording.peakDb()
        assertTrue("level $level", level in Recording.SILENCE_DB..0f)
        assertTrue("elapsed ${recording.elapsedUs}", recording.elapsedUs >= 1_500_000)
        val file = recording.stop()
        assertNotNull(file)
        file!!
        assertEquals(recorder.folder, file.parentFile)
        assertTrue(file.length() > 0)

        val cache = MediaCache(
            File(context.cacheDir, "recorder-test/cache").apply { deleteRecursively() },
        )
        val asset = MediaImporter(context, cache).importRecording(file, "voiceover.m4a")
        assertEquals(MediaKind.Audio, asset.kind)
        assertTrue(asset.recorded)
        assertEquals("voiceover.m4a", asset.displayName)
        assertTrue(asset.hasAudio)
        // About what was recorded: the encoder starts a moment after start().
        assertTrue("duration ${asset.durationUs}", asset.durationUs in 1_200_000..2_500_000)
        // Readable as any other media, also for the export.
        val export = ProjectMediaResolver(context, cache, forExport = true, maxImageSide = 1080)
        assertEquals(Uri.fromFile(file).toString(), asset.uri)
        assertEquals(asset.uri, export.source(asset))
        assertTrue(file.delete())
    }

    @Test
    fun aDiscardedRecordingLeavesNothing() {
        val recording = recorder.start()
        Thread.sleep(300)
        recording.cancel()
        assertFalse(recording.file.exists())
    }

    @Test
    fun levelsAreInDecibelsFromTheFloorToFullScale() {
        assertEquals(Recording.SILENCE_DB, Recording.levelDb(0))
        assertEquals(Recording.SILENCE_DB, Recording.levelDb(10))
        assertEquals(0f, Recording.levelDb(32_767))
        assertEquals(-6.02f, Recording.levelDb(16_384), 0.01f)
    }
}
