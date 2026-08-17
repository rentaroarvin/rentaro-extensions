package eu.kanade.tachiyomi.animeextension.en.rentaro

import android.util.Base64
import java.math.BigInteger

/**
 * Request signing for VidLink's `/api/b` endpoint.
 *
 * VidLink signs requests with a NaCl `crypto_secretbox_easy` token:
 *
 *     msg    = utf8(tmdbId) ++ int64_be(unixNow + 120)
 *     token  = base64url_nopad( nonce(24 zero bytes) ++ tag(16) ++ ciphertext )
 *
 * The secretbox key and the nonce are both hard-coded constants on their side,
 * which makes the XSalsa20 keystream invariant. That lets us skip a Salsa20
 * implementation entirely and keep only the parts that depend on the message:
 * a keystream XOR for the ciphertext and Poly1305 for the tag.
 *
 * Verified byte-exact against a libsodium reference vector, and end-to-end
 * against the live API.
 */
internal object VidLinkToken {

    /**
     * First 64 bytes of the XSalsa20 keystream for VidLink's fixed key and
     * 24-byte zero nonce. Bytes 0..31 are the Poly1305 one-time key; bytes
     * 32.. are the pad the plaintext is XORed against.
     *
     * Messages are ~15 bytes (7-digit id + 8-byte expiry), so 32 pad bytes is
     * ample; [secretBox] fails loudly rather than silently truncating.
     */
    private val KEYSTREAM = byteArrayOfHex(
        "36b39d60b3bcb0685e8feaa755c9667b8b235149616e879d758a9b536aa2a141" +
            "f0b338fe4d405619d9c44a88a638d78c248aa9b809bfcf42bcdadeefbbf45838",
    )

    private const val NONCE_LEN = 24
    private const val TAG_LEN = 16

    private val TWO = BigInteger.valueOf(2)

    /**
     * 2^130 - 5, the Poly1305 prime.
     *
     * Built from [BigInteger.ONE], not `BigInteger.TWO`: that constant is only
     * available from API 33, while extensions target a minSdk of 21. Referencing
     * it threw NoSuchFieldError during class init on older devices, which the
     * caller's runCatching swallowed — the server just silently disappeared.
     */
    private val P = TWO.pow(130).subtract(BigInteger.valueOf(5))

    /** Clamp mask applied to `r`, per RFC 8439. */
    private val R_CLAMP = BigInteger(1, byteArrayOfHex("0ffffffc0ffffffc0ffffffc0fffffff"))

    private val TWO_POW_128 = TWO.pow(128)

    fun create(tmdbId: String, expiryEpochSeconds: Long): String {
        val idBytes = tmdbId.toByteArray(Charsets.UTF_8)
        val message = ByteArray(idBytes.size + 8)
        idBytes.copyInto(message)
        for (i in 0 until 8) {
            // int64 big-endian
            message[idBytes.size + i] = (expiryEpochSeconds ushr (8 * (7 - i))).toByte()
        }

        val box = secretBox(message)
        val out = ByteArray(NONCE_LEN + box.size)
        box.copyInto(out, NONCE_LEN)
        return Base64.encodeToString(out, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Returns `tag || ciphertext`, matching `crypto_secretbox_easy`. */
    private fun secretBox(message: ByteArray): ByteArray {
        val padLen = KEYSTREAM.size - 32
        require(message.size <= padLen) {
            "VidLink token message too long: ${message.size} > $padLen"
        }

        val cipher = ByteArray(message.size)
        for (i in message.indices) {
            cipher[i] = (message[i].toInt() xor KEYSTREAM[32 + i].toInt()).toByte()
        }

        val tag = poly1305(cipher, KEYSTREAM.copyOfRange(0, 32))
        return tag + cipher
    }

    /**
     * Poly1305 one-time authenticator (RFC 8439).
     *
     * Uses [BigInteger] rather than 26-bit limb arithmetic: the inputs here are
     * a single block, so the allocation cost is irrelevant and the carry
     * handling is far harder to get wrong.
     */
    private fun poly1305(message: ByteArray, key: ByteArray): ByteArray {
        val r = leToBigInt(key, 0, 16).and(R_CLAMP)
        val s = leToBigInt(key, 16, 16)

        var acc = BigInteger.ZERO
        var offset = 0
        while (offset < message.size) {
            val len = minOf(16, message.size - offset)
            // Append the 0x01 byte the spec requires, then interpret little-endian.
            val block = ByteArray(len + 1)
            message.copyInto(block, 0, offset, offset + len)
            block[len] = 1
            acc = acc.add(leToBigInt(block, 0, block.size)).multiply(r).mod(P)
            offset += len
        }

        val digest = acc.add(s).mod(TWO_POW_128)
        return bigIntToLe(digest, TAG_LEN)
    }

    private fun leToBigInt(bytes: ByteArray, from: Int, length: Int): BigInteger {
        val be = ByteArray(length)
        for (i in 0 until length) {
            be[length - 1 - i] = bytes[from + i]
        }
        return BigInteger(1, be)
    }

    private fun bigIntToLe(value: BigInteger, length: Int): ByteArray {
        val be = value.toByteArray()
        val out = ByteArray(length)
        // toByteArray() is big-endian and may carry a leading sign byte.
        var src = be.size - 1
        var dst = 0
        while (src >= 0 && dst < length) {
            out[dst] = be[src]
            src--
            dst++
        }
        return out
    }

    private fun byteArrayOfHex(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
