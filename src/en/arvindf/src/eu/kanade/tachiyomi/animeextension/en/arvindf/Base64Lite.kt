package eu.kanade.tachiyomi.animeextension.en.arvindf

import java.io.ByteArrayOutputStream

/**
 * A small Base64 decoder with no platform dependency. `java.util.Base64` needs API 26 and this app runs
 * on 21, `android.util.Base64` does not exist in a plain JVM unit test, and the standard alphabet and
 * the URL-safe one both turn up on this site. Padding is optional and whitespace is ignored.
 */
internal object Base64Lite {
    private val TABLE = IntArray(128) { -1 }.also { table ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { index, char ->
            table[char.code] = index
        }
        table['-'.code] = 62
        table['_'.code] = 63
    }

    fun decode(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (char in text) {
            if (char == '=' || char.isWhitespace()) continue
            val value = if (char.code < TABLE.size) TABLE[char.code] else -1
            require(value >= 0) { "Not Base64 data (character code ${char.code})" }
            buffer = ((buffer shl 6) or value) and 0xFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
