package eu.kanade.tachiyomi.animeextension.en.arvindf

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-CTR the way the Abyss player applies it. Its key is a 32 character lowercase hex string
 * (an MD5 digest) used as 32 ASCII bytes, not decoded to 16, and the counter starts at the first 16 of
 * those same bytes. The same call encrypts and decrypts, which is how the tests build their fixtures.
 */
internal object AbyssCrypto {

    fun md5Hex(text: String): String = MessageDigest.getInstance("MD5")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /**
     * Runs [data] through the keystream starting [offset] bytes in, so a range that begins inside the
     * encrypted head of a file can be decrypted without reading from byte zero.
     */
    fun apply(keyText: String, data: ByteArray, offset: Long = 0L): ByteArray {
        val key = keyText.toByteArray(Charsets.US_ASCII)
        require(key.size == KEY_BYTES) { "Abyss keys are $KEY_BYTES characters, got ${key.size}" }
        val counter = advance(key.copyOf(BLOCK_BYTES), offset / BLOCK_BYTES)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
        // A range can start mid-block: burn the unwanted front of that block's keystream.
        val skip = (offset % BLOCK_BYTES).toInt()
        if (skip > 0) cipher.update(ByteArray(skip))
        return cipher.doFinal(data)
    }

    /** Adds [blocks] to a 128-bit big-endian counter, which is how CTR with a 128-bit counter steps. */
    private fun advance(counter: ByteArray, blocks: Long): ByteArray {
        val out = counter.copyOf()
        var remaining = blocks
        var carry = 0
        for (index in BLOCK_BYTES - 1 downTo 0) {
            val sum = (out[index].toInt() and 0xFF) + (remaining and 0xFF).toInt() + carry
            out[index] = sum.toByte()
            carry = sum ushr 8
            remaining = remaining ushr 8
        }
        return out
    }

    private const val KEY_BYTES = 32
    private const val BLOCK_BYTES = 16
}
