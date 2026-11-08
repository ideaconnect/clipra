package tech.idct.clipra.core.engine.export

import android.content.Context
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.Cutouts
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.VoiceCleaner
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.models.ModelStore
import java.io.File
import java.io.IOException

/**
 * A failed export leaves its cause in the log (#236): the failure screen only says "Something
 * went wrong", so the log is all there is to go on when an export fails on someone's phone.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class ExportFailureLogTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theCodeIsMedia3sOwnBelowOurException() {
        val media3 = ExportException.createForAssetLoader(
            IOException("gone"),
            ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
        )
        val failed = ExportFailedException(ExportFailure.MissingMedia, media3)

        assertEquals("ERROR_CODE_IO_FILE_NOT_FOUND", ExportErrors.codeName(failed))
        assertEquals(
            "ERROR_CODE_IO_FILE_NOT_FOUND",
            ExportErrors.codeName(IllegalStateException("wrapped", failed)),
        )
    }

    @Test
    fun anExportThatFailsLogsWhy() = runBlocking {
        // A clip whose file is there but holds no video: its decoding fails.
        val dir = File(context.cacheDir, "export-log-test").apply { mkdirs() }
        val broken = File(dir, "broken.mp4").apply { writeText("This is not a video.") }
        val asset = MediaAsset(
            id = "broken",
            uri = Uri.fromFile(broken).toString(),
            kind = MediaKind.Video,
            durationUs = 2_000_000,
            width = 640,
            height = 360,
        )
        val project = Project(
            id = "export-log-test",
            name = "Export log test",
            media = listOf(asset),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip("a", ClipType.Video, 0, 2_000_000, MediaRef(asset.id, 0, 2_000_000)),
                    ),
                ),
            ),
        )
        val cache = MediaCache(File(dir, "cache"))
        val manager = ExportManager(
            context,
            cache,
            VoiceCleaner(context, cache, ModelStore(File(context.filesDir, "models"))),
            Cutouts(context, cache),
        )
        val before = logged().count(::isFailure)

        manager.start(project, ExportSettings())
        val state = withTimeout(TIMEOUT_MS) {
            manager.state.first { it is ExportState.Failed || it is ExportState.Done }
        }

        assertTrue("$state", state is ExportState.Failed)
        val failed = state as ExportState.Failed
        // The clip is opened inside Media3's frame processing, so Media3 names the error.
        assertNotNull(failed.errorCodeName)
        val lines = loggedAfter(before)
        val failures = lines.filter(::isFailure)
        assertEquals(lines.joinToString("\n"), before + 1, failures.size)
        val entry = lines.subList(lines.lastIndexOf(failures.last()), lines.size)
        assertTrue(
            entry.first(),
            entry.first().endsWith(ExportErrors.message(failed.failure, failed.errorCodeName)),
        )
        // The exception follows the line, with its causes.
        assertTrue(
            entry.joinToString("\n"),
            entry.any { "Caused by: ${ExportException::class.java.name}" in it },
        )
    }

    private fun isFailure(line: String) = "Export failed: " in line

    /**
     * [logged] once it has more than [before] failure lines, or after [LOG_WAIT_MS]. logd takes
     * the line in on its own thread, so right after the export it may not be readable yet.
     */
    private fun loggedAfter(before: Int): List<String> {
        val until = SystemClock.uptimeMillis() + LOG_WAIT_MS
        var lines = logged()
        while (lines.count(::isFailure) <= before && SystemClock.uptimeMillis() < until) {
            Thread.sleep(LOG_POLL_MS)
            lines = logged()
        }
        return lines
    }

    /** This process's lines from ExportManager in logcat. */
    private fun logged(): List<String> {
        val logcat = ProcessBuilder(
            "logcat",
            "-d",
            "--pid=${Process.myPid()}",
            "-s",
            "ExportManager:W",
        ).redirectErrorStream(true).start()
        return logcat.inputStream.bufferedReader().readLines().also { logcat.waitFor() }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val LOG_WAIT_MS = 5_000L
        const val LOG_POLL_MS = 100L
    }
}
