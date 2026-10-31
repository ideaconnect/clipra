package tech.idct.clipra.core.engine.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * A sound deleted while its waveform is made, as a discarded voiceover: the failure reaches the
 * caller, which shows no waveform, instead of ending the app on a decoding thread.
 */
@RunWith(AndroidJUnit4::class)
class WaveformsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aMissingSoundFailsOnTheCallersThread() {
        val root = File(context.cacheDir, "waveforms").apply { mkdirs() }
        val missing = File(root, "gone.m4a").apply { delete() }
        // A short sound decodes in one slice, a long one in four; both fail the same way.
        for (durationUs in listOf(5_000_000L, 60_000_000L)) {
            val file = File(root, "gone-$durationUs.wave")
            assertThrows(IOException::class.java) {
                Waveforms.waveform(context, missing.path, durationUs, file)
            }
            assertFalse(file.exists())
        }
    }
}
