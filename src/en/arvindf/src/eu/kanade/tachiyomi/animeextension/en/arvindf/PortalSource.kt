package eu.kanade.tachiyomi.animeextension.en.arvindf

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import keiyoushi.utils.parallelMapNotNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.io.IOException
import java.io.InputStream

internal data class EpisodeItem(val name: String, val number: Float, val url: String, val note: String? = null)

/** Cloudflare's "Just a moment" page, which the portals answer a plain client with. */
internal object Cloudflare {
    fun isChallenge(code: Int, body: String): Boolean = (code == 403 || code == 503) &&
        (body.contains("Just a moment", ignoreCase = true) || body.contains("challenges.cloudflare.com", ignoreCase = true))

    fun message(host: String): String = "$host asked for a Cloudflare check. Open this source in WebView, let the page load, then try again."
}

/** Reads up to [count] bytes, fewer if the stream ends first. */
internal fun InputStream.readUpTo(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = read(buffer, filled, count - filled)
        if (read < 0) break
        filled += read
    }
    return buffer.copyOf(filled)
}

/**
 * Lists, describes and plays the titles of the provider portals dramafren.org links to (see [Portals]).
 *
 * The portals' own pages sit behind Cloudflare, so those go through [pageClient], the host's
 * Cloudflare-aware client. Their video resolvers (`cdn-*` hosts) and media hosts answered a plain
 * client in the capture, so those use [plain], which can never open a WebView.
 *
 * Nothing here answers an ad, a human check or a coin wall. An episode a portal says is locked is
 * reported, not unlocked.
 */
