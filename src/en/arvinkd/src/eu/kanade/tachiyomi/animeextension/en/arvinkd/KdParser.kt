package eu.kanade.tachiyomi.animeextension.en.arvinkd

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node

/**
 * Reads KiligDrama's server-rendered pages. Kept free of network and Android types so it can be run
 * against saved pages.
 *
 * KiligDrama is a Next.js site: catalogue, detail and episode pages arrive as HTML, and the data the
 * player needs rides along as React Flight text inside `self.__next_f.push([1,"..."])` scripts.
 */
internal object KdParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    class Card(val title: String, val path: String, val thumbnail: String?)

    class Listing(val cards: List<Card>, val hasNext: Boolean)

    class Detail(val title: String?, val thumbnail: String?, val description: String?, val source: String?)

    class Player(val bookId: String?, val currentSerial: Int?, val firstSerial: Int?, val source: SourceDescriptorDto?)

    /** True for Cloudflare's "Just a moment..." page, which is served instead of the content. */
    fun isChallenge(html: String): Boolean = html.contains("<title>Just a moment") || html.contains("Enable JavaScript and cookies to continue")

    // React streams a page: the first bytes hold empty `<template id="P:n">` slots, and the content that
    // fills them arrives later in hidden `<div id="S:n">` blocks, each followed by a `$RS("S:n","P:n")` call
    // that moves its children into the slot (the ids count in hexadecimal). A browser runs those calls. An HTTP client does not, so it sees
    // a grid of 18 cards as 2 cards and 16 empty slots. The swaps are replayed here, in order.
    //
    // A Suspense boundary is completed by a second call, `$RC("B:n","S:m")`: the boundary starts with
    // `<!--$?--><template id="B:n"></template>`, holds a loading skeleton, ends at the matching `<!--/$-->`,
    // and the content arrives in a hidden `<div id="S:m">` further down the document. The search results
    // are such a boundary (a skeleton grid that the real grid replaces), so without replaying it the
    // results page has no result grid at all.
    private val streamStep = Regex("""\${'$'}R([SC])\("([A-Z]:[0-9a-f]+)","([A-Z]:[0-9a-f]+)"\)""")

    private fun parse(html: String): Document {
        val document = Jsoup.parse(html)
        val steps = document.select("script").flatMap { streamStep.findAll(it.data()).toList() }
        for (step in steps) {
            val (kind, first, second) = step.destructured
            if (kind == "S") swapSegment(document, first, second) else completeBoundary(document, first, second)
        }
        return document
    }

    /** `$RS(content, slot)`: the children of the hidden content block replace the empty slot. */
    private fun swapSegment(document: Document, contentId: String, slotId: String) {
        val content = document.getElementById(contentId) ?: return
        val slot = document.getElementById(slotId) ?: return
        content.childNodes().toList().forEach { slot.before(it) }
        slot.remove()
        content.remove()
    }

    /** `$RC(boundary, content)`: the hidden content block replaces the boundary's loading fallback. */
    private fun completeBoundary(document: Document, boundaryId: String, contentId: String) {
        val marker = document.getElementById(boundaryId) ?: return
        val content = document.getElementById(contentId) ?: return

        // The fallback runs from the marker to the boundary's own closing comment; boundaries inside it nest.
        var depth = 1
        val fallback = mutableListOf<Node>(marker)
        var end: Node? = null
        var node = marker.nextSibling()
        while (node != null) {
            if (node is Comment) {
                val text = node.data
                if (text.startsWith("$")) {
                    depth++
                } else if (text == "/$" && --depth == 0) {
                    end = node
                    break
                }
            }
            fallback += node
            node = node.nextSibling()
        }
        if (end == null) return

        content.childNodes().toList().forEach { end.before(it) }
        fallback.forEach { it.remove() }
        content.remove()
    }

    // ================================ Listings ================================

    /**
     * The cards of the main grid of a catalogue, genre, source or search page.
     *
     * A page also carries a featured strip and sidebar rails that hold more cards (a genre page holds
     * 58 cards for a grid of 20), so only the first grid of the content column counts.
     */
    fun parseListing(html: String, page: Int): Listing {
        val grid = parse(html).selectFirst("div.min-w-0 > div.grid") ?: return Listing(emptyList(), false)
        val cards = grid.select("> article").mapNotNull { article ->
            val link = article.selectFirst("a[href^=/en/drama/]") ?: return@mapNotNull null
            val title = link.attr("aria-label").ifBlank { article.selectFirst("img")?.attr("alt").orEmpty() }.trim()
            if (title.isEmpty()) return@mapNotNull null
            Card(title, link.attr("href"), thumbnail(article))
        }

        // Page links look like `/page/2` on browse routes and `?q=...&page=2` on search.
        val nextPage = Regex("""(?:/page/|[?&]page=)${page + 1}(?:$|[^0-9])""")
        val hasNext = cards.isNotEmpty() && grid.parent()?.select("a[href]").orEmpty().any { nextPage.containsMatchIn(it.attr("href")) }
        return Listing(cards, hasNext)
    }

    private fun thumbnail(article: Element): String? {
        val image = article.selectFirst("img") ?: return null
        return image.attr("src").takeIf { it.startsWith("http") }
            ?: Regex("""https?://\S+""").find(image.attr("srcset"))?.value
    }

    // ================================= Details =================================

    fun parseDetail(html: String): Detail {
        val document = parse(html)
        val heading = document.selectFirst("h1")
        val rating = heading?.nextElementSibling()?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val source = document.selectFirst("dl a[href^=/en/studio/]")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        // The page has no synopsis (its meta description is the site's own advertising), only a rating.
        val description = listOfNotNull(rating, source?.let { "Source: $it" }).joinToString("\n").ifEmpty { null }
        return Detail(
            title = heading?.text()?.trim()?.takeIf { it.isNotEmpty() },
            thumbnail = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") },
            description = description,
            source = source,
        )
    }

    /** `(display number, path)` of every episode the detail page links, which is all of them. */
    fun parseEpisodes(html: String, seriesPath: String): List<Pair<Int, String>> {
        val link = Regex("""^${Regex.escape(seriesPath.trimEnd('/'))}/episode-(\d+)$""")
        return parse(html).select("a[href]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href")
                link.find(href)?.groupValues?.get(1)?.toIntOrNull()?.let { it to href }
            }
            .distinctBy { it.first }
    }

    // ============================== Player data ==============================

    /**
     * The player props an episode page embeds: the book id, the serial being shown, and the stream
     * descriptor for it.
     *
     * The descriptor is the bootstrap value of the page that was loaded. After the site's own client-side
     * switch to another episode it is stale, so a caller that did not load the page for that exact episode
     * must compare [Player.currentSerial] before trusting it.
     */
    fun parsePlayer(html: String): Player? {
        val flight = flight(html)
        if (flight.isEmpty()) return null
        val bookId = Regex(""""bookId":"([^"]+)"""").find(flight)?.groupValues?.get(1)
        val currentSerial = Regex(""""currentSerial":(\d+)""").find(flight)?.groupValues?.get(1)?.toIntOrNull()
        val firstSerial = Regex(""""firstSerial":(\d+)""").find(flight)?.groupValues?.get(1)?.toIntOrNull()
        if (bookId == null && currentSerial == null) return null

        val source = objectAfter(flight, "sourceFirst")?.let {
            try {
                json.decodeFromString<SourceDescriptorDto>(it)
            } catch (_: SerializationException) {
                null
            }
        }
        return Player(bookId, currentSerial, firstSerial, source)
    }

    /** The descriptor of an `/api/episode-source` reply. */
    fun parseDescriptor(body: String): SourceDescriptorDto? = try {
        json.decodeFromString<EpisodeSourceDto>(body).descriptor
    } catch (_: SerializationException) {
        null
    }

    private const val PUSH = "self.__next_f.push([1,\""

    /** The Flight text, with every `push([1,"..."])` chunk decoded and joined in order. */
    private fun flight(html: String): String {
        val out = StringBuilder()
        var from = 0
        while (true) {
            val start = html.indexOf(PUSH, from)
            if (start < 0) break
            // Scanned by hand: a pattern for a string literal this long overflows the regex stack.
            val bodyStart = start + PUSH.length - 1
            var end = bodyStart + 1
            while (end < html.length) {
                val c = html[end]
                if (c == '\\') {
                    end += 2
                    continue
                }
                if (c == '"') break
                end++
            }
            if (end >= html.length) break
            try {
                out.append(json.decodeFromString<String>(html.substring(bodyStart, end + 1)))
            } catch (_: SerializationException) {
                // A chunk that is not a plain string is not text we need.
            }
            from = end + 1
        }
        return out.toString()
    }

    /** The JSON object that follows `"key":` in [text], matched by braces outside string literals. */
    private fun objectAfter(text: String, key: String): String? {
        val marker = "\"$key\":"
        val at = text.indexOf(marker)
        if (at < 0) return null
        val start = at + marker.length
        if (start >= text.length || text[start] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val c = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }
}
