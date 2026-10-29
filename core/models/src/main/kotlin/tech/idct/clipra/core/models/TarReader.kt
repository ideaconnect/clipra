package tech.idct.clipra.core.models

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Reads a tar stream (ustar), enough for DeepFilterNet's model archive: regular files with
 * their names and sizes. Calls [onFile] for each file with a stream of exactly its bytes.
 */
internal object TarReader {
    fun read(input: InputStream, onFile: (name: String, size: Long, data: InputStream) -> Unit) {
        val header = ByteArray(BLOCK)
        while (true) {
            if (!readBlock(input, header)) return
            if (header.all { it == 0.toByte() }) return
            val name = prefix(header) + text(header, 0, 100)
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val data = Limited(input, size)
            if (type == '0' || type == '\u0000') onFile(name, size, data)
            data.skipRest()
            val padding = (BLOCK - size % BLOCK) % BLOCK
            skipFully(input, padding)
        }
    }

    private fun prefix(header: ByteArray): String {
        val magic = text(header, 257, 6)
        val prefix = if (magic.startsWith("ustar")) text(header, 345, 155) else ""
        return if (prefix.isEmpty()) "" else "$prefix/"
    }

    private fun text(header: ByteArray, from: Int, length: Int): String {
        var end = from
        while (end < from + length && header[end] != 0.toByte()) end++
        return String(header, from, end - from, Charsets.UTF_8)
    }

    private fun octal(header: ByteArray, from: Int, length: Int): Long {
        val digits = text(header, from, length).trim()
        if (digits.isEmpty()) return 0
        return digits.toLongOrNull(8) ?: throw IOException("Bad tar size: $digits")
    }

    /** False at a clean end of stream; throws when a block is cut short. */
    private fun readBlock(input: InputStream, block: ByteArray): Boolean {
        var read = 0
        while (read < block.size) {
            val n = input.read(block, read, block.size - read)
            if (n < 0) {
                if (read == 0) return false
                throw EOFException("Truncated tar header")
            }
            read += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val buffer = ByteArray(BLOCK)
        while (left > 0) {
            val n = input.read(buffer, 0, minOf(left, BLOCK.toLong()).toInt())
            if (n < 0) throw EOFException("Truncated tar entry")
            left -= n
        }
    }

    /** One entry's bytes, without closing the archive stream. */
    private class Limited(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = input.read()
            if (b < 0) throw EOFException("Truncated tar entry")
            left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw EOFException("Truncated tar entry")
            left -= n
            return n
        }

        fun skipRest() = skipFully(input, left).also { left = 0 }

        override fun close() = Unit
    }

    private const val BLOCK = 512
}
