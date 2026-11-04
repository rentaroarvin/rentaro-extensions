package eu.kanade.tachiyomi.animeextension.en.arvindf

import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

internal data class PortalEpisode(val href: String, val number: Int, val label: String)

internal data class PortalDetail(
    val title: String?,
    val poster: String?,
    val description: String?,
    val genre: String?,
)

/** A MoboReels episode: its number, and whether the portal flags it as locked behind coins. */
internal data class MoboEpisode(val number: Int, val locked: Boolean)

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
    fun dramas(root: JsonObject): List<Card> = root["dramas"].asArray().mapNotNull { entry ->
        val o = entry.asObject() ?: return@mapNotNull null
        val id = o["id"].text() ?: return@mapNotNull null
        Card(id, o["title"].text() ?: return@mapNotNull null, o["cover"].text())
    }.distinctBy { it.url }

    // ------------------------------- ShotShort -------------------------------

    /** The `data` of ShotShort's `{code: 0, msg, data}` envelope, or null if its code says the call failed. */
    fun shotshortData(root: JsonObject): JsonObject? {
        val code = root["code"].text()
        if (code != null && code != "0") return null
        return root["data"].asObject()
    }

    /** `/category` (`data.pageData`) and `/search` (`data.list`) items: `{bookId, bookName, coverUrl}`. */
    fun shotshortBooks(root: JsonObject): List<Card> {
        val data = shotshortData(root) ?: return emptyList()
        return data["pageData"].asArray().ifEmpty { data["list"].asArray() }.mapNotNull { entry ->
            val o = entry.asObject() ?: return@mapNotNull null
            val id = o["bookId"].text() ?: return@mapNotNull null
            Card(id, o["bookName"].text() ?: return@mapNotNull null, o["coverUrl"].text())
        }.distinctBy { it.url }
    }

    fun shotshortDetail(root: JsonObject): PortalDetail {
        val data = shotshortData(root)
        val genres = listOf("video1stCategory", "video2ndCategory", "video3rdCategory")
            .flatMap { key -> data?.get(key).asArray().mapNotNull { it.text() } }
            .distinct()
        return PortalDetail(
            title = data?.get("bookName").text(),
            poster = data?.get("coverUrl").text(),
            description = data?.get("recommendIntro").text(),
            genre = genres.joinToString(", ").takeIf { it.isNotEmpty() },
        )
    }

    /** `/book/{id}/episodes`: `data.episodes[{chapterId, chapterOrder}]` as (order, chapter id). Playback uses the id. */
    fun shotshortChapters(root: JsonObject): List<Pair<Int, String>> = shotshortData(root)?.get("episodes").asArray().mapNotNull { entry ->
        val o = entry.asObject() ?: return@mapNotNull null
        val id = o["chapterId"].text() ?: return@mapNotNull null
        val order = o["chapterOrder"].text()?.toIntOrNull() ?: return@mapNotNull null
        order to id
    }.distinctBy { it.second }

    // ------------------------------- TvSeries -------------------------------

    /**
     * The `data` of TvSeries' `{code: 0, message, data}` envelope. Its own script treats 0, 1 and 200 as
     * success and anything else, or an `error` field, as a failure.
     */
    private fun tvData(root: JsonObject): JsonObject? {
        if (root["error"].text() != null) return null
        val code = root["code"].text()
        if (code != null && code !in setOf("0", "1", "200")) return null
        return root["data"].asObject()
    }

    /** A title the portal can open: a numeric, non-zero `subjectId` and a `detailPath`, as its own script requires. */
    private fun tvCard(o: JsonObject): Card? {
        val id = o["subjectId"].text()?.takeIf { value -> value.all { it.isDigit() } && value.trimStart('0').isNotEmpty() } ?: return null
        // A slash would make the stored `<id>/<detailPath>/<season>/<episode>` reference ambiguous.
        val path = o["detailPath"].text()?.takeIf { '/' !in it } ?: return null
        val title = o["title"].text() ?: return null
        // The title is opened with both values, so both are kept in the reference.
        return Card("$id/$path", title, o["cover_url"].text())
    }

    /** Home and movie: `data.hero_banners` and every section's `items`. One page, repeats removed. */
    fun tvSections(root: JsonObject): List<Card> {
        val data = tvData(root) ?: return emptyList()
        val items = data["hero_banners"].asArray() + data["content_sections"].asArray().flatMap { it.asObject()?.get("items").asArray() }
        return items.mapNotNull { it.asObject()?.let(::tvCard) }.distinctBy { it.url }
    }

    /**
     * Trending, movie trending and search: `data.items` with a `pager` (or `pagination`) holding `hasMore`.
     * Search answers the same shape.
     */
    fun tvItems(root: JsonObject): ListingPage {
        val data = tvData(root) ?: return ListingPage(emptyList(), false)
        val cards = data["items"].asArray().mapNotNull { it.asObject()?.let(::tvCard) }.distinctBy { it.url }
        val pager = (data["pager"].asObject() ?: data["pagination"].asObject())
        val more = pager?.get("hasMore")
        val hasMore = more.flag() == true || more.text() == "true"
        return ListingPage(cards, hasMore)
    }

    fun tvDetail(root: JsonObject): PortalDetail {
        val data = tvData(root)
        val genres = data?.get("genre").text()?.split(',', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty().distinct()
        val year = data?.get("releaseDate").text()?.take(4)?.takeIf { y -> y.length == 4 && y.all { it.isDigit() } }
        val country = data?.get("country").text()
        val intro = data?.get("description").text()
        // The site's description is often short or missing, so the year and country go in front of it.
        val facts = listOfNotNull(year, country).joinToString(" · ").takeIf { it.isNotEmpty() }
        return PortalDetail(
            title = data?.get("title").text(),
            poster = data?.get("cover_url").text(),
            description = listOfNotNull(facts, intro).joinToString("\n\n").takeIf { it.isNotEmpty() },
            genre = genres.joinToString(", ").takeIf { it.isNotEmpty() },
        )
    }

    /** One playable episode of a TvSeries title. A movie is season 0, episode 0, as its script represents it. */
    data class TvEpisode(val season: Int, val episode: Int)

    /** `details`: a movie is one entry; a series is every episode of every `seasons_list` season. */
    fun tvEpisodes(root: JsonObject): List<TvEpisode> {
        val data = tvData(root) ?: return emptyList()
        if (data["subjectType"].text() == "1") return listOf(TvEpisode(0, 0))
        return data["seasons_list"].asArray().flatMap { season ->
            val o = season.asObject() ?: return@flatMap emptyList()
            val number = o["season"].text()?.toIntOrNull() ?: return@flatMap emptyList()
            o["episodes"].asArray().mapNotNull { ep -> ep.text()?.toIntOrNull()?.let { TvEpisode(number, it) } }
        }.distinct()
    }

    // ------------------------------- MoboReels -------------------------------

    /** The `data` of MoboReels' `{code: 200, data}` envelope, or null if its code says the call failed. */
    private fun moboreelsData(root: JsonObject): JsonObject? {
        val code = root["code"].text()
        if (code != null && code != "200") return null
        return root["data"].asObject()
    }

    /** The channel the portal marks as its default (Home), which is what its own front page shows. */
    fun moboreelsDefaultChannel(root: JsonObject): String? {
        val channels = moboreelsData(root)?.get("channels").asArray().mapNotNull { it.asObject() }
        return (channels.firstOrNull { it["isDefault"].flag() == true } ?: channels.firstOrNull())?.get("channelId").text()
    }

    private fun moboreelsCard(o: JsonObject): Card? {
        val id = o["seriesId"].text() ?: return null
        return Card(id, o["seriesName"].text() ?: return null, o["coverUrl"].text())
    }

    /** A channel's `list`: `data.sections[].series[]`, flattened. It is one page, not a paged catalogue. */
    fun moboreelsSections(root: JsonObject): List<Card> = moboreelsData(root)?.get("sections").asArray().flatMap { section ->
        section.asObject()?.get("series").asArray().mapNotNull { it.asObject()?.let(::moboreelsCard) }
    }.distinctBy { it.url }

    /** `search`: `data.items` with `pageNo`, `pageSize` and `total`, from which the last page is worked out. */
    fun moboreelsSearch(root: JsonObject): ListingPage {
        val data = moboreelsData(root) ?: return ListingPage(emptyList(), false)
        val cards = data["items"].asArray().mapNotNull { it.asObject()?.let(::moboreelsCard) }.distinctBy { it.url }
        val pageNo = data["pageNo"].text()?.toIntOrNull()
        val pageSize = data["pageSize"].text()?.toIntOrNull()
        val total = data["total"].text()?.toIntOrNull()
        val hasNext = pageNo != null && pageSize != null && total != null && pageNo * pageSize < total
        return ListingPage(cards, hasNext)
    }

    fun moboreelsDetail(root: JsonObject): PortalDetail {
        val data = moboreelsData(root)
        val genres = data?.get("genres").asArray().mapNotNull { it.text() }
        return PortalDetail(
            title = data?.get("seriesName").text(),
            poster = data?.get("coverUrl").text(),
            description = data?.get("description").text(),
            genre = genres.joinToString(", ").takeIf { it.isNotEmpty() },
        )
    }

    /** `episodes`: `data.items[{episNum, isLock, isRedLock}]`. A flagged episode is reported, never unlocked. */
    fun moboreelsEpisodes(root: JsonObject): List<MoboEpisode> = moboreelsData(root)?.get("items").asArray().mapNotNull { entry ->
        val o = entry.asObject() ?: return@mapNotNull null
        val number = o["episNum"].text()?.toIntOrNull() ?: return@mapNotNull null
        MoboEpisode(number, o["isLock"].flag() == true || o["isRedLock"].flag() == true)
    }.distinctBy { it.number }
}
