package tech.idct.clipra.core.engine.media

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.Project
import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * The sound of reversed clips (#22), played back to front: the stretch of media a clip plays,
 * decoded and written in reverse as 16-bit PCM WAV in [MediaCache], with the channels and rate
 * of the source. A cleaned voice (#38, #39) is reversed instead of the source's own sound once
 * it is ready. Nothing leaves the device.
 */
@Singleton
class ReversedSounds @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cache: MediaCache,
) {
    private val resolver = ProjectMediaResolver(context, cache, forExport = true, maxImageSide = 1)

    /**
     * Makes the reversed sound of every reversed clip in [project] that has none yet. Call it
     * off the main thread; the preview picks each one up when it is written.
     */
    fun requestAll(project: Project) {
        for (clip in project.layers.flatMap { it.clips }) prepare(project, clip)
    }

    /**
     * Makes [clip]'s reversed sound now unless it is there; false when the sound can't be read.
     * Clips playing forwards, held frames and clips without sound need none.
     */
    fun prepare(project: Project, clip: Clip): Boolean {
        val (asset, voice) = soundOf(project, clip) ?: return true
        val ref = clip.source ?: return true
        val file = cache.reversedSound(key(asset, voice), ref.inPointUs, ref.outPointUs)
        val sound = voice ?: originalSound(asset) ?: return false
        return synchronized(LOCKS.getOrPut(file.path) { Any() }) {
            if (file.exists()) return@synchronized true
            try {
                reverse(sound, ref.inPointUs, ref.outPointUs, file).also {
                    if (it) cache.voiceReady()
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    /** The asset whose sound [clip] reverses, and the cleaned voice it plays, if any. */
    private fun soundOf(project: Project, clip: Clip): Pair<MediaAsset, String?>? {
        if (!clip.speed.reverse || (clip.speed.rate <= 0 && clip.speed.ramp == null)) return null
        val asset = clip.source?.let { project.asset(it.mediaId) } ?: return null
        if (!asset.hasAudio && clip.type != ClipType.Audio) return null
        val audio = clip.audio
        val voice = if (audio.voiceCleanup != null || audio.noiseReduction != null) {
            resolver.voice(asset, audio.voiceCleanup, audio.noiseReduction)
        } else {
            null
        }
        return asset to voice
    }

    /** The original's sound, or the proxy's when the original is gone. */
    private fun originalSound(asset: MediaAsset): String? =
        resolver.source(asset) ?: cache.proxy(asset.id).takeIf { it.exists() }?.path

    /**
     * Writes [source]'s sound from [fromUs] to [toUs] to [out], last sample first. The samples
     * go to a forward file first, then are copied back to front block by block, so a long clip
     * never has to fit in memory. Where the sound track doesn't cover the stretch (it may start
     * after the picture or end before it), silence fills in, so the file always starts at
     * [toUs] and lasts the whole stretch.
     */
    private fun reverse(source: String, fromUs: Long, toUs: Long, out: File): Boolean {
        out.parentFile?.mkdirs()
        val forward = File.createTempFile("forward", ".pcm", out.parentFile)
        val part = File.createTempFile(out.name, ".part", out.parentFile)
        try {
            var bytes = ByteArray(0)
            var firstUs = -1L
            val format = BufferedOutputStream(forward.outputStream(), BUFFER).use { pcm ->
                AudioDecoder.decode(context, source, fromUs, toUs) { samples, count, f, timeUs ->
                    val frames = count / f.channels
                    fun frameAt(us: Long): Int {
                        val frame = ceil((us - timeUs) * f.sampleRate / 1e6).toLong()
                        return frame.coerceIn(0, frames.toLong()).toInt()
                    }
                    val first = frameAt(fromUs)
                    val last = frameAt(toUs)
                    if (last <= first) return@decode
                    if (firstUs < 0) firstUs = timeUs + first * 1_000_000L / f.sampleRate
                    val values = (last - first) * f.channels
                    if (bytes.size < values * 2) bytes = ByteArray(values * 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        .put(samples, first * f.channels, values)
                    pcm.write(bytes, 0, values * 2)
                }
            } ?: return false
            val frameBytes = format.channels * 2
            val frames = forward.length() / frameBytes
            fun framesIn(us: Long) = (us * format.sampleRate / 1e6).roundToLong()
            val head = if (firstUs < 0) 0 else framesIn(firstUs - fromUs).coerceAtLeast(0)
            val tail = (framesIn(toUs - fromUs) - head - frames).coerceAtLeast(0)
            RandomAccessFile(forward, "r").use { input ->
                BufferedOutputStream(part.outputStream(), BUFFER).use { output ->
                    val total = tail + frames + head
                    output.write(PcmWav.header(format.sampleRate, format.channels, total))
                    val block = ByteArray(BLOCK_FRAMES * frameBytes)
                    output.silence(tail * frameBytes)
                    val backwards = ByteArray(block.size)
                    var end = frames
                    while (end > 0) {
                        val count = minOf(BLOCK_FRAMES.toLong(), end).toInt()
                        end -= count
                        input.seek(end * frameBytes)
                        input.readFully(block, 0, count * frameBytes)
                        for (i in 0 until count) {
                            System.arraycopy(
                                block,
                                i * frameBytes,
                                backwards,
                                (count - 1 - i) * frameBytes,
                                frameBytes,
                            )
                        }
                        output.write(backwards, 0, count * frameBytes)
                    }
                    output.silence(head * frameBytes)
                }
            }
            return part.renameTo(out)
        } finally {
            forward.delete()
            part.delete()
        }
    }

    private fun BufferedOutputStream.silence(bytes: Long) {
        val zeros = ByteArray(BUFFER)
        var left = bytes
        while (left > 0) {
            val count = minOf(left, zeros.size.toLong()).toInt()
            write(zeros, 0, count)
            left -= count
        }
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val BLOCK_FRAMES = 16 * 1024

        /** Shared by every instance: the editor's and an export's may make the same file. */
        private val LOCKS = ConcurrentHashMap<String, Any>()

        /** What a reversed sound is named after: the cleaned voice it reverses, or the media. */
        fun key(asset: MediaAsset, voice: String?): String =
            voice?.let { File(it).nameWithoutExtension } ?: asset.id
    }
}

/** The header of a 16-bit PCM WAV file of any channel count. */
internal object PcmWav {
    fun header(sampleRate: Int, channels: Int, frames: Long): ByteArray {
        val data = frames * channels * 2
        return ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt((36 + data).toInt())
            .put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(sampleRate).putInt(sampleRate * channels * 2)
            .putShort((channels * 2).toShort()).putShort(16)
            .put("data".toByteArray()).putInt(data.toInt())
            .array()
    }

    const val HEADER = 44
}
