package eu.kanade.tachiyomi.animeextension.en.arvindf

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.net.URLDecoder

internal data class Card(val url: String, val title: String, val thumbnail: String?)

internal data class ListingPage(val cards: List<Card>, val hasNext: Boolean)

/** Where DramaWave lives, and the requests built against it. */
internal object Routes {
    const val DRAMAWAVE = "https://dramawave.dramafren.org"

    /** Where DramaWave's own pages say to ask for a source. Both answered the same on 2026-11-02. */
    val WATCH_SOURCE_HOSTS = listOf(
        "https://cdn-dramawavev2.dramafren.org/index.php",
        "https://cdn-dramawave.dramafren.org/index.php",
    )

    /** DramaWave's Recently Watched feed pages with `hp`; a search is a single page of up to 20. */
    fun dramaWaveList(page: Int, lang: String, query: String?): HttpUrl {
        val builder = "$DRAMAWAVE/index.php".toHttpUrl().newBuilder().addQueryParameter("lang", lang)
        if (query != null) {
            builder.addQueryParameter("q", query)
        } else if (page > 1) {
            builder.addQueryParameter("hp", page.toString())
        }
        return builder.build()
    }

    fun dramaWaveDetail(id: String, lang: String): HttpUrl = "$DRAMAWAVE/index.php".toHttpUrl().newBuilder()
        .addQueryParameter("page", "detail")
        .addQueryParameter("id", id)
        .addQueryParameter("lang", lang)
        .build()

    fun dramaWaveWatch(id: String, episode: String, lang: String): HttpUrl = "$DRAMAWAVE/index.php".toHttpUrl().newBuilder()
        .addQueryParameter("page", "watch")
        .addQueryParameter("id", id)
        .addQueryParameter("ep", episode)
        .addQueryParameter("lang", lang)
        .build()

    fun watchSource(endpoint: String, id: String, episode: String, server: Int, lang: String): HttpUrl = endpoint.toHttpUrl().newBuilder()
        .addQueryParameter("action", "watch_source")
        .addQueryParameter("id", id)
        .addQueryParameter("ep", episode)
        .addQueryParameter("server", server.toString())
        .addQueryParameter("lang", lang)
        .build()

    /**
     * A subtitle's `?proxy_sub=<base64>` link goes through DramaWave's own host, which sits behind a
     * Cloudflare check that a player cannot answer. The base64 is just the upstream `.srt` address,
     * which is open, so that is what gets handed to the player when it decodes.
     */
    fun subtitleUrl(option: String): String? {
        val raw = option.substringAfter("proxy_sub=", "").substringBefore('&').trim()
        if (raw.isEmpty()) return null
        // Only a percent-escaped value needs URL decoding, and a bare '+' is a Base64 digit there.
        val encoded = if ('%' in raw) URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") else raw
        val decoded = runCatching { String(Base64Lite.decode(encoded), Charsets.UTF_8) }.getOrNull()
        if (decoded != null && (decoded.startsWith("https://") || decoded.startsWith("http://"))) return decoded
        return if (option.startsWith("?")) "$DRAMAWAVE/index.php$option" else null
    }
}

/** Reads the PHP pages of the DramaWave subsite. */
internal object DramaWaveParser {
    private val ID = Regex("""[?&]id=([A-Za-z0-9_-]+)""")
    private val EPISODE = Regex("""[?&]ep=(\d+)""")

    fun listing(document: Document): ListingPage {
        val cards = document.select("a[href*=page=detail]").mapNotNull { anchor ->
            val id = ID.find(anchor.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val title = anchor.selectFirst("h3")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Card(id, title, anchor.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() })
        }.distinctBy { it.url }
        // The pager shows "Next" as a link while there is a next page and as plain text on the last one.
        val hasNext = document.select("a[href*=hp=]").any { it.text().contains("Next", ignoreCase = true) }
        return ListingPage(cards, hasNext)
    }

    fun title(document: Document): String? = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }

    fun poster(document: Document): String? = document.selectFirst("meta[property=og:image]")?.attr("content")
        ?.takeIf { it.isNotBlank() }

    fun description(document: Document): String? = document.selectFirst("p.line-clamp-3")?.text()?.trim()?.takeIf { it.isNotEmpty() }

    /** Distinct episode numbers; the "Start Watching" button repeats the first one. */
    fun episodes(document: Document): List<Int> = document.select("a[href*=page=watch]")
        .mapNotNull { EPISODE.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
        .distinct()
        .sorted()
}
