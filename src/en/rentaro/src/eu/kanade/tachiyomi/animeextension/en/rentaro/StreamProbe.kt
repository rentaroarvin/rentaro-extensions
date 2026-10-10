package eu.kanade.tachiyomi.animeextension.en.rentaro

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.UnknownHostException

/**
 * Finds the resolution of a stream from the start of the media itself: the `RESOLUTION` of an
 * HLS master, the video track of an MP4 (or an fMP4 init segment) or Matroska file, the H.264
 * sequence header in the first MPEG-TS segment of an HLS media playlist, or the largest
 * representation of a DASH manifest.
 *
 * Several sources only ever offer one rendition - every KissKH episode, StremFx's single MKV,
 * XDrop's MP4 - so there is no second quality to list, and "Auto" said nothing about the one
 * there is. The same read tells a dead stream apart: an HTTP error or a host that no longer
 * resolves, which the player would otherwise only hit after the row had been chosen.
 */
internal object StreamProbe {

    sealed interface Result

    class Resolution(val width: Int, val height: Int) : Result

    /** The stream answered an error status or its host no longer exists. */
    data object Gone : Result

    /** The stream could not be read in time or its format was not recognised. */
    data object Unknown : Result

    private const val READ_LIMIT = 256 * 1024
    private const val MOOV_READ_LIMIT = 4 * 1024 * 1024
    private const val TS_PACKET = 188

    /**
     * @param range whether to ask for the bytes as a player does for a file (`bytes=0-`). Some
     *   workers answer a ranged request differently, so files are probed the way they will be
     *   played; playlists are fetched without one, as the player fetches them.
     */
    suspend fun probe(client: OkHttpClient, url: String, headers: Headers, range: Boolean): Result {
        val bytes = when (val first = fetch(client, url, headers, if (range) 0L else null, READ_LIMIT)) {
            is Body -> first.bytes
            is Failed -> return if (first.gone) Gone else Unknown
        }
        return when {
            bytes.startsWithText("#EXTM3U") -> hls(client, url, headers, String(bytes, Charsets.UTF_8))
            isMatroska(bytes) -> matroska(bytes)
            isMp4(bytes) -> mp4(client, url, headers, bytes)
            bytes.startsWithText("<?xml") || bytes.startsWithText("<MPD") -> dash(String(bytes, Charsets.UTF_8))
            else -> transportStream(bytes)
        } ?: Unknown
    }

    /** The conventional quality name for a frame size: 1280×536 and 1280×720 are both 720p. */
    fun heightClass(width: Int, height: Int): Int {
        val nominal = maxOf(height, width * 9 / 16)
        return STANDARD_HEIGHTS.firstOrNull { nominal >= it * 9 / 10 } ?: nominal
    }

    private val STANDARD_HEIGHTS = listOf(2160, 1440, 1080, 720, 576, 480, 360, 240)

    // ================================ fetching ================================

    private sealed interface Fetch

    private class Body(val bytes: ByteArray) : Fetch

    private class Failed(val gone: Boolean) : Fetch

    private suspend fun fetch(client: OkHttpClient, url: String, headers: Headers, rangeFrom: Long?, limit: Int): Fetch {
        val requestHeaders = if (rangeFrom == null) headers else headers.newBuilder().set("Range", "bytes=$rangeFrom-").build()
        val response = try {
            client.newCall(GET(url, requestHeaders)).await()
        } catch (_: UnknownHostException) {
            return Failed(gone = true)
        } catch (_: IOException) {
            return Failed(gone = false)
        }
        return response.use {
            if (!it.isSuccessful) return@use Failed(gone = it.code == 403 || it.code == 404 || it.code == 410 || it.code >= 500)
            runCatching {
                val source = it.body.source()
                source.request(limit.toLong())
                Body(source.buffer.readByteArray(minOf(limit.toLong(), source.buffer.size)))
            }.getOrElse { Failed(gone = false) }
        }
    }

    // ================================== HLS ==================================

