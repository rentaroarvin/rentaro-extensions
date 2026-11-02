package eu.kanade.tachiyomi.animeextension.en.arvinhp

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Toast
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import keiyoushi.utils.applicationContext
import keiyoushi.utils.delegate
import keiyoushi.utils.getEditTextPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.getSwitchPreference
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HotPlot (hotplot.ai) is a Next.js site of vertical short dramas that re-hosts titles from eleven
 * short-drama apps. Browsing uses the JSON the site's own pages load (`/{locale}/discover-more`), search
 * and details use its `/api/v1` routes, and playback is the two calls its player makes: a playback
 * session for an episode, then the signed HLS playlist that session names.
 *
 * Every route sits behind Cloudflare. This source does not try to answer the challenge. The host's regular
 * client deals with it the way the app does, with the clearance the user earns by opening the site once in
 * the app's WebView, and when the site still answers the challenge this source says so.
 *
 * The site decides what a visitor may watch, and a session response of HTTP 201 does not settle it: the
 * `gate`, `mode` and playback URL do. A gated episode is reported as an error and nothing else is tried.
 * The coin, ad-unlock, order and quote endpoints are never called, and the settings below sign in only
 * through the site's own email-code form.
 *
 * `SAnime.url` is the show slug. `SEpisode.url` is the episode UUID the playback session is keyed by.
 */
