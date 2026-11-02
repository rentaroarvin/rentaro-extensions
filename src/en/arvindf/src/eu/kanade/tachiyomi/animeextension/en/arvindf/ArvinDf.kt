package eu.kanade.tachiyomi.animeextension.en.arvindf

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import keiyoushi.utils.parallelMapNotNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * DramaFren (dramafren.org) is a WordPress catalogue of short Chinese dramas that links out to
 * third-party players, and it runs a separate DramaWave subsite (dramawave.dramafren.org) with its own
 * titles and a JSON source API. Both are covered here, as two catalogues behind one source.
 *
 *  - **DramaFren.** Listing, search and the series page are plain HTML. Each episode's watch page offers
 *    a handful of players; only Abyss is played. The embed carries its own source configuration (see
 *    [AbyssConfig]), which this reads directly. The player's click-to-open-popups overlay is a page
 *    script that never runs here, and nothing is forged to stand in for it.
 *  - **DramaWave.** Pages are behind a Cloudflare check that the host's client answers the way the app
 *    does (open the source in WebView once); when that is not enough this says so. The source API on
 *    the CDN hosts answers openly and returns an HLS master whose audio is a separate rendition.
 *
 *  - **Provider portals.** dramafren.org links to a set of provider portals (DramaBox, GoodShort,
 *    NetShort, FlickReels, ReelShort and others) that have their own titles. The ones that answered in
 *    the capture are browsable through the Catalogue filter; see [Portals] and [PortalSource].
 *
 * UPNShare, the other player the site embeds, is not used: its page runs a "Verifying human" check
 * that this extension does not answer. The same goes for the site's other mirrors.
 *
 * `SAnime.url` is a site path (`/series/<slug>/`, or `/watch/<slug>/` for cards that point at an
 * episode), `dw/<lang>/<id>` for DramaWave, or `p/<portal>/<reference>` for a provider portal.
 * `SEpisode.url` is the watch path, `dw/<lang>/<id>/<episode>`, or `p/<portal>/<reference>`.
 */
class ArvinDf : AnimeHttpSource() {

    override val name = "Arvin-DF"

    override val baseUrl = Routes.DRAMAFREN

    override val lang = "en"
    override val supportsLatest = true

    // ============================ Browse / search ============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = dramaFrenListing(Routes.advancedSearch(page, OrderFilter.POPULAR, null))