    private suspend fun hls(client: OkHttpClient, url: String, headers: Headers, playlist: String): Result? {
        if ("#EXT-X-STREAM-INF" in playlist) {
            return HLS_RESOLUTION_REGEX.findAll(playlist)
                .map { Resolution(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
                .maxByOrNull { it.height }
        }
        val base = url.toHttpUrlOrNull() ?: return null

        // An fMP4 playlist names its init segment, which holds the track header.
        HLS_MAP_REGEX.find(playlist)?.groupValues?.get(1)?.let { init ->
            val initUrl = base.resolve(init)?.toString() ?: return null
            val bytes = (fetch(client, initUrl, headers, null, READ_LIMIT) as? Body)?.bytes ?: return null
            return mp4Boxes(bytes)
        }

        val segment = playlist.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return null
        val segmentUrl = base.resolve(segment)?.toString() ?: return null
        val bytes = when (val fetched = fetch(client, segmentUrl, headers, null, READ_LIMIT)) {
            is Body -> fetched.bytes
            is Failed -> return if (fetched.gone) Gone else null
        }
        return if (isMp4(bytes)) mp4Boxes(bytes) else transportStream(bytes)
    }

    private val HLS_RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""")
    private val HLS_MAP_REGEX = Regex("""#EXT-X-MAP:.*?URI="([^"]+)"""")

    // ================================== DASH =================================

    private fun dash(manifest: String): Result? {
        val height = DASH_HEIGHT_REGEX.findAll(manifest).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
            ?: return null
        val width = DASH_WIDTH_REGEX.findAll(manifest).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
        return Resolution(width, height)
    }

    private val DASH_HEIGHT_REGEX = Regex("""\bheight="(\d+)"""")
    private val DASH_WIDTH_REGEX = Regex("""\bwidth="(\d+)"""")

    // =================================== MP4 =================================

    private fun isMp4(bytes: ByteArray) = bytes.size >= 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) in MP4_LEADING_BOXES

    private val MP4_LEADING_BOXES = setOf("ftyp", "styp", "moov", "moof")

    /**
     * Reads the video track header. A file written with its index at the end puts `mdat` first;
     * the index then starts right after it, so that offset is fetched as a second range.
     */
    private suspend fun mp4(client: OkHttpClient, url: String, headers: Headers, bytes: ByteArray): Result? {
        mp4Boxes(bytes)?.let { return it }
        var offset = 0L
        while (offset + 8 <= bytes.size) {
            val (size, header) = boxSize(bytes, offset.toInt()) ?: return null
            if (boxType(bytes, offset.toInt()) == "mdat") {
                if (size <= header) return null
                val moov = (fetch(client, url, headers, offset + size, MOOV_READ_LIMIT) as? Body)?.bytes ?: return null
                return mp4Boxes(moov)
            }
            if (size <= 0) return null
            offset += size
        }
        return null
    }

    /** Walks `moov` → `trak` → `tkhd` and returns the first track with a frame size. */
    private fun mp4Boxes(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): Resolution? {
        var offset = start
        while (offset + 8 <= end) {
            val (size, header) = boxSize(bytes, offset) ?: return null
            val boxEnd = if (size == 0L) end else minOf(end.toLong(), offset + size).toInt()
            when (boxType(bytes, offset)) {
                "moov", "trak" -> mp4Boxes(bytes, offset + header, boxEnd)?.let { return it }
                "tkhd" -> trackSize(bytes, offset + header, boxEnd)?.let { return it }
            }
            // A zero size runs to the end, so nothing follows it.
            if (size == 0L || size < header) return null
            offset = boxEnd
        }
        return null
    }

    private fun trackSize(bytes: ByteArray, start: Int, end: Int): Resolution? {
        val version = bytes.getOrNull(start)?.toInt() ?: return null
        // version, flags, times, track id, reserved, duration, reserved, layer, group, volume,
        // reserved and the 3×3 matrix precede the 16.16 fixed-point width and height.
        val sizeOffset = start + 4 + (if (version == 1) 32 else 20) + 8 + 8 + 36
        if (sizeOffset + 8 > end) return null
        val width = u32(bytes, sizeOffset) ushr 16
        val height = u32(bytes, sizeOffset + 4) ushr 16
        return if (width > 0 && height > 0) Resolution(width.toInt(), height.toInt()) else null
    }

    private fun boxSize(bytes: ByteArray, offset: Int): Pair<Long, Int>? {
        if (offset + 8 > bytes.size) return null
        val size = u32(bytes, offset)
        if (size != 1L) return size to 8
        if (offset + 16 > bytes.size) return null
        return ((u32(bytes, offset + 8) shl 32) or u32(bytes, offset + 12)) to 16
    }

    private fun boxType(bytes: ByteArray, offset: Int) = String(bytes, offset + 4, 4, Charsets.ISO_8859_1)

    // ================================ Matroska ===============================

    private fun isMatroska(bytes: ByteArray) = bytes.size >= 4 && bytes[0] == 0x1A.toByte() &&
        bytes[1] == 0x45.toByte() && bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()

    /** Walks `Segment` → `Tracks` → `TrackEntry` → `Video` for `PixelWidth` and `PixelHeight`. */
    private fun matroska(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): Resolution? {
        var offset = start
        var width = 0
        var height = 0
        while (offset < end) {
            val id = ebmlId(bytes, offset) ?: return null
            val size = ebmlSize(bytes, offset + id.second) ?: return null
            val dataStart = offset + id.second + size.second
            val dataEnd = if (size.first < 0) end else minOf(end.toLong(), dataStart + size.first).toInt()
            when (id.first) {
                MKV_SEGMENT, MKV_TRACKS, MKV_TRACK_ENTRY -> matroska(bytes, dataStart, dataEnd)?.let { return it }
                MKV_VIDEO -> return matroska(bytes, dataStart, dataEnd)
                MKV_PIXEL_WIDTH -> width = ebmlUInt(bytes, dataStart, dataEnd)
                MKV_PIXEL_HEIGHT -> height = ebmlUInt(bytes, dataStart, dataEnd)
                // Clusters follow the track list; nothing past the first one is needed.
                MKV_CLUSTER -> return null
            }
            if (width > 0 && height > 0) return Resolution(width, height)
            if (size.first < 0) return null
            offset = dataEnd
        }
        return null
    }

    private const val MKV_SEGMENT = 0x18538067L
    private const val MKV_TRACKS = 0x1654AE6BL
    private const val MKV_TRACK_ENTRY = 0xAEL
    private const val MKV_VIDEO = 0xE0L
    private const val MKV_PIXEL_WIDTH = 0xB0L
    private const val MKV_PIXEL_HEIGHT = 0xBAL
    private const val MKV_CLUSTER = 0x1F43B675L

    /** An element id, marker bit kept, and its length in bytes. */
    private fun ebmlId(bytes: ByteArray, offset: Int): Pair<Long, Int>? {
        val first = bytes.getOrNull(offset)?.toInt()?.and(0xFF) ?: return null
        val length = Integer.numberOfLeadingZeros(first) - 23
        if (length !in 1..4 || offset + length > bytes.size) return null
        var value = 0L
        for (i in 0 until length) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value to length
    }

    /** A data size with its marker bit removed, or -1 for the all-ones "unknown" size. */
    private fun ebmlSize(bytes: ByteArray, offset: Int): Pair<Long, Int>? {
        val first = bytes.getOrNull(offset)?.toInt()?.and(0xFF) ?: return null
        val length = Integer.numberOfLeadingZeros(first) - 23
        if (length !in 1..8 || offset + length > bytes.size) return null
        var value = (first and (0xFF ushr length)).toLong()
        var allOnes = value == (0xFF ushr length).toLong()
        for (i in 1 until length) {
            val b = bytes[offset + i].toLong() and 0xFF
            if (b != 0xFFL) allOnes = false
            value = (value shl 8) or b
        }
        return (if (allOnes) -1L else value) to length
    }

    private fun ebmlUInt(bytes: ByteArray, start: Int, end: Int): Int {
        var value = 0L
        for (i in start until minOf(end, bytes.size)) value = (value shl 8) or (bytes[i].toLong() and 0xFF)
        return value.toInt()
    }

    // ================================ MPEG-TS ================================

    /**
     * Reassembles the video elementary stream from the first TS packets and reads its H.264
     * sequence parameter set. Some CDNs prepend an image header to each segment, so the packets
     * are found by their sync byte repeating every 188 bytes rather than assumed at offset 0.
     */
    private fun transportStream(bytes: ByteArray): Resolution? {
        val start = (0 until minOf(bytes.size - TS_PACKET * 2, 4096)).firstOrNull { o ->
            bytes[o] == SYNC && bytes[o + TS_PACKET] == SYNC && bytes[o + TS_PACKET * 2] == SYNC
        } ?: return null

        val es = java.io.ByteArrayOutputStream()
        var videoPid = -1
        var packet = start
        while (packet + TS_PACKET <= bytes.size && bytes[packet] == SYNC) {
            val unitStart = bytes[packet + 1].toInt() and 0x40 != 0
            val pid = ((bytes[packet + 1].toInt() and 0x1F) shl 8) or (bytes[packet + 2].toInt() and 0xFF)
            val adaptation = (bytes[packet + 3].toInt() shr 4) and 0x03
            var payload = packet + 4
            if (adaptation == 2 || adaptation == 3) payload += 1 + (bytes[payload].toInt() and 0xFF)
            val end = packet + TS_PACKET
            if (payload >= end) {
                packet = end
                continue
            }
            if ((adaptation == 1 || adaptation == 3) && payload < end) {
                if (unitStart && payload + 9 <= end && bytes[payload] == 0.toByte() &&
                    bytes[payload + 1] == 0.toByte() && bytes[payload + 2] == 1.toByte()
                ) {
                    val streamId = bytes[payload + 3].toInt() and 0xFF
                    if (streamId in 0xE0..0xEF) {
                        if (videoPid == -1) videoPid = pid
                        if (pid == videoPid) {
                            val esStart = payload + 9 + (bytes[payload + 8].toInt() and 0xFF)
                            if (esStart < end) es.write(bytes, esStart, end - esStart)
                        }
                    }
                } else if (pid == videoPid) {
                    es.write(bytes, payload, end - payload)
                }
            }
            // The parameter sets open the first access unit, so a few kilobytes are plenty.
            if (es.size() >= ES_READ_LIMIT) break
            packet = end
        }
        return if (es.size() > 0) h264Resolution(es.toByteArray()) else null
    }

    private const val ES_READ_LIMIT = 64 * 1024

    private const val SYNC = 0x47.toByte()

    private fun h264Resolution(es: ByteArray): Resolution? {
        var i = 0
        while (i + 4 < es.size) {
            if (es[i] == 0.toByte() && es[i + 1] == 0.toByte() && es[i + 2] == 1.toByte()) {
                val header = es[i + 3].toInt() and 0xFF
                if (header and 0x80 == 0 && header and 0x1F == 7) {
                    val nalEnd = (i + 4 until es.size - 2).firstOrNull { j ->
                        es[j] == 0.toByte() && es[j + 1] == 0.toByte() && (es[j + 2] == 1.toByte() || es[j + 2] == 0.toByte())
                    } ?: return null // the parameter set has not fully arrived yet
                    return runCatching { parseSps(unescape(es, i + 4, nalEnd)) }.getOrNull()
                }
                i += 3
            } else {
                i++
            }
        }
        return null
    }

    /** Drops the emulation-prevention byte from every `00 00 03`. */
    private fun unescape(bytes: ByteArray, start: Int, end: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(end - start)
        var zeros = 0
        for (i in start until end) {
            val b = bytes[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** ITU-T H.264 §7.3.2.1.1, as far as the cropped frame size. */
    private fun parseSps(sps: ByteArray): Resolution {
        val r = BitReader(sps)
        val profile = r.bits(8)
        r.bits(16) // constraint flags, level
        r.ue() // seq_parameter_set_id
        var chromaFormat = 1
        if (profile in HIGH_PROFILES) {
            chromaFormat = r.ue()
            if (chromaFormat == 3) r.bits(1)
            r.ue()
            r.ue()
            r.bits(1)
            if (r.bits(1) == 1) {
                repeat(if (chromaFormat != 3) 8 else 12) { list ->
                    if (r.bits(1) == 1) {
                        var last = 8
                        var next = 8
                        repeat(if (list < 6) 16 else 64) {
                            if (next != 0) next = (last + r.se() + 256) % 256
                            if (next != 0) last = next
                        }
                    }
                }
            }
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) {
            0 -> r.ue()
            1 -> {
                r.bits(1)
                r.se()
                r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        r.ue() // max_num_ref_frames
        r.bits(1)
        val widthMbs = r.ue() + 1
        val heightUnits = r.ue() + 1
        val frameMbsOnly = r.bits(1)
        if (frameMbsOnly == 0) r.bits(1)
        r.bits(1)
        var width = widthMbs * 16
        var height = (2 - frameMbsOnly) * heightUnits * 16
        if (r.bits(1) == 1) {
            val cropX = if (chromaFormat == 1 || chromaFormat == 2) 2 else 1
            val cropY = (if (chromaFormat == 1) 2 else 1) * (2 - frameMbsOnly)
            val left = r.ue()
            val right = r.ue()
            val top = r.ue()
            val bottom = r.ue()
            width -= (left + right) * (if (chromaFormat == 0) 1 else cropX)
            height -= (top + bottom) * (if (chromaFormat == 0) 2 - frameMbsOnly else cropY)
        }
        return Resolution(width, height)
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    private class BitReader(private val data: ByteArray) {
        private var position = 0

        fun bits(count: Int): Int {
            var value = 0
            repeat(count) {
                val byte = data.getOrNull(position ushr 3)?.toInt() ?: throw IndexOutOfBoundsException()
                value = (value shl 1) or ((byte shr (7 - (position and 7))) and 1)
                position++
            }
            return value
        }

        fun ue(): Int {
            var zeros = 0
            while (bits(1) == 0) if (++zeros > 31) throw IllegalStateException()
            return (1 shl zeros) - 1 + bits(zeros)
        }

        fun se(): Int {
            val code = ue()
            return if (code and 1 == 1) (code + 1) / 2 else -(code / 2)
        }
    }

    // ================================ helpers ================================

    private fun u32(bytes: ByteArray, offset: Int): Long = ((bytes[offset].toLong() and 0xFF) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
        (bytes[offset + 3].toLong() and 0xFF)

    private fun ByteArray.startsWithText(prefix: String): Boolean {
        // Tolerate a UTF-8 byte-order mark and leading whitespace.
        var i = if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()) 3 else 0
        while (i < size && this[i].toInt().toChar().isWhitespace()) i++
        if (i + prefix.length > size) return false
        return prefix.indices.all { this[i + it] == prefix[it].code.toByte() }
    }
}
