package eu.kanade.tachiyomi.animeextension.en.rentaro

import android.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Request signing and reply decryption for the Screenscape (Sun) backend.
 *
 * Lifted from the site's player bundle on 26 September 2026. Nothing here needs a
 * browser or an external service; it is HMAC-SHA256, SHA-256, MD5, a pair of XOR
 * passes and CryptoJS-compatible AES:
 *
 *  1. A bootstrap POST to `/api/<token route>` with a random hex secret returns an
 *     envelope that decrypts, under that secret, to a `responseKey` and `apiToken`.
 *  2. Each server call is `/api/<route id>/<server id>?q=<tmdb id>&type=…`, every id a
 *     `base64url(payload).hmac24(payload, responseKey)` pair, sent with `x-api-token`.
 *  3. Every reply is an envelope `{d, s, v}` whose key is derived from the response key,
 *     the canonical request (method, path, sorted query) and the hour of its timestamp.
 */
internal object ScreenscapeCipher {

    /** Static secret mixed into every envelope key (deobfuscated from the bundle). */
    private const val ENVELOPE_SECRET = "IAGFXUlAEZymC5s3XEnuLO9P0xHCCKrnJ6tMVE7MeavZmwhvV3siY0OxqWyWRLw"

    /** Static secret for the inner XOR layer. */
    private const val INNER_SECRET = "9dZUFgMqUg3_CYHUGpVL1wCV-UOp7EvbflD6srGLsI9HzYF-0MX06wy3rHoxMV8"

    /** Length of the envelope-key prefix used as the outer XOR key. */
    private const val OUTER_XOR_LENGTH = 18

    /** Length of the digest prefix used as the inner XOR key. */
    private const val INNER_XOR_LENGTH = 14

    /** Signature length the site truncates its request ids to. */
    private const val ID_SIGNATURE_LENGTH = 24

    private const val HOUR_MS = 3_600_000L

    private val random = SecureRandom()

