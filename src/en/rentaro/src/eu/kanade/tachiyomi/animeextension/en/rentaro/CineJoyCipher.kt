package eu.kanade.tachiyomi.animeextension.en.rentaro

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decryptor for the CineJoy (Jay) reply.
 *
 * The site's own crypto lives in a WASM module, so building the *request* body
 * still needs enc-dec.app. Reading the reply does not: that service hands back
 * the AES key and additional data in plain, and the reply itself is ordinary
 * AES-256-GCM. Decrypting here removes one of the two remote calls, and with it
 * a round trip on the critical path.
 *
 * The reply frames as `iv(12) ++ ciphertext ++ tag(16)`, with the tag appended
 * as the platform's GCM implementation expects. Verified against live payloads.
 */
internal object CineJoyCipher {

    /** GCM nonce length the endpoint uses. */
    private const val IV_SIZE = 12

    /** GCM tag length, in bits, as `GCMParameterSpec` wants it. */
    private const val TAG_BITS = 128

    /** Bytes the tag occupies at the end of the reply. */
    private const val TAG_SIZE = TAG_BITS / 8

    /**
     * Label the endpoint binds into the additional data, ahead of the request
     * header. Only needed when reconstructing the AAD locally.
     */
    private const val AAD_LABEL = "lumen-gate-v2\u0000"

    /** Bytes of the request body the additional data covers. */
    private const val AAD_BODY_SIZE = 67

    /**
     * Decrypts a reply from `api.shegu.st/g`.
     *
     * @param response raw reply bytes
     * @param key 32-byte AES key, from the enc step's `responseKey`
     * @param aad additional data, from the enc step's `aad`
     * @return the JSON plaintext, or null if authentication fails
     */
    fun decrypt(response: ByteArray, key: ByteArray, aad: ByteArray): String? = runCatching {
        // Anything shorter cannot hold both a nonce and a tag, and would make the
        // range arithmetic below produce a negative length.
        if (response.size <= IV_SIZE + TAG_SIZE) return null
        if (key.size != 32) return null

        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, response, 0, IV_SIZE),
            )
            updateAAD(aad)
        }

        // The tag stays attached: GCM expects it as the tail of the input.
        val body = cipher.doFinal(response, IV_SIZE, response.size - IV_SIZE)
        String(body, Charsets.UTF_8)
    }.getOrNull()

    /**
     * Rebuilds the additional data from the request body.
     *
     * Only used if the enc step stops returning `aad`; the returned value is
     * byte-identical to the one it sends today.
     */
    fun aadFor(requestBody: ByteArray): ByteArray? {
        if (requestBody.size < AAD_BODY_SIZE) return null
        return AAD_LABEL.toByteArray(Charsets.UTF_8) +
            requestBody.copyOfRange(0, AAD_BODY_SIZE)
    }

    /** Decodes base64, accepting both the url-safe and standard alphabets. */
    fun decodeBase64(value: String): ByteArray? = runCatching {
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }.getOrNull()
}
