package eu.kanade.tachiyomi.animeextension.en.rentaro

/**
 * What ZxcStream's player (zxcstream.cc, Flux) keeps in its bundle: the passphrase its links are
 * sealed under and the wire names of its request fields.
 *
 * Lifted from the player's chunks on 18 October 2026 and re-read on 31 October 2026, when all ten
 * field names below were replaced. Each 18 October name was the current one with one extra hex digit
 * in it, and the backend refused them with 400; whether the site changed them or they were copied
 * wrong is not known. The chunk file names are build hashes, so read them from the player page's HTML
 * (`/player/movie/<tmdb id>`) and search for the patterns below.
 */
internal object ZxcCipher {

    /**
     * The phrase the links are sealed under: the string in `AES.decrypt(link, "<this>")` in the get-source
     * hook (`\.AES\.decrypt\([A-Za-z_$][\w$.]*,"([0-9a-f]{64})"\)`). It ships in the page for every
     * visitor. Links are CryptoJS strings, so [ScreenscapeCipher.cryptoJsDecrypt] opens them.
     */
    private const val LINK_PHRASE = "7f4c9e2a81d63b05c4f7a9e8126d3b50e1a8c7f23d9465ab0c6e9f1d4a7b832c"

    // The field map (`\{id:"[0-9a-f]{20,32}",fToken:.*?latestDate:"[0-9a-f]+"\}`) the player builds
    // every request from; the names are meaningless hex, so a rotation shows up as 400s. [TICKET] is the
    // map's `token`, the field that carries the per-server value `/backend/fucku` hands out.
    const val ID = "a7f39c821d604e5b91e547b"
    const val TIMESTAMP = "61d9a5274c8e3b2f38c291e6"
    const val TICKET = "c492f7a183d01e436c538a716d"
    const val TITLE = "5e28c9147a06d1e29f3674b392a1"
    const val YEAR = "b731e6c94f8675f8341c306e"
    const val SEASON = "d8427b5c38a2f957c3613e85b"
    const val EPISODE = "91c6ea753d1f785c92346b713d"
    const val PATH = "6b491e258d392e7561a9384c"
    const val MEDIA_TYPE = "c81b306d28147a35632e816b"
    const val DATE = "e164931a39e5814b3027"

    /** The stream URL a sources reply seals in `link`, or null when it does not open to one. */
    fun decryptLink(link: String): String? = runCatching { ScreenscapeCipher.cryptoJsDecrypt(link, LINK_PHRASE) }
        .getOrNull()
        ?.takeIf { it.startsWith("http") }
}
