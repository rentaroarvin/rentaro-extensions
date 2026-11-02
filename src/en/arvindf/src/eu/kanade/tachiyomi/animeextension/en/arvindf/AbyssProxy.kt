package eu.kanade.tachiyomi.animeextension.en.arvindf

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * A loopback HTTP server that presents one Abyss file to a player as an ordinary range-capable MP4.
 *
 * A player cannot be given an Abyss file as a plain URL, for two reasons the Abyss web player handles
 * in a service worker:
 *  - the first [HEAD_BYTES] of every file are AES-CTR encrypted (keyed by the file name), and the rest
 *    is not, so those bytes must be decrypted on the way through;
 *  - a large file is stored as several part files, `<name>`, `<name>1`, `<name>2`, and a read can cross
 *    from one into the next.
 * The CDN also wants the embed's own origin as the Referer, which is what the web player sends.
 *
 * Each registered file gets an unguessable path, and the socket only listens on 127.0.0.1, so no other
 * app can borrow this as an open relay. Because the address is local and changes between sessions, a
 * host that downloads streams cannot save this one for later; it plays while the app is open.
 */
internal object AbyssProxy {
    const val HEAD_BYTES = 65_536L

    private const val MAX_ENTRIES = 32
    private const val BUFFER_BYTES = 64 * 1024
    private const val UPSTREAM_CHUNK = 4L * 1024 * 1024
    private val PATH = Regex("""^/v/([0-9a-f]{32})\.mp4$""")
    private val RANGE = Regex("""^bytes=(\d*)-(\d*)$""")