class ArvinHp :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "Arvin-HP"

    override val baseUrl = "https://hotplot.ai"

    override val lang = "en"
    override val supportsLatest = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val auth by lazy { HpAuth(client, baseUrl, apiHeaders) }

    // ============================ Browse / search ============================

    override suspend fun getPopularAnime(page: Int): AnimesPage = discover(page, rail = null, sort = null)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = discover(page, rail = null, sort = SortFilter.SORT_UPDATED)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Filters apply when the search box is empty. Search ignores them."),
        ProviderFilter(),
        SortFilter(),
    )

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val text = query.trim()
        if (text.isEmpty()) {
            val rail = filters.filterIsInstance<ProviderFilter>().firstOrNull()?.rail
            val sort = filters.filterIsInstance<SortFilter>().firstOrNull()?.value
            return discover(page, rail, sort)
        }

        // The site's search box asks for 20 titles and no paging parameter was observed, so one page is all
        // there is. Its "from all apps" mode starts an import on the site's side, which this source never does.
        if (page > 1) return AnimesPage(emptyList(), false)
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/v1/search")
            .addQueryParameter("q", text)
            .addQueryParameter("locale", HpAuth.LOCALE)
            .addQueryParameter("limit", SEARCH_LIMIT.toString())
            .build()
        val cards = fetch(url.toString()).parseAs<SearchResponseDto>().data?.items.orEmpty()
        return AnimesPage(cards.mapNotNull(::toAnime), false)
    }

    private suspend fun discover(page: Int, rail: String?, sort: String?): AnimesPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("${HpAuth.LOCALE}/discover-more")
            .apply {
                rail?.let { addQueryParameter("rail", it) }
                sort?.let { addQueryParameter("sort", it) }
                addQueryParameter("page", page.toString())
            }
            .build()
        val result = fetch(url.toString()).parseAs<DiscoverPageDto>()
        val animes = result.items.mapNotNull(::toAnime).distinctBy { it.url }
        // `total` moves between closely timed responses, so it is not a stop condition; `done` and an
        // empty page are.
        return AnimesPage(animes, result.done != true && result.items.isNotEmpty())
    }

    private fun toAnime(card: CardDto): SAnime? {
        val slug = card.slug?.takeIf { it.isNotBlank() } ?: return null
        val title = card.title?.takeIf { it.isNotBlank() } ?: return null
        return SAnime.create().apply {
            this.title = title
            url = slug
            thumbnail_url = thumbnailOf(card.posterUrl)
        }
    }

    /** The site's own smaller poster form for cards, `img.hotplot.ai/posters/w480/<file>.webp`. */
    private fun thumbnailOf(poster: String?): String? {
        if (poster.isNullOrBlank()) return null
        val match = POSTER_REGEX.matchEntire(poster) ?: return poster
        return "https://img.hotplot.ai/posters/w480/${match.groupValues[1]}.webp"
    }

    // The abstract request/parse pairs are unused: every call above is a suspend override.
    override fun popularAnimeRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun popularAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException("Not used")

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used")

    // ================================ Details ================================

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl/${HpAuth.LOCALE}/shows/${anime.url}"

    override fun animeDetailsRequest(anime: SAnime): Request = GET(getAnimeUrl(anime), apiHeaders)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val show = showPage(anime).show
        return SAnime.create().apply {
            show?.title?.takeIf { it.isNotBlank() }?.let { title = it }
            description = show?.synopsis?.takeIf { it.isNotBlank() }
            show?.posterUrl?.takeIf { it.isNotBlank() }?.let { thumbnail_url = it }
            (show?.genreLabel as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { genre = it }
            val planned = show?.plannedEpisodes
            val published = show?.episodesPublished
            status = if (planned != null && published != null && published >= planned) SAnime.COMPLETED else SAnime.ONGOING
        }
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException("Not used")

    private suspend fun showPage(anime: SAnime): ShowPageDto {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/v1/pages/show")
            .addQueryParameter("slug", anime.url)
            .addQueryParameter("locale", HpAuth.LOCALE)
            .build()
        return fetch(url.toString()).parseAs<ShowResponseDto>().data ?: throw Exception("HotPlot returned no page for this title")
    }

    // ================================ Episodes ================================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodes = showPage(anime).episodes.filter { it.hasMedia != false && !it.id.isNullOrBlank() }
        if (episodes.isEmpty()) throw Exception("HotPlot listed no episodes for this title")

        // The metadata calls most episodes CREDITS, but the playback session is the real verdict (a title
        // whose later episodes read CREDITS played FREE once signed in). So nothing is labelled from it.
        return episodes
            .mapIndexed { index, episode -> (episode.episodeNo ?: episode.displayOrder ?: (index + 1)) to episode }
            .sortedByDescending { it.first }
            .map { (number, episode) ->
                SEpisode.create().apply {
                    name = episode.title?.takeIf { it.isNotBlank() } ?: "Episode $number"
                    episode_number = number.toFloat()
                    url = episode.id.orEmpty()
                }
            }
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException("Not used")

    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException("Not used")

    // ============================== Video links ==============================

    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException("Not used")

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(Hoster(hosterUrl = episode.url, hosterName = "HotPlot"))

    /**
     * Asks for a playback session, as the site's player does, and plays what it names.
     *
     * The session also carries the AES key and a heartbeat token. Neither is read. The media playlist names
     * its key as `https://hotplot.ai/api/v1/playback/key/<id>`, which the player requests itself with the
     * headers below. The site's own player never makes that request (it primes its loader from the session
     * response), so whether the endpoint answers an outside player has not been observed, and no cookie is
     * copied into these headers because they would reach the media host as well.
     */
    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val episodeId = hoster.hosterUrl
        val body = PlaybackRequestDto(episodeId).toJsonRequestBody()
        val request = POST("$baseUrl/api/v1/playback/sessions", apiHeaders.newBuilder().set("Origin", baseUrl).build(), body)
        val session = client.newCall(request).await().use { response ->
            val text = response.body.string()
            if (HpAuth.isChallenge(response, text)) throw Exception("HotPlot: ${HpAuth.CHALLENGE_MESSAGE}")
            if (!response.isSuccessful) throw Exception("HotPlot refused to start playback (HTTP ${response.code}).")
            text.parseAs<PlaybackResponseDto>().data
        } ?: throw Exception("HotPlot returned no playback session")

        val link = (session.playbackUrl?.takeIf { it.isNotBlank() } ?: session.manifestUrl?.takeIf { it.isNotBlank() })
        if (session.gate != null || link == null || (session.mode != null && session.mode != MODE_FULL)) {
            throw Exception(lockedMessage(session))
        }

        val master = link.toHttpUrl()
        val text = client.newCall(GET(master, mediaHeaders)).await().use { response ->
            if (!response.isSuccessful) throw Exception("HotPlot's media host answered HTTP ${response.code}. Try again.")
            response.body.string()
        }
        val subtitles = subtitleTracks(session.subtitles)

        val variants = HlsMaster.parse(text, master)
        // A playlist with no variants is itself the stream.
        if (variants.isEmpty()) {
            if (!text.contains("#EXTINF")) throw Exception("HotPlot's playlist held no stream")
            return listOf(Video(videoUrl = link, videoTitle = "Video", headers = mediaHeaders, subtitleTracks = subtitles))
        }
        return variants
            .sortedByDescending { it.bandwidth ?: 0L }
            .map { variant ->
                Video(
                    videoUrl = variant.url,
                    videoTitle = variant.label ?: "Video",
                    headers = mediaHeaders,
                    subtitleTracks = subtitles,
                )
            }
    }

    private fun lockedMessage(session: PlaybackDto): String = when (session.gate) {
        "SIGNIN_REQUIRED" -> "HotPlot wants you signed in for this episode. Use Settings > HotPlot account to sign in with an email code."
        "LOCKED_CREDITS" -> "This HotPlot episode needs coins. Nothing is spent from here; unlock it on the site."
        "LOCKED_VIP" -> "This HotPlot episode is for VIP members. Unlock it on the site."
        "REGION_BLOCKED" -> "HotPlot does not offer this episode in your region."
        null -> when {
            session.mode != null && session.mode != MODE_FULL -> "HotPlot only offers a preview of this episode (${session.mode})."
            else -> "HotPlot returned no stream for this episode."
        }
        else -> "HotPlot did not open this episode (${session.gate})."
    }

    private fun subtitleTracks(subtitles: List<SubtitleDto>): List<Track> = subtitles
        .mapNotNull { subtitle ->
            val link = subtitle.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val label = subtitle.locale?.takeIf { it.isNotBlank() }
                ?.let { Locale.forLanguageTag(it).getDisplayName(Locale.ENGLISH).ifBlank { it } }
                ?: "Subtitle"
            Track(if (link.startsWith("http")) link else baseUrl + link, label)
        }
        .sortedByDescending { it.lang.startsWith("English") }

    // ================================ Network ================================

    /** Headers for the site's own JSON routes. */
    private val apiHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Accept", "application/json")
            .set("Referer", "$baseUrl/")
            .build()
    }

    /** What the browser sent the media host, which is all that host was seen to be sent. */
    private val mediaHeaders: Headers by lazy {
        headers.newBuilder()
            .set("Accept", "*/*")
            .set("Origin", baseUrl)
            .set("Referer", "$baseUrl/")
            .build()
    }

    /** Fetches a site route as text, and says so plainly when the site answers a challenge instead of content. */
    private suspend fun fetch(url: String): String = client.newCall(GET(url, apiHeaders)).await().use { response ->
        val body = response.body.string()
        if (HpAuth.isChallenge(response, body)) throw Exception("HotPlot: ${HpAuth.CHALLENGE_MESSAGE}")
        if (!response.isSuccessful) throw Exception("HotPlot answered HTTP ${response.code}")
        body
    }

    // ================================ Settings ================================

    private val SharedPreferences.email by preferences.delegate(PREF_EMAIL_KEY, "")

    private val SharedPreferences.termsAccepted by preferences.delegate(PREF_TERMS_KEY, false)

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private val busy = AtomicBoolean(false)

    @Volatile
    private var lastCodeRequestAt = 0L

    /**
     * Signing in is optional: browsing and free episodes work without it, and it only matters for episodes the
     * site gates behind an account. It is the site's own email-code form, in order: set the email, accept the
     * Terms, send a code, enter the code from the inbox. A new address creates an account, as it does on the site.
     *
     * The host's preference classes on the extension classpath are stubs with no categories and no plain
     * `Preference` constructor, so every row is an `EditTextPreference` or a `SwitchPreferenceCompat`, the two
     * kinds the shared helpers already build.
     */
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(
            screen.getEditTextPreference(
                key = PREF_EMAIL_KEY,
                default = "",
                title = "HotPlot account: email",
                summary = emailSummary(preferences.email),
                getSummary = ::emailSummary,
                dialogMessage = "Optional. Only needed for episodes HotPlot gates behind an account. " +
                    "A code is sent to this address when you tap “Send sign-in code”.",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                validate = { EMAIL_REGEX.matches(it.trim()) },
                validationMessage = { "That doesn't look like an email address." },
            ),
        )

        screen.addPreference(
            screen.getSwitchPreference(
                key = PREF_TERMS_KEY,
                default = false,
                title = TERMS_TITLE,
                summary = "HotPlot requires this to sign in, so it is your choice and never assumed. Read them on " +
                    "hotplot.ai first. Nothing is sent until you tap “Send sign-in code”.",
            ),
        )

        screen.addPreference(
            actionPreference(
                screen,
                "HotPlot account: send sign-in code",
                "Tap to email a six-digit code to the address above. HotPlot allows another after 60 seconds.",
            ) { sendCode() },
        )

        screen.addPreference(
            screen.getEditTextPreference(
                key = PREF_CODE_KEY,
                default = "",
                title = "HotPlot account: enter sign-in code",
                summary = "Type the six digits from the email and tap OK. The code is never stored.",
                inputType = InputType.TYPE_CLASS_NUMBER,
                validate = { it.length <= CODE_LENGTH && it.all(Char::isDigit) },
                validationMessage = { "Six digits only." },
                // Returning false keeps the code out of the saved settings; confirming the dialog is what signs in.
                onChange = { _, value ->
                    verifyCode(value.trim())
                    false
                },
            ),
        )

        screen.addPreference(
            actionPreference(screen, "HotPlot account: check status", "Tap to ask HotPlot whether this device is signed in.") { checkStatus() },
        )
        screen.addPreference(
            actionPreference(
                screen,
                "HotPlot account: sign out on this device",
                "Tap to forget the HotPlot session stored on this device. HotPlot is not told, so it stays valid on its side.",
            ) { signOut() },
        )
    }

    private fun emailSummary(value: String): String = value.trim().ifEmpty { "Not set" }

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

    private fun sendCode() {
        val email = preferences.email.trim()
        if (!EMAIL_REGEX.matches(email)) return toast("Set your email address first.")
        val wait = RESEND_SECONDS - (System.currentTimeMillis() - lastCodeRequestAt) / MILLIS
        if (wait > 0) return toast("HotPlot allows another code in $wait seconds.")
        // The wait only starts once HotPlot has accepted the request, so a failed send can be retried at once.
        runAccountAction({ auth.requestCode(email) }) { lastCodeRequestAt = System.currentTimeMillis() }
    }

    private fun verifyCode(code: String) {
        val email = preferences.email.trim()
        if (!EMAIL_REGEX.matches(email)) return toast("Set your email address first.")
        runAccountAction({ auth.verifyCode(email, code, preferences.termsAccepted) })
    }

    private fun checkStatus() {
        runAccountAction({ auth.status() })
    }

    private fun signOut() {
        runAccountAction({ auth.signOutLocally() })
    }

    /**
     * Runs one account call off the main thread and shows what it said. A second tap while one is running
     * is ignored. [onSuccess] runs on that worker thread, only when the call reported success.
     */
    private fun runAccountAction(call: () -> AuthResult, onSuccess: () -> Unit = {}) {
        if (!busy.compareAndSet(false, true)) return toast("Still working on the last request.")
        Thread {
            val result = try {
                call()
            } catch (e: IOException) {
                AuthResult(false, "Could not reach HotPlot (${e.message ?: "network error"}).")
            } finally {
                busy.set(false)
            }
            if (result.ok) onSuccess()
            toast(result.message, long = true)
        }.start()
    }

    private fun toast(message: String, long: Boolean = false) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    // ================================ Helpers ================================

    companion object {
        private const val SEARCH_LIMIT = 20
        private const val MODE_FULL = "FULL"
        private const val CODE_LENGTH = 6
        private const val RESEND_SECONDS = 60L
        private const val MILLIS = 1000L

        private const val TERMS_TITLE = "I accept HotPlot's Terms and Privacy Policy"
        private const val PREF_EMAIL_KEY = "hotplot_email"
        private const val PREF_TERMS_KEY = "hotplot_terms_accepted"
        private const val PREF_CODE_KEY = "hotplot_code_entry"

        private val POSTER_REGEX = Regex("""^https://(?:www\.)?hotplot\.ai/img/posters/([^/?#]+\.(?:jpe?g|png|webp|gif|heic|avif))$""", RegexOption.IGNORE_CASE)
        private val EMAIL_REGEX = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")
    }
}
