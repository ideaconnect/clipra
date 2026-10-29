package tech.idct.clipra.core.engine.media

import tech.idct.clipra.ml.voice.SampleSink
import tech.idct.clipra.ml.voice.SampleSource
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Writes mono 16-bit PCM WAV, the format of Clipra's cleaned voices (#39). */
class WavWriter(private val file: File, private val sampleRate: Int) :
    SampleSink,
    Closeable {
    private val out = BufferedOutputStream(file.outputStream(), BUFFER)
    private var bytes = ByteArray(0)
    var frames = 0L
        private set

    init {
        out.write(ByteArray(HEADER))
    }

    override fun write(buffer: FloatArray, count: Int) {
        if (bytes.size < count * 2) bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = (buffer[i] * 32767f).roundToInt().coerceIn(-32768, 32767)
            bytes[2 * i] = s.toByte()
            bytes[2 * i + 1] = (s shr 8).toByte()
        }
        out.write(bytes, 0, count * 2)
        frames += count
    }

    override fun close() {
        out.close()
        val data = frames * 2
        val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt((36 + data).toInt())
            .put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(data.toInt())
        RandomAccessFile(file, "rw").use { it.write(header.array()) }
    }

    private companion object {
        const val HEADER = 44
        const val BUFFER = 64 * 1024
    }
}

/** Reads mono 16-bit PCM WAV as written by [WavWriter]. */
class WavReader(file: File) :
    SampleSource,
    Closeable {
    private val input = DataInputStream(BufferedInputStream(file.inputStream(), 64 * 1024))
    val sampleRate: Int
    val frames: Long
    private var left: Long
    private var bytes = ByteArray(0)

    init {
        val riff = ByteArray(12)
        input.readFully(riff)
        if (String(riff, 0, 4) != "RIFF" || String(riff, 8, 4) != "WAVE") {
            throw IOException("Not a WAV file: $file")
        }
        var rate = 0
        while (true) {
            val id = ByteArray(4).also { input.readFully(it) }
            val size = Integer.reverseBytes(input.readInt()).toLong() and 0xFFFFFFFFL
            when (String(id)) {
                "fmt " -> {
                    val fmt = ByteArray(size.toInt()).also { input.readFully(it) }
                    val buffer = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    val channels = buffer.getShort(2).toInt()
                    rate = buffer.getInt(4)
                    val bits = buffer.getShort(14).toInt()
                    if (channels != 1 || bits != 16) throw IOException("Expected mono 16-bit")
                }

                "data" -> {
                    sampleRate = rate
                    frames = size / 2
                    left = frames
                    break
                }

                else -> input.skipBytes(size.toInt())
            }
        }
    }

    override fun read(buffer: FloatArray, offset: Int, count: Int): Int {
        if (left <= 0) return -1
        val n = minOf(count.toLong(), left).toInt()
        if (bytes.size < n * 2) bytes = ByteArray(n * 2)
        try {
            input.readFully(bytes, 0, n * 2)
        } catch (_: EOFException) {
            left = 0
            return -1
        }
        for (i in 0 until n) {
            val s = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
            buffer[offset + i] = s.toShort() / 32768f
        }
        left -= n
        return n
    }

    override fun close() = input.close()
}
