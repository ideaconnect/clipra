package tech.idct.clipra.core.engine.export

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.engine.media.Loudness
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.MediaInspector
import tech.idct.clipra.core.engine.media.VoiceCleaner
import tech.idct.clipra.core.engine.render.PauseGate
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.LoudnessTargets
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.VoiceCleanup
import tech.idct.clipra.core.model.VoiceEngine
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelDownloader
import tech.idct.clipra.core.models.ModelStore
import java.io.File
import java.util.Locale

/**
 * The exit check of the audio epic (#35) on real footage: each video is cleaned up with
 * DeepFilterNet 3 at the default strength, normalized to -14 LUFS and exported at 1080p by
 * [ExportRenderer], as Export makes it. A voice clip has to land within 1 LU of the target, no
 * export may end up more than 1 LU over it, and none may reach 0 dBTP. A clip that is mostly
 * noise (see [MOSTLY_NOISE_LU]) keeps little after its cleanup, with peaks far above its
 * loudness, and the -1 dBTP limiter holds it under the target, so its loudness is only logged.
 *
 * The footage is not in the repository: push the videos to [FOOTAGE] first
 * (`adb push clips/. /data/local/tmp/clipra-footage/`). The test reads them through the shell,
 * one at a time, and is skipped without them. Each clip is normalized from the loudness of its
 * sound as recorded, which a clip keeps when it is measured before its cleanup is ready (the
 * Audio screen closed early, the tablet's Normalize switch); the instrumentation argument
 * `normalizeFrom=cleanup` measures the cleaned sound instead, as the Audio screen does once the
 * cleanup is there. Logcat tag `Footage` has the numbers. The exports stay in the cache's
 * `footage/exports` folder for listening (`adb exec-out run-as <test package> cat ...`).
 */
@RunWith(AndroidJUnit4::class)
class FootageSoundTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val fromCleanup =
        InstrumentationRegistry.getArguments().getString("normalizeFrom") == "cleanup"
    private val dir = File(context.cacheDir, "footage")

    @Test
    fun cleanedFootageIsExportedAtTheSocialTarget() = runBlocking {
        val names = footage()
        assumeTrue("no footage in $FOOTAGE", names.isNotEmpty())
        dir.deleteRecursively()
        val exports = File(dir, "exports").apply { mkdirs() }
        val store = ModelStore(File(context.filesDir, "models"))
        ModelDownloader(store).download(ModelCatalog.deepFilterNet)
        val cache = MediaCache(File(dir, "cache"))
        val cleaner = VoiceCleaner(context, cache, store)
        val cleanup = VoiceCleanup(VoiceEngine.DeepFilterNet)
        val misses = ArrayList<String>()
        for (name in names) {
            val file = copy(name)
            val asset = MediaInspector.inspect(context, Uri.fromFile(file))
            val seconds = asset.durationUs / 1e6
            val recorded = Loudness.measure(context, file.path, 0, asset.durationUs)
            if (recorded == null) {
                Log.i(TAG, "$name: no sound, skipped")
                continue
            }
            val started = SystemClock.elapsedRealtime()
            val mix = cleaner.prepare(asset, cleanup)
            val cleaningS = (SystemClock.elapsedRealtime() - started) / 1e3
            val cleaned = mix?.let { Loudness.measure(context, it.path, 0, asset.durationUs) }
            val voice = cleaned != null && recorded.lufs - cleaned.lufs < MOSTLY_NOISE_LU
            val stored = if (fromCleanup && cleaned != null) cleaned else recorded
            val project = project(asset, cleanup, stored)
            val exportStarted = SystemClock.elapsedRealtime()
            val rendered = ExportRenderer(context, cache, cleaner)
                .render(project, ExportSettings(), PauseGate()) {}
            val exportS = (SystemClock.elapsedRealtime() - exportStarted) / 1e3
            val exported = Loudness.measure(context, rendered.file.path, 0, asset.durationUs)
            val kept = File(exports, "${file.nameWithoutExtension}-clean-14.mp4")
            rendered.file.renameTo(kept)
            file.delete()
            Log.i(
                TAG,
                (
                    "%s: %.1f s, %s; recorded %.1f LUFS, %.1f dBTP; cleaned %s " +
                        "(DeepFilterNet %.1f s, %.2fx real time); normalized from %s; " +
                        "export %s in %.1f s"
                    ).format(
                    Locale.ROOT,
                    name,
                    seconds,
                    if (voice) "voice" else "mostly noise",
                    recorded.lufs,
                    recorded.truePeakDb,
                    levels(cleaned),
                    cleaningS,
                    seconds / cleaningS,
                    if (stored === cleaned) "cleanup" else "recording",
                    levels(exported),
                    exportS,
                ),
            )
            val target = LoudnessTargets.SOCIAL
            when {
                mix == null -> misses += "$name: no cleaned sound"
                exported == null -> misses += "$name: the export is silent"
                exported.truePeakDb >= 0f -> misses += "$name: peak ${exported.truePeakDb} dBTP"
                exported.lufs > target + 1f -> misses += "$name: ${exported.lufs} LUFS"
                voice && exported.lufs < target - 1f -> misses += "$name: ${exported.lufs} LUFS"
            }
        }
        assertTrue(misses.joinToString("\n"), misses.isEmpty())
    }

    private fun levels(measured: Loudness.Measurement?) = measured?.let {
        "%.1f LUFS, %.1f dBTP".format(Locale.ROOT, it.lufs, it.truePeakDb)
    } ?: "-"

    /** One clip with all of [asset], cleaned up and normalized from [measured]. */
    private fun project(asset: MediaAsset, cleanup: VoiceCleanup, measured: Loudness.Measurement) =
        Project(
            id = "footage-${asset.id.take(8)}",
            name = asset.displayName,
            format = Format(
                ratio = if (asset.displaySize.width >= asset.displaySize.height) {
                    AspectRatio.Landscape16x9
                } else {
                    AspectRatio.Portrait9x16
                },
            ),
            media = listOf(asset),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        Clip(
                            "clip",
                            ClipType.Video,
                            0,
                            asset.durationUs,
                            MediaRef(asset.id, 0, asset.durationUs),
                            audio = AudioSettings(
                                loudnessTarget = LoudnessTargets.SOCIAL,
                                measuredLufs = measured.lufs,
                                truePeakDb = measured.truePeakDb,
                                voiceCleanup = cleanup,
                            ),
                        ),
                    ),
                ),
            ),
        )

    /** The names of the videos in [FOOTAGE], read through the shell. */
    private fun footage(): List<String> = shell("ls $FOOTAGE").lines()
        .map { it.trim() }
        .filter { it.endsWith(".mp4", ignoreCase = true) }
        .sorted()

    private fun copy(name: String): File {
        val file = File(dir, name).apply { parentFile?.mkdirs() }
        val output = instrumentation.uiAutomation.executeShellCommand("cat $FOOTAGE/$name")
        ParcelFileDescriptor.AutoCloseInputStream(output).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun shell(command: String): String {
        val output = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(output).use {
            it.readBytes().decodeToString()
        }
    }

    private companion object {
        const val TAG = "Footage"
        const val FOOTAGE = "/data/local/tmp/clipra-footage"

        /**
         * A clip the cleanup takes this much loudness from is mostly noise: its voice, if any, is
         * more than about 5 dB under the noise.
         */
        const val MOSTLY_NOISE_LU = 6f
    }
}
