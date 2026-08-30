package eu.kanade.tachiyomi.animeextension.en.rentaro

import android.util.Base64

/**
 * Decryptor for the Videasy `enc=2` payload.
 *
 * Ported from the site's own player bundle, so no external decryption service
 * is involved. The response is a base64url blob XORed with a keystream derived
 * from the per-media `seed` and the TMDB id; the plaintext is prefixed with a
 * four-byte magic that doubles as an integrity check.
 *
 * The bundle guards two branches with `(e * (e + 1) & 1) == 0` and its
 * negation. `e * (e + 1)` is the product of consecutive integers and so always
 * even, which makes the first always true and the second unreachable — the
 * RC4-style table and the alternate seeding path are decoys. Only the branch
 * kept below ever runs, verified against live payloads from several servers.
 */
internal object VideasyCipher {

    /** "mvm1" — plaintext prefix, and the tamper check. */
    private val MAGIC = byteArrayOf(109, 118, 109, 49)

    /** SHA-256 round constants, reused by the bundle as an arbitrary table. */
    private val TABLE = intArrayOf(
        1116352408, 1899447441, -1245643825, -373957723,
        961987163, 1508970993, -1841331548, -1424204075,
        -670586216, 310598401, 607225278, 1426881987,
        1925078388, -2132889090, -1680079193, -1046744716,
    )

    private const val GOLDEN = -1640531527 // 2654435769
    private const val STATE_SIZE = 61

    /**
     * Decrypts a `sources-with-title` response.
     *
     * @param payload base64url body as returned by the API
     * @param seed value from the `/seed` endpoint used for the same request
     * @param mediaId TMDB id the seed was issued for
     * @return the JSON plaintext, or null if the seed does not match
     */
    fun decrypt(payload: String, seed: String, mediaId: Int): String? = runCatching {
        val data = decodeBase64Url(payload)
        val keystream = keystream(seed, mediaId, data.size)

        for (i in data.indices) {
            data[i] = (data[i].toInt() xor keystream[i].toInt()).toByte()
        }

        if (data.size <= MAGIC.size) return@runCatching null
        for (i in MAGIC.indices) {
            if (data[i] != MAGIC[i]) return@runCatching null
        }

        String(data, MAGIC.size, data.size - MAGIC.size, Charsets.UTF_8)
    }.getOrNull()

    private fun decodeBase64Url(text: String): ByteArray {
        val normalised = text.replace('-', '+').replace('/', '/').replace('_', '/')
        val padded = normalised.padEnd(4 * ((normalised.length + 3) / 4), '=')
        return Base64.decode(padded, Base64.DEFAULT)
    }

    /** Finalising mix; the bundle's `w()`. */
    private fun mix(value: Int): Int {
        var e = value
        e = e xor (e ushr 16)
        e *= -2048144789 // 2246822507
        e = e xor (e ushr 13)
        e *= -1028477387 // 3266489909
        e = e xor (e ushr 16)
        return e
    }

    private fun rotateLeft(value: Int, bits: Int): Int {
        val n = bits and 31
        return if (n == 0) value else (value shl n) or (value ushr (32 - n))
    }

    /**
     * `x % 61` as the bundle computes it.
     *
     * JavaScript applies `%` to a uint32, so the operand is never negative.
     * Kotlin's `Int` is signed and both `%` and `floorMod` disagree once the
     * high bit is set — `0xFFFFFFFF % 61` is 56 there but 60 under `floorMod`.
     * Widening to an unsigned long first keeps the two in step.
     */
    private fun unsignedMod(value: Int, modulus: Int): Int = (Integer.toUnsignedLong(value) % modulus).toInt()

    /** FNV-1a over the seed string. */
    private fun fnv1a(text: String): Int {
        var t = -2128831035 // 2166136261
        for (ch in text) {
            t = (t xor ch.code) * 16777619
        }
        return mix(t)
    }

    /**
     * Generator state. The bundle uses a sparse `Array(61)`, and the generator
     * distinguishes a slot that has been written from one that has not, so
     * occupancy is tracked rather than relying on a zero value.
     */
    private class State(val slots: IntArray, val filled: BooleanArray, var acc: Int)

    private fun initState(seed: String, mediaId: Int): State {
        var a = mix(fnv1a(seed) xor mix(mediaId xor GOLDEN))
        val slots = IntArray(STATE_SIZE)
        val filled = BooleanArray(STATE_SIZE)

        // The bundle's loop runs 8 times and only ever takes the first branch.
        for (i in 0 until 8) {
            val index = unsignedMod(a, STATE_SIZE)
            a = rotateLeft(a + GOLDEN, 7 + (i and 7))
            slots[index] = a xor mix(a)
            filled[index] = true
            a = mix(a + index)
        }

        return State(slots, filled, mix(-1515870811 xor a)) // 2779096485
    }

    private fun next(state: State, counter: Int): Int {
        val n = unsignedMod(state.acc, STATE_SIZE)
        val o = state.acc
        // `0 - Number(n in r)`: all bits set once the slot has been written.
        val occupied = if (state.filled[n]) -1 else 0
        val d = state.slots[n]

        val a = d xor (GOLDEN * (counter + 1))
        var l = (o xor a) or (o and a and occupied)
        l = rotateLeft(l + o, 31 and n) xor rotateLeft(o, 31 and (n * 7))

        val result = mix(l + GOLDEN)
        state.slots[n] = result
        state.filled[n] = true
        state.acc = result
        return result
    }

    private fun keystream(seed: String, mediaId: Int, length: Int): ByteArray {
        val state = initState(seed, mediaId)
        val out = ByteArray(length)
        var written = 0
        var counter = 0

        while (written < length) {
            val t = next(state, counter++)
            out[written++] = (t and 255).toByte()
            if (written < length) out[written++] = ((t ushr 8) and 255).toByte()
            if (written < length) out[written++] = ((t ushr 16) and 255).toByte()
            if (written < length) out[written++] = ((t ushr 24) and 255).toByte()
        }

        return out
    }
}
