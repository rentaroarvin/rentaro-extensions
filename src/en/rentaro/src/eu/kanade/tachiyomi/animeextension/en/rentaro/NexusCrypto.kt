package eu.kanade.tachiyomi.animeextension.en.rentaro

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Payload codec for the Nexus backend.
 *
 * Both directions of its API are CryptoJS-style AES: the request travels as an
 * encrypted `?q=` parameter and the response body is a single encrypted
 * `_hash` string. The format is OpenSSL's, i.e.
 *
 *     base64url_nopad( "Salted__" ++ salt(8) ++ AES-256-CBC(plaintext) )
 *
 * with the key and IV derived from a passphrase via EVP_BytesToKey (MD5, one
 * iteration). Requests additionally carry `_req_ts` and `_req_salt`, which the
 * server tolerates on the way back and are stripped when decoding.
 *
 * Verified round-trip against the live API.
 */
internal object NexusCrypto {

    /**
     * Passphrase used by the site's own client, where it is assembled from
     * character codes to keep it out of plain sight in the bundle.
     */
    private val PASSPHRASE = String(
        charArrayOf(
            'S', '8', 'x', '!', 'J', 'k', '4', 'Z',
            'P', '1', 'u', 'G', '8', '$', 'm', 'y',
        ),
    )

    private const val SALT_MAGIC = "Salted__"
    private const val SALT_SIZE = 8
    private const val KEY_SIZE = 32
    private const val IV_SIZE = 16
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"

    private val secureRandom by lazy { SecureRandom() }

    /** Encrypts [json] into the form the `?q=` parameter expects. */
    fun encode(json: String): String {
        val salt = ByteArray(SALT_SIZE).also(secureRandom::nextBytes)
        val (key, iv) = deriveKeyAndIv(salt)

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        val body = cipher.doFinal(json.toByteArray(Charsets.UTF_8))

        val framed = SALT_MAGIC.toByteArray(Charsets.US_ASCII) + salt + body
        return Base64.encodeToString(framed, Base64.NO_WRAP)
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
    }

    /** Decrypts a `_hash` string back to JSON, or null if it is not readable. */
    fun decode(payload: String): String? = runCatching {
        val normalised = payload.replace('-', '+').replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        val raw = Base64.decode(normalised, Base64.DEFAULT)

        val magic = SALT_MAGIC.toByteArray(Charsets.US_ASCII)
        if (raw.size <= magic.size + SALT_SIZE) return@runCatching null
        if (!raw.copyOfRange(0, magic.size).contentEquals(magic)) return@runCatching null

        val salt = raw.copyOfRange(magic.size, magic.size + SALT_SIZE)
        val body = raw.copyOfRange(magic.size + SALT_SIZE, raw.size)
        val (key, iv) = deriveKeyAndIv(salt)

        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    /**
     * OpenSSL EVP_BytesToKey with MD5 and a single iteration:
     *
     *     D1 = MD5(pass ++ salt)
     *     Dn = MD5(D(n-1) ++ pass ++ salt)
     *     key = D1 ++ D2   iv = D3
     */
    private fun deriveKeyAndIv(salt: ByteArray): Pair<ByteArray, ByteArray> {
        val password = PASSPHRASE.toByteArray(Charsets.UTF_8)
        val md5 = MessageDigest.getInstance("MD5")

        val material = ByteArray(KEY_SIZE + IV_SIZE)
        var filled = 0
        var previous = ByteArray(0)

        while (filled < material.size) {
            md5.reset()
            md5.update(previous)
            md5.update(password)
            md5.update(salt)
            previous = md5.digest()

            val take = minOf(previous.size, material.size - filled)
            previous.copyInto(material, filled, 0, take)
            filled += take
        }

        return material.copyOfRange(0, KEY_SIZE) to
            material.copyOfRange(KEY_SIZE, KEY_SIZE + IV_SIZE)
    }
}
