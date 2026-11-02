package eu.kanade.tachiyomi.animeextension.en.arvindf

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.random.Random

/**
 * Runs the real [AbyssProxy] against a local stand-in for the Abyss storage host: it refuses a request
 * without the embed's Referer, serves a file whose first 64 KiB are AES-CTR encrypted, and splits that
 * file into part files that are fetched with byte ranges, as the live host was observed to do.
 */
class AbyssProxyTest {
    private val fileName = "deadbeefdeadbeefdeadbeef12345.9000.4"
    private val partSize = 40_000L
    private val size = 100_000L
    private val referer = "https://player.example.test/"
    private val movie = Random(42).nextBytes(size.toInt()).also {
        // Anything that reads as an MP4 head: size field, then "ftyp".
        "\u0000\u0000\u0000 ftypisom".toByteArray(Charsets.ISO_8859_1).copyInto(it)
    }
    private val stored: ByteArray = movie.copyOf().also {
        val head = AbyssCrypto.apply(AbyssCrypto.md5Hex(fileName), movie.copyOfRange(0, AbyssProxy.HEAD_BYTES.toInt()))
        head.copyInto(it)
    }

    private lateinit var cdn: ServerSocket
    private val seenRanges = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val client = OkHttpClient()

    @Before
    fun startCdn() {
        cdn = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        Thread({
            while (!cdn.isClosed) {
                val socket = try { cdn.accept() } catch (e: Exception) { break }
                Thread { handle(socket) }.start()
            }
        }, "fake-cdn").apply { isDaemon = true }.start()
    }

    @After
    fun stopCdn() = cdn.close()

    private fun handle(socket: Socket) = socket.use {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val request = input.readLine() ?: return
        val headers = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }
            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val path = request.split(' ')[1]
        val out = socket.getOutputStream()
        fun reply(code: Int, text: String, body: ByteArray = ByteArray(0), extra: String = "") {
            out.write("HTTP/1.1 $code $text\r\n${extra}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
        }
        if (headers["referer"] != referer) return reply(403, "Forbidden")
        val index = when (path) {
            "/e/8/4/$fileName" -> 0
            "/e/8/4/${fileName}1" -> 1
            "/e/8/4/${fileName}2" -> 2
            else -> return reply(404, "Not Found")
        }
        val partStart = partSize * index
        val partEnd = minOf(size, partStart + partSize) - 1
        if (partStart > size - 1) return reply(404, "Not Found")
        val range = Regex("""bytes=(\d+)-(\d+)""").matchEntire(headers["range"].orEmpty())
            ?: return reply(200, "OK", stored.copyOfRange(partStart.toInt(), partEnd.toInt() + 1))
        seenRanges += "$index:${range.groupValues[1]}-${range.groupValues[2]}"
        val from = partStart + range.groupValues[1].toLong()
        val to = minOf(partEnd, partStart + range.groupValues[2].toLong())
        if (from > to) return reply(416, "Range Not Satisfiable")
        reply(206, "Partial Content", stored.copyOfRange(from.toInt(), to.toInt() + 1))
    }

    private val source get() = AbyssSource("720p", size, partSize, "http://127.0.0.1:${cdn.localPort}/e/8/4/$fileName")

    private fun fetch(url: String, range: String? = null, method: String = "GET"): Triple<Int, Map<String, String>, ByteArray> {
        val request = Request.Builder().url(url).method(method, null).apply { range?.let { header("Range", it) } }.build()
        return client.newCall(request).execute().use { response ->
            Triple(response.code, response.headers.toMultimap().mapValues { it.value.first() }, response.body.bytes())
        }
    }

    private fun proxied(encrypted: Boolean = true, referer: String = this.referer) =
        AbyssProxy.urlFor(source, encrypted, referer, "test-agent", client)

    @Test
    fun `a whole file comes back decrypted and in one piece across the part boundary`() {
        val (code, headers, body) = fetch(proxied())
        assertEquals(200, code)
        assertEquals("100000", headers["content-length"])
        assertEquals("video/mp4", headers["content-type"])
        assertArrayEquals(movie, body)
    }

    @Test
    fun `a range inside the encrypted head is decrypted from the right keystream offset`() {
        val (code, headers, body) = fetch(proxied(), "bytes=100-5000")
        assertEquals(206, code)
        assertEquals("bytes 100-5000/100000", headers["content-range"])
        assertArrayEquals(movie.copyOfRange(100, 5001), body)
    }

    @Test
    fun `a range that starts mid-block and ends past the head boundary is joined seamlessly`() {
        val (_, _, body) = fetch(proxied(), "bytes=65531-65545")
        assertArrayEquals(movie.copyOfRange(65_531, 65_546), body)
    }

    @Test
    fun `a range that spans two part files is stitched`() {
        val (code, _, body) = fetch(proxied(), "bytes=39990-40010")
        assertEquals(206, code)
        assertArrayEquals(movie.copyOfRange(39_990, 40_011), body)
        assertEquals(true, seenRanges.any { it.startsWith("0:") } && seenRanges.any { it.startsWith("1:") })
    }

    @Test
    fun `the last part is reached and a suffix range counts from the end`() {
        val (_, _, tail) = fetch(proxied(), "bytes=-1000")
        assertArrayEquals(movie.copyOfRange(99_000, 100_000), tail)
        val (_, _, open) = fetch(proxied(), "bytes=90000-")
        assertArrayEquals(movie.copyOfRange(90_000, 100_000), open)
    }

    @Test
    fun `a head stored plain is passed through untouched`() {
        val plainStored = movie
        val plainCdn = object { val copy = stored }
        // The fake CDN stores the encrypted head, so reading it as plain must NOT equal the movie.
        val (_, _, body) = fetch(proxied(encrypted = false), "bytes=0-99")
        assertArrayEquals(plainCdn.copy.copyOfRange(0, 100), body)
        assertEquals(false, body.contentEquals(plainStored.copyOfRange(0, 100)))
    }

    @Test
    fun `head and out-of-range requests are answered correctly`() {
        val url = proxied()
        val (headCode, headHeaders, headBody) = fetch(url, method = "HEAD")
        assertEquals(200, headCode)
        assertEquals("100000", headHeaders["content-length"])
        assertEquals(0, headBody.size)
        assertEquals(416, fetch(url, "bytes=200000-").first)
    }

    @Test
    fun `an unknown path is a 404 and the wrong referer is a bad gateway not stray bytes`() {
        val base = proxied().substringBeforeLast('/')
        assertEquals(404, fetch("$base/00000000000000000000000000000000.mp4").first)
        assertEquals(502, fetch(proxied(referer = "https://wrong.example.test/")).first)
    }

    @Test
    fun `the proxy only listens on loopback`() {
        val host = proxied().removePrefix("http://").substringBefore(':')
        assertEquals("127.0.0.1", host)
        assertNull(runCatching { java.net.URL(proxied()).host.takeIf { it != "127.0.0.1" } }.getOrNull())
    }
}
