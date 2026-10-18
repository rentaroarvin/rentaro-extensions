package eu.kanade.tachiyomi.animeextension.en.rentaro

import android.util.Base64

/**
 * Opens the stream list of the vidsrc.sh player, the backend behind Videasy and VidSrc.to (Haru).
 *
 * Lifted from the player's `vsdec.js` and the decryptor modules it loads on 18 October 2026.
 * `data.stream_urls` arrives as one base64 string, a 12-byte nonce followed by ChaCha20
 * ciphertext, the URLs inside separated by newlines. The site hands the browser a WebAssembly
 * module (`vs.wasm_url`) that decrypts it, regenerated for every five-minute window. In all six
 * windows checked the module was the same IETF ChaCha20 (20 rounds, block counter from 0) and
 * only its data moved: the 256-bit key is two 32-byte data segments XORed, one at address 0 and
 * one wherever that window put it, among decoys of the same size. So the module is read for its
 * data segments rather than run, and the pair that opens the string to a URL is the key.
 *
 * A window whose module no longer has that shape opens to nothing and Haru returns no rows;
 * `wasm2wat` on the new module's `decrypt` function shows what changed.
 */
internal object VidSrcCipher {

    private const val KEY_LENGTH = 32
    private const val NONCE_LENGTH = 12
    private const val BLOCK_LENGTH = 64
    private const val DATA_SECTION = 11
    private const val OP_I32_CONST = 0x41
    private const val OP_END = 0x0B

    private val WASM_HEADER = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

    /** What every opened list starts with, which tells the right key from a wrong one. */
    private val SCHEME = "http".toByteArray(Charsets.US_ASCII)

    /** The URLs [sealed] holds, or an empty list when no key in [wasm] opens it. Never throws. */
    fun open(sealed: String, wasm: ByteArray): List<String> = runCatching {
        val raw = Base64.decode(sealed, Base64.DEFAULT)
        if (raw.size <= NONCE_LENGTH) return@runCatching emptyList()
        val nonce = IntArray(3) { word(raw, it * 4) }
        val body = raw.copyOfRange(NONCE_LENGTH, raw.size)

        val segments = dataSegments(wasm).filter { it.size == KEY_LENGTH }
        val pairs = segments.flatMapIndexed { index, a ->
            segments.drop(index + 1).map { b -> ByteArray(KEY_LENGTH) { (a[it].toInt() xor b[it].toInt()).toByte() } }
        }

        (segments + pairs)
            .map { key -> IntArray(8) { word(key, it * 4) } }
            .firstOrNull { key -> chacha(key, nonce, body, SCHEME.size).contentEquals(SCHEME) }
            ?.let { key ->
                String(chacha(key, nonce, body, body.size), Charsets.UTF_8)
                    .lines()
                    .map(String::trim)
                    .filter { it.startsWith("http") }
            }
            .orEmpty()
    }.getOrDefault(emptyList())

    /** The data segments of a module, in file order. Empty for anything but plain active or passive segments. */
    private fun dataSegments(wasm: ByteArray): List<ByteArray> {
        if (wasm.size < WASM_HEADER.size || !wasm.copyOfRange(0, WASM_HEADER.size).contentEquals(WASM_HEADER)) return emptyList()
        val reader = Reader(wasm, WASM_HEADER.size)
        while (reader.hasMore()) {
            val id = reader.byte()
            val end = reader.unsigned().let { reader.position + it }
            if (id != DATA_SECTION) {
                reader.position = end
                continue
            }
            val segments = mutableListOf<ByteArray>()
            repeat(reader.unsigned()) {
                when (reader.unsigned()) {
                    // Active in memory 0: an `i32.const` offset, which only places the bytes.
                    0 -> {
                        if (reader.byte() != OP_I32_CONST) return emptyList()
                        reader.signed()
                        if (reader.byte() != OP_END) return emptyList()
                    }
                    1 -> Unit
                    else -> return emptyList()
                }
                segments += reader.bytes(reader.unsigned())
            }
            return segments
        }
        return emptyList()
    }

    private class Reader(private val data: ByteArray, var position: Int) {
        fun hasMore(): Boolean = position < data.size

        fun byte(): Int = data[position++].toInt() and 0xFF

        fun bytes(length: Int): ByteArray = data.copyOfRange(position, position + length).also { position += length }

        /** LEB128, as the module format stores every size and count. */
        fun unsigned(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val next = byte()
                result = result or ((next and 0x7F) shl shift)
                if (next and 0x80 == 0) return result
                shift += 7
            }
        }

        /** Signed LEB128. Only read to step over an offset, so the value itself is dropped. */
        fun signed() {
            while (byte() and 0x80 != 0) Unit
        }
    }

    /** XORs the first [length] bytes of [data] with the ChaCha20 keystream. */
    private fun chacha(key: IntArray, nonce: IntArray, data: ByteArray, length: Int): ByteArray {
        val out = ByteArray(minOf(length, data.size))
        val stream = ByteArray(BLOCK_LENGTH)
        var offset = 0
        var counter = 0
        while (offset < out.size) {
            block(key, counter++, nonce, stream)
            for (i in 0 until minOf(BLOCK_LENGTH, out.size - offset)) {
                out[offset + i] = (data[offset + i].toInt() xor stream[i].toInt()).toByte()
            }
            offset += BLOCK_LENGTH
        }
        return out
    }

    private fun block(key: IntArray, counter: Int, nonce: IntArray, out: ByteArray) {
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646E
        state[2] = 0x79622D32
        state[3] = 0x6B206574
        key.copyInto(state, 4)
        state[12] = counter
        nonce.copyInto(state, 13)

        val x = state.copyOf()
        repeat(10) {
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 1, 5, 9, 13)
            quarterRound(x, 2, 6, 10, 14)
            quarterRound(x, 3, 7, 11, 15)
            quarterRound(x, 0, 5, 10, 15)
            quarterRound(x, 1, 6, 11, 12)
            quarterRound(x, 2, 7, 8, 13)
            quarterRound(x, 3, 4, 9, 14)
        }

        for (i in 0 until 16) {
            val value = x[i] + state[i]
            out[i * 4] = value.toByte()
            out[i * 4 + 1] = (value ushr 8).toByte()
            out[i * 4 + 2] = (value ushr 16).toByte()
            out[i * 4 + 3] = (value ushr 24).toByte()
        }
    }

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]
        x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]
        x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]
        x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]
        x[b] = (x[b] xor x[c]).rotateLeft(7)
    }

    /** Little-endian 32-bit word at [offset]. */
    private fun word(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
