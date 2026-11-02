package eu.kanade.tachiyomi.animeextension.en.arvindf

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

internal data class PortalEpisode(val href: String, val number: Int, val label: String)

internal data class PortalDetail(
    val title: String?,
    val poster: String?,
    val description: String?,
    val genre: String?,
)

/** Reads the PHP pages the HTML portals share. Every portal serves the same Tailwind template. */
internal object PortalHtml {
    private val ID = Regex("""[?&]id=([^&]+)""")
    private val NUMBER = Regex("""\d+""")
    private val EPISODE_PARAM = Regex("""[?&](?:ep|no)=(\d+)""")
    private val CHAPTERS = Regex("""chapterIds\s*=\s*\[([^\]]*)]""")
    private val QUOTED = Regex("""['"](\d+)['"]""")
    private val ENCRYPTED = Regex("""encryptedSrc\s*=\s*"([A-Za-z0-9+/=_-]+)"""")
    private val VIDEO_SRC = Regex("""videoSrc\s*=\s*atob\(\s*"([A-Za-z0-9+/=_-]+)"\s*\)""")
    private val QUALITIES = Regex("""availableQualities\s*=\s*(\[[^;]*]);""")
    private val NOT_A_GENRE = Regex("""(?i)(\d+\s*(eps?|episodes?)\b|^server\b|^\d{4}-\d{2}-\d{2}$)""")

    /** `index.php?page=...` as the site wrote it; absolute and rooted forms are cut down to that. */
    fun relative(href: String): String? {
        val start = href.indexOf("index.php")
        return if (start >= 0) href.substring(start) else null
    }

    /** Cards of a home or search page. A card with no id (ReelShort sent one) is not a title. */
    fun cards(document: Document): List<Card> = document.select("a[href*=page=detail]").mapNotNull { anchor ->
        val ref = relative(anchor.attr("href")) ?: return@mapNotNull null
        if (ID.find(ref)?.groupValues?.get(1).isNullOrBlank()) return@mapNotNull null
        val image = anchor.selectFirst("img")
        val title = anchor.selectFirst("h3")?.text()?.trim().orEmpty()
            .ifEmpty { image?.attr("alt")?.trim().orEmpty() }
            .ifEmpty { return@mapNotNull null }
        Card(ref, title, image?.attr("src")?.takeIf { it.isNotBlank() })
    }.distinctBy { it.url }

    /** The pager shows "Next »" as a link while there is a next page. */
    fun hasNext(document: Document): Boolean = document.select("a[href*=index.php]").any { it.text().contains("Next", ignoreCase = true) }

    fun detail(document: Document): PortalDetail {
        val genres = document.select("span.bg-slate-700").map { it.text().trim() }
            .filter { it.isNotEmpty() && !NOT_A_GENRE.containsMatchIn(it) }
        return PortalDetail(
            title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("img.poster-shadow, img.rounded-lg")?.attr("src")?.takeIf { it.isNotBlank() },
            description = document.selectFirst("p.leading-relaxed")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("Description unavailable.", ignoreCase = true) },
            genre = genres.joinToString(", ").takeIf { it.isNotEmpty() },
        )
    }

    /** Watch links of a detail page, once each. The "Start Watching" button repeats the first one. */
    fun episodes(document: Document): List<PortalEpisode> {
        val anchors = document.select("a[href*=page=watch]")
        val numbered = anchors.filterNot { it.text().contains("Start", ignoreCase = true) }
        return parseEpisodes(numbered.ifEmpty { anchors })
    }

    private fun parseEpisodes(anchors: List<org.jsoup.nodes.Element>): List<PortalEpisode> = anchors.mapNotNull { anchor ->
        val href = relative(anchor.attr("href")) ?: return@mapNotNull null
        val label = anchor.text().trim()
        val number = NUMBER.find(label)?.value?.toIntOrNull()
            ?: EPISODE_PARAM.find(href)?.groupValues?.get(1)?.toIntOrNull()
            ?: return@mapNotNull null
        PortalEpisode(href, number, label)
    }.distinctBy { it.href }

    /** GoodShort numbers its episodes from zero and resolves each through a chapter id kept on the watch page. */
    fun chapterIds(html: String): List<String> = CHAPTERS.find(html)?.groupValues?.get(1)
        ?.let { list -> QUOTED.findAll(list).map { it.groupValues[1] }.toList() }
        .orEmpty()

    /**
     * The source a watch page embeds itself, Base64-wrapped in its inline script as `encryptedSrc`,
     * `videoSrc = atob("...")`, or (ReelShort) a list of qualities. The wrapping hides nothing: it is
     * the address of the playlist or file.
     */
    fun embedded(html: String): List<Stream> {
        val found = ArrayList<Stream>()
        QUALITIES.find(html)?.groupValues?.get(1)?.let { Js.parse(it) }?.let { list ->
            list.asArray().forEach { entry ->
                val item = entry.asObject() ?: return@forEach
                val url = item["hash64"].text()?.let(::unwrap) ?: return@forEach
                val quality = item["quality"].text()?.takeUnless { it.startsWith("0") } ?: "Video"
                found += Stream(quality, url)
            }
        }
        (ENCRYPTED.find(html) ?: VIDEO_SRC.find(html))?.groupValues?.get(1)?.let(::unwrap)?.let { found += Stream("Video", it) }
        return found.distinctBy { it.url }
    }

    /** A page whose Base64 source is the empty string, which is how a portal says it has no video. */
    fun hasEmptySource(html: String): Boolean = EMPTY_SOURCE.containsMatchIn(html)

    private val EMPTY_SOURCE = Regex("""(?:videoSrc\s*=\s*atob\(\s*""\s*\)|encryptedSrc\s*=\s*"")""")

    private fun unwrap(encoded: String): String? = runCatching { String(Base64Lite.decode(encoded), Charsets.UTF_8) }
        .getOrNull()?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    /** DramaPops puts a plain `<source>` and one `<track>` per language in its watch page. */
    fun dramapops(document: Document, base: String): Resolved? {
        val url = document.selectFirst("video source[src]")?.attr("src")?.takeIf { it.startsWith("http") } ?: return null
        val subs = document.select("track[src]").mapNotNull { track ->
            // The page proxies each subtitle through its own host; the address it wraps is open.
            val upstream = "$base${track.attr("src")}".toHttpUrlOrNull()?.queryParameter("url")?.takeIf { it.startsWith("http") }
                ?: return@mapNotNull null
            Sub(track.attr("label").ifBlank { "Subtitles" }, upstream)
        }.sortedByDescending { it.label.startsWith("English") }
        return Resolved(listOf(Stream("Video", url)), subs)
    }
}

/** Reads the JSON the three same-origin portals return. */
internal object PortalJson {
    /** MicroDrama and ShortWave `list` / `search`: `{dramas: [{id, title, cover, episodes}]}`. */
    fun dramas(root: kotlinx.serialization.json.JsonObject): List<Card> = root["dramas"].asArray().mapNotNull { entry ->
        val o = entry.asObject() ?: return@mapNotNull null
        val id = o["id"].text() ?: return@mapNotNull null
        Card(id, o["title"].text() ?: return@mapNotNull null, o["cover"].text())
    }.distinctBy { it.url }
}
