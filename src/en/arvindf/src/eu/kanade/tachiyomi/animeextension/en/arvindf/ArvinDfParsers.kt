package eu.kanade.tachiyomi.animeextension.en.arvindf

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.net.URLDecoder

internal data class Card(val url: String, val title: String, val thumbnail: String?)

internal data class ListingPage(val cards: List<Card>, val hasNext: Boolean)

internal data class EpisodeRow(val path: String, val number: Float?, val title: String, val date: String)

internal data class Mirror(val label: String, val url: String)

/** Where the two catalogues live, and the requests built against them. */
internal object Routes {
    const val DRAMAFREN = "https://dramafren.org"
    const val DRAMAWAVE = "https://dramawave.dramafren.org"

    /** Where DramaWave's own pages say to ask for a source. Both answered the same on 2026-11-02. */
    val WATCH_SOURCE_HOSTS = listOf(
        "https://cdn-dramawavev2.dramafren.org/index.php",
        "https://cdn-dramawave.dramafren.org/index.php",
    )

    /**
     * The site's advanced search. Array parameters must be written `type[0]`: the plain `type[]` form
     * answers a redirect from page 2 onwards, and the site's own pager rewrites them the same way.
     */
    fun advancedSearch(page: Int, order: String, status: String?): HttpUrl {
        val builder = DRAMAFREN.toHttpUrl().newBuilder()
            .addPathSegments(if (page > 1) "advanced-search/page/$page/" else "advanced-search/")
            .addQueryParameter("order", order)
            .addQueryParameter("type[0]", "series")
        status?.let { builder.addQueryParameter("status[0]", it) }
        return builder.build()
    }

    fun keywordSearch(page: Int, query: String): HttpUrl = DRAMAFREN.toHttpUrl().newBuilder()
        .addPathSegments(if (page > 1) "page/$page/" else "")
        .addQueryParameter("s", query)
        .build()

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

/** Reads the WordPress pages of dramafren.org. */
internal object DramaFrenParser {
    private val EPISODE_NUMBER = Regex("""\d+(?:\.\d+)?""")
    private val IFRAME_SRC = Regex("""(?i)\bsrc\s*=\s*["']([^"']+)["']""")

    /** Cards of a listing or search page. A card's link is a series page or a single watch post. */
    fun listing(document: Document): ListingPage {
        val cards = document.select("article.box a.tip[href]").mapNotNull { anchor ->
            val path = pathOf(anchor.attr("href")) ?: return@mapNotNull null
            if (!path.startsWith("/series/") && !path.startsWith("/watch/")) return@mapNotNull null
            val title = anchor.selectFirst("h2.entry-title")?.text()?.trim().orEmpty()
                .ifEmpty { anchor.attr("title").trim() }
                .ifEmpty { return@mapNotNull null }
            Card(path, title, anchor.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() })
        }.distinctBy { it.url }
        return ListingPage(cards, document.selectFirst("a.next.page-numbers") != null)
    }

    /** A watch post belongs to a series, linked second in its breadcrumb. */
    fun seriesPath(document: Document): String? = document.select("a.breadcrumb-link[href]")
        .mapNotNull { pathOf(it.attr("href")) }
        .firstOrNull { it.startsWith("/series/") && it.trimEnd('/').length > "/series".length }

    fun title(document: Document): String? = document.selectFirst("h1.entry-title")?.text()?.trim()?.takeIf { it.isNotEmpty() }

    fun poster(document: Document): String? = document.selectFirst(".limage img[src], img.entry-image[src]")
        ?.attr("src")?.substringBefore('?')?.takeIf { it.isNotBlank() }

    /** "Completed", "Ongoing" or "Dropped", from the `Status:` row of the info list. */
    fun status(document: Document): String? = document.select("ul.data li").firstNotNullOfOrNull { row ->
        row.takeIf { it.selectFirst("b")?.text()?.trim()?.startsWith("Status", ignoreCase = true) == true }
            ?.text()?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun episodes(document: Document): List<EpisodeRow> = document.select("div.epsdlist li a[href]").mapNotNull { anchor ->
        val path = pathOf(anchor.attr("href")) ?: return@mapNotNull null
        val label = anchor.selectFirst(".epl-num")?.text().orEmpty()
        EpisodeRow(
            path = path,
            number = EPISODE_NUMBER.find(label)?.value?.toFloatOrNull(),
            title = anchor.selectFirst(".epl-title")?.text()?.trim().orEmpty(),
            date = anchor.selectFirst(".epl-date")?.text()?.trim().orEmpty(),
        )
    }.distinctBy { it.path }

    /**
     * The player choices of a watch page. Each `data-em` is the iframe HTML in Base64; decoding it only
     * unwraps the embed address, it is not the video.
     */
    fun mirrors(document: Document): List<Mirror> = document.select("a[data-em]").mapNotNull { anchor ->
        val html = runCatching { String(Base64Lite.decode(anchor.attr("data-em")), Charsets.UTF_8) }.getOrNull()
            ?: return@mapNotNull null
        val src = IFRAME_SRC.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.startsWith("http") }
            ?: return@mapNotNull null
        Mirror(anchor.text().trim(), src)
    }

    fun isAbyss(url: String): Boolean {
        val host = runCatching { url.toHttpUrl().host.lowercase() }.getOrNull() ?: return false
        return host == "abyssplayer.com" || host.endsWith(".abyssplayer.com") || host == "abyss.to" || host.endsWith(".abyss.to")
    }

    /** `https://dramafren.org/series/x/` to `/series/x/`; anything off-site is not one of ours. */
    fun pathOf(href: String): String? = when {
        href.startsWith(Routes.DRAMAFREN) -> href.removePrefix(Routes.DRAMAFREN).ifEmpty { "/" }
        href.startsWith("/") -> href
        else -> null
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
