package eu.kanade.tachiyomi.animeextension.en.rentaro

import okio.ByteString.Companion.decodeBase64
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Decodes API metadata, not media or DRM. Material is public in OneTouchTV's `main.dart.js`, `mP`. */
internal object OneTouchCipher {
    private val key = "im72charPasswordofdInitVectorStm".toByteArray(Charsets.UTF_8)

    fun decrypt(body: String): String {
        val ciphertext = body.trim().replace("@", "+").replace("-_.", "/").decodeBase64()
            ?: throw IOException("Invalid OneTouchTV response encoding")
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key.copyOf(16)))
            return cipher.doFinal(ciphertext.toByteArray()).toString(Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            // A changed API key must cost only this backend, not cancel the other servers.
            throw IOException("Could not decode OneTouchTV response", e)
        }
    }
}
