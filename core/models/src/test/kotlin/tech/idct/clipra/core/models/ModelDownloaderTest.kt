package tech.idct.clipra.core.models

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/** Downloads resume, failed checks are rejected, archives unpack and sizes add up (#43). */
class ModelDownloaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val bytes = Random(7).nextBytes(300_000)
    private val model = model(bytes)
    private val store by lazy { ModelStore(folder.newFolder("models")) }

    @Test
    fun anInterruptedDownloadResumesWhereItStopped() {
        val server = FakeServer(bytes, failAfter = 120_000)
        try {
            ModelDownloader(store, server).download(model)
            fail("the connection drops")
        } catch (_: IOException) {
        }
        assertFalse(store.isInstalled(model))
        assertEquals(120_000L, store.partial(model).length())

        server.failAfter = null
        ModelDownloader(store, server).download(model)
        assertEquals(listOf(0L, 120_000L), server.offsets)
        assertTrue(store.isInstalled(model))
        assertArrayEquals(bytes, store.file(model, "model.bin")!!.readBytes())
        assertFalse(store.partial(model).exists())
    }

    @Test
    fun aServerThatIgnoresTheRangeStartsOver() {
        val server = FakeServer(bytes, failAfter = 50_000)
        runCatching { ModelDownloader(store, server).download(model) }
        server.failAfter = null
        server.honoursRange = false
        ModelDownloader(store, server).download(model)
        assertArrayEquals(bytes, store.file(model, "model.bin")!!.readBytes())
    }

    @Test
    fun aCorruptDownloadIsRejectedAndDeleted() {
        val damaged = bytes.copyOf().also { it[1234] = (it[1234] + 1).toByte() }
        try {
            ModelDownloader(store, FakeServer(damaged)).download(model)
            fail("the checksum does not match")
        } catch (_: CorruptDownloadException) {
        }
        assertFalse(store.isInstalled(model))
        assertFalse(store.partial(model).exists())
        assertNull(store.file(model, "model.bin"))
    }

    @Test
    fun aFileOfTheWrongSizeIsRejected() {
        try {
            ModelDownloader(store, FakeServer(bytes + byteArrayOf(1, 2, 3))).download(model)
            fail("the size does not match")
        } catch (_: CorruptDownloadException) {
        }
        assertFalse(store.isInstalled(model))
    }

    @Test
    fun aStoppedDownloadKeepsWhatItHas() {
        var seen = 0L
        ModelDownloader(store, FakeServer(bytes)).download(
            model,
            onProgress = { seen = it },
            stopped = { seen >= 100_000 },
        )
        assertFalse(store.isInstalled(model))
        assertTrue(store.partial(model).length() in 100_000L until bytes.size)
    }

    @Test
    fun anArchiveUnpacksTheListedFilesAndTheStorageAddsUp() {
        val enc = Random(1).nextBytes(1_000)
        val dec = Random(2).nextBytes(700)
        val archive = tarGz(
            "tmp/export/enc.onnx" to enc,
            "tmp/export/notes.txt" to "ignored".toByteArray(),
            "tmp/export/dec.onnx" to dec,
        )
        val packed = model(archive).copy(
            unpacked = listOf(
                UnpackedFile("tmp/export/enc.onnx", enc.size.toLong()),
                UnpackedFile("tmp/export/dec.onnx", dec.size.toLong()),
            ),
        )
        ModelDownloader(store, FakeServer(archive)).download(packed)
        assertArrayEquals(enc, store.file(packed, "enc.onnx")!!.readBytes())
        assertArrayEquals(dec, store.file(packed, "dec.onnx")!!.readBytes())
        assertNull(store.file(packed, "notes.txt"))
        // The storage shown is the files on disk, which is what the model says it takes.
        assertEquals(1_700L, store.bytesOnDisk(packed))
        assertEquals(packed.installedBytes, store.bytesOnDisk(packed))

        store.remove(packed)
        assertFalse(store.isInstalled(packed))
        assertEquals(0L, store.bytesOnDisk(packed))
    }

    @Test
    fun anArchiveWithoutAListedFileIsRejected() {
        val archive = tarGz("tmp/export/enc.onnx" to ByteArray(10))
        val packed = model(archive).copy(
            unpacked = listOf(
                UnpackedFile("tmp/export/enc.onnx", 10),
                UnpackedFile("tmp/export/dec.onnx", 20),
            ),
        )
        try {
            ModelDownloader(store, FakeServer(archive)).download(packed)
            fail("dec.onnx is missing")
        } catch (_: CorruptDownloadException) {
        }
        assertFalse(store.isInstalled(packed))
    }

    /** A translation direction (#47): three gzipped files, each checked, then unpacked. */
    @Test
    fun aModelOfSeveralGzippedFilesFetchesAndUnpacksEach() {
        val parts = listOf(
            "model.bin" to Random(3).nextBytes(5_000),
            "vocab.spm" to Random(4).nextBytes(800),
            "lex.bin" to Random(5).nextBytes(1_200),
        )
        val packed = parts.map { (name, data) -> "$name.gz" to gzip(data) }
        val files = parts.zip(packed).map { (part, gz) ->
            ModelFile(
                "https://example.invalid/${gz.first}",
                gz.second.size.toLong(),
                sha(gz.second),
                unpackedBytes = part.second.size.toLong(),
            )
        }
        val pair = AiModel(
            id = "pair",
            name = "en-pl",
            group = ModelGroup.Translation,
            download = files[0],
            moreFiles = files.drop(1),
        )
        val server = Fetcher { url, offset ->
            FakeServer(packed.first { url.endsWith(it.first) }.second).open(url, offset)
        }
        val progress = mutableListOf<Long>()

        ModelDownloader(store, server).download(pair, onProgress = { progress += it })

        for ((name, data) in parts) assertArrayEquals(data, store.file(pair, name)!!.readBytes())
        assertEquals(pair.installedBytes, store.bytesOnDisk(pair))
        // Progress counts across the files and never goes back.
        assertEquals(pair.downloadBytes, progress.last())
        assertEquals(progress.sorted(), progress)
        pair.files.indices.forEach { assertFalse(store.partial(pair, it).exists()) }
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun model(content: ByteArray) = AiModel(
        id = "test",
        name = "Test",
        group = ModelGroup.Captions,
        download = ModelFile(
            "https://example.invalid/model.bin",
            content.size.toLong(),
            sha(content),
        ),
    )

    private fun sha(content: ByteArray) = MessageDigest.getInstance("SHA-256").digest(content)
        .joinToString("") { "%02x".format(it) }

    /** Serves [content] with range requests; drops the connection after [failAfter] bytes. */
    private class FakeServer(private val content: ByteArray, var failAfter: Int? = null) :
        Fetcher {
        var honoursRange = true
        val offsets = mutableListOf<Long>()

        override fun open(url: String, offset: Long): Fetched {
            offsets += offset
            val start = if (honoursRange) offset.toInt() else 0
            val stop = failAfter
            val stream = object : InputStream() {
                private var position = start

                override fun read(): Int = throw UnsupportedOperationException()

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (stop != null && position >= stop) throw IOException("connection reset")
                    if (position >= content.size) return -1
                    var n = minOf(len, content.size - position, 8_192)
                    if (stop != null) n = minOf(n, stop - position)
                    System.arraycopy(content, position, b, off, n)
                    position += n
                    return n
                }
            }
            return Fetched(stream, start.toLong(), content.size.toLong())
        }
    }

    companion object {
        /** A minimal ustar archive of regular files, gzipped. */
        fun tarGz(vararg files: Pair<String, ByteArray>): ByteArray {
            val tar = ByteArrayOutputStream()
            for ((name, data) in files) {
                val header = ByteArray(512)
                name.toByteArray().copyInto(header, 0)
                "%011o".format(data.size).toByteArray().copyInto(header, 124)
                header[156] = '0'.code.toByte()
                "ustar".toByteArray().copyInto(header, 257)
                tar.write(header)
                tar.write(data)
                tar.write(ByteArray((512 - data.size % 512) % 512))
            }
            tar.write(ByteArray(1024))
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { ByteArrayInputStream(tar.toByteArray()).copyTo(it) }
            return out.toByteArray()
        }
    }
}
