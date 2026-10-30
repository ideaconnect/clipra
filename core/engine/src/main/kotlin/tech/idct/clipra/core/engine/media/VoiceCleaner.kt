package tech.idct.clipra.core.engine.media

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.audio.NoiseProfile
import tech.idct.clipra.core.audio.SpectralGate
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.NoiseReduction
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.VoiceCleanup
import tech.idct.clipra.core.model.VoiceEngine
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelStore
import tech.idct.clipra.ml.voice.DeepFilterNet
import tech.idct.clipra.ml.voice.OnnxDeepFilterModel
import tech.idct.clipra.ml.voice.RnNoise
import tech.idct.clipra.ml.voice.VoiceDenoiser
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI voice cleanup (#39) on the device. A media's sound is decoded once to mono 48 kHz, the
 * engine cleans it once, and each strength is a mix of the two; clips play the mix once it is
 * there and the sound as recorded until then. Everything lives in [MediaCache].
 */
@Singleton
class VoiceCleaner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cache: MediaCache,
    private val models: ModelStore,
) {
    private val locks = ConcurrentHashMap<String, Any>()
    private val progress = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Cleanups under way, by [key], with their progress from 0 to 1. */
    val running: StateFlow<Map<String, Float>> = progress.asStateFlow()

    /** The mix [cleanup] makes of [asset]'s sound, once it is ready. */
    fun mix(asset: MediaAsset, cleanup: VoiceCleanup): File? =
        cache.voiceMix(asset.id, cleanup).takeIf { it.exists() }

    /**
     * What the clip plays (#38, #39): its sound cleaned up by [cleanup] and gated by [noise],
     * once that is ready; null when neither is set or it is not ready yet.
     */
    fun played(asset: MediaAsset, cleanup: VoiceCleanup?, noise: Float?): File? = when {
        noise != null -> gated(asset, cleanup, noise)
        cleanup != null -> mix(asset, cleanup)
        else -> null
    }

    private fun gated(asset: MediaAsset, cleanup: VoiceCleanup?, noise: Float): File? =
        cache.voiceGated(asset.id, cleanup, NoiseReduction.percent(noise)).takeIf { it.exists() }

    /** The cleaned sound of [asset] at full strength, for its waveform; null until ready. */
    fun cleaned(asset: MediaAsset, engine: VoiceEngine): File? =
        cache.voiceCleaned(asset.id, engine).takeIf { it.exists() }

    /** Whether [engine] can run: RNNoise is built in, DeepFilterNet needs its download. */
    fun isAvailable(engine: VoiceEngine): Boolean = when (engine) {
        VoiceEngine.RNNoise -> true
        VoiceEngine.DeepFilterNet -> models.isInstalled(ModelCatalog.deepFilterNet)
    }

    /** Cleans up in the background unless the mix is ready (WorkManager, once per mix). */
    fun request(asset: MediaAsset, cleanup: VoiceCleanup) = request(asset, cleanup, null)

    /** Cleans up and gates in the background unless that is ready (once per result). */
    fun request(asset: MediaAsset, cleanup: VoiceCleanup?, noise: Float?) {
        if (cleanup == null && noise == null) return
        if (played(asset, cleanup, noise) != null) return
        if (cleanup != null && !isAvailable(cleanup.engine)) return
        VoiceCleanupWorker.enqueue(context, asset, cleanup, noise)
    }

    /** Requests every cleanup of [project] that is not ready, such as after the cache was cleared. */
    fun requestAll(project: Project) {
        for (clip in project.layers.flatMap { it.clips }) {
            val cleanup = clip.audio.voiceCleanup
            val noise = clip.audio.noiseReduction
            if (cleanup == null && noise == null) continue
            val asset = clip.source?.let { project.asset(it.mediaId) } ?: continue
            if (asset.hasAudio || clip.type == ClipType.Audio) request(asset, cleanup, noise)
        }
    }

    /**
     * Does the work now and returns the mix; null when the media has no sound, the engine's
     * model is missing or [stopped] said so. Safe to call from several threads.
     */
    fun prepare(
        asset: MediaAsset,
        cleanup: VoiceCleanup,
        stopped: () -> Boolean = { false },
    ): File? = synchronized(locks.getOrPut(asset.id) { Any() }) {
        mix(asset, cleanup)?.let { return it }
        if (!isAvailable(cleanup.engine)) return null
        val key = key(asset, cleanup.engine)
        progress.update { it + (key to 0f) }
        try {
            val recorded = cache.voiceRecorded(asset.id)
            if (!recorded.exists() && !decode(asset, recorded, key)) return null
            val cleaned = cache.voiceCleaned(asset.id, cleanup.engine)
            if (!cleaned.exists() && !clean(recorded, cleaned, cleanup.engine, key, stopped)) {
                return null
            }
            val target = cache.voiceMix(asset.id, cleanup)
            mix(recorded, cleaned, cleanup.noisyShare, target)
            cache.voiceReady()
            target
        } finally {
            progress.update { it - key }
        }
    }

    /**
     * Does the cleanup and the noise reduction now, and returns what plays; null when neither
     * is set, the media has no sound, the engine's model is missing or [stopped] said so.
     */
    fun prepare(
        asset: MediaAsset,
        cleanup: VoiceCleanup?,
        noise: Float?,
        stopped: () -> Boolean = { false },
    ): File? {
        if (noise == null) return cleanup?.let { prepare(asset, it, stopped) }
        return synchronized(locks.getOrPut(asset.id) { Any() }) {
            gated(asset, cleanup, noise)?.let { return it }
            val base = if (cleanup != null) {
                prepare(asset, cleanup, stopped) ?: return null
            } else {
                recorded(asset) ?: return null
            }
            val key = gateKey(asset)
            progress.update { it + (key to 0f) }
            try {
                val target = cache.voiceGated(asset.id, cleanup, NoiseReduction.percent(noise))
                if (!gate(base, target, noise, key, stopped)) return null
                cache.voiceReady()
                target
            } finally {
                progress.update { it - key }
            }
        }
    }

    /** The media's sound as recorded, mono at 48 kHz, decoded once. */
    private fun recorded(asset: MediaAsset): File? {
        val recorded = cache.voiceRecorded(asset.id)
        if (recorded.exists()) return recorded
        val key = gateKey(asset)
        return recorded.takeIf { decode(asset, it, key) }
    }

    /**
     * Noise reduction (#38): learns the sound's noise floor in one pass, then gates it in a
     * second, at [strength].
     */
    private fun gate(
        source: File,
        target: File,
        strength: Float,
        key: String,
        stopped: () -> Boolean,
    ): Boolean {
        val profile = NoiseProfile()
        val block = FloatArray(BLOCK)
        WavReader(source).use { reader ->
            while (!stopped()) {
                val n = reader.read(block, 0, BLOCK)
                if (n < 0) break
                profile.add(block, n)
            }
        }
        val part = File(target.path + ".part")
        WavReader(source).use { reader ->
            WavWriter(part, DeepFilterNet.SAMPLE_RATE).use { writer ->
                val gate = SpectralGate(profile.floor(), strength)
                val emit = { out: FloatArray, n: Int -> writer.write(out, n) }
                var done = 0L
                while (!stopped()) {
                    val n = reader.read(block, 0, BLOCK)
                    if (n < 0) break
                    gate.process(block, n, emit)
                    done += n
                    report(key, done.toFloat() / reader.frames.coerceAtLeast(1))
                }
                gate.finish(emit)
            }
        }
        if (stopped()) {
            part.delete()
            return false
        }
        return part.renameTo(target)
    }

    /** The media's sound, down-mixed to mono and resampled to 48 kHz. */
    private fun decode(asset: MediaAsset, target: File, key: String): Boolean {
        val durationUs = asset.durationUs.takeIf { it > 0 } ?: Long.MAX_VALUE
        return MonoAudio.write(
            context,
            asset.uri,
            0,
            durationUs,
            DeepFilterNet.SAMPLE_RATE,
            target,
        ) { report(key, DECODE_SHARE * it) }
    }

    private fun clean(
        recorded: File,
        target: File,
        engine: VoiceEngine,
        key: String,
        stopped: () -> Boolean,
    ): Boolean {
        val part = File(target.path + ".part")
        val started = System.nanoTime()
        WavReader(recorded).use { reader ->
            WavWriter(part, DeepFilterNet.SAMPLE_RATE).use { writer ->
                denoiser(engine).use {
                    it.process(
                        reader,
                        reader.frames,
                        writer,
                        { done -> report(key, DECODE_SHARE + (1 - DECODE_SHARE) * done) },
                        stopped,
                    )
                }
            }
            val seconds = (System.nanoTime() - started) / 1e9
            Log.i(
                TAG,
                "$engine cleaned %.1f s of sound in %.1f s"
                    .format(reader.frames / DeepFilterNet.SAMPLE_RATE.toDouble(), seconds),
            )
        }
        if (stopped()) {
            part.delete()
            return false
        }
        return part.renameTo(target)
    }

    private fun denoiser(engine: VoiceEngine): VoiceDenoiser = when (engine) {
        VoiceEngine.DeepFilterNet -> {
            val folder = models.file(ModelCatalog.deepFilterNet, OnnxDeepFilterModel.FILES[0])
                ?.parentFile ?: error("DeepFilterNet 3 is not installed")
            DeepFilterNet(OnnxDeepFilterModel(folder))
        }

        VoiceEngine.RNNoise -> RnNoise()
    }

    /** The cleaned sound with [noisyShare] of the recorded one mixed back in. */
    private fun mix(recorded: File, cleaned: File, noisyShare: Float, target: File) {
        val part = File(target.path + ".part")
        WavReader(recorded).use { noisy ->
            WavReader(cleaned).use { clean ->
                WavWriter(part, DeepFilterNet.SAMPLE_RATE).use { out ->
                    val a = FloatArray(BLOCK)
                    val b = FloatArray(BLOCK)
                    while (true) {
                        val n = noisy.read(a, 0, BLOCK)
                        if (n < 0) break
                        var got = 0
                        while (got < n) {
                            val m = clean.read(b, got, n - got)
                            if (m < 0) break
                            got += m
                        }
                        for (i in 0 until n) {
                            val c = if (i < got) b[i] else 0f
                            a[i] = c * (1 - noisyShare) + a[i] * noisyShare
                        }
                        out.write(a, n)
                    }
                }
            }
        }
        part.renameTo(target)
    }

    private fun report(key: String, done: Float) = progress.update { it + (key to done) }

    companion object {
        private const val TAG = "VoiceCleaner"
        private const val BLOCK = 8_192
        private const val DECODE_SHARE = 0.1f

        /** The key of [running] for a media and an engine. */
        fun key(asset: MediaAsset, engine: VoiceEngine) = "${asset.id}-${engine.name}"

        /** The key of [running] for a media's noise reduction (#38). */
        fun gateKey(asset: MediaAsset) = "${asset.id}-gate"
    }
}