    /** The series archive lists newest first and has no ordering parameter, which suits "latest". */
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments(if (page > 1) "series/page/$page/" else "series/")
            .build()
        return dramaFrenListing(url)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Pick a catalogue. Sort and status apply to DramaFren; language applies to DramaWave."),
        CatalogueFilter(),
        LanguageFilter(),
        OrderFilter(),
        StatusFilter(),
    )

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val text = query.trim()
        val catalogue = filters.filterIsInstance<CatalogueFilter>().firstOrNull()
        catalogue?.portal?.let { portal -> return portalListing(portal, page, text.ifEmpty { null }) }
        if (catalogue?.isDramaWave == true) {
            val language = filters.filterIsInstance<LanguageFilter>().firstOrNull()?.code ?: DEFAULT_LANGUAGE
            return dramaWaveListing(page, language, text.ifEmpty { null })
        }
        if (text.isNotEmpty()) return dramaFrenListing(Routes.keywordSearch(page, text))
        val order = filters.filterIsInstance<OrderFilter>().firstOrNull()?.value ?: OrderFilter.POPULAR
        val status = filters.filterIsInstance<StatusFilter>().firstOrNull()?.value
        return dramaFrenListing(Routes.advancedSearch(page, order, status))
    }

    private suspend fun dramaFrenListing(listUrl: HttpUrl): AnimesPage {
        val result = DramaFrenParser.listing(document(listUrl))
        val animes = result.cards.map { card ->
            SAnime.create().apply {
                title = card.title
                url = card.url
                thumbnail_url = card.thumbnail
            }
        }
        return AnimesPage(animes, result.hasNext && animes.isNotEmpty())
    }

    private suspend fun dramaWaveListing(page: Int, language: String, query: String?): AnimesPage {
        // A search answers one page of up to 20; only the Recently Watched feed pages.
        if (query != null && page > 1) return AnimesPage(emptyList(), false)
        val result = DramaWaveParser.listing(document(Routes.dramaWaveList(page, language, query)))
        val animes = result.cards.map { card ->
            SAnime.create().apply {
                title = card.title
                url = "$DW_PREFIX$language/${card.url}"
                thumbnail_url = card.thumbnail
            }
        }
        return AnimesPage(animes, query == null && result.hasNext && animes.isNotEmpty())
    }

    private suspend fun portalListing(portal: Portal, page: Int, query: String?): AnimesPage {
        val result = portalSource.listing(portal, page, query)
        val animes = result.cards.map { card ->
            SAnime.create().apply {
                title = card.title
                url = card.url
                thumbnail_url = card.thumbnail
            }
        }
        return AnimesPage(animes, result.hasNext && animes.isNotEmpty())
    }

    // The abstract request/parse pairs are unused: every call above is a suspend override.
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException("Not used")

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    // ================================ Details ================================

    override fun getAnimeUrl(anime: SAnime): String {
        parsePortalRef(anime.url)?.let { (portal, ref) ->
            return if (portal.isJson) "${portal.base}/?id=$ref" else "${portal.base}/$ref"
        }
        if (anime.url.startsWith(DW_PREFIX)) {
            val ref = dramaWaveRef(anime.url)
            return Routes.dramaWaveDetail(ref.id, ref.lang).toString()
        }
        return absolute(anime.url)
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(getAnimeUrl(anime), headers)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        parsePortalRef(anime.url)?.let { (portal, ref) ->
            val detail = portalSource.details(portal, ref)
            return SAnime.create().apply {
                detail.title?.let { title = it }
                detail.poster?.let { thumbnail_url = it }
                description = detail.description
                genre = detail.genre
                status = SAnime.UNKNOWN
            }
        }
        if (anime.url.startsWith(DW_PREFIX)) {
            val ref = dramaWaveRef(anime.url)
            val page = document(Routes.dramaWaveDetail(ref.id, ref.lang))
            return SAnime.create().apply {
                DramaWaveParser.title(page)?.let { title = it }
                DramaWaveParser.poster(page)?.let { thumbnail_url = it }
                description = DramaWaveParser.description(page)
                status = SAnime.UNKNOWN
            }
        }
        val (_, page) = seriesDocument(anime.url)
        return SAnime.create().apply {
            DramaFrenParser.title(page)?.let { title = it }
            DramaFrenParser.poster(page)?.let { thumbnail_url = it }
            status = when (DramaFrenParser.status(page)?.lowercase()) {
                "completed" -> SAnime.COMPLETED
                "ongoing" -> SAnime.ONGOING
                "dropped" -> SAnime.CANCELLED
                else -> SAnime.UNKNOWN
            }
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException("Not used")

    /**
     * The advanced search lists episode posts (`/watch/...`) where the archive lists series, and a watch
     * page names its series in the breadcrumb. A post with no series is shown as a title of its own.
     */
    private suspend fun seriesDocument(path: String): Pair<String, Document> {
        val first = document(absolute(path).toHttpUrl())
        if (!path.startsWith("/watch/")) return path to first
        val series = DramaFrenParser.seriesPath(first) ?: return path to first
        return series to document(absolute(series).toHttpUrl())
    }

    // ================================ Episodes ================================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        parsePortalRef(anime.url)?.let { (portal, ref) ->
            val items = portalSource.episodes(portal, ref)
            if (items.isEmpty()) throw Exception("${portal.label} listed no episodes for this title")
            return items.map { item ->
                SEpisode.create().apply {
                    name = item.name
                    episode_number = item.number
                    url = item.url
                    scanlator = item.note
                }
            }
        }
        if (anime.url.startsWith(DW_PREFIX)) {
            val ref = dramaWaveRef(anime.url)
            val numbers = DramaWaveParser.episodes(document(Routes.dramaWaveDetail(ref.id, ref.lang)))
            if (numbers.isEmpty()) throw Exception("DramaWave listed no episodes for this title")
            return numbers.sortedDescending().map { number ->
                SEpisode.create().apply {
                    name = "Episode $number"
                    episode_number = number.toFloat()
                    url = "${anime.url}/$number"
                }
            }
        }

        val (_, page) = seriesDocument(anime.url)
        val rows = DramaFrenParser.episodes(page)
        if (rows.isEmpty()) {
            // A post with no episode list is the episode itself.
            if (!anime.url.startsWith("/watch/")) throw Exception("DramaFren listed no episodes for this title")
            return listOf(
                SEpisode.create().apply {
                    name = DramaFrenParser.title(page) ?: "Episode 1"
                    episode_number = 1f
                    url = anime.url
                },
            )
        }
        return rows.sortedByDescending { it.number ?: 0f }.map { row ->
            SEpisode.create().apply {
                val number = row.number
                name = when {
                    number == null -> row.title.ifEmpty { "Episode" }
                    row.title.isEmpty() || row.title.equals("FULL EPISODE", ignoreCase = true) -> "Episode ${number.toInt()}"
                    else -> "Episode ${number.toInt()} - ${row.title}"
                }
                episode_number = number ?: 0f
                url = row.path
                date_upload = parseDate(row.date)
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used")

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException("Not used")

    private fun parseDate(text: String): Long = runCatching {
        SimpleDateFormat("MMMM d, yyyy", Locale.US).parse(text)?.time
    }.getOrNull() ?: 0L

    // ============================== Video links ==============================

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException("Not used")

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        parsePortalRef(episode.url)?.let { (portal, _) ->
            return listOf(Hoster(hosterUrl = episode.url, hosterName = portal.label))
        }
        if (episode.url.startsWith(DW_PREFIX)) {
            val ref = dramaWaveRef(episode.url)
            val number = ref.episode ?: throw Exception("This DramaWave episode has no number")
            return listOf(1, 2).map { server ->
                Hoster(hosterUrl = "$DW_PREFIX$server/${ref.lang}/${ref.id}/$number", hosterName = "DramaWave server $server")
            }
        }

        val mirrors = DramaFrenParser.mirrors(document(absolute(episode.url).toHttpUrl()))
        val abyss = mirrors.filter { DramaFrenParser.isAbyss(it.url) }
        if (abyss.isEmpty()) {
            if (mirrors.isEmpty()) throw Exception("DramaFren lists no player for this episode")
            val names = mirrors.joinToString(", ") { it.label.ifBlank { "unnamed" } }
            throw Exception("This episode is only on $names. Arvin-DF plays Abyss, and the site's UPNShare asks for a human check it does not answer.")
        }
        return abyss.mapIndexed { index, mirror ->
            Hoster(hosterUrl = "$ABYSS_PREFIX${mirror.url}", hosterName = if (abyss.size > 1) "Abyss ${index + 1}" else "Abyss")
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = when {
        hoster.hosterUrl.startsWith(ABYSS_PREFIX) -> abyssVideos(hoster.hosterUrl.removePrefix(ABYSS_PREFIX))
        hoster.hosterUrl.startsWith(DW_PREFIX) -> dramaWaveVideos(hoster.hosterUrl.removePrefix(DW_PREFIX))
        hoster.hosterUrl.startsWith(PORTAL_PREFIX) -> {
            val (portal, ref) = parsePortalRef(hoster.hosterUrl) ?: throw Exception("Arvin-DF does not know this portal")
            portalSource.videos(portal, ref)
        }
        else -> throw Exception("Arvin-DF does not know this player")
    }

    // ---------------------------------- Abyss ----------------------------------

    private suspend fun abyssVideos(embedUrl: String): List<Video> {
        val embed = embedUrl.toHttpUrl()
        val html = client.newCall(GET(embed, headers.newBuilder().set("Referer", "$baseUrl/").build())).await().use { response ->
            if (!response.isSuccessful) throw Exception("Abyss answered HTTP ${response.code}")
            response.body.string()
        }
        val config = try {
            AbyssConfig.read(html)
        } catch (e: IllegalArgumentException) {
            throw Exception("Abyss changed its player page, so Arvin-DF cannot read it")
        } catch (e: SerializationException) {
            throw Exception("Abyss changed its player data, so Arvin-DF cannot read it")
        }
        if (config.sources.isEmpty()) {
            throw Exception(
                if (config.skipped > 0) {
                    "This Abyss file uses a delivery route Arvin-DF does not play yet"
                } else {
                    "Abyss lists no file for this episode"
                },
            )
        }

        // The storage host answers 403 unless the request carries the embed player's own origin, which
        // is what the player sends from inside its iframe.
        val referer = "${embed.scheme}://${embed.host}/"
        val userAgent = headers["User-Agent"].orEmpty()
        val reachable = config.sources.parallelMapNotNull { source ->
            headState(source, referer, userAgent)?.let { encrypted -> source to encrypted }
        }
        if (reachable.isEmpty()) throw Exception("Abyss's storage did not serve this episode. Try again later.")

        return reachable
            .sortedWith(
                compareByDescending<Pair<AbyssSource, Boolean>> { it.first.label.filter(Char::isDigit).toIntOrNull() ?: 0 }
                    .thenByDescending { it.first.size },
            )
            .map { (source, encrypted) ->
                Video(
                    videoUrl = AbyssProxy.urlFor(source, encrypted, referer, userAgent, client),
                    videoTitle = "Abyss · ${source.label} (${source.size / 1_000_000} MB)",
                    headers = headers,
                )
            }
    }

    /**
     * Fetches the first bytes of [source] and works out how its head is stored, the way the Abyss
     * player does: if the file opens as an MP4 as served it is plain, and if it only does after the
     * AES-CTR pass it is encrypted. Returns true for encrypted, false for plain, null if neither
     * reads as an MP4 or the storage host refused.
     */
    private suspend fun headState(source: AbyssSource, referer: String, userAgent: String): Boolean? {
        val request = Request.Builder()
            .url(source.base)
            .header("User-Agent", userAgent)
            .header("Referer", referer)
            .header("Range", "bytes=0-31")
            .build()
        return try {
            client.newCall(request).await().use { response ->
                if (response.code != 206 && response.code != 200) return null
                val head = response.body.byteStream().readNBytesCompat(32)
                if (head.size < 12) return null
                when {
                    isMp4Head(head) -> false
                    isMp4Head(AbyssCrypto.apply(AbyssCrypto.md5Hex(source.fileName), head)) -> true
                    else -> null
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun isMp4Head(bytes: ByteArray): Boolean = bytes.size >= 8 && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp"

    private fun java.io.InputStream.readNBytesCompat(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = read(buffer, filled, count - filled)
            if (read < 0) break
            filled += read
        }
        return buffer.copyOf(filled)
    }

    // -------------------------------- DramaWave --------------------------------

    private suspend fun dramaWaveVideos(reference: String): List<Video> {
        // <server>/<lang>/<id>/<episode>
        val parts = reference.split('/')
        if (parts.size != 4) throw Exception("Arvin-DF could not read this DramaWave episode")
        val (server, language, id, episode) = parts
        val source = watchSource(id, episode, server.toIntOrNull() ?: 1, language)

        val subtitles = source.subtitle_options.mapNotNull { option ->
            val link = option.url?.let(Routes::subtitleUrl) ?: return@mapNotNull null
            Track(link, option.label ?: option.lang_code ?: "Subtitle")
        }.sortedByDescending { it.lang.startsWith("English") }

        // The direct host can refuse; the site's own relay serves the same stream.
        var failure: Exception? = null
        for (candidate in listOfNotNull(source.url, source.proxy_rescue_url).distinct()) {
            val master = candidate.toHttpUrlOrNull() ?: continue
            try {
                val text = client.newCall(GET(master, headers)).await().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body.string()
                }
                return dramaWaveStreams(text, master, subtitles)
            } catch (e: IOException) {
                failure = e
            }
        }
        throw Exception("DramaWave's media host did not answer (${failure?.message ?: "no stream"}). Try the other server.")
    }

    private fun dramaWaveStreams(playlist: String, master: HttpUrl, subtitles: List<Track>): List<Video> {
        val info = HlsMaster.parse(playlist, master)
        // The master itself is always correct: it carries the separate audio rendition and lets the
        // player adapt. The single-quality entries below hand that audio over explicitly.
        val auto = Video(videoUrl = master.toString(), videoTitle = "Auto (adaptive)", headers = headers, subtitleTracks = subtitles)
        if (info.variants.isEmpty()) return listOf(auto)
        val audio = info.audio.map { Track(it.url, it.name) }
        return listOf(auto) + info.variants.map { variant ->
            Video(
                videoUrl = variant.url,
                videoTitle = variant.label,
                headers = headers,
                subtitleTracks = subtitles,
                audioTracks = audio,
            )
        }
    }

    /** Asks the source API on each of its hosts in turn; both answered identically when tested. */
    private suspend fun watchSource(id: String, episode: String, server: Int, language: String): WatchSourceDataDto {
        var message: String? = null
        for (endpoint in Routes.WATCH_SOURCE_HOSTS) {
            val url = Routes.watchSource(endpoint, id, episode, server, language)
            val result = try {
                client.newCall(GET(url, headers)).await().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body.string().parseAs<WatchSourceDto>()
                }
            } catch (e: IOException) {
                null
            } catch (e: SerializationException) {
                null
            } ?: continue
            val data = result.data
            if (result.status == "success" && data != null && !data.url.isNullOrBlank()) return data
            message = result.message
        }
        throw Exception(message?.takeIf { it.isNotBlank() } ?: "DramaWave has no source for episode $episode on server $server right now")
    }

    // ================================ Helpers ================================

    private class DramaWaveRef(val lang: String, val id: String, val episode: String?)

    private fun dramaWaveRef(url: String): DramaWaveRef {
        val parts = url.removePrefix(DW_PREFIX).split('/')
        if (parts.size < 2) throw Exception("Arvin-DF could not read this DramaWave title")
        return DramaWaveRef(parts[0], parts[1], parts.getOrNull(2))
    }

    /**
     * DramaWave's pages sit behind a Cloudflare check, which the host's Cloudflare-aware client answers
     * by loading the page in a WebView. It is kept to those pages: the Abyss storage calls use the plain
     * client, so a refused request there can never open a WebView.
     */
    private val pageClient by lazy { network.cloudflareClient }

    private val portalSource by lazy { PortalSource(client, { pageClient }, headers) }

    /** Fetches a page as HTML, and says plainly when a Cloudflare check answered instead. */
    private suspend fun document(url: HttpUrl): Document = (if (url.host == DRAMAWAVE_HOST) pageClient else client).newCall(GET(url, headers)).await().use { response ->
        val body = response.body.string()
        if ((response.code == 403 || response.code == 503) && isChallenge(body)) throw Exception(CHALLENGE_MESSAGE)
        if (!response.isSuccessful) throw Exception("${url.host} answered HTTP ${response.code}")
        Jsoup.parse(body, url.toString())
    }

    private fun isChallenge(body: String): Boolean = body.contains("Just a moment", ignoreCase = true) ||
        body.contains("challenges.cloudflare.com", ignoreCase = true)

    private fun absolute(path: String): String = if (path.startsWith("http://") || path.startsWith("https://")) path else baseUrl + path

    private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()

    companion object {
        private const val DW_PREFIX = "dw/"
        private const val ABYSS_PREFIX = "abyss:"
        private const val DEFAULT_LANGUAGE = "en-US"
        private const val DRAMAWAVE_HOST = "dramawave.dramafren.org"

        private const val CHALLENGE_MESSAGE =
            "DramaWave asked for a Cloudflare check. Open this source in WebView, let the page load, then try again."
    }
}