    private class Entry(
        val source: AbyssSource,
        val referer: String,
        val userAgent: String,
        val client: OkHttpClient,
        /** The AES key for the file's first [HEAD_BYTES], or null when the head is stored plain. */
        val headKey: String?,
    )

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > MAX_ENTRIES
    }
    private val random = SecureRandom()
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "arvindf-proxy").apply { isDaemon = true }
    }
    private var server: ServerSocket? = null

    /** Registers [source] and returns the local URL a player can open for it. */
    @Synchronized
    fun urlFor(source: AbyssSource, encryptedHead: Boolean, referer: String, userAgent: String, client: OkHttpClient): String {
        val port = (server ?: start()).localPort
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val headKey = if (encryptedHead) AbyssCrypto.md5Hex(source.fileName) else null
        entries[token] = Entry(source, referer, userAgent, client, headKey)
        return "http://127.0.0.1:$port/v/$token.mp4"
    }

    private fun start(): ServerSocket {
        val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        server = socket
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                pool.execute { serve(client) }
            }
        }, "arvindf-proxy-accept").apply { isDaemon = true }.start()
        return socket
    }

    // ================================ HTTP ================================

    private class Req(val method: String, val path: String, val headers: Map<String, String>)

    private fun serve(socket: Socket) {
        socket.use {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 120_000
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                while (true) {
                    val request = readRequest(input) ?: return
                    val keepAlive = respond(request, output)
                    output.flush()
                    if (!keepAlive) return
                }
            } catch (e: IOException) {
                // The player closing the connection (a seek, or leaving the video) ends up here.
            }
        }
    }

    private fun readRequest(input: BufferedInputStream): Req? {
        val first = readLine(input) ?: return null
        val parts = first.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return Req(parts[0].uppercase(), parts[1].substringBefore('?'), headers)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > 8192) return null
            line.append(byte.toChar())
        }
    }

    /** Answers one request. Returns whether the connection can be kept open for the next one. */
    private fun respond(request: Req, output: OutputStream): Boolean {
        val keepAlive = !request.headers["connection"].equals("close", ignoreCase = true)
        val token = PATH.matchEntire(request.path)?.groupValues?.get(1)
        val entry = token?.let { synchronized(this) { entries[it] } }
        if (entry == null) {
            writeStatus(output, 404, "Not Found", keepAlive)
            return keepAlive
        }
        if (request.method != "GET" && request.method != "HEAD") {
            writeStatus(output, 405, "Method Not Allowed", keepAlive)
            return keepAlive
        }

        val size = entry.source.size
        var start = 0L
        var end = size - 1
        val rangeHeader = request.headers["range"]
        if (rangeHeader != null) {
            val match = RANGE.matchEntire(rangeHeader.trim())
            if (match != null && (match.groupValues[1].isNotEmpty() || match.groupValues[2].isNotEmpty())) {
                val from = match.groupValues[1]
                val to = match.groupValues[2]
                if (from.isEmpty()) {
                    start = maxOf(0L, size - to.toLong())
                } else {
                    start = from.toLong()
                    if (to.isNotEmpty()) end = minOf(end, to.toLong())
                }
            }
            if (start >= size || start > end) {
                writeStatus(output, 416, "Range Not Satisfiable", keepAlive, "Content-Range: bytes */$size")
                return keepAlive
            }
        }
        val partial = rangeHeader != null
        val head = buildString {
            append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            append("Content-Type: video/mp4\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: ${end - start + 1}\r\n")
            if (partial) append("Content-Range: bytes $start-$end/$size\r\n")
            append(if (keepAlive) "Connection: keep-alive\r\n" else "Connection: close\r\n")
            append("\r\n")
        }
        if (request.method == "HEAD") {
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            return keepAlive
        }

        var sent = false
        try {
            stream(entry, start, end) {
                if (!sent) {
                    output.write(head.toByteArray(Charsets.ISO_8859_1))
                    sent = true
                }
                output
            }
        } catch (e: IOException) {
            if (!sent) {
                writeStatus(output, 502, "Bad Gateway", false)
            }
            // Past the first byte the length is already promised, so the only honest answer is to hang up.
            return false
        }
        return keepAlive
    }

    private fun writeStatus(output: OutputStream, code: Int, text: String, keepAlive: Boolean, extra: String? = null) {
        val response = buildString {
            append("HTTP/1.1 $code $text\r\n")
            extra?.let { append(it).append("\r\n") }
            append("Content-Length: 0\r\n")
            append(if (keepAlive) "Connection: keep-alive\r\n" else "Connection: close\r\n")
            append("\r\n")
        }
        output.write(response.toByteArray(Charsets.ISO_8859_1))
    }

    // =============================== Upstream ===============================

    /**
     * Copies bytes [start]..[end] of the whole file to the player, one part file at a time. [out] is
     * asked for the destination only once the first upstream response is in hand, so a CDN failure can
     * still be reported as a status code instead of a truncated body.
     */
    private fun stream(entry: Entry, start: Long, end: Long, out: () -> OutputStream) {
        val source = entry.source
        var position = start
        val buffer = ByteArray(BUFFER_BYTES)
        for (index in 0 until source.partCount) {
            val partStart = source.partSize * index
            val partEnd = partStart + source.partLength(index) - 1
            val from = maxOf(position, partStart)
            val to = minOf(end, partEnd)
            if (from > to) continue

            // The host client gives a whole call two minutes, which a long read of one big part would
            // outlast. Bounded requests keep every call short, and let a seek abandon the old read fast.
            var cursor = from
            while (cursor <= to) {
                val last = minOf(to, cursor + UPSTREAM_CHUNK - 1)
                val request = Request.Builder()
                    .url(source.partUrl(index))
                    .header("User-Agent", entry.userAgent)
                    .header("Referer", entry.referer)
                    .header("Accept", "*/*")
                    .header("Range", "bytes=${cursor - partStart}-${last - partStart}")
                    .build()
                entry.client.newCall(request).execute().use { response ->
                    // 206 is the answer to a range. A 200 is only acceptable when it began at the start.
                    val ok = response.code == 206 || (response.code == 200 && cursor == partStart)
                    if (!ok) throw IOException("Abyss CDN answered HTTP ${response.code}")
                    val body = response.body.byteStream()
                    val destination = out()
                    var remaining = last - cursor + 1
                    while (remaining > 0) {
                        val read = body.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) throw IOException("Abyss CDN ended early")
                        if (entry.headKey != null && position < HEAD_BYTES) decryptHead(entry.headKey, buffer, read, position)
                        destination.write(buffer, 0, read)
                        position += read
                        remaining -= read
                    }
                }
                cursor = last + 1
            }
        }
    }

    /** Decrypts whichever part of [buffer] (which starts at file offset [position]) lies inside the head. */
    private fun decryptHead(key: String, buffer: ByteArray, length: Int, position: Long) {
        val count = minOf(length.toLong(), HEAD_BYTES - position).toInt()
        val plain = AbyssCrypto.apply(key, buffer.copyOfRange(0, count), position)
        System.arraycopy(plain, 0, buffer, 0, count)
    }
}
