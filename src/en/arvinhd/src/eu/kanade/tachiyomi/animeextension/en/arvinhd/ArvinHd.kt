package eu.kanade.tachiyomi.animeextension.en.arvinhd

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Toast
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import keiyoushi.utils.applicationContext
import keiyoushi.utils.delegate
import keiyoushi.utils.getEditTextPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HepDrama (hepdrama.com) is a Next.js site of vertical short dramas. Browsing and search are plain
 * HTML listing pages, an episode list is embedded in each series page, and playback goes through the
 * same two calls the site's own player makes: `GET /api/play/<episodeId>` for what this visitor may
 * watch, then the `/api/stream/...` URL it names, which redirects to the media.
 *
 * The site gives every visitor a limited number of free episodes (ten per series was advertised) and
 * asks the rest to sign in, watch an ad, or buy Premium. This extension plays what the site serves to
 * the visitor it is talking to: anonymous by default, or the user's own account when they sign in under
 * Settings (see [HdAuth]). When the site says an episode is locked, that is reported as an error and
 * nothing else is attempted: no account is created, no ad unlock, no Premium workaround, no change of
 * identity.
 *
 * `SAnime.url` is the series path (`/en/series/<slug>`); `SEpisode.url` is `<slug>/<episodeId>`.
 */
class ArvinHd :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Arvin-HD"

    override val baseUrl = "https://hepdrama.com"

    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    // ============================ Browse / search ============================

    override fun popularAnimeRequest(page: Int): Request = listingRequest(DISCOVER_PATH, page, sort = null)

    override fun popularAnimeParse(response: Response): AnimesPage = parseListing(response)

    override fun latestUpdatesRequest(page: Int): Request = listingRequest(DISCOVER_PATH, page, sort = SortFilter.SORT_UPDATED)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseListing(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(CategoryFilter(), SortFilter())

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.slug
        val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.value
        val path = if (category != null) "$CATEGORY_PATH/$category" else DISCOVER_PATH
        return listingRequest(path, page, sort, query.trim().takeIf { it.isNotEmpty() })
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseListing(response)

    /**
     * The site's search box calls `/api/search`, which answers at most eight titles. The discover and
     * category pages take the same `q` and page through every match, so those are used instead.
     */
    private fun listingRequest(path: String, page: Int, sort: String?, query: String? = null): Request {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addEncodedPathSegments(path.trimStart('/'))
            .apply {
                query?.let { addQueryParameter("q", it) }
                sort?.let { addQueryParameter("sirala", it) }
                if (page > 1) addQueryParameter("sayfa", page.toString())
            }
            .build()
        return GET(url, headers)
    }

    private fun parseListing(response: Response): AnimesPage {
        val document = response.use { Jsoup.parse(it.body.string(), it.request.url.toString()) }
        val animes = document.select("a[href^=/en/series/]")
            .distinctBy { it.attr("href") }
            .mapNotNull { anchor ->
                val image = anchor.selectFirst("img") ?: return@mapNotNull null
                val title = image.attr("alt").trim().ifEmpty { return@mapNotNull null }
                SAnime.create().apply {
                    this.title = title
                    url = anchor.attr("href")
                    thumbnail_url = posterOf(image.attr("src"))
                }
            }
        // The listings page in blocks of 30. A page that comes back short is the last one; a page past
        // the end answers 200 with no cards.
        return AnimesPage(animes, animes.size >= PAGE_SIZE)
    }

    /** Cards load posters through the image optimiser; the file behind it is served from the site itself. */
    private fun posterOf(src: String): String? {
        if (src.isBlank()) return null
        val original = src.toHttpUrlOrNullSafe()?.queryParameter("url") ?: return absolute(src)
        return absolute(original)
    }

    private fun String.toHttpUrlOrNullSafe() = runCatching { absolute(this).toHttpUrl() }.getOrNull()

    // ================================ Details ================================

    override fun getAnimeUrl(anime: SAnime): String = absolute(anime.url)

    override fun animeDetailsRequest(anime: SAnime): Request = GET(absolute(anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.use { Jsoup.parse(it.body.string(), it.request.url.toString()) }
        val series = seriesLd(document)
        return SAnime.create().apply {
            (series?.name ?: document.selectFirst("h1")?.text())?.takeIf { it.isNotBlank() }?.let { title = it }
            description = series?.description?.takeIf { it.isNotBlank() }
            genre = series?.genre?.joinToString(", ")?.ifBlank { null }
            series?.image?.takeIf { it.isNotBlank() }?.let { thumbnail_url = absolute(it) }
            status = if (isCompleted(document)) SAnime.COMPLETED else SAnime.ONGOING
        }
    }

    private fun seriesLd(document: Document): SeriesLdDto? = document.select("script[type=application/ld+json]")
        .asSequence()
        .mapNotNull { runCatching { it.data().parseAs<SeriesLdDto>() }.getOrNull() }
        .firstOrNull { it.type == "TVSeries" }

    /**
     * Every card on the page, related series included, carries its own `isCompleted`, so the one read
     * is the nearest to this series' own `episodeCount`/`freeEpisodes` block. All 26 on the three
     * series checked said true, so a missing or unclear flag is treated as ongoing.
     */
    private fun isCompleted(document: Document): Boolean {
        val text = flightText(document)
        val anchor = text.indexOf("\"episodeCount\"").takeIf { it >= 0 } ?: return false
        return COMPLETED_REGEX.findAll(text)
            .minByOrNull { kotlin.math.abs(it.range.first - anchor) }
            ?.groupValues?.get(1) == "true"
    }

    // ================================ Episodes ================================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.newCall(animeDetailsRequest(anime)).awaitSuccess()
            .use { Jsoup.parse(it.body.string(), absolute(anime.url)) }
        val slug = anime.url.substringAfterLast('/')

        // Episode ids live in the series page's React Flight data. The play endpoint is keyed by that
        // id and not by the episode number.
        val episodes = EPISODES_REGEX.find(flightText(document))?.groupValues?.get(1)
            ?.let { runCatching { it.parseAs<List<EpisodeDto>>() }.getOrNull() }
            .orEmpty()
        if (episodes.isEmpty()) throw Exception("HepDrama listed no episodes for this series")

        // Episodes past the free ones need an account on the site. They stay listed, and are labelled so
        // the reason is visible before a play attempt ends in the site's own lock message.
        val free = FREE_EPISODES_REGEX.find(flightText(document))?.groupValues?.get(1)?.toIntOrNull()

        return episodes.sortedByDescending { it.number }.map { episode ->
            SEpisode.create().apply {
                name = "Episode ${episode.number}"
                episode_number = episode.number.toFloat()
                url = "$slug/${episode.id}"
                if (free != null && episode.number > free) scanlator = "Needs account"
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used")

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException("Not used")

    /** The inline `self.__next_f.push([1,"..."])` strings of a page, joined and unescaped. */
    private fun flightText(document: Document): String = FLIGHT_REGEX.findAll(document.html())
        .joinToString("") { match ->
            try {
                "\"${match.groupValues[1]}\"".parseAs<String>()
            } catch (e: SerializationException) {
                ""
            }
        }

    // ============================== Video links ==============================

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException("Not used")

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(Hoster(hosterUrl = episode.url, hosterName = "HepDrama"))

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val episodeId = hoster.hosterUrl.substringAfterLast('/')
        val playUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/play")
            .addPathSegment(episodeId)
            .build()
        val play = client.newCall(GET(playUrl, jsonHeaders)).awaitSuccess().parseAs<PlayDto>()

        // The site decides what a visitor may watch. Anything other than OPEN is reported as it is and
        // nothing else is tried: there is no sign-up, ad unlock or identity change here.
        when (val status = play.access?.status) {
            "OPEN" -> Unit
            // Only the first episodes of a series are free to a visitor without an account.
            "LOGIN_REQUIRED" -> throw Exception(
                "HepDrama: only the first episodes of a series are free without an account. " +
                    "Sign in under Settings > HepDrama account (with an account you already have on the site).",
            )
            // The visitor's free allowance for the day is spent.
            "PREMIUM_REQUIRED" -> throw Exception("HepDrama: today's free episodes are used up. Unlock on the site.")
            "SOURCE_UNAVAILABLE" -> throw Exception("HepDrama has no source for this episode right now. Try again later.")
            else -> throw Exception("HepDrama did not open this episode (${status ?: "no access status"})")
        }

        val subtitles = play.subtitles.mapNotNull { subtitle ->
            val link = subtitle.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Track(absolute(link), subtitle.label ?: subtitle.lang ?: "Subtitle")
        }

        val videos = withContext(Dispatchers.IO) {
            play.sources.mapNotNull { source ->
                val link = source.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                resolveSource(absolute(link), source, subtitles)
            }.flatten()
        }
        if (videos.isEmpty()) throw Exception("HepDrama returned no playable stream for this episode")
        // The preferred audio comes first; the site lists one variant per episode, so this only orders
        // a series that offers both.
        val preferred = variantLabel(preferences.variantPref)
        return videos.sortedByDescending { it.videoTitle.startsWith(preferred) }
    }

    /**
     * An HLS source is a playlist behind a redirect; an MP4 source is the file behind one. The first
     * bytes decide which, because both live under the same `/api/stream/<id>/<variant>` path shape.
     */
    private fun resolveSource(url: String, source: SourceDto, subtitles: List<Track>): List<Video>? {
        val label = variantLabel(source.variant)
        val head = try {
            client.newCall(GET(url, headers.newBuilder().set("Range", "bytes=0-63").build())).execute().use { response ->
                if (!response.isSuccessful) return null
                String(response.peekBody(HEAD_BYTES).bytes(), Charsets.ISO_8859_1).trimStart()
            }
        } catch (e: IOException) {
            return null
        }
        return if (head.contains("#EXTM3U")) {
            try {
                playlists.extractFromHls(
                    playlistUrl = url,
                    masterHeaders = headers,
                    videoHeaders = headers,
                    subtitleList = subtitles,
                    videoNameGen = { quality -> "$label · $quality · HLS" },
                ).map { video ->
                    // A media playlist can remain behind /api/stream/<id>/<variant>.
                    // Media3 selects its media source before following that redirect,
                    // so retain a client-only HLS hint without changing the request URL.
                    val mediaUrl = video.videoUrl
                    video.copy(
                        videoUrl = if (mediaUrl.contains(".m3u8", ignoreCase = true) || '#' in mediaUrl) {
                            mediaUrl
                        } else {
                            "$mediaUrl#.m3u8"
                        },
                    )
                }
            } catch (e: IOException) {
                null
            }
        } else if (head.isEmpty() || head.startsWith("<") || head.startsWith("{")) {
            null
        } else {
            listOf(Video(videoUrl = url, videoTitle = "$label · MP4", headers = headers, subtitleTracks = subtitles))
        }
    }

    private val playlists by lazy { aniyomi.lib.playlistutils.PlaylistUtils(client, headers) }

    private val jsonHeaders by lazy { headers.newBuilder().set("Accept", "application/json").build() }

    private fun variantLabel(variant: String?): String = when (variant) {
        "DUB" -> "English Dub"
        "SUB" -> "English Subtitles"
        else -> "Original"
    }

    // ================================ Settings ================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_VARIANT_KEY,
            title = "Preferred audio",
            entries = listOf("Original", "English Dub", "English Subtitles"),
            entryValues = listOf("ORIGINAL", "DUB", "SUB"),
            default = PREF_VARIANT_DEFAULT,
            summary = "%s",
        )

        // Signing in is optional: browsing and the free episodes work without it. The host's preference
        // classes on the extension classpath have no categories and no plain `Preference` constructor, so
        // every row is an `EditTextPreference` or a `SwitchPreferenceCompat`, the two kinds already used here.
        screen.addPreference(
            screen.getEditTextPreference(
                key = PREF_EMAIL_KEY,
                default = "",
                title = "HepDrama account: email",
                summary = emailSummary(preferences.email),
                getSummary = ::emailSummary,
                dialogMessage = "Optional. The email of an account you already have on hepdrama.com. " +
                    "Only needed for episodes the site keeps for signed-in viewers.",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                validate = { EMAIL_REGEX.matches(it.trim()) },
                validationMessage = { "That doesn't look like an email address." },
            ),
        )

        screen.addPreference(
            screen.getEditTextPreference(
                key = PREF_PASSWORD_KEY,
                default = "",
                title = "HepDrama account: password (signs in)",
                summary = "Type your password and tap OK to sign in with the email above. The password is used once and never stored.",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
                // Returning false keeps the password out of the saved settings; confirming the dialog is what signs in.
                onChange = { _, value ->
                    signIn(value)
                    false
                },
            ),
        )

        screen.addPreference(
            actionPreference(screen, "HepDrama account: check status", "Tap to ask HepDrama whether this device is signed in.") { checkStatus() },
        )
        screen.addPreference(
            actionPreference(
                screen,
                "HepDrama account: sign out on this device",
                "Tap to forget the HepDrama session stored on this device. HepDrama is not told, so it stays valid on its side.",
            ) { signOut() },
        )
    }

    private val SharedPreferences.variantPref by preferences.delegate(PREF_VARIANT_KEY, PREF_VARIANT_DEFAULT)

    private val SharedPreferences.email by preferences.delegate(PREF_EMAIL_KEY, "")

    private fun emailSummary(value: String): String = value.trim().ifEmpty { "Not set" }

    private val auth by lazy { HdAuth(client, baseUrl, headers) }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val busy = AtomicBoolean(false)

    /**
     * A row that runs [action] when tapped. It is a switch whose change is always refused, so it never
     * shows as on and nothing is saved; the tap is the only thing that happens.
     */
    private fun actionPreference(screen: PreferenceScreen, title: String, summary: String, action: () -> Unit): SwitchPreferenceCompat = SwitchPreferenceCompat(screen.context).apply {
        this.title = title
        this.summary = summary
        setOnPreferenceChangeListener { _, _ ->
            action()
            false
        }
    }

    private fun signIn(password: String) {
        val email = preferences.email.trim()
        if (!EMAIL_REGEX.matches(email)) return toast("Set your email address first.")
        runAccountAction { auth.signIn(email, password) }
    }

    private fun checkStatus() = runAccountAction { auth.status() }

    private fun signOut() = runAccountAction { auth.signOutLocally() }

    /**
     * Runs one account call off the main thread and shows what it said. A second tap while one is running
     * is ignored.
     */
    private fun runAccountAction(call: () -> AuthResult) {
        if (!busy.compareAndSet(false, true)) return toast("Still working on the last request.")
        Thread {
            val result = try {
                call()
            } catch (e: IOException) {
                AuthResult(false, "Could not reach HepDrama (${e.message ?: "network error"}).")
            } finally {
                busy.set(false)
            }
            toast(result.message, long = true)
        }.start()
    }

    private fun toast(message: String, long: Boolean = false) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    // ================================ Helpers ================================

    private fun absolute(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        else -> baseUrl + (if (url.startsWith("/")) url else "/$url")
    }

    companion object {
        private const val DISCOVER_PATH = "en/discover"
        private const val CATEGORY_PATH = "en/category"
        private const val PAGE_SIZE = 30
        private const val HEAD_BYTES = 64L

        private val FLIGHT_REGEX = Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)*)"\]\)""")
        private val EPISODES_REGEX = Regex(""""episodes":(\[\{"id".*?\}\]),""")
        private val COMPLETED_REGEX = Regex(""""isCompleted":(true|false)""")
        private val FREE_EPISODES_REGEX = Regex(""""episodeCount":\d+,"freeEpisodes":(\d+)""")

        private const val PREF_VARIANT_KEY = "preferred_variant"
        private const val PREF_VARIANT_DEFAULT = "ORIGINAL"
        private const val PREF_EMAIL_KEY = "hepdrama_email"
        private const val PREF_PASSWORD_KEY = "hepdrama_password_entry"

        private val EMAIL_REGEX = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
    }
}