    /** A random lowercase hex string of [bytes] bytes, as the site's nonces are. */
    fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).toHex()

    /** Path code for the bootstrap POST, signed with the bootstrap secret. */
    fun tokenRouteCode(bootstrap: String): String = signedId("token.${now36()}.${randomHex(9)}", bootstrap)

    /** First path segment of a server call. */
    fun routeId(responseKey: String): String = signedId(
        JsonObject(
            mapOf(
                "k" to JsonPrimitive("route"),
                "v" to JsonPrimitive("server"),
                "t" to JsonPrimitive(System.currentTimeMillis()),
                "n" to JsonPrimitive(randomHex(9)),
            ),
        ).toString(),
        responseKey,
    )

    /** Second path segment of a server call, naming the upstream server. */
    fun serverId(server: String, responseKey: String): String = signedId("$server.${now36()}.${randomHex(9)}", responseKey)

    /** The `q` parameter of a server call, carrying the title being asked for. */
    fun tmdbId(tmdbId: String, season: Int?, episode: Int?, responseKey: String): String = signedId(
        JsonObject(
            mapOf(
                "k" to JsonPrimitive("tmdb"),
                "t" to JsonPrimitive(System.currentTimeMillis()),
                "n" to JsonPrimitive(randomHex(9)),
                "tmdbId" to JsonPrimitive(tmdbId),
                "season" to JsonPrimitive(season),
                "episode" to JsonPrimitive(episode),
            ),
        ).toString(),
        responseKey,
    )

    /**
     * The canonical request the envelope key is bound to: `METHOD:/path?sorted-query`.
     *
     * Query pairs are sorted by name then value, as the site's URLSearchParams sort does.
     * Every value this backend sends is base64url or a plain word, so no escaping is needed.
     */
    fun cipherContext(method: String, path: String, query: List<Pair<String, String>>): String {
        val sorted = query.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        return "${method.uppercase()}:$path?" + sorted.joinToString("&") { (name, value) -> "$name=$value" }
    }

    /**
     * Decrypts a `{d, s, v}` envelope to its JSON plaintext.
     *
     * @return the plaintext, or null if the signature does not verify under [key]
     */
    fun open(d: String, s: String, v: Int, key: String, context: String): String? = runCatching {
        val body = String(Base64.decode(d, Base64.DEFAULT), Charsets.UTF_8)
        val colon = body.indexOf(':').takeIf { it > 0 } ?: return null
        val stamp = body.substring(0, colon).toLong()

        // v2 keys the hour to the reply's own timestamp and accepts the previous hour at the
        // boundary; v1 keys it to the local clock.
        val hours = if (v >= 2) {
            listOf(stamp / HOUR_MS, stamp / HOUR_MS - 1)
        } else {
            listOf(System.currentTimeMillis() / HOUR_MS)
        }

        val envelopeKey = hours.firstNotNullOfOrNull { hour ->
            val salt = md5Hex("$ENVELOPE_SECRET:$hour").take(16)
            val candidate = sha256Hex("$key|$context|$ENVELOPE_SECRET|$salt")
            val signed = if (v >= 2) "$d:$v" else d
            candidate.takeIf { hmacHex(signed, it) == s }
        } ?: return null

        openInner(body, colon, envelopeKey)
    }.getOrNull()

    /** Undoes the two XOR passes and the reversal, then the CryptoJS AES layer. */
    private fun openInner(body: String, colon: Int, envelopeKey: String): String? {
        val stamp = body.substring(0, colon)
        val payload = body.substring(colon + 1)
        val innerKey = sha256Hex("$INNER_SECRET:$stamp:$envelopeKey").take(INNER_XOR_LENGTH)
        val reversed = xor(payload, innerKey).reversed()
        val openSsl = String(
            Base64.decode(xor(reversed, envelopeKey.take(OUTER_XOR_LENGTH)), Base64.DEFAULT),
            Charsets.UTF_8,
        )
        return cryptoJsDecrypt(openSsl, envelopeKey)
    }

    /**
     * CryptoJS `AES.decrypt(ciphertext, passphrase)`: OpenSSL "Salted__" framing, key and IV
     * from EVP_BytesToKey with MD5, AES-256-CBC with PKCS#7 padding.
     */
    private fun cryptoJsDecrypt(encoded: String, passphrase: String): String? {
        val raw = Base64.decode(encoded, Base64.DEFAULT)
        if (raw.size < 16 || String(raw, 0, 8, Charsets.US_ASCII) != "Salted__") return null
        val salt = raw.copyOfRange(8, 16)
        val password = passphrase.toByteArray(Charsets.UTF_8)

        var derived = ByteArray(0)
        var block = ByteArray(0)
        val md5 = MessageDigest.getInstance("MD5")
        while (derived.size < 48) {
            md5.reset()
            md5.update(block)
            md5.update(password)
            md5.update(salt)
            block = md5.digest()
            derived += block
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(derived.copyOfRange(0, 32), "AES"),
                IvParameterSpec(derived.copyOfRange(32, 48)),
            )
        }
        return String(cipher.doFinal(raw, 16, raw.size - 16), Charsets.UTF_8)
    }

    private fun signedId(payload: String, key: String): String = base64Url(payload) + "." + hmacHex(payload, key).take(ID_SIGNATURE_LENGTH)

    private fun now36(): String = System.currentTimeMillis().toString(36)

    private fun base64Url(value: String): String = Base64.encodeToString(
        value.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
    )

    /** Character-wise XOR with a repeating key, as the site's JavaScript does on UTF-16 units. */
    private fun xor(value: String, key: String): String {
        if (key.isEmpty()) return value
        val out = CharArray(value.length)
        for (i in value.indices) out[i] = (value[i].code xor key[i % key.length].code).toChar()
        return String(out)
    }

    private fun hmacHex(message: String, key: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    private fun md5Hex(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
