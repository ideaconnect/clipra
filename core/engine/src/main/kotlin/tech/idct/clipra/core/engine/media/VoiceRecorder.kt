package tech.idct.clipra.core.engine.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10

/**
 * Records a voiceover (#41) from the microphone into the app's own storage: AAC in an .m4a,
 * mono, 48 kHz. Nothing leaves the device. The caller holds RECORD_AUDIO, which the editor asks
 * for only when recording starts.
 */
@Singleton
class VoiceRecorder @Inject constructor(@param:ApplicationContext private val context: Context) {
    /** Where recordings live: app storage, kept when the cache is cleared. */
    val folder: File get() = File(context.filesDir, "recordings")

    /** Starts recording; throws when the microphone can't be opened. */
    fun start(): Recording {
        folder.mkdirs()
        val file = File(folder, "${UUID.randomUUID()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioChannels(1)
            recorder.setAudioSamplingRate(SAMPLE_RATE)
            recorder.setAudioEncodingBitRate(BIT_RATE)
            recorder.setOutputFile(file)
            recorder.prepare()
            recorder.start()
        } catch (e: Exception) {
            recorder.release()
            file.delete()
            throw e
        }
        return Recording(file, recorder)
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val BIT_RATE = 128_000
    }
}

/** A voiceover being recorded. Stop or cancel it once; both release the microphone. */
class Recording internal constructor(val file: File, private val recorder: MediaRecorder) {
    private val startedNs = System.nanoTime()

    /** How long it has been recording. */
    val elapsedUs: Long get() = (System.nanoTime() - startedNs) / 1_000

    /** The loudest moment since the last call, in dBFS; [SILENCE_DB] for silence. */
    fun peakDb(): Float = levelDb(
        try {
            recorder.maxAmplitude
        } catch (_: IllegalStateException) {
            0
        },
    )

    /** Stops and keeps the file; null when nothing was recorded (stopped at once). */
    fun stop(): File? {
        val kept = try {
            recorder.stop()
            true
        } catch (_: RuntimeException) {
            // MediaRecorder throws when it got no data yet.
            false
        } finally {
            recorder.release()
        }
        if (!kept) file.delete()
        return file.takeIf { kept }
    }

    /** Stops and throws the recording away. */
    fun cancel() {
        try {
            recorder.stop()
        } catch (_: RuntimeException) {
        }
        recorder.release()
        file.delete()
    }

    companion object {
        /** The meter's floor: quieter than this shows as silence. */
        const val SILENCE_DB = -60f

        /** A 16-bit peak amplitude (0 to 32767) in dBFS, no lower than [SILENCE_DB]. */
        fun levelDb(amplitude: Int): Float {
            if (amplitude <= 0) return SILENCE_DB
            return (20 * log10(amplitude / 32_767f)).coerceIn(SILENCE_DB, 0f)
        }
    }
}
