package tech.idct.clipra.core.engine.media

import tech.idct.clipra.core.model.CutoutMath
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The person masks of one media (#50), made once and kept in the cache: for each frame, a
 * [SIZE] × [SIZE] grid (top row first) of confidences, 0 to 255, that the pixel shows a person,
 * upright as the picture is drawn, with the frame's media time and the share of the picture the
 * people take. Frames are deflated; [frameAt] inflates the one on screen.
 *
 * The file: "CLPM", a version and the grid's side; the frames; then the index (count, and per
 * frame its time, offset, length and coverage); last the index's offset and "CLPM" again.
 */
class CutoutMasks private constructor(
    private val file: RandomAccessFile,
    /** Each frame's media time, in order. */
    val timesUs: LongArray,
    private val offsets: LongArray,
    private val lengths: IntArray,
    private val coverages: ByteArray,
) : Closeable {
    private val inflater = Inflater()
    private var compressed = ByteArray(0)

    val frames: Int get() = timesUs.size

    /** Whether any frame shows a person (a few per cent of the picture or more). */
    val found: Boolean get() = coverages.any { (it.toInt() and 0xFF) >= FOUND }

    /**
     * Writes into [out] ([SIZE] × [SIZE]) the mask showing at [mediaUs]: the last frame at or
     * before it, or the first. Returns its index.
     */
    @Synchronized
    fun frameAt(mediaUs: Long, out: ByteArray): Int {
        val index = indexAt(mediaUs)
        frame(index, out)
        return index
    }

    /** The frame showing at [mediaUs]. */
    fun indexAt(mediaUs: Long): Int {
        var found = timesUs.binarySearch(mediaUs)
        if (found < 0) found = -found - 2
        return found.coerceIn(0, frames - 1)
    }

    @Synchronized
    fun frame(index: Int, out: ByteArray) {
        require(out.size == SIZE * SIZE)
        val length = lengths[index]
        if (compressed.size < length) compressed = ByteArray(length)
        file.seek(offsets[index])
        file.readFully(compressed, 0, length)
        inflater.reset()
        inflater.setInput(compressed, 0, length)
        inflater.inflate(out)
    }

    @Synchronized
    override fun close() {
        inflater.end()
        file.close()
    }

    /** Writes masks frame by frame into a part file, which [finish] puts in place. */
    class Writer(private val target: File) : Closeable {
        private val part = File(target.path + ".part")
        private val out: DataOutputStream
        private val deflater = Deflater(Deflater.BEST_SPEED)
        private val buffer = ByteArray(SIZE * SIZE + 1024)
        private val times = ArrayList<Long>()
        private val offsets = ArrayList<Long>()
        private val lengths = ArrayList<Int>()
        private val coverages = ArrayList<Byte>()
        private var written = 0L

        init {
            target.parentFile?.mkdirs()
            out = DataOutputStream(FileOutputStream(part).buffered())
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(SIZE)
            written = HEADER_BYTES
        }

        /** Adds the mask of the frame at [timeUs]; frames come in time order. */
        fun add(timeUs: Long, mask: ByteArray) {
            require(mask.size == SIZE * SIZE)
            require(times.isEmpty() || timeUs > times.last())
            deflater.reset()
            deflater.setInput(mask)
            deflater.finish()
            val length = deflater.deflate(buffer)
            check(deflater.finished())
            out.write(buffer, 0, length)
            times += timeUs
            offsets += written
            lengths += length
            coverages += coverage(mask).toByte()
            written += length
        }

        /** Writes the index and puts the file in place. */
        fun finish() {
            out.writeInt(times.size)
            for (i in times.indices) {
                out.writeLong(times[i])
                out.writeLong(offsets[i])
                out.writeInt(lengths[i])
                out.writeByte(coverages[i].toInt())
            }
            out.writeLong(written)
            out.writeInt(MAGIC)
            out.close()
            deflater.end()
            check(part.renameTo(target) || (target.delete() && part.renameTo(target)))
        }

        /** Stops without a file. */
        override fun close() {
            runCatching { out.close() }
            deflater.end()
            part.delete()
        }

        /** The share of the picture that is mostly person, 0 to 255. */
        private fun coverage(mask: ByteArray): Int {
            val person = mask.count { (it.toInt() and 0xFF) >= 128 }
            return person * 255 / mask.size
        }
    }

    companion object {
        const val SIZE = CutoutMath.MASK_SIZE

        private const val MAGIC = 0x434C504D // "CLPM"
        private const val VERSION = 1
        private const val HEADER_BYTES = 12L
        private const val FOOTER_BYTES = 12L
        private const val ENTRY_BYTES = 21

        /** A person counts as found from about 2 % of the picture (5 of 255). */
        private const val FOUND = 5

        private val shared = java.util.concurrent.ConcurrentHashMap<String, CutoutMasks>()

        /**
         * The masks in [file], opened once and shared by the preview, the export and the
         * Cutout board; null while there are none (and a file that went is let go).
         */
        fun cached(file: File): CutoutMasks? {
            val key = file.path
            if (!file.exists()) {
                shared.remove(key)?.close()
                return null
            }
            shared[key]?.let { return it }
            val masks = open(file) ?: return null
            return shared.putIfAbsent(key, masks)?.also { masks.close() } ?: masks
        }

        /** The masks in [file], or null when there are none or it can't be read. */
        fun open(file: File): CutoutMasks? {
            if (!file.exists()) return null
            val raf = RandomAccessFile(file, "r")
            return try {
                check(raf.readInt() == MAGIC && raf.readInt() == VERSION && raf.readInt() == SIZE)
                raf.seek(raf.length() - FOOTER_BYTES)
                val indexAt = raf.readLong()
                check(raf.readInt() == MAGIC)
                raf.seek(indexAt)
                val count = raf.readInt()
                val end = indexAt + 4 + count.toLong() * ENTRY_BYTES + FOOTER_BYTES
                check(count > 0 && end == raf.length())
                val times = LongArray(count)
                val offsets = LongArray(count)
                val lengths = IntArray(count)
                val coverages = ByteArray(count)
                for (i in 0 until count) {
                    times[i] = raf.readLong()
                    offsets[i] = raf.readLong()
                    lengths[i] = raf.readInt()
                    coverages[i] = raf.readByte()
                }
                CutoutMasks(raf, times, offsets, lengths, coverages)
            } catch (_: Exception) {
                raf.close()
                null
            }
        }
    }
}