internal class PortalSource(
    private val plain: OkHttpClient,
    private val pageClient: () -> OkHttpClient,
    private val headers: Headers,
) {

    // ================================ Browse ================================

    suspend fun listing(portal: Portal, page: Int, query: String?, tv: TvOptions = TvOptions()): ListingPage {
        if (portal.resolver == Resolver.TVSERIES) return tvListing(portal, page, query, tv)
        if (portal.isJson) return jsonListing(portal, page, query)
        if (query != null && page > 1 && !portal.searchPages) return ListingPage(emptyList(), false)
        val url = if (query == null) portal.homeUrl(page) else portal.searchUrl(query, page)
        val document = Jsoup.parse(text(url), url.toString())
        val cards = PortalHtml.cards(document).map { it.copy(url = portalRef(portal, it.url)) }
        val next = PortalHtml.hasNext(document) && (query == null || portal.searchPages)
        return ListingPage(cards, next && cards.isNotEmpty())
    }

    private suspend fun jsonListing(portal: Portal, page: Int, query: String?): ListingPage {
        when (portal.resolver) {
            Resolver.SHOTSHORT -> return shotshortListing(portal, page, query)
            Resolver.MOBOREELS -> return moboreelsListing(portal, page, query)
            else -> Unit
        }
        // MicroDrama's page 2 repeated page 1 (same 50 ids) in the capture, so its first page is all there is.
        if (portal.resolver == Resolver.MICRODRAMA && page > 1) return ListingPage(emptyList(), false)
        val url = if (query == null) {
            portal.api("list", "lang" to portal.lang, "page" to page.toString())
        } else {
            portal.api("search", "lang" to portal.lang, "q" to query, "page" to page.toString())
        }
        val cards = PortalJson.dramas(json(url)).map { it.copy(url = portalRef(portal, it.url)) }
        return ListingPage(cards, portal.resolver == Resolver.SHORTWAVE && cards.size >= SHORTWAVE_PAGE)
    }

    /**
     * ShotShort's category browse and its search both page by `page` with `limit=24`, and its own Next
     * button stays off once a page comes back short. The category route answers an empty `pageData`
     * unless the request carries `type=2` (the site's "Latest" tab); with no `type`, `type=1` (Trending)
     * or `type=3` every category came back empty. The portal's own page sends no `type`, so why 2 works
     * is not known, only that it does (reported from a live test on 2026-11-04). Search needs no `type`.
     */
    private suspend fun shotshortListing(portal: Portal, page: Int, query: String?): ListingPage {
        val paging = arrayOf("page" to page.toString(), "limit" to SHOTSHORT_PAGE.toString(), "lang" to portal.lang)
        val url = if (query == null) {
            portal.api("/category", "category" to "All", "type" to SHOTSHORT_LATEST, *paging)
        } else {
            portal.api("/search", "q" to query, *paging)
        }
        val cards = PortalJson.shotshortBooks(json(url)).map { it.copy(url = portalRef(portal, it.url)) }
        return ListingPage(cards, cards.size >= SHOTSHORT_PAGE)
    }

    /**
     * MoboReels' search pages by `pageNo`. Its front page is a channel's `sections`, one page of them: the
     * default channel (Home) is looked up first, as the portal's own page does.
     */
    private suspend fun moboreelsListing(portal: Portal, page: Int, query: String?): ListingPage {
        if (query != null) {
            val result = PortalJson.moboreelsSearch(json(portal.api("search", "lang" to portal.lang, "q" to query, "pageNo" to page.toString())))
            return result.copy(cards = result.cards.map { it.copy(url = portalRef(portal, it.url)) })
        }
        if (page > 1) return ListingPage(emptyList(), false)
        val channel = PortalJson.moboreelsDefaultChannel(json(portal.api("channels", "lang" to portal.lang)))
            ?: throw Exception("MoboReels listed no channels")
        val cards = PortalJson.moboreelsSections(json(portal.api("list", "lang" to portal.lang, "channelId" to channel)))
            .map { it.copy(url = portalRef(portal, it.url)) }
        return ListingPage(cards, false)
    }

    /**
     * TvSeries browses four tabs and searches by POST. Home and Movies answer one page of sections; the two
     * trending tabs page with `page` and `perPage=18`, the first being 0 for series and 1 for movies, as the
     * portal's own script asks. A search sends `{keyword, page, perPage: "28", subjectType}` as JSON, with
     * every value a string, and pages while the answer's `hasMore` is true.
     */
    private suspend fun tvListing(portal: Portal, page: Int, query: String?, tv: TvOptions): ListingPage {
        if (query != null) {
            val body = JsonObject(
                mapOf(
                    "keyword" to JsonPrimitive(query),
                    "page" to JsonPrimitive(page.toString()),
                    "perPage" to JsonPrimitive(TV_SEARCH_PAGE.toString()),
                    "subjectType" to JsonPrimitive(tv.searchType.toString()),
                ),
            )
            return withRefs(portal, PortalJson.tvItems(postJson(portal.api("search"), body.toString())))
        }
        val feed = tv.feed
        if (!feed.paged) {
            if (page > 1) return ListingPage(emptyList(), false)
            return ListingPage(PortalJson.tvSections(json(portal.api(feed.route))).map { it.copy(url = portalRef(portal, it.url)) }, false)
        }
        val url = portal.api(feed.route, "page" to (feed.firstPage + page - 1).toString(), "perPage" to TV_FEED_PAGE.toString())
        return withRefs(portal, PortalJson.tvItems(json(url)))
    }

    private fun withRefs(portal: Portal, page: ListingPage) = page.copy(cards = page.cards.map { it.copy(url = portalRef(portal, it.url)) })

    /** `<subjectId>/<detailPath>` is how a TvSeries title is stored. */
    private fun tvIds(ref: String): Pair<String, String> {
        val id = ref.substringBefore('/')
        val path = ref.substringAfter('/', "")
        if (id.isEmpty() || path.isEmpty()) throw Exception("This TvSeries title link is not valid")
        return id to path
    }

    private suspend fun tvDetails(portal: Portal, ref: String): PortalDetail {
        val (id, path) = tvIds(ref)
        return PortalJson.tvDetail(json(portal.api("details", "id" to id, "detailPath" to path)))
    }

    /**
     * A series lists every episode of every season; the stored reference is
     * `<id>/<detailPath>/<season>/<episode>`. Episodes are numbered in order across seasons, so a three
     * season title reads 1 to 48, and each is named by its own season and episode. A movie is one entry.
     */
    private suspend fun tvEpisodes(portal: Portal, ref: String): List<EpisodeItem> {
        val (id, path) = tvIds(ref)
        val episodes = PortalJson.tvEpisodes(json(portal.api("details", "id" to id, "detailPath" to path)))
        val isMovie = episodes.singleOrNull()?.let { it.season == 0 && it.episode == 0 } == true
        return episodes.mapIndexed { index, ep ->
            val name = if (isMovie) "Movie" else "S${ep.season} E${ep.episode}"
            EpisodeItem(name, (index + 1).toFloat(), portalRef(portal, "$id/$path/${ep.season}/${ep.episode}"))
        }.sortedByDescending { it.number }
    }

    private suspend fun tvVideos(portal: Portal, ref: String): List<Video> {
        val parts = ref.split('/')
        if (parts.size != 4) throw Exception("This TvSeries episode link is not valid")
        val (id, path, season, episode) = parts
        val root = json(portal.api("getplay", "subjectId" to id, "detailPath" to path, "se" to season, "ep" to episode, "lang" to portal.lang))
        val resolved = PortalResolvers.tvseries(root)
        if (resolved == null) {
            // The portal answers with a message when it has no stream, and its page then offers other titles of
            // the same name. That search is not repeated here; the title is simply reported.
            val said = root["message"].text()?.takeUnless { it.equals("ok", ignoreCase = true) }
            throw Exception("TvSeries has no stream for this episode right now${said?.let { " ($it)" }.orEmpty()}. A title of the same name may have one.")
        }
        return openable(portal, resolved).ifEmpty { throw Exception(EXPIRED.format(portal.label)) }
    }

    // ================================ Details ================================

    suspend fun details(portal: Portal, ref: String): PortalDetail {
        if (portal.resolver == Resolver.TVSERIES) return tvDetails(portal, ref)
        if (portal.isJson) {
            when (portal.resolver) {
                Resolver.SHOTSHORT -> return PortalJson.shotshortDetail(json(portal.api("/book/$ref", "lang" to portal.lang)))
                Resolver.MOBOREELS -> return PortalJson.moboreelsDetail(json(portal.api("detail", "lang" to portal.lang, "id" to ref)))
                else -> Unit
            }
            val drama = json(portal.api("detail", "lang" to portal.lang, "id" to ref))["drama"].asObject()
            return PortalDetail(drama?.get("title").text(), drama?.get("cover").text(), drama?.get("description").text(), null)
        }
        val url = htmlUrl(portal, ref)
        return PortalHtml.detail(Jsoup.parse(text(url), url.toString()))
    }

    // ================================ Episodes ================================

    suspend fun episodes(portal: Portal, ref: String): List<EpisodeItem> {
        if (portal.resolver == Resolver.TVSERIES) return tvEpisodes(portal, ref)
        if (portal.isJson) {
            when (portal.resolver) {
                Resolver.SHOTSHORT -> return shotshortEpisodes(portal, ref)
                Resolver.MOBOREELS -> return moboreelsEpisodes(portal, ref)
                else -> Unit
            }
            val root = json(portal.api("detail", "lang" to portal.lang, "id" to ref))
            return root["episodes"].asArray().mapNotNull { entry ->
                val o = entry.asObject() ?: return@mapNotNull null
                val index = o["index"].text()?.toIntOrNull() ?: return@mapNotNull null
                if (portal.resolver == Resolver.SHORTWAVE) {
                    val chapter = o["chapter_id"].text() ?: return@mapNotNull null
                    val free = o["is_free"].flag() == true
                    EpisodeItem("Episode $index", index.toFloat(), portalRef(portal, "$ref/$chapter/${if (free) 1 else 0}"), if (free) null else "Locked")
                } else {
                    EpisodeItem("Episode $index", index.toFloat(), portalRef(portal, "$ref/$index"))
                }
            }.sortedByDescending { it.number }
        }
        val url = htmlUrl(portal, ref)
        return PortalHtml.episodes(Jsoup.parse(text(url), url.toString()))
            .sortedByDescending { it.number }
            .map { EpisodeItem("Episode ${it.number}", it.number.toFloat(), portalRef(portal, it.href)) }
    }

    /** ShotShort plays by `chapterId`, not by the episode's ordinal, so the stored reference is `<book>/<chapter>`. */
    private suspend fun shotshortEpisodes(portal: Portal, ref: String): List<EpisodeItem> = PortalJson
        .shotshortChapters(json(portal.api("/book/$ref/episodes", "lang" to portal.lang)))
        .sortedByDescending { it.first }
        .map { (order, chapter) -> EpisodeItem("Episode $order", order.toFloat(), portalRef(portal, "$ref/$chapter")) }

    /** MoboReels plays by `episNum`; the last part of the reference is 1 for a free episode and 0 for a flagged one. */
    private suspend fun moboreelsEpisodes(portal: Portal, ref: String): List<EpisodeItem> = PortalJson
        .moboreelsEpisodes(json(portal.api("episodes", "lang" to portal.lang, "id" to ref)))
        .sortedByDescending { it.number }
        .map { ep ->
            val free = if (ep.locked) 0 else 1
            EpisodeItem("Episode ${ep.number}", ep.number.toFloat(), portalRef(portal, "$ref/${ep.number}/$free"), if (ep.locked) "Locked" else null)
        }

    // ================================ Video ================================

    suspend fun videos(portal: Portal, ref: String): List<Video> {
        if (portal.pool.isNotEmpty()) return poolVideos(portal, ref)
        when (portal.resolver) {
            Resolver.SHOTSHORT -> return shotshortVideos(portal, ref)
            Resolver.MOBOREELS -> return moboreelsVideos(portal, ref)
            Resolver.TVSERIES -> return tvVideos(portal, ref)
            else -> Unit
        }
        val resolved = when (portal.resolver) {
            Resolver.MICRODRAMA -> microdrama(portal, ref)
            Resolver.SHORTWAVE -> shortwave(portal, ref)
            Resolver.DRAMAPOPS -> {
                val url = htmlUrl(portal, ref)
                val html = text(url)
                // Later episodes of long series answer slowly (45 to 100 seconds were seen) and then
                // say so on the page, with no source in it.
                if ("Video Unavailable" in html) throw Exception("DramaPops reports this episode's video as unavailable.")
                PortalHtml.dramapops(Jsoup.parse(html, url.toString()), portal.base)
            }
            else -> embedded(portal, ref)
        } ?: throw Exception("${portal.label} gave no stream for this episode")
        return openable(portal, resolved).ifEmpty { throw Exception(EXPIRED.format(portal.label)) }
    }

    private suspend fun shotshortVideos(portal: Portal, ref: String): List<Video> {
        val (book, chapter) = ref.split('/').takeIf { it.size == 2 } ?: throw Exception("This ShotShort episode link is not valid")
        val root = json(portal.api("/book/$book/chapter/$chapter", "lang" to portal.lang))
        if (root["isEncrypt"].flag() == true) throw Exception("ShotShort marks this episode's video as encrypted, which Arvin-DF does not play.")
        // The portal's own price and lock flags are respected, never answered.
        if (PortalResolvers.shotshortLocked(root)) throw Exception("ShotShort keeps this episode behind its own unlock. Only its free episodes play here.")
        val resolved = PortalResolvers.shotshort(root) ?: throw Exception("${portal.label} gave no stream for this episode")
        return openable(portal, resolved).ifEmpty { throw Exception(EXPIRED.format(portal.label)) }
    }

    private suspend fun moboreelsVideos(portal: Portal, ref: String): List<Video> {
        val parts = ref.split('/')
        if (parts.size != 3) throw Exception("This MoboReels episode link is not valid")
        if (parts[2] != "1") throw Exception("MoboReels keeps this episode behind its own coin unlock. Only its free episodes play here.")
        // The portal's page asks once more with `fallback=1` when the first source does not play; so does this.
        for (fallback in listOf(false, true)) {
            val params = arrayOf("lang" to portal.lang, "id" to parts[0], "episNum" to parts[1]) +
                if (fallback) arrayOf("fallback" to "1") else emptyArray()
            val resolved = PortalResolvers.moboreels(json(portal.api("video", *params))) ?: continue
            openable(portal, resolved).takeIf { it.isNotEmpty() }?.let { return it }
        }
        throw Exception(EXPIRED.format(portal.label))
    }

    /** A page that embeds its own source. An empty one is the site saying there is no video yet. */
    private suspend fun embedded(portal: Portal, ref: String): Resolved? {
        val html = text(htmlUrl(portal, ref))
        val streams = PortalHtml.embedded(html)
        if (streams.isEmpty() && PortalHtml.hasEmptySource(html)) {
            throw Exception("${portal.label}'s page carries no video for this episode. It may be locked on the site or not out yet.")
        }
        return streams.takeIf { it.isNotEmpty() }?.let { Resolved(it) }
    }

    private suspend fun microdrama(portal: Portal, ref: String): Resolved? {
        val (id, index) = ref.split('/').takeIf { it.size == 2 } ?: return null
        val episode = json(portal.api("detail", "lang" to portal.lang, "id" to id))["episodes"].asArray()
            .mapNotNull { it.asObject() }
            .firstOrNull { it["index"].text() == index } ?: return null
        return PortalResolvers.microdrama(episode)
    }

    private suspend fun shortwave(portal: Portal, ref: String): Resolved? {
        val parts = ref.split('/')
        if (parts.size != 3) return null
        // The portal marks which chapters are free. The rest sit behind its own unlock, which is left alone.
        if (parts[2] != "1") throw Exception("ShortWave keeps this episode behind its own unlock. Only its free episodes play here.")
        val root = json(portal.api("unlock", "lang" to portal.lang, "drama_id" to parts[0], "chapter_id" to parts[1]))
        return PortalResolvers.shortwave(root)
    }

    /**
     * Asks every endpoint of the portal's resolver pool at once. An endpoint only wins once one of the
     * streams it names opens: the pool hosts answer at different speeds and not all of them name links
     * that work (ShortMax 2's `cdn-shortmax` replied fastest with links that all answered 403).
     */
    private suspend fun poolVideos(portal: Portal, ref: String): List<Video> {
        val watch = htmlUrl(portal, ref)
        val id = watch.queryParameter("id") ?: throw Exception("${portal.label} episode link has no id")
        val episode = watch.queryParameter("ep") ?: watch.queryParameter("no") ?: "1"
        // GoodShort counts episodes from zero and resolves each through a chapter id kept on the watch page.
        val chapter = if (portal.resolver == Resolver.GOODSHORT) {
            PortalHtml.chapterIds(text(watch)).getOrNull(episode.toIntOrNull() ?: 0)
                ?: throw Exception("GoodShort has no chapter for episode $episode")
        } else {
            null
        }
        val tasks: List<suspend () -> List<Video>?> = portal.pool.map { endpoint ->
            {
                PortalResolvers.poolUrl(portal.resolver, portal.lang, endpoint, id, episode, chapter)
                    ?.let { fetchPool(portal.resolver, it) }
                    ?.let { openable(portal, it) }
                    ?.takeIf { it.isNotEmpty() }
            }
        }
        return firstSuccess(tasks) ?: throw Exception(EXPIRED.format(portal.label))
    }

    private suspend fun fetchPool(resolver: Resolver, url: HttpUrl): Resolved? {
        // `cdn-` hosts answered a plain client; a portal's own origin needs the Cloudflare-aware one.
        val client = if (url.host.startsWith("cdn-")) plain else pageClient()
        return client.newCall(GET(url, headers)).await().use { response ->
            if (!response.isSuccessful) return null
            val root = Js.parse(response.body.string()).asObject() ?: return null
            PortalResolvers.pool(resolver, root)
        }
    }

    /** The streams of [resolved] that open, best quality first. May be empty. */
    private suspend fun openable(portal: Portal, resolved: Resolved): List<Video> {
        val tracks = resolved.subs.map { Track(it.url, it.label) }
        val mediaHeaders = mediaHeaders(portal)
        return resolved.streams.distinctBy { it.url }.parallelMapNotNull { stream ->
            val hls = isHls(stream.url, mediaHeaders) ?: return@parallelMapNotNull null
            Video(
                videoUrl = if (hls) PortalResolvers.hlsUrl(stream.url) else stream.url,
                videoTitle = "${portal.label} · ${stream.label}",
                headers = mediaHeaders,
                subtitleTracks = tracks,
            )
        }.sortedByDescending { PortalResolvers.rank(it.videoTitle) }
    }

    /** What a browser playing the portal's page sends to its media host: the page's own origin as Referer. */
    private fun mediaHeaders(portal: Portal): Headers = if (portal.mediaReferer) {
        headers.newBuilder().set("Referer", "${portal.base}/").build()
    } else {
        headers
    }

    /** True for an HLS playlist, false for a file, null if the link does not open or is a page/JSON. */
    private suspend fun isHls(url: String, requestHeaders: Headers): Boolean? {
        val target = url.toHttpUrlOrNull() ?: return null
        val request = Request.Builder().url(target).headers(requestHeaders).header("Range", "bytes=0-63").build()
        return try {
            plain.newCall(request).await().use { response ->
                if (!response.isSuccessful) return null
                val head = String(response.body.byteStream().readUpTo(64), Charsets.ISO_8859_1).trimStart()
                when {
                    head.startsWith("#EXTM3U") -> true
                    head.isEmpty() || head.startsWith("<") || head.startsWith("{") -> null
                    else -> false
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    // ================================ Network ================================

    private fun htmlUrl(portal: Portal, ref: String): HttpUrl = "${portal.base}/$ref".toHttpUrl()

    private suspend fun text(url: HttpUrl): String = pageClient().newCall(GET(url, headers)).await().use { response ->
        val body = response.body.string()
        if (Cloudflare.isChallenge(response.code, body)) throw Exception(Cloudflare.message(url.host))
        if (!response.isSuccessful) throw Exception("${url.host} answered HTTP ${response.code}")
        body
    }

    private suspend fun json(url: HttpUrl): JsonObject = Js.parse(text(url)).asObject()
        ?: throw Exception("${url.host} did not answer with data")

    /** A JSON POST through the Cloudflare-aware client, like [text]. Only TvSeries' search uses one. */
    private suspend fun postJson(url: HttpUrl, body: String): JsonObject {
        val request = Request.Builder().url(url).headers(headers).post(body.toRequestBody(JSON_TYPE)).build()
        val answer = pageClient().newCall(request).await().use { response ->
            val text = response.body.string()
            if (Cloudflare.isChallenge(response.code, text)) throw Exception(Cloudflare.message(url.host))
            if (!response.isSuccessful) throw Exception("${url.host} answered HTTP ${response.code}")
            text
        }
        return Js.parse(answer).asObject() ?: throw Exception("${url.host} did not answer with data")
    }

    private companion object {
        const val SHORTWAVE_PAGE = 24
        const val SHOTSHORT_PAGE = 24
        const val SHOTSHORT_LATEST = "2"
        const val TV_FEED_PAGE = 18
        const val TV_SEARCH_PAGE = 28
        val JSON_TYPE = "application/json".toMediaType()
        const val EXPIRED = "%s returned no playable stream for this episode. Its links may have expired; try again."
    }
}
