package eu.kanade.tachiyomi.animeextension.en.arvinkd

import android.content.SharedPreferences
import android.util.Log
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
import keiyoushi.utils.addListPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * KiligDrama (kiligdrama.com) aggregates short-drama sources behind one Next.js site. Catalogue,
 * detail and episode pages are server-rendered HTML; nothing here is a JSON catalogue API.
 *
 * Every page, and `/api/episode-source`, sits behind a Cloudflare challenge. This source does not try to
 * answer it. The host's regular client already deals with Cloudflare the way the app does, with the
 * clearance the user earns by opening the site once in the app's WebView, and when the site still answers
 * the challenge this source says so instead of returning an empty list.
 *
 * `SAnime.url` is a site path (`/en/drama/<slug>`). `SEpisode.url` is the episode path
 * (`/en/drama/<slug>/episode-<n>`), which is the page the stream is read from.
 */
class ArvinKd :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Arvin-KD"

    override val baseUrl = "https://kiligdrama.com"

    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val playlists by lazy { PlaylistUtils(client, headers) }

    // ============================ Browse / search ============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = listing(popularPath(page))

    override suspend fun getLatestUpdates(page: Int): AnimesPage = listing(pagePath("/en/genre/new-releases", page))

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply when the search box is empty. Search ignores them."),
        GenreFilter(),
        SourceFilter(),
    )

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val text = query.trim()
        if (text.isNotEmpty()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments("en/search")
                .addQueryParameter("q", text)
                .apply { if (page > 1) addQueryParameter("page", page.toString()) }
                .build()
            return listing(url.encodedPath + "?" + url.encodedQuery)
        }

        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()?.state?.let { KD_GENRES.getOrNull(it - 1) }
        val source = filters.filterIsInstance<SourceFilter>().firstOrNull()?.state?.let { KD_SOURCES.getOrNull(it - 1) }
        return when {
            // The site has no combined genre-and-source route, so a chosen source wins.
            source != null -> listing(pagePath("/en/studio/${source.slug}", page))
            genre != null -> listing(pagePath("/en/genre/${genre.slug}", page))
            else -> listing(popularPath(page))
        }
    }

    private fun popularPath(page: Int): String = pagePath("/en/genre/popular", page)

    private fun pagePath(base: String, page: Int): String = if (page <= 1) base else "$base/page/$page"

    private suspend fun listing(path: String): AnimesPage {
        val page = Regex("""(?:/page/|[?&]page=)(\d+)""").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val html = fetch(path)
        val parsed = KdParser.parseListing(html, page)
        if (parsed.cards.isEmpty() && page == 1) {
            throw Exception("KiligDrama returned no titles for this list. Open the site once in WebView and retry.")
        }
        return AnimesPage(
            parsed.cards.map { card ->
                SAnime.create().apply {
                    title = card.title
                    url = card.path
                    thumbnail_url = card.thumbnail
                }
            },
            parsed.hasNext,
        )
    }

    // The abstract request/parse pairs are unused: every call above is a suspend override.
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException("Not used")

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    // ================================ Details ================================

    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, pageHeaders)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val detail = KdParser.parseDetail(fetch(anime.url))
        return SAnime.create().apply {
            detail.title?.let { title = it }
            detail.description?.let { description = it }
            detail.thumbnail?.let { thumbnail_url = it }
            detail.source?.let { genre = it }
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException("Not used")

    // ================================ Episodes ================================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = KdParser.parseEpisodes(fetch(anime.url), anime.url)
        if (episodes.isEmpty()) throw Exception("KiligDrama listed no episodes for this title")
        return episodes.sortedByDescending { it.first }.map { (number, path) ->
            SEpisode.create().apply {
                name = "Episode $number"
                episode_number = number.toFloat()
                url = path
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used")

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException("Not used")

    // ============================== Video links ==============================

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException("Not used")

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(Hoster(hosterUrl = episode.url, hosterName = "KiligDrama"))

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val html = fetch(hoster.hosterUrl)
        val player = KdParser.parsePlayer(html) ?: throw Exception("KiligDrama's episode page carried no player data")

        val requested = Regex("""/episode-(\d+)$""").find(hoster.hosterUrl)?.groupValues?.get(1)?.toIntOrNull()
        val serial = requested?.let { it - 1 + (player.firstSerial ?: 1) }
        val initial = player.source?.takeIf { player.currentSerial == null || serial == null || player.currentSerial == serial }

        var lastProblem: String? = null
        val videos = resolveKdPlayback(
            initial = initial,
            refresh = if (player.bookId != null && serial != null) {
                {
                    Log.i(TAG, "Refreshing stream for ${hoster.hosterUrl.substringBefore('?')}")
                    val body = fetch(
                        "/api/episode-source/${player.bookId}/$serial?lang=$LANG&refresh=1",
                        json = true,
                        forceNetwork = true,
                        referer = hoster.hosterUrl,
                    )
                    KdParser.parseDescriptor(body).also {
                        if (it == null) lastProblem = "the refreshed episode source was missing"
                    }
                }
            } else {
                null
            },
            resolve = { descriptor ->
                // A refreshed descriptor can also replace an expired subtitle URL.
                val subtitles = listOfNotNull(
                    descriptor.subtitle?.enc?.let(KdCipher::open)?.let { Track(kdMediaUrl(it), subtitleLabel(descriptor.subtitle.language)) },
                )
                var resolved: List<Video> = emptyList()
                for (entry in descriptor.entries()) {
                    val link = KdCipher.open(entry.enc)
                    if (link == null) {
                        lastProblem = "a source link did not open (the site may have rotated its key)"
                        Log.w(TAG, "Could not unwrap ${entry.source ?: entry.type ?: "source"} link")
                        continue
                    }
                    resolved = withContext(Dispatchers.IO) { resolve(link, subtitles) }
                    if (resolved.isNotEmpty()) break
                    lastProblem = "${entry.source ?: entry.type ?: "a source"} refused the request"
                }
                resolved
            },
        )
        if (videos.isNotEmpty()) return videos.sortedByQuality()
        throw Exception("KiligDrama could not supply a playable source: ${lastProblem ?: "none was listed"}")
    }

    /**
     * Turns one unwrapped link into videos, or into nothing when it does not play.
     *
     * ShortTV's upstream denies direct requests, so it follows the same media relay as the site's
     * player. Other providers keep their existing direct URLs. A refusal moves on to the next
     * source in the chain, then allows one descriptor refresh.
     */
    private fun resolve(link: String, subtitles: List<Track>): List<Video> {
        val playbackUrl = kdMediaUrl(link)
        val videoHeaders = kdMediaHeaders(headers, baseUrl, playbackUrl)
        val probe = videoHeaders.newBuilder().set("Range", "bytes=0-63").build()
        val head = try {
            client.newCall(GET(playbackUrl, probe)).execute().use { response ->
                // Keep signed URLs and their tokens out of logs.
                Log.i(TAG, "Stream probe HTTP ${response.code}: ${response.request.url.host}")
                if (!response.isSuccessful) return emptyList()
                String(response.peekBody(HEAD_BYTES).bytes(), Charsets.ISO_8859_1).trimStart()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Stream probe failed: ${e.javaClass.simpleName}")
            return emptyList()
        }
        return when {
            head.contains("#EXTM3U") -> try {
                playlists.extractFromHls(
                    playlistUrl = playbackUrl,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                    subtitleList = subtitles,
                )
            } catch (e: IOException) {
                Log.w(TAG, "HLS playlist failed: ${e.javaClass.simpleName}")
                emptyList()
            }
            head.isEmpty() || head.startsWith("<") || head.startsWith("{") -> emptyList()
            else -> listOf(Video(videoUrl = playbackUrl, videoTitle = "Video", headers = videoHeaders, subtitleTracks = subtitles))
        }
    }

    private fun subtitleLabel(language: String?): String = when {
        language.isNullOrBlank() -> "Subtitles"
        language.startsWith("en", ignoreCase = true) -> "English"
        else -> language
    }

    private fun List<Video>.sortedByQuality(): List<Video> {
        val preferred = preferences.qualityPref.toIntOrNull()
        return sortedWith(
            compareByDescending<Video> { heightOf(it.videoTitle) == preferred }
                .thenByDescending { heightOf(it.videoTitle) ?: 0 },
        )
    }

    private fun heightOf(label: String): Int? = QUALITY_REGEX.find(label)?.groupValues?.get(1)?.toIntOrNull()

    // ================================ Network ================================

    private val pageHeaders: Headers by lazy {
        headers.newBuilder().set("Accept", "text/html,application/json;q=0.9,*/*;q=0.8").build()
    }

    /** Fetches a site path as text, and says so plainly when the site answers a challenge instead of content. */
    private suspend fun fetch(path: String, json: Boolean = false, forceNetwork: Boolean = false, referer: String? = null): String {
        val request = GET(baseUrl + path, if (json) pageHeaders.newBuilder().set("Accept", "application/json").build() else pageHeaders)
            .newBuilder()
            .apply {
                if (forceNetwork) cacheControl(CacheControl.FORCE_NETWORK)
                if (referer != null) {
                    header("Referer", baseUrl + referer)
                    header("Origin", baseUrl)
                }
            }
            .build()
        return client.newCall(request).await().use { response ->
            val body = response.body.string()
            if (response.header("cf-mitigated") == "challenge" || KdParser.isChallenge(body)) {
                throw Exception("KiligDrama is asking for a Cloudflare check. Open the site once in WebView, pass it, and retry.")
            }
            if (!response.isSuccessful) {
                Log.w(TAG, "Site request HTTP ${response.code}: ${request.url.encodedPath}")
                throw Exception("KiligDrama answered HTTP ${response.code}")
            }
            body
        }
    }

    // ================================ Settings ================================

    private val SharedPreferences.qualityPref by preferences.delegate(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            title = "Preferred quality",
            entries = listOf("1080p", "720p", "480p", "360p"),
            entryValues = listOf("1080", "720", "480", "360"),
            default = PREF_QUALITY_DEFAULT,
            summary = "%s",
        )
    }

    // ================================ Helpers ================================

    private class GenreFilter : AnimeFilter.Select<String>("Genre", arrayOf("Any") + KD_GENRES.map { it.label }.toTypedArray())

    private class SourceFilter : AnimeFilter.Select<String>("Source (wins over genre)", arrayOf("Any") + KD_SOURCES.map { it.label }.toTypedArray())

    companion object {
        private const val TAG = "ArvinKD"
        private const val LANG = "en"
        private const val HEAD_BYTES = 64L

        private val QUALITY_REGEX = Regex("""(\d{3,4})\s*p""", RegexOption.IGNORE_CASE)

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }
}
