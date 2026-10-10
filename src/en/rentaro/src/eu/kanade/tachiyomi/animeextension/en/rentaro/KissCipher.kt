package eu.kanade.tachiyomi.animeextension.en.rentaro

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Request keys and subtitle decryption for the KissKH (Kiss) backend.
 *
 * Lifted from kisskh.do on 10 October 2026. The episode and subtitle routes refuse a request
 * (403) without a `kkey`, which the site computes in `common.js` as `_0x54b991`. That is
 * AES-128-CBC hand-rolled over an inline key schedule: its first round key is the cipher key
 * and the block it XORs into the first block is the IV, so standard AES reproduces it exactly
 * (checked against the site's own function on 14 inputs). The player passes the page's own
 * constants rather than browser details, so nothing here depends on the device.
 *
 * Subtitles ending `.txt` or `.txt1` carry each cue as base64 AES-128-CBC, decrypted by the
 * site's `a1`/`a2`; any other non-SRT extension goes through `a3`.
 */
internal object KissCipher {

    private const val SALT = "mg3c3b04ba"
    private const val APP_VERSION = "2.8.10"
    private const val PLATFORM_VERSION = "4830201"
    private const val APP_NAME = "kisskh"

    /** The guid the player signs `/DramaList/Episode` with. */
    private const val VIDEO_GUID = "62f176f3bb1b5b8e70e39932ad34a0c7"

    /** The guid the player signs `/Sub` with. */
    private const val SUBTITLE_GUID = "VgV52sWhwvBSf8BsM3BRY9weWiiCbtGp"

    private val KEY = hex("4f6bdaa39e2f8cb07f5e722d9edef314")
    private val IV = hex("01504af356e619cf2e42bba68c3f70f9")

    /** Key and IV per subtitle extension, from the site's `a1`, `a2` and `a3`. */
    private val SUBTITLE_KEYS = mapOf(
        "txt" to ("8056483646328763" to "6852612370185273"),
        "txt1" to ("AmSmZVcH93UQUezi" to "ReBKWW8cqdjPEnF6"),
    )
    private val SUBTITLE_FALLBACK_KEY = "sWODXX04QRTkHdlZ" to "8pwhapJeC4hrS9hO"

    /** Extensions served as plain text, which the site's player shows untouched. */
    private val PLAIN_SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

    fun videoKey(episodeId: Int): String = key(episodeId, VIDEO_GUID)

    fun subtitleKey(episodeId: Int): String = key(episodeId, SUBTITLE_GUID)

    /** The file extension the site reads a subtitle's format from, ignoring query and fragment. */
    fun subtitleExtension(url: String): String = url.substringBefore('?').substringBefore('#')
        .substringAfterLast('/').substringAfterLast('.', "").lowercase()

    fun isEncryptedSubtitle(url: String): Boolean = subtitleExtension(url) !in PLAIN_SUBTITLE_EXTENSIONS

    /**
     * Decrypts one cue's text, or returns it unchanged when it does not decrypt: the site wraps
     * each attempt in a try and shows the original on failure, so a plain line in an encrypted
     * file still reads.
     */
    fun decryptCueLine(line: String, extension: String): String {
        val (key, iv) = SUBTITLE_KEYS[extension] ?: SUBTITLE_FALLBACK_KEY
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
            }
            String(cipher.doFinal(Base64.decode(line.trim(), Base64.DEFAULT)), Charsets.UTF_8)
        }.getOrDefault(line)
    }

    /**
     * `_0x54b991(episodeId, null, appVer, guid, platformVer, appName × 6)`: the fields joined
     * with `|` (null as empty), the hash of that string spliced in second, then encrypted and
     * hex-encoded in upper case.
     */
    private fun key(episodeId: Int, guid: String): String {
        val parts = mutableListOf(
            "",
            episodeId.toString(),
            "",
            SALT,
            APP_VERSION,
            guid,
            PLATFORM_VERSION,
            APP_NAME,
            APP_NAME,
            APP_NAME,
            APP_NAME,
            APP_NAME,
            APP_NAME,
            "00",
            "",
        )
        parts.add(1, jsHash(parts.joinToString("|")).toString())

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES"), IvParameterSpec(IV))
        }
        return cipher.doFinal(parts.joinToString("|").toByteArray(Charsets.ISO_8859_1))
            .joinToString("") { "%02X".format(it) }
    }

    /**
     * The site's string hash, `h = (h << 5) - h + c`. JavaScript wraps only the shift to 32
     * bits and keeps the rest as a double, so the result is not Java's `hashCode` and can run
     * past the Int range; a Long holds it exactly for any key-sized input.
     */
    private fun jsHash(value: String): Long {
        var hash = 0L
        for (c in value) hash = (hash.toInt() shl 5).toLong() - hash + c.code
        return hash
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { i ->
        value.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
