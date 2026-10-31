package tech.idct.clipra.core.models

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** A file's bytes from an [offset] on: where the stream really starts and the file's size. */
class Fetched(val stream: InputStream, val offset: Long, val total: Long?) : Closeable {
    override fun close() = stream.close()
}

/** Opens a URL from a byte offset. Production uses [HttpFetcher]; tests use their own. */
fun interface Fetcher {
    fun open(url: String, offset: Long): Fetched
}

/** The file failed its SHA-256 or size check, so it was deleted. */
class CorruptDownloadException(message: String) : IOException(message)

/** The source answered with an error (moved or removed), so retrying will not help. */
class SourceUnavailableException(message: String) : IOException(message)

/**
 * Downloads a model from its official source into [store] (#43): resumes a partial file with a
 * range request, checks the size and SHA-256, unpacks archives and installs the files. Network
 * errors keep the partial file for the next attempt; a failed check deletes it.
 */
class ModelDownloader(private val store: ModelStore, private val fetcher: Fetcher = HttpFetcher) {
    /**
     * Installs [model] unless it already is. [onProgress] gets the bytes downloaded so far;
     * [stopped] ends the download early (the partial file stays for later).
     */
    fun download(
        model: AiModel,
        onProgress: (Long) -> Unit = {},
        stopped: () -> Boolean = { false },
    ) {
        if (model.files.isEmpty() || store.isInstalled(model)) return
        store.root.mkdirs()
        // The files one after the other, each resumed and checked on its own.
        var done = 0L
        for ((index, file) in model.files.withIndex()) {
            val partial = store.partial(model, index)
            if (partial.length() > file.bytes) partial.delete()
            if (partial.length() < file.bytes) {
                fetch(file, partial, { onProgress(done + it) }, stopped)
            }
            if (stopped()) return
            if (partial.length() != file.bytes) {
                throw IOException("Download ended at ${partial.length()} of ${file.bytes} bytes")
            }
            if (sha256(partial) != file.sha256) {
                partial.delete()
                throw CorruptDownloadException("${file.name} does not match its SHA-256")
            }
            done += file.bytes
        }
        install(model)
    }

    private fun fetch(
        file: ModelFile,
        partial: File,
        onProgress: (Long) -> Unit,
        stopped: () -> Boolean,
    ) {
        val have = partial.length()
        fetcher.open(file.url, have).use { fetched ->
            if (fetched.total != null && fetched.total != file.bytes) {
                throw CorruptDownloadException(
                    "${file.name} is ${fetched.total} bytes, not ${file.bytes}",
                )
            }
            // A server that ignores the range sends the whole file again.
            val append = fetched.offset == have && have > 0
            var written = if (append) have else 0
            FileOutputStream(partial, append).use { out ->
                val buffer = ByteArray(BUFFER)
                while (!stopped()) {
                    val n = fetched.stream.read(buffer)
                    if (n < 0) break
                    if (written + n > file.bytes) {
                        partial.delete()
                        throw CorruptDownloadException("${file.name} is longer than expected")
                    }
                    out.write(buffer, 0, n)
                    written += n
                    onProgress(written)
                }
            }
        }
    }

    private fun install(model: AiModel) {
        val staged = store.staging(model)
        staged.deleteRecursively()
        staged.mkdirs()
        for ((index, file) in model.files.withIndex()) {
            val partial = store.partial(model, index)
            when {
                index == 0 && model.unpacked.isNotEmpty() -> unpack(model, partial, staged)

                file.unpackedBytes != null -> gunzip(file, partial, staged)

                else -> check(partial.renameTo(File(staged, file.name))) {
                    "Cannot move ${file.name}"
                }
            }
            partial.delete()
        }
        store.install(model, staged)
    }

    /** Unpacks a gzipped [file] and checks its size. */
    private fun gunzip(file: ModelFile, gz: File, into: File) {
        val out = File(into, file.installedName)
        GZIPInputStream(gz.inputStream().buffered()).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        if (out.length() != file.unpackedBytes) {
            throw CorruptDownloadException("${file.name} unpacks to ${out.length()} bytes")
        }
    }

    /** Keeps the archive's files the model lists, flattened, and checks their sizes. */
    private fun unpack(model: AiModel, archive: File, into: File) {
        val wanted = model.unpacked.associateBy { it.path }
        GZIPInputStream(archive.inputStream().buffered()).use { input ->
            TarReader.read(input) { name, size, data ->
                val entry = wanted[name] ?: return@read
                if (size != entry.bytes) {
                    throw CorruptDownloadException("$name is $size bytes, not ${entry.bytes}")
                }
                File(into, entry.name).outputStream().use { data.copyTo(it) }
            }
        }
        val missing = model.unpacked.filterNot { File(into, it.name).length() == it.bytes }
        if (missing.isNotEmpty()) {
            throw CorruptDownloadException("Archive is missing ${missing.map { it.path }}")
        }
    }

    companion object {
        private const val BUFFER = 64 * 1024

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Fetches over HTTPS, following the sources' redirects to their file hosts. */
object HttpFetcher : Fetcher {
    override fun open(url: String, offset: Long): Fetched {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
        val code = connection.responseCode
        return when (code) {
            HttpURLConnection.HTTP_OK ->
                Fetched(connection.inputStream, 0, connection.contentLengthLong.takeIf { it >= 0 })

            HttpURLConnection.HTTP_PARTIAL -> {
                // "bytes 100-199/7983136"
                val range = connection.getHeaderField("Content-Range").orEmpty()
                val start = range.substringAfter("bytes ").substringBefore('-').toLongOrNull()
                val total = range.substringAfter('/').toLongOrNull()
                Fetched(connection.inputStream, start ?: 0, total)
            }

            // Moved or removed: the fix ships in an app update.
            HttpURLConnection.HTTP_FORBIDDEN,
            HttpURLConnection.HTTP_NOT_FOUND,
            HttpURLConnection.HTTP_GONE,
            -> {
                connection.disconnect()
                throw SourceUnavailableException("HTTP $code for $url")
            }

            else -> {
                connection.disconnect()
                throw IOException("HTTP $code for $url")
            }
        }
    }

    private const val TIMEOUT_MS = 30_000
}
