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
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

/**
 * Arvin-DF covers the subsites of dramafren.org, not the WordPress site itself:
 *
 *  - **DramaWave** (dramawave.dramafren.org) has its own titles and a JSON source API. Its pages are
 *    behind a Cloudflare check that the host's client answers the way the app does (open the source in
 *    WebView once); when that is not enough this says so. The source API on the CDN hosts answers openly
 *    and returns an HLS master whose audio is a separate rendition.
 *  - **Provider portals.** dramafren.org links to a set of provider portals (DramaBox, GoodShort,
 *    NetShort, FlickReels, ReelShort and others) that have their own titles. The ones that answered in
 *    the capture are browsable through the Catalogue filter; see [Portals] and [PortalSource].
 *
 * `SAnime.url` is `dw/<lang>/<id>` for DramaWave, or `p/<portal>/<reference>` for a provider portal.
 * `SEpisode.url` is `dw/<lang>/<id>/<episode>` or `p/<portal>/<reference>`. A library entry saved by an
 * older version from the main site (`/series/...`, `/watch/...`) has neither shape and says so.
 */
class ArvinDf : AnimeHttpSource() {

    override val name = "Arvin-DF"

    override val baseUrl = Routes.DRAMAWAVE

    override val lang = "en"

    // DramaWave's only browse feed is its Recently Watched list, which is what Popular shows. A Latest
    // tab would repeat it.
    override val supportsLatest = false

    // ============================ Browse / search ============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = dramaWaveListing(page, DEFAULT_LANGUAGE, null)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Pick a catalogue. Language applies to DramaWave; the last two apply to TvSeries."),
        CatalogueFilter(),
        LanguageFilter(),
        TvFeedFilter(),
        TvSearchTypeFilter(),
    )

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val text = query.trim().ifEmpty { null }
        val tv = TvOptions(
            feed = filters.filterIsInstance<TvFeedFilter>().firstOrNull()?.feed ?: TvFeed.HOME,
            searchType = filters.filterIsInstance<TvSearchTypeFilter>().firstOrNull()?.subjectType ?: 2,
        )
        filters.filterIsInstance<CatalogueFilter>().firstOrNull()?.portal?.let { portal -> return portalListing(portal, page, text, tv) }
        val language = filters.filterIsInstance<LanguageFilter>().firstOrNull()?.code ?: DEFAULT_LANGUAGE
        return dramaWaveListing(page, language, text)
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

    private suspend fun portalListing(portal: Portal, page: Int, query: String?, tv: TvOptions): AnimesPage {
        val result = portalSource.listing(portal, page, query, tv)
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
        parsePortalRef(anime.url)?.let { (portal, ref) -> return portal.webUrl(ref) }
        if (anime.url.startsWith(DW_PREFIX)) {
            val ref = dramaWaveRef(anime.url)
            return Routes.dramaWaveDetail(ref.id, ref.lang).toString()
        }
        return baseUrl
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
        if (!anime.url.startsWith(DW_PREFIX)) throw mainSiteEntry()
        val ref = dramaWaveRef(anime.url)
        val page = document(Routes.dramaWaveDetail(ref.id, ref.lang))
        return SAnime.create().apply {
            DramaWaveParser.title(page)?.let { title = it }
            DramaWaveParser.poster(page)?.let { thumbnail_url = it }
            description = DramaWaveParser.description(page)
            status = SAnime.UNKNOWN
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException("Not used")

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
        if (!anime.url.startsWith(DW_PREFIX)) throw mainSiteEntry()
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

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used")

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException("Not used")

    // ============================== Video links ==============================

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException("Not used")

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        parsePortalRef(episode.url)?.let { (portal, _) ->
            return listOf(Hoster(hosterUrl = episode.url, hosterName = portal.label))
        }
        if (!episode.url.startsWith(DW_PREFIX)) throw mainSiteEntry()
        val ref = dramaWaveRef(episode.url)
        val number = ref.episode ?: throw Exception("This DramaWave episode has no number")
        return listOf(1, 2).map { server ->
            Hoster(hosterUrl = "$DW_PREFIX$server/${ref.lang}/${ref.id}/$number", hosterName = "DramaWave server $server")
        }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> = when {
        hoster.hosterUrl.startsWith(DW_PREFIX) -> dramaWaveVideos(hoster.hosterUrl.removePrefix(DW_PREFIX))
        hoster.hosterUrl.startsWith(PORTAL_PREFIX) -> {
            val (portal, ref) = parsePortalRef(hoster.hosterUrl) ?: throw Exception("Arvin-DF does not know this portal")
            portalSource.videos(portal, ref)
        }
        else -> throw Exception("Arvin-DF does not know this player")
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

    /** What an entry saved from the old main-site catalogue gets, instead of a confusing network error. */
    private fun mainSiteEntry() = Exception(
        "This title was saved from the dramafren.org main site, which Arvin-DF no longer covers. " +
            "Remove it from your library and look it up under DramaWave or a provider portal.",
    )

    /**
     * The subsites' pages sit behind a Cloudflare check, which the host's Cloudflare-aware client answers
     * by loading the page in a WebView. It is kept to those pages: the media and resolver calls use the
     * plain client, so a refused request there can never open a WebView.
     */
    private val pageClient by lazy { network.cloudflareClient }

    private val portalSource by lazy { PortalSource(client, { pageClient }, headers) }

    /** Fetches a DramaWave page as HTML, and says plainly when a Cloudflare check answered instead. */
    private suspend fun document(url: HttpUrl): Document = pageClient.newCall(GET(url, headers)).await().use { response ->
        val body = response.body.string()
        if (Cloudflare.isChallenge(response.code, body)) throw Exception(Cloudflare.message(url.host))
        if (!response.isSuccessful) throw Exception("${url.host} answered HTTP ${response.code}")
        Jsoup.parse(body, url.toString())
    }

    private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()

    companion object {
        private const val DW_PREFIX = "dw/"
        private const val DEFAULT_LANGUAGE = "en-US"
    }
}
