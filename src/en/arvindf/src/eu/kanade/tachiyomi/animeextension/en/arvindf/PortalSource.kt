package eu.kanade.tachiyomi.animeextension.en.arvindf

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import keiyoushi.utils.parallelMapNotNull
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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

    suspend fun listing(portal: Portal, page: Int, query: String?): ListingPage {
        if (portal.isJson) return jsonListing(portal, page, query)
        if (query != null && page > 1 && !portal.searchPages) return ListingPage(emptyList(), false)
        val url = if (query == null) portal.homeUrl(page) else portal.searchUrl(query, page)
        val document = Jsoup.parse(text(url), url.toString())
        val cards = PortalHtml.cards(document).map { it.copy(url = portalRef(portal, it.url)) }
        val next = PortalHtml.hasNext(document) && (query == null || portal.searchPages)
        return ListingPage(cards, next && cards.isNotEmpty())
    }

    private suspend fun jsonListing(portal: Portal, page: Int, query: String?): ListingPage {
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

    // ================================ Details ================================

    suspend fun details(portal: Portal, ref: String): PortalDetail {
        if (portal.isJson) {
            val drama = json(portal.api("detail", "lang" to portal.lang, "id" to ref))["drama"].asObject()
            return PortalDetail(drama?.get("title").text(), drama?.get("cover").text(), drama?.get("description").text(), null)
        }
        val url = htmlUrl(portal, ref)
        return PortalHtml.detail(Jsoup.parse(text(url), url.toString()))
    }

    // ================================ Episodes ================================

    suspend fun episodes(portal: Portal, ref: String): List<EpisodeItem> {
        if (portal.isJson) {
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

    // ================================ Video ================================

    suspend fun videos(portal: Portal, ref: String): List<Video> {
        if (portal.pool.isNotEmpty()) return poolVideos(portal, ref)
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
        return resolved.streams.distinctBy { it.url }.parallelMapNotNull { stream ->
            val hls = isHls(stream.url) ?: return@parallelMapNotNull null
            Video(
                videoUrl = if (hls) PortalResolvers.hlsUrl(stream.url) else stream.url,
                videoTitle = "${portal.label} · ${stream.label}",
                headers = headers,
                subtitleTracks = tracks,
            )
        }.sortedByDescending { PortalResolvers.rank(it.videoTitle) }
    }

    /** True for an HLS playlist, false for a file, null if the link does not open or is a page/JSON. */
    private suspend fun isHls(url: String): Boolean? {
        val target = url.toHttpUrlOrNull() ?: return null
        val request = Request.Builder().url(target).headers(headers).header("Range", "bytes=0-63").build()
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

    private companion object {
        const val SHORTWAVE_PAGE = 24
        const val EXPIRED = "%s returned no playable stream for this episode. Its links may have expired; try again."
    }
}
