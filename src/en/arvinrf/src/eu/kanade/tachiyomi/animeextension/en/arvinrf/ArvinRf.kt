package eu.kanade.tachiyomi.animeextension.en.arvinrf

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

/**
 * ReelFren (reelfren.net) is a front end over an open JSON API at api.reelfren.net that fronts
 * dozens of short-drama apps ("providers"). The website sits behind a Cloudflare challenge but the
 * API does not, so this source talks to the API only and never loads the website.
 *
 * Every title is identified by a provider plus a provider-specific id, so both travel together:
 * `SAnime.url` is `provider/id` and `SEpisode.url` is `provider/id/episode`.
 */
class ArvinRf :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Arvin-RF"

    // Only used to build "open in browser" links; no request in this class goes to it.
    override val baseUrl = "https://reelfren.net"

    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    // ============================ Browse / search ============================

    override fun popularAnimeRequest(page: Int): Request = feedRequest(preferences.providerPref, page, popular = true)

    override fun popularAnimeParse(response: Response): AnimesPage = parseFeed(response)

    /** Providers whose `popular` feed answered an empty first page (ReelShort did, 4 of 4 tries). */
    private val noPopular = java.util.Collections.synchronizedSet(HashSet<String>())

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val provider = preferences.providerPref
        if (provider !in noPopular) {
            val result = client.newCall(popularAnimeRequest(page)).awaitSuccess().use { popularAnimeParse(it) }
            if (result.animes.isNotEmpty() || page > 1) return result
            noPopular.add(provider)
        }
        // Fall back to the default listing so the tab is not blank for such a provider.
        return client.newCall(feedRequest(provider, page, popular = false)).awaitSuccess().use { popularAnimeParse(it) }
    }

    // Explore's "All Regions" listing omits provider and feed, mixing titles across providers.
    // Keep it independent of Popular's selected provider instead of repeating that provider's home.
    override fun latestUpdatesRequest(page: Int): Request = feedRequest(null, page, popular = false)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseFeed(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(ProviderFilter())

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val index = filters.filterIsInstance<ProviderFilter>().firstOrNull()?.state ?: 0
        val provider = ARVIN_RF_PROVIDERS.getOrNull(index - 1)?.id

        // Searching all providers at once is the point of a search box, but an empty query across
        // all of them is a 350 KB mixed dump, so browsing falls back to the chosen provider.
        if (query.isBlank()) return feedRequest(provider ?: preferences.providerPref, page, popular = true)

        val url = API_URL.toHttpUrl().newBuilder()
            .addPathSegments("api/search")
            .addQueryParameter("q", query.trim())
            .addQueryParameter("lang", LANG)
            .addQueryParameter("offset", (page - 1).toString())
            .apply { if (provider != null) addQueryParameter("provider", provider) }
            .build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseFeed(response)

    private fun feedRequest(provider: String?, page: Int, popular: Boolean): Request = GET(feedUrl(provider, page, popular), headers)

    /**
     * Several providers ignore `offset` past the first page and answer the same titles again
     * (DramaBox repeats its 20 forever). Remembering what a listing already showed lets the list
     * stop instead of scrolling the same rows indefinitely.
     */
    private val seenLock = Any()
    private val seen = HashMap<String, MutableSet<String>>()

    private fun unseen(key: String, page: Int, titles: List<TitleDto>): List<TitleDto> = synchronized(seenLock) {
        val known = if (page == 1) {
            if (seen.size >= MAX_TRACKED_LISTINGS) seen.clear()
            HashSet<String>().also { seen[key] = it }
        } else {
            seen.getOrPut(key) { HashSet() }
        }
        titles.filter { known.add("${it.provider}/${it.id}") }
    }

    private fun parseFeed(response: Response): AnimesPage {
        val url = response.request.url
        val page = (url.queryParameter("offset")?.toIntOrNull() ?: 0) + 1
        val feed = response.parseAs<FeedDto>()

        val fresh = unseen(feedKey(url), page, feed.data)

        val shown = fresh.filter { it.provider in SUPPORTED_IDS && !it.title.isNullOrBlank() }
        // Paging stops when a page brings nothing new. The decision uses the unfiltered page so a
        // search whose page happens to hold only unsupported providers does not end early.
        val hasNext = fresh.isNotEmpty() && feed.hasMore != false
        return AnimesPage(shown.map { it.toSAnime() }, hasNext)
    }

    private fun TitleDto.toSAnime(): SAnime = SAnime.create().apply {
        title = this@toSAnime.title.orEmpty()
        url = "$provider/$id"
        thumbnail_url = cover?.let(::absolute)
    }

    // ================================ Details ================================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/drama/${anime.url}?lang=$LANG"

    override fun animeDetailsRequest(anime: SAnime): Request {
        val (provider, id) = splitTitle(anime.url)
        val url = API_URL.toHttpUrl().newBuilder()
            .addPathSegments("api/detail")
            .addQueryParameter("provider", provider)
            .addQueryParameter("id", id)
            .addQueryParameter("lang", LANG)
            .build()
        return GET(url, headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val provider = response.request.url.queryParameter("provider").orEmpty()
        val detail = response.parseAs<DetailDto>()
        return SAnime.create().apply {
            detail.title?.let { title = it }
            description = detail.intro
            genre = providerLabel(provider)
            thumbnail_url = detail.cover?.let(::absolute)
        }
    }

    // ================================ Episodes ================================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val (provider, id) = splitTitle(anime.url)
        val detail = client.newCall(animeDetailsRequest(anime)).awaitSuccess().parseAs<DetailDto>()

        // The detail page is the episode catalogue: the video endpoint's own `totalEpisodes` can be
        // far lower (JoyReels reports 1 for a 50-episode title) and must not be used for this.
        val numbers = detail.videos.filter { it.available }.map { it.episode }
            .ifEmpty { (1..(detail.episodes ?: 1)).toList() }

        return numbers.sortedDescending().map { number ->
            SEpisode.create().apply {
                name = "Episode $number"
                episode_number = number.toFloat()
                url = "$provider/$id/$number"
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used")

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException("Not used")

    // ============================== Video links ==============================

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException("Not used")

    /** One hoster, named after the provider the title comes from. Streams are resolved lazily. */
    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(hosterUrl = episode.url, hosterName = providerLabel(episode.url.substringBefore('/'))),
    )

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val provider = hoster.hosterUrl.substringBefore('/')
        val rest = hoster.hosterUrl.substringAfter('/')
        val response = fetchVideo(provider, rest.substringBeforeLast('/'), rest.substringAfterLast('/'))

        val subtitles = response.subtitles.mapNotNull { subtitle ->
            val url = subtitle.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Track(absolute(url), subtitle.label ?: subtitle.srclang ?: "Subtitle")
        }

        // Entries with a `kid` are decrypted by the website's own script and are not playable as is.
        val sources = if (response.qualityList.isEmpty()) {
            listOfNotNull(response.videoUrl?.takeIf { it.isNotBlank() }?.let { it to "Video" })
        } else {
            response.qualityList
                .filter { it.kid == null }
                .mapNotNull { quality ->
                    quality.url?.takeIf { it.isNotBlank() }?.let { it to (quality.label ?: "Video") }
                }
        }

        val videos = sources
            .map { (url, label) -> absolute(url) to label }
            .distinctBy { it.first }
            .map { (url, label) ->
                // No Referer or Origin on purpose: KalosTV and HappyShort answer 403 when one is sent.
                Video(videoUrl = url, videoTitle = label, headers = headers, subtitleTracks = subtitles)
            }

        if (videos.isEmpty()) {
            throw Exception(
                if (response.locked) {
                    "This episode is locked on ReelFren"
                } else {
                    "ReelFren returned no playable stream for this episode (keyed or unavailable)"
                },
            )
        }
        return videos.sortedByQuality()
    }

    /**
     * The video endpoint sometimes answers 502 and then succeeds moments later (observed on
     * RapidTV 2 and FlexTV 2), so a failed call is retried before giving up.
     */
    private suspend fun fetchVideo(provider: String, id: String, episode: String): VideoDto {
        val url = API_URL.toHttpUrl().newBuilder()
            .addPathSegments("api/video")
            .addQueryParameter("provider", provider)
            .addQueryParameter("id", id)
            .addQueryParameter("ep", episode)
            .addQueryParameter("lang", LANG)
            .addQueryParameter("server", "1")
            // The website's own version marker for this endpoint (cv=v21 in its player bundle).
            // If streams ever stop resolving, check whether the site bumped it.
            .addQueryParameter("cv", CLIENT_VERSION)
            .build()

        var failure: Exception? = null
        repeat(VIDEO_ATTEMPTS) { attempt ->
            try {
                return client.newCall(GET(url, headers)).awaitSuccess().parseAs<VideoDto>()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure = e
                if (attempt < VIDEO_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
            }
        }
        throw failure ?: IllegalStateException("No response from ReelFren")
    }

    private fun List<Video>.sortedByQuality(): List<Video> {
        val preferred = preferences.qualityPref.toIntOrNull()
        return sortedWith(
            compareByDescending<Video> { heightOf(it.videoTitle) == preferred }
                .thenByDescending { heightOf(it.videoTitle) ?: 0 },
        )
    }

    private fun heightOf(label: String): Int? = QUALITY_REGEX.find(label)?.groupValues?.get(1)?.toIntOrNull()

    // ================================ Settings ================================

    private val SharedPreferences.providerPref by preferences.delegate(PREF_PROVIDER_KEY, PREF_PROVIDER_DEFAULT)
    private val SharedPreferences.qualityPref by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_PROVIDER_KEY,
            title = "Provider shown in Popular",
            entries = ARVIN_RF_PROVIDERS.map { it.label },
            entryValues = ARVIN_RF_PROVIDERS.map { it.id },
            default = PREF_PROVIDER_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = listOf("1080p", "720p", "540p", "480p"),
            entryValues = listOf("1080", "720", "540", "480"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    // ================================ Helpers ================================

    private class ProviderFilter :
        AnimeFilter.Select<String>(
            "Provider",
            arrayOf("All providers") + ARVIN_RF_PROVIDERS.map { it.label }.toTypedArray(),
        )

    private fun splitTitle(url: String): Pair<String, String> = url.substringBefore('/') to url.substringAfter('/')

    private fun providerLabel(id: String): String = ARVIN_RF_PROVIDERS.firstOrNull { it.id == id }?.label ?: id

    /** Site-relative `/api/...` paths in responses point at the API or its stream proxy host. */
    private fun absolute(url: String): String = when {
        url.startsWith("/api/proxy/") -> PROXY_URL + url
        url.startsWith("/api/") -> API_URL + url
        else -> url
    }

    companion object {
        private const val API_URL = "https://api.reelfren.net"
        private const val PROXY_URL = "https://proxy.reelfren.net"
        private const val LANG = "en"
        private const val CLIENT_VERSION = "v21"
        private const val WETV_CATEGORY = "10240"

        private const val VIDEO_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 700L
        private const val MAX_TRACKED_LISTINGS = 32

        private val SUPPORTED_IDS = ARVIN_RF_PROVIDERS.map { it.id }.toSet()
        private val QUALITY_REGEX = Regex("""(\d{3,4})\s*p""", RegexOption.IGNORE_CASE)

        private const val PREF_PROVIDER_KEY = "default_provider"
        private const val PREF_PROVIDER_DEFAULT = "mydrama"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        internal fun feedUrl(provider: String?, page: Int, popular: Boolean): HttpUrl = API_URL.toHttpUrl().newBuilder()
            .addPathSegments("api/home")
            // `offset` is a page index, not an item offset.
            .addQueryParameter("offset", (page - 1).toString())
            .addQueryParameter("lang", LANG)
            .apply {
                if (provider != null) addQueryParameter("provider", provider)
                if (popular) addQueryParameter("feed", "popular")
                // WeTV's plain home feed answers 502; only its anime category (10240) loads.
                if (provider == "wetv") addQueryParameter("category", WETV_CATEGORY)
            }
            .build()

        internal fun feedKey(url: HttpUrl): String = listOf(
            url.encodedPath,
            url.queryParameter("provider").orEmpty(),
            url.queryParameter("feed").orEmpty(),
            url.queryParameter("category").orEmpty(),
            url.queryParameter("lang").orEmpty(),
            url.queryParameter("q").orEmpty(),
        ).joinToString("|")
    }
}
