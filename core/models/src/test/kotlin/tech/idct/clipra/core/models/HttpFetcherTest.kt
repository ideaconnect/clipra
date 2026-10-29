package tech.idct.clipra.core.models

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import kotlin.random.Random

/** The real fetcher against a local server: redirects, range requests and missing files. */
class HttpFetcherTest {
    private val content = Random(3).nextBytes(10_000)
    private lateinit var server: HttpServer
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/resolve/model.bin") { exchange ->
            // Like Hugging Face and GitHub: the source redirects to its file host.
            exchange.responseHeaders.add("Location", "$base/files/model.bin")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/files/model.bin") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            if (range != null) {
                exchange.responseHeaders.add(
                    "Content-Range",
                    "bytes $start-${content.size - 1}/${content.size}",
                )
            }
            val body = content.copyOfRange(start, content.size)
            exchange.sendResponseHeaders(if (range != null) 206 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/missing") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    @Test
    fun followsTheRedirectAndAsksForTheRest() {
        HttpFetcher.open("$base/resolve/model.bin", 0).use {
            assertEquals(0L, it.offset)
            assertEquals(content.size.toLong(), it.total)
            assertArrayEquals(content, it.stream.readBytes())
        }
        HttpFetcher.open("$base/resolve/model.bin", 4_000).use {
            assertEquals(4_000L, it.offset)
            assertEquals(content.size.toLong(), it.total)
            assertArrayEquals(content.copyOfRange(4_000, content.size), it.stream.readBytes())
        }
    }

    @Test
    fun aMissingFileIsUnavailable() {
        try {
            HttpFetcher.open("$base/missing", 0)
            fail("404")
        } catch (_: SourceUnavailableException) {
        }
    }
}
