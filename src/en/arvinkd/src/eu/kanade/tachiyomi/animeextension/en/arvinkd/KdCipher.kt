package eu.kanade.tachiyomi.animeextension.en.arvinkd

import okio.ByteString.Companion.decodeBase64
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Opens the URL envelopes KiligDrama wraps around every stream and subtitle link (`chain[].enc`,
 * `subtitle.enc`, the flat legacy `enc`).
 *
 * Lifted from the player chunk on 1 November 2026: the Next.js chunk that contains
 * `"/api/episode-source/"` also defines `async function u(e)`, which base64-decodes the envelope and feeds
 * it to `crypto.subtle.decrypt({name:"AES-GCM",iv}, key, data)`. [PLAYER_KEY] is the base64 text handed to
 * `crypto.subtle.importKey("raw", ..., {name:"AES-GCM"}, ...)` just before it. An envelope is base64 of a
 * 12-byte IV followed by the ciphertext and its 16-byte tag, which is the layout the JDK's GCM wants after
 * the IV. The key ships in the page for every visitor, and a rebuild that rotates it shows up as every
 * episode failing to open.
 *
 * This only unwraps a link. The streams it points to were plain, unencrypted HLS in the one source tested.
 */
internal object KdCipher {

    private const val PLAYER_KEY = "QC6Ir2trghxRAyyyWZEOEFR4GgLhnfQ4A19I3QBlQkc="

    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val key by lazy { PLAYER_KEY.decodeBase64()!!.toByteArray() }

    /** The URL [sealed] holds, or null when it does not open under the current key. */
    fun open(sealed: String?): String? {
        val raw = sealed?.trim()?.decodeBase64()?.toByteArray() ?: return null
        if (raw.size <= IV_BYTES + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
                .trim()
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
        } catch (_: GeneralSecurityException) {
            null
        }
    }
}
