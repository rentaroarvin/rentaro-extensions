package eu.kanade.tachiyomi.animeextension.en.rentaro

import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.decodeHex
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Opens the stream URLs Vidrock (vidrock.net, Ishi) seals in each source of its `/api/...` reply.
 *
 * Lifted from the player's bundle on 30 October 2026. The page at `/tv/<id>/<season>/<episode>`
 * loads `/assets/index-<hash>.js`; in it, [PLAYER_KEY] is the 64-hex string declared just before
 * the `Uint8Array(r.length/2)` hex decoder that feeds `crypto.subtle.importKey("raw", ...,
 * {name:"AES-GCM"}, ...)`. A sealed URL is base64url of a 12-byte IV followed by the ciphertext and
 * its 16-byte tag, which is the layout the JDK's GCM wants after the IV. It ships in the page for
 * every visitor; a rebuild that rotates it shows up as every source failing to open.
 */
internal object VidrockCipher {

    private const val PLAYER_KEY = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"

    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val key by lazy { PLAYER_KEY.decodeHex().toByteArray() }

    /** The URL [sealed] holds, or null when it does not open under the current key. */
    fun decrypt(sealed: String): String? {
        val raw = sealed.trim().decodeBase64()?.toByteArray() ?: return null
        if (raw.size <= IV_BYTES + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        }
    }
}
