package eu.kanade.tachiyomi.animeextension.en.rentaro

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/** HollyMovieHD's public WordPress catalogue and GoodStream playback chain, exposed as Aki. */
internal class HollyMovieHd(
    private val client: OkHttpClient,
    headers: Headers,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // Identify the native extension honestly instead of advertising a browser that cannot run
    // the site's JavaScript. Its public API accepts this client without a challenge exchange.
    private val headers = headers.newBuilder().set("User-Agent", USER_AGENT).build()

    suspend fun streams(
        title: String,
        year: String,
        tmdbId: String,
        imdbId: String,
        season: Int,
        episode: Int,
        isMovie: Boolean,
        subLimit: Int,
    ): List<AkiStream> {
        if (title.isBlank() || season < 1 || episode < 1) return emptyList()
        val page = findPage(title, year, tmdbId, imdbId, season, episode, isMovie) ?: return emptyList()
        val player = page.getElementById("player2") ?: return emptyList()
        val key = player.attr("data-streamkey").takeIf { it.isNotBlank() } ?: return emptyList()
        val nonce = player.attr("data-wpnonce").takeIf { it.isNotBlank() } ?: return emptyList()
        val pageUrl = page.location().toHttpUrl()
        val form = FormBody.Builder()
            .add("action", "ajax_getlinkstream")
            .add("streamkey", key)
            .add("nonce", nonce)
            .add("imdbid", page.selectFirst(".imdb-r[data-imdbid]")?.attr("data-imdbid").orEmpty())
            .add("tmdbid", page.selectFirst("span[data-tmdbid]")?.attr("data-tmdbid").orEmpty())
            .build()
        val result = response(POST("$ORIGIN/wp-admin/admin-ajax.php", ajaxHeaders(pageUrl), form), requireSuccess = true)
            .use { json.decodeFromString<AkiEmbedsDto>(it.body.string()) }
        val subtitles = (subtitleTracks(result.subtitles) + tracks(player.attr("data-sub"), "English"))
            .distinctBy { it.url }
            .sortedBy { !it.lang.startsWith("English", true) }
            .take(subLimit.coerceAtLeast(0))

        return result.iframes.orEmpty().values
            .mapNotNull { it.toHttpUrlOrNull() }
            .filter { it.host == GOODSTREAM_HOST || it.host.endsWith(".$GOODSTREAM_HOST") }
            .distinct()
            .parallelCatchingFlatMap { iframe ->
                val embed = iframe.newBuilder().apply {
                    player.attr("data-thumb").takeIf { it.isNotBlank() }?.let { addQueryParameter("poster", it) }
                    subtitles.firstOrNull { it.lang.equals("English", true) }?.let { addQueryParameter("sub", it.url) }
                }.build()
                embedStreams(embed, pageUrl, subtitles, subLimit)
            }
            .distinctBy { it.url }
    }

    /**
     * The normal HTML search can be challenge-gated while WordPress's public REST search works.
     * Use that catalogue, check title/type/season, and corroborate IMDb/TMDB ids on the page.
     */
    private suspend fun findPage(
        title: String,
        year: String,
        tmdbId: String,
        imdbId: String,
        season: Int,
        episode: Int,
        isMovie: Boolean,
    ): Document? {
        val wanted = normalise(title)
        val wantedYear = year.take(4).toIntOrNull()
        for (page in 1..MAX_SEARCH_PAGES) {
            val search = "$ORIGIN/wp-json/wp/v2/search".toHttpUrl().newBuilder()
                .addQueryParameter("search", title)
                .addQueryParameter("subtype", if (isMovie) "post" else "tvshows")
                .addQueryParameter("per_page", SEARCH_PAGE_SIZE.toString())
                .addQueryParameter("page", page.toString())
                .build()
            val hits = response(GET(search, headers)).use { response ->
                if (!response.isSuccessful) return null
                json.decodeFromString<List<AkiSearchDto>>(response.body.string())
            }
            val candidates = hits.filter { candidate ->
                val name = Jsoup.parseBodyFragment(candidate.title.orEmpty()).text()
                normalise(name) == wanted &&
                    candidate.subtype == (if (isMovie) "post" else "tvshows") &&
                    (isMovie || seasonOf(name) == season) &&
                    (wantedYear == null || yearOf(name) == null || (!isMovie && season > 1) || abs(yearOf(name)!! - wantedYear) <= 1)
            }.sortedByDescending { yearOf(it.title.orEmpty()) == wantedYear }
            for (candidate in candidates) {
                val url = candidate.url?.toHttpUrlOrNull()?.takeIf(::isSiteUrl) ?: continue
                val detail = document(url) ?: continue
                val name = detail.selectFirst("h1[itemprop=name]")?.text() ?: continue
                if (normalise(name) != wanted || (!isMovie && seasonOf(name) != season)) continue
                val actualYear = yearOf(name)
                if (wantedYear != null && actualYear != null && (isMovie || season == 1) && abs(actualYear - wantedYear) > 1) continue
                if (!sameIds(detail, tmdbId, imdbId)) continue
                if (isMovie) {
                    if (detail.getElementById("player2") != null) return detail
                    continue
                }

                // Each series entry is one season. Match its numbered link, never its position
                // or a guessed episode slug, and do not fall back to another episode.
                val episodeUrl = detail.select(".episodeList a[href]")
                    .firstOrNull { it.text().trim().toDoubleOrNull() == episode.toDouble() }
                    ?.absUrl("href")?.toHttpUrlOrNull()?.takeIf(::isSiteUrl) ?: continue
                val episodePage = document(episodeUrl) ?: continue
                if (sameIds(episodePage, tmdbId, imdbId)) return episodePage
            }
            if (hits.size < SEARCH_PAGE_SIZE) break
        }
        return null
    }

    private suspend fun document(url: HttpUrl): Document? = response(GET(url, headers)).use { response ->
        if (!response.isSuccessful) return null
        Jsoup.parse(response.body.string(), response.request.url.toString())
    }

    private fun sameIds(document: Document, tmdbId: String, imdbId: String): Boolean {
        val actualImdb = document.selectFirst(".imdb-r[data-imdbid]")?.attr("data-imdbid").orEmpty()
        if (IMDB_ID.matches(imdbId) && IMDB_ID.matches(actualImdb) && imdbId != actualImdb) return false
        val actualTmdb = document.selectFirst("span[data-tmdbid]")?.attr("data-tmdbid")?.toIntOrNull()
        return actualTmdb == null || tmdbId.toIntOrNull() == null || actualTmdb == tmdbId.toInt()
    }

    private suspend fun embedStreams(
        embed: HttpUrl,
        page: HttpUrl,
        subtitles: List<Track>,
        subLimit: Int,
    ): List<AkiStream> {
        val requestHeaders = headers.newBuilder().set("Referer", page.toString()).build()
        val document = response(GET(embed, requestHeaders), requireSuccess = true).use { response ->
            Jsoup.parse(response.body.string(), response.request.url.toString())
        }
        val url = document.location().toHttpUrl()
        val csrf = document.getElementById("csrf_token")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: INIT_PLAYER.find(document.html())?.groupValues?.get(1)
            ?: return emptyList()
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("token", "")
            .addFormDataPart("csrf_token", csrf)
            .build()
        // The player POSTs to its own URL, including the untouched opaque e query parameter.
        val result = response(POST(url.toString(), ajaxHeaders(url), form), requireSuccess = true)
            .use { json.decodeFromString<AkiSourcesDto>(it.body.string()) }
        if (!result.success) return emptyList()
        val streamHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Origin", url.resolve("/")!!.toString().removeSuffix("/"))
            .set("Referer", url.toString())
            .build()
        val embeddedTracks = result.tracks.orEmpty()
            .filter { it.kind == null || it.kind.equals("captions", true) || it.kind.equals("subtitles", true) }
            .flatMap { tracks(it.file, it.label ?: "Unknown") }
        val subtitleTracks = (subtitles + embeddedTracks).distinctBy { it.url }.take(subLimit.coerceAtLeast(0))
        return result.sources.orEmpty().mapNotNull { source ->
            if (source.type != null && !source.type.equals("hls", true)) return@mapNotNull null
            val file = source.file?.let(url::resolve)?.toString() ?: return@mapNotNull null
            AkiStream(file, source.label?.takeIf { it.isNotBlank() } ?: "GoodStream", streamHeaders, subtitleTracks)
        }
    }

    private fun ajaxHeaders(referer: HttpUrl): Headers = headers.newBuilder()
        .set("Accept", "application/json, text/javascript, */*; q=0.01")
        .set("X-Requested-With", "XMLHttpRequest")
        .set("Origin", referer.resolve("/")!!.toString().removeSuffix("/"))
        .set("Referer", referer.toString())
        .build()

    /** Back off on a throttled metadata lookup; never retry earlier than Retry-After permits. */
    private suspend fun response(request: Request, requireSuccess: Boolean = false): Response {
        var retries = 0
        while (true) {
            val response = client.newCall(request).await()
            if (response.code == 429 && retries < MAX_THROTTLE_RETRIES) {
                val after = response.header("Retry-After")
                val wait = after?.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
                    if (seconds > MAX_THROTTLE_DELAY_MS / 1000) Long.MAX_VALUE else seconds * 1000
                }
                    ?: after?.let {
                        runCatching { SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(it)?.time }
                            .getOrNull()?.let { time -> (time - System.currentTimeMillis()).coerceAtLeast(0) }
                    }
                    ?: THROTTLE_DELAY_MS
                if (wait <= MAX_THROTTLE_DELAY_MS) {
                    response.close()
                    delay(wait.coerceAtLeast(1000))
                    retries++
                    continue
                }
            }
            if (requireSuccess && !response.isSuccessful) {
                val code = response.code
                response.close()
                throw HttpException(code)
            }
            return response
        }
    }

    private fun subtitleTracks(value: JsonElement?): List<Track> = when (value) {
        is JsonObject -> value.entries.flatMap { (language, url) -> tracks((url as? JsonPrimitive)?.contentOrNull, language) }
        is JsonArray -> value.flatMap { entry ->
            val item = entry as? JsonObject ?: return@flatMap emptyList()
            val file = (item["file"] ?: item["url"] ?: item["src"]) as? JsonPrimitive
            val label = (item["label"] ?: item["name"] ?: item["language"]) as? JsonPrimitive
            tracks(file?.contentOrNull, label?.contentOrNull ?: "Unknown")
        }
        else -> emptyList()
    }

    private fun tracks(url: String?, language: String): List<Track> = url?.toHttpUrlOrNull()?.let { listOf(Track(it.toString(), language)) }.orEmpty()

    private fun isSiteUrl(url: HttpUrl): Boolean = url.host == ORIGIN.toHttpUrl().host

    private fun seasonOf(name: String): Int = SEASON.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun yearOf(name: String): Int? = YEAR.find(name)?.groupValues?.get(1)?.toIntOrNull()

    private fun normalise(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFD)
        .replace(MARKS, "")
        .replace(YEAR, " ")
        .replace(SEASON, " ")
        .lowercase(Locale.ROOT)
        .replace("&", " and ")
        .filter { it.isLetterOrDigit() || it.isWhitespace() }
        .replace(WHITESPACE, " ")
        .trim()

    companion object {
        private const val ORIGIN = "https://hollymoviehd.cc"
        private const val USER_AGENT = "Rentaro"
        private const val GOODSTREAM_HOST = "goodstream.cc"
        private const val SEARCH_PAGE_SIZE = 30
        private const val MAX_SEARCH_PAGES = 3
        private const val MAX_THROTTLE_RETRIES = 2
        private const val THROTTLE_DELAY_MS = 5_000L
        private const val MAX_THROTTLE_DELAY_MS = 30_000L
        private val YEAR = Regex("""\(((?:19|20)\d{2})\)""")
        private val SEASON = Regex("""(?i)\b(?:season\s*|s)(\d+)\b""")
        private val IMDB_ID = Regex("""tt\d+""")
        private val INIT_PLAYER = Regex("""\binit_player\(\s*["'][^"']*["']\s*,\s*["'][^"']*["']\s*,\s*["']([^"']+)["']""")
        private val MARKS = Regex("""\p{M}+""")
        private val WHITESPACE = Regex("""\s+""")
    }
}

internal data class AkiStream(
    val url: String,
    val name: String,
    val headers: Headers,
    val subtitles: List<Track>,
)
