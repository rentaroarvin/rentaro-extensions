package eu.kanade.tachiyomi.animeextension.en.rentaro

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Opens the stream MegaPlay (megaplay.buzz), the player behind every Anikoto (Koto) server,
 * hides in the `enc` field of `/stream/getSources`.
 *
 * Lifted from megaplay.buzz on 15 October 2026 by watching its player in a browser: `enc` is
 * base64url AES-256-CBC of `{"file":"<master.m3u8>"}`. The key and IV are the `trustAesKey` and
 * `trustAesIv` defaults in `newclient.min.js`, each zero-padded to its length; the same pair
 * also opens the player's domain list and trust state.
 */
internal object MegaPlayCipher {

    private val KEY = padded("i?LMTAx0Q6,:}50U", 32)
    private val IV = padded("W0;27ToaUpl_P%'c", 16)

    /** The decrypted JSON, or null when [enc] does not open with the current key. */
    fun decrypt(enc: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
        String(cipher.doFinal(Base64.decode(enc, Base64.URL_SAFE or Base64.NO_PADDING)), Charsets.UTF_8)
    }.getOrNull()

    /** The page's `I()`: UTF-8 bytes cut or zero-filled to [length]. */
    private fun padded(value: String, length: Int): ByteArray = value.toByteArray(Charsets.UTF_8).copyOf(length)
}
