package eu.kanade.tachiyomi.animeextension.en.arvinnd

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import aniyomi.lib.playlistutils.PlaylistUtils
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
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Narto Drama (narto-drama.com) aggregates dozens of short-drama providers behind one Laravel site.
 * Its catalogue and search endpoints answer anonymously with JSON; titles and episodes are plain HTML
 * pages with the episode sources embedded as a JavaScript literal. Nothing here needs a login, a
 * cookie or a browser challenge, and the site's home page (which does show a cookie interstitial) is
 * never requested.
 *
 * `SAnime.url` is a site-relative link: either the `/search/import?...` link of a provider card,
 * which redirects to the local detail page, or a `/detail/watch/...` link from search.
 * `SEpisode.url` is the site-relative link of one episode page.
 */
class ArvinNd :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Arvin-ND"

    override val baseUrl = "https://narto-drama.com"

    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val playlists by lazy { PlaylistUtils(client, headers) }

    private val xhrHeaders by lazy {
        headers.newBuilder()
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Accept", "application/json")
            .build()
    }

    // ============================ Browse / search ============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = browse(preferences.providerPref, page, Rail.POPULAR)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = browse(preferences.providerPref, page, Rail.LATEST)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Provider applies when the search box is empty."),
        ProviderFilter(),
    )

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val text = query.trim()
        if (text.isEmpty()) {
            val index = filters.filterIsInstance<ProviderFilter>().firstOrNull()?.state ?: 0
            val provider = ARVIN_ND_PROVIDERS.getOrNull(index - 1)?.id ?: preferences.providerPref
            return browse(provider, page, Rail.POPULAR)
        }

        // The site's own search needs at least two characters and answers one page of 50.
        if (text.length < MIN_QUERY_LENGTH || page > 1) return AnimesPage(emptyList(), false)

        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", text)
            .addQueryParameter("limit", SEARCH_LIMIT.toString())
            .addQueryParameter("lang", LANG)
            .build()
        val result = client.newCall(GET(url, xhrHeaders)).awaitSuccess().parseAs<SearchDto>()

        // The index mixes languages (about a third of the hits are Indonesian); show only English.
        val animes = result.items
            .filter { it.languageCode == null || it.languageCode == LANG }
            .mapNotNull { item ->
                val link = item.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SAnime.create().apply {
                    title = item.title.orEmpty().ifBlank { return@mapNotNull null }
                    this.url = relative(link)
                    thumbnail_url = item.posterUrl?.let(::absolute)
                    description = item.description
                }
            }
        return AnimesPage(animes, false)
    }

    private enum class Rail(val pattern: Regex) {
        POPULAR(Regex("hot|popular|trending|rank|top", RegexOption.IGNORE_CASE)),
        LATEST(Regex("new|latest|recent", RegexOption.IGNORE_CASE)),
    }

    /** Section keys are opaque and can change, so they are read from the response and only cached. */
    private val tabKeys = ConcurrentHashMap<String, String>()

    /**
     * Several providers answer later pages with the titles they already showed, so a listing
     * remembers what it has returned and stops when a page brings nothing new.
     */
    private val seenLock = Any()
    private val seen = HashMap<String, MutableSet<String>>()

    private suspend fun browse(provider: String, page: Int, rail: Rail): AnimesPage {
        val listing = "$provider|${rail.name}"
        val cards = if (page == 1) {
            val section = pickSection(fetchSections(provider).sections, rail)
            section?.tabKey?.let { tabKeys[listing] = it }
            section?.items.orEmpty()
        } else {
            val key = tabKeys[listing]
                ?: pickSection(fetchSections(provider).sections, rail)?.tabKey?.also { tabKeys[listing] = it }
            if (key == null) {
                emptyList()
            } else {
                fetchSections(provider, onlyTab = key, page = page).sections.firstOrNull()?.items.orEmpty()
            }
        }

        val fresh = synchronized(seenLock) {
            val known = if (page == 1) {
                if (seen.size >= MAX_TRACKED_LISTINGS) seen.clear()
                HashSet<String>().also { seen[listing] = it }
            } else {
                seen.getOrPut(listing) { HashSet() }
            }
            cards.filter { card -> card.watchUrl?.let(known::add) == true }
        }

        val animes = fresh.mapNotNull { card ->
            val link = card.watchUrl ?: return@mapNotNull null
            SAnime.create().apply {
                title = card.title.orEmpty().ifBlank { return@mapNotNull null }
                url = relative(link)
                thumbnail_url = card.posterUrl?.let(::absolute)
                description = card.description
                genre = (card.tagNames as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
                    ?.joinToString(", ")
                    ?.ifBlank { null }
            }
        }
        return AnimesPage(animes, fresh.isNotEmpty())
    }

    /** Skips the `fallback` sections: those are the site's own database filler, not the provider's rail. */
    private fun pickSection(sections: List<SectionDto>, rail: Rail): SectionDto? {
        val live = sections.filter { it.items.isNotEmpty() && it.fallback != true }
        return live.firstOrNull { it.tabLabel?.contains(rail.pattern) == true }
            ?: live.getOrNull(if (rail == Rail.LATEST) 1 else 0)
            ?: live.firstOrNull()
    }

    private suspend fun fetchSections(provider: String, onlyTab: String? = null, page: Int = 1): SectionsDto {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("home/providers/sections")
            .addQueryParameter("provider", provider)
            .addQueryParameter("lang", LOCALE)
            .addQueryParameter("target_lang", LOCALE)
            .apply {
                if (onlyTab != null) {
                    addQueryParameter("only_tab", onlyTab)
                    addQueryParameter("tab_pages[$onlyTab]", page.toString())
                }
            }
            .build()
        return client.newCall(GET(url, xhrHeaders)).awaitSuccess().parseAs<SectionsDto>()
    }

    // The abstract request/parse pairs are unused: every call above is a suspend override.
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException("Not used")

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    // ================================ Details ================================

    override fun getAnimeUrl(anime: SAnime): String = absolute(anime.url)

    // OkHttp follows the import redirect, so the response is already the local detail page.
    override fun animeDetailsRequest(anime: SAnime): Request = GET(absolute(anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val html = response.use { it.body.string() }
        requireNoBrowserCheck(html)
        val document = Jsoup.parse(html, response.request.url.toString())
        return SAnime.create().apply {
            document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }?.let { title = it }
            document.selectFirst(".movie-desc")?.text()?.takeIf { it.isNotBlank() }?.let { description = it }
            document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?.let { thumbnail_url = absolute(it) }
        }
    }

    // ================================ Episodes ================================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val response = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
        val path = response.request.url.encodedPath
        val html = response.use { it.body.string() }
        requireNoBrowserCheck(html)

        val links: List<Pair<Int, String>> = if (path.startsWith(DUMMY_PREFIX)) {
            // Providers the site has not imported open a "dummy" page whose own list holds every
            // episode; only that page's episode has a source, the others load theirs when opened.
            val base = path.substringBeforeLast('/')
            episodeEntries(html).mapNotNull { entry ->
                val number = entry.episodeNumber?.toInt() ?: return@mapNotNull null
                number to "$base/$number?lang=$LOCALE"
            }
        } else {
            val slug = path.substringAfter(WATCH_PREFIX).substringBefore('/')
            Regex("""${Regex.escape(WATCH_PREFIX + slug)}/(\d+)""").findAll(html)
                .map { it.groupValues[1].toInt() }
                .distinct()
                .map { number -> number to "$WATCH_PREFIX$slug/$number?lang=$LOCALE&from=home" }
                .toList()
        }
        if (links.isEmpty()) throw Exception("Narto Drama listed no episodes for this title")

        return links.sortedByDescending { it.first }.map { (number, link) ->
            SEpisode.create().apply {
                name = "Episode $number"
                episode_number = number.toFloat()
                url = link
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used")

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException("Not used")

    // ============================== Video links ==============================

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException("Not used")

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(Hoster(hosterUrl = episode.url, hosterName = "Narto Drama"))

    /**
     * Resolves an episode in the order the site's own player uses: the primary source, then the
     * site-hosted copy, then a fresh source from the page's `refresh-source` call. Every candidate is
     * checked by its actual bytes, because a stale source answers 410 or 403 and some fallbacks answer
     * 200 with a body that is not media at all.
     */
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val html = client.newCall(GET(absolute(hoster.hosterUrl), headers)).awaitSuccess().use { it.body.string() }
        requireNoBrowserCheck(html)

        // Each episode page lists every episode, but a fresh source is only guaranteed for the one the
        // page was opened for, so the entry is matched on the number in the link.
        val number = hoster.hosterUrl.substringBefore('?').substringAfterLast('/').toIntOrNull()
        val entry = episodeEntries(html).firstOrNull { it.episodeNumber?.toInt() == number }
        val subtitles = subtitleTracks(entry?.subtitles)

        val tried = HashSet<String>()
        val direct = videosFrom(listOf(entry?.playUrl, entry?.directPlayUrl, initialSource(html)), subtitles, tried)
        if (direct.isNotEmpty()) return direct.sortedByQuality()

        val refreshed = refreshSource(html, number)
        val repaired = videosFrom(
            listOf(refreshed?.playUrl, refreshed?.directPlayUrl),
            subtitleTracks(refreshed?.subtitles).ifEmpty { subtitles },
            tried,
        )
        if (repaired.isNotEmpty()) return repaired.sortedByQuality()

        val reason = refreshed?.error ?: refreshed?.message
        throw Exception(
            if (reason != null) {
                "Narto Drama could not supply a working source for this episode ($reason)"
            } else {
                "Narto Drama could not supply a working source for this episode"
            },
        )
    }

    private fun subtitleTracks(subtitles: List<SubtitleDto>?): List<Track> = subtitles.orEmpty()
        .mapNotNull { subtitle ->
            val link = subtitle.subtitleUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            subtitle.languageCode to Track(absolute(link), subtitle.label ?: subtitle.languageCode ?: "Subtitle")
        }
        .sortedByDescending { (code, _) -> code == LOCALE }
        .map { it.second }

    /** The streams of the first candidate that actually plays; [tried] keeps a source from being probed twice. */
    private suspend fun videosFrom(candidates: List<String?>, subtitles: List<Track>, tried: MutableSet<String>): List<Video> {
        for (candidate in candidates) {
            val link = candidate?.trim()?.takeIf { it.isNotEmpty() && tried.add(it) } ?: continue
            val videos = withContext(Dispatchers.IO) { resolveSource(absolute(link), subtitles) }
            if (videos.isNotEmpty()) return videos
        }
        return emptyList()
    }

    /**
     * Asks the site for a fresh source of one episode, exactly as its player does after a source dies.
     *
     * Everything needed is read from the page: the refresh base, the page-issued context (empty on
     * "dummy" pages, where the player leaves it out), and which host carries the call. The context is
     * passed on unchanged and never made up.
     */
    private suspend fun refreshSource(html: String, number: Int?): RefreshDto? {
        val url = number?.let { refreshUrl(html, it) } ?: return null
        return try {
            // The server answers a repair with its reason even on an error status, so the body is read either way.
            refreshClient.newCall(GET(url, xhrHeaders)).await().use { response ->
                response.body.string().parseAs<RefreshDto>()
            }
        } catch (e: IOException) {
            null
        } catch (e: SerializationException) {
            null
        }
    }

    private val refreshClient by lazy { client.newBuilder().callTimeout(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS).build() }

    private fun refreshUrl(html: String, episode: Int): HttpUrl? {
        val base = jsString(html, "refreshSourceBaseUrl")?.toHttpUrlOrNull() ?: return null
        val edge = jsString(html, "refreshSourceEdgeBase")?.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()
        val path = base.encodedPath.trimEnd('/') + "/$episode/refresh-source"

        // The page says which host serves the call: the edge host under `/e/rs`, CandyJar's edge host
        // on the plain path, or the site itself.
        val builder = when {
            edge != null && jsFlag(html, "refreshSourceUseEdge") -> edge.newBuilder().encodedPath("/e/rs$path")
            edge != null && jsFlag(html, "refreshSourceCandyjarEdgeDirect") -> edge.newBuilder().encodedPath(path)
            else -> base.newBuilder().encodedPath(path)
        }.query(null)

        base.queryParameterNames.forEach { name ->
            base.queryParameterValues(name).forEach { builder.addQueryParameter(name, it) }
        }
        jsString(html, "refreshSourceContextToken")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { builder.addQueryParameter("rs_ctx", it) }
        return builder.addQueryParameter("force", "1").build()
    }

    /**
     * Turns one candidate source into videos, or into nothing when it does not play.
     *
     * The source is an HLS master or a progressive file, and a link without an extension does not say
     * which, so the first bytes decide. A refused request is not an error here, only a reason to try the
     * next candidate, and so is a 200 whose body is a page or JSON instead of media.
     */
    private fun resolveSource(source: String, subtitles: List<Track>): List<Video> {
        val probe = headers.newBuilder().set("Range", "bytes=0-63").build()
        val head = try {
            client.newCall(GET(source, probe)).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                String(response.peekBody(HEAD_BYTES).bytes(), Charsets.ISO_8859_1).trimStart()
            }
        } catch (e: IOException) {
            return emptyList()
        }

        return when {
            head.contains("#EXTM3U") -> try {
                playlists.extractFromHls(
                    playlistUrl = source,
                    masterHeaders = headers,
                    videoHeaders = headers,
                    subtitleList = subtitles,
                )
            } catch (e: IOException) {
                emptyList()
            }
            head.isEmpty() || head.startsWith("<") || head.startsWith("{") -> emptyList()
            else -> listOf(Video(videoUrl = source, videoTitle = "Video", headers = headers, subtitleTracks = subtitles))
        }
    }

    private fun List<Video>.sortedByQuality(): List<Video> {
        val preferred = preferences.qualityPref.toIntOrNull()
        return sortedWith(
            compareByDescending<Video> { heightOf(it.videoTitle) == preferred }
                .thenByDescending { heightOf(it.videoTitle) ?: 0 },
        )
    }

    private fun heightOf(label: String): Int? = QUALITY_REGEX.find(label)?.groupValues?.get(1)?.toIntOrNull()

    // ================================ Page parsing ================================

    /** The `episodeItemsRaw` JavaScript array of an episode or dummy page, as plain JSON. */
    private fun episodeEntries(html: String): List<EpisodeEntryDto> = extractJsonArray(html, "episodeItemsRaw")
        ?.parseAs<List<EpisodeEntryDto>>()
        .orEmpty()

    private fun initialSource(html: String): String? = jsString(html, "initialSourceUrl")

    /** A string constant the page declares (`const name = "..."`), decoded from its JSON form. */
    private fun jsString(html: String, name: String): String? = Regex("""\b${Regex.escape(name)}\s*=\s*("(?:[^"\\]|\\.)*")""")
        .find(html)?.groupValues?.get(1)
        ?.parseAs<String>()
        ?.takeIf { it.isNotBlank() }

    private fun jsFlag(html: String, name: String): Boolean = Regex("""\b${Regex.escape(name)}\s*=\s*(true|false)""")
        .find(html)?.groupValues?.get(1) == "true"

    /** Returns the bracketed array assigned to [name], matching brackets outside of string literals. */
    private fun extractJsonArray(html: String, name: String): String? {
        val start = Regex("""\b${Regex.escape(name)}\s*=\s*\[""").find(html)?.range?.last ?: return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until html.length) {
            val c = html[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> if (--depth == 0) return html.substring(start, index + 1)
                }
            }
        }
        return null
    }

    /** The home page answers a cookie interstitial instead of content when the site wants a browser. */
    private fun requireNoBrowserCheck(html: String) {
        if (BROWSER_CHECK in html) throw Exception("Narto Drama asked for a browser check; open it in WebView once and retry")
    }

    // ================================ Settings ================================

    private val SharedPreferences.providerPref by preferences.delegate(PREF_PROVIDER_KEY, PREF_PROVIDER_DEFAULT)
    private val SharedPreferences.qualityPref by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_PROVIDER_KEY,
            title = "Provider shown in Popular and Latest",
            entries = ARVIN_ND_PROVIDERS.map { it.label },
            entryValues = ARVIN_ND_PROVIDERS.map { it.id },
            default = PREF_PROVIDER_DEFAULT,
            summary = "%s",
        )
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = listOf("1080p", "720p", "480p"),
            entryValues = listOf("1080", "720", "480"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    // ================================ Helpers ================================

    private class ProviderFilter :
        AnimeFilter.Select<String>(
            "Provider",
            arrayOf("Default (from settings)") + ARVIN_ND_PROVIDERS.map { it.label }.toTypedArray(),
        )

    private fun absolute(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> baseUrl + (if (url.startsWith("/")) url else "/$url")
    }

    private fun relative(url: String): String = url.removePrefix(baseUrl)

    companion object {
        private const val LANG = "en"
        private const val LOCALE = "en-US"
        private const val WATCH_PREFIX = "/detail/watch/"
        private const val DUMMY_PREFIX = "/detail/dummy/"
        private const val BROWSER_CHECK = "Checking your browser"

        private const val MIN_QUERY_LENGTH = 2
        private const val SEARCH_LIMIT = 50
        private const val MAX_TRACKED_LISTINGS = 32
        private const val HEAD_BYTES = 32L

        private val QUALITY_REGEX = Regex("""(\d{3,4})\s*p""", RegexOption.IGNORE_CASE)
        private const val REFRESH_TIMEOUT_SECONDS = 100L

        private const val PREF_PROVIDER_KEY = "default_provider"
        private const val PREF_PROVIDER_DEFAULT = "anyreel"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}