/** Runs [VoiceCleaner.prepare] in the background, once per media and mix. */
@HiltWorker
class VoiceCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cleaner: VoiceCleaner,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val asset = MediaAsset(
            id = inputData.getString(KEY_ID) ?: return Result.failure(),
            uri = inputData.getString(KEY_URI) ?: return Result.failure(),
            kind = tech.idct.clipra.core.model.MediaKind.Video,
            displayName = "",
            durationUs = inputData.getLong(KEY_DURATION, 0),
            hasAudio = true,
        )
        val cleanup = inputData.getString(KEY_ENGINE)?.let {
            VoiceCleanup(
                engine = VoiceEngine.valueOf(it),
                strength = inputData.getFloat(KEY_STRENGTH, VoiceCleanup.DEFAULT_STRENGTH),
            )
        }
        val noise = inputData.getFloat(KEY_NOISE, -1f).takeIf { it >= 0f }
        return try {
            withContext(Dispatchers.Default) {
                cleaner.prepare(asset, cleanup, noise) { isStopped }
            }
            Result.success()
        } catch (e: Exception) {
            Log.w("VoiceCleaner", "Voice cleanup failed", e)
            if (runAttemptCount < 1) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_URI = "uri"
        private const val KEY_DURATION = "duration"
        private const val KEY_ENGINE = "engine"
        private const val KEY_STRENGTH = "strength"
        private const val KEY_NOISE = "noise"

        fun enqueue(context: Context, asset: MediaAsset, cleanup: VoiceCleanup?, noise: Float?) {
            val request = OneTimeWorkRequestBuilder<VoiceCleanupWorker>()
                .setInputData(
                    workDataOf(
                        KEY_ID to asset.id,
                        KEY_URI to asset.uri,
                        KEY_DURATION to asset.durationUs,
                        KEY_ENGINE to cleanup?.engine?.name,
                        KEY_STRENGTH to (cleanup?.strength ?: VoiceCleanup.DEFAULT_STRENGTH),
                        KEY_NOISE to (noise ?: -1f),
                    ),
                )
                .build()
            val cleaned = cleanup?.let { "-${it.engine.name}-${it.percent}" }.orEmpty()
            val gated = noise?.let { "-gate${NoiseReduction.percent(it)}" }.orEmpty()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "voice-${asset.id}$cleaned$gated",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
