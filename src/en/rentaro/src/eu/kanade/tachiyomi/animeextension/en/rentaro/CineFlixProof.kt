package eu.kanade.tachiyomi.animeextension.en.rentaro

import java.security.MessageDigest

/**
 * Proof of work for the CineFlix playback API.
 *
 * `/api/playback/challenge` answers with a random string and a difficulty, and
 * `/api/playback/stream` only releases the stream for a counter whose digest
 * clears that difficulty:
 *
 *     SHA-256("<challenge>:<counter>")  ->  >= difficulty leading zero bits
 *
 * The site ships an obfuscated solver, but the scheme was recovered from a
 * captured session instead: the challenge, its difficulty and the winning
 * counter were all present, which is enough to confirm a candidate formulation
 * outright. The separator is a literal colon, and the accepted counter is the
 * lowest one that clears the bar, so a plain upward search reproduces it.
 *
 * Difficulty 12 is what the API asks for in practice and solves in under a
 * millisecond; the work grows exponentially, hence the ceiling.
 */
internal object CineFlixProof {

    /** Refuses a difficulty that could spin for minutes rather than millis. */
    private const val MAX_DIFFICULTY = 28

    /** Bounds the search even if the digest never clears the bar. */
    private const val MAX_COUNTER = 1 shl 26

    /**
     * Returns the lowest counter whose digest satisfies [difficulty], or null
     * if the search is refused or exhausted.
     */
    fun solve(challenge: String, difficulty: Int): Int? {
        if (challenge.isBlank()) return null
        if (difficulty <= 0 || difficulty > MAX_DIFFICULTY) return null

        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = "$challenge:".toByteArray(Charsets.UTF_8)

        for (counter in 0 until MAX_COUNTER) {
            digest.reset()
            digest.update(prefix)
            digest.update(counter.toString().toByteArray(Charsets.UTF_8))
            if (leadingZeroBits(digest.digest()) >= difficulty) return counter
        }
        return null
    }

    /** Counts leading zero bits, stopping at the first set bit. */
    private fun leadingZeroBits(hash: ByteArray): Int {
        var bits = 0
        for (byte in hash) {
            val value = byte.toInt() and 0xFF
            if (value == 0) {
                bits += 8
                continue
            }
            var mask = 0x80
            while (mask != 0 && value and mask == 0) {
                bits++
                mask = mask shr 1
            }
            break
        }
        return bits
    }
}
