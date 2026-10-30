package eu.kanade.tachiyomi.animeextension.en.rentaro

import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.parallelCatchingFlatMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * OneTouchTV's public Devcorp API. Its catalogue uses its own slugs, not TMDB ids.
 *
 * Both HLS and DASH can arrive under a `.m3u8` URL, and the same episode can change format or
 * upstream between requests. The manifest body, never its suffix or API hint, selects the parser.
 */
internal class OneTouchTv(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val cacheDir: File = File(System.getProperty("java.io.tmpdir") ?: "."),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val playlists = PlaylistUtils(client, headers)
    private val apiHeaders = headers.newBuilder()
        .set("Origin", ORIGIN)
        .set("Referer", "$ORIGIN/")
        .build()
    private val mediaHeaders = headers.newBuilder()
        .set("Accept", "*/*")
        .set("Origin", PLAYER_ORIGIN)
        .set("Referer", "$PLAYER_ORIGIN/")
        .build()

    suspend fun videos(
        title: String,
        year: String,
        season: Int,
        episode: Int,
        isMovie: Boolean,
        subLimit: Int,
    ): List<Video> {
        if (title.isBlank() || season < 1 || episode < 1) return emptyList()
        val selected = findEpisode(title, year, season, episode, isMovie) ?: return emptyList()
        val identifier = selected.identifier?.takeIf { it.isNotBlank() } ?: return emptyList()
        val result = api<OneTouchSourcesDto>(
            API.toHttpUrl().newBuilder()
                .addPathSegments("web/vod")
                .addPathSegment(identifier)
                .addPathSegment("episode")
                .addPathSegment(selected.sourceId)
                .build(),
        ) ?: return emptyList()
        val subtitles = result.track.orEmpty()
            .filter { it.kind == null || it.kind.equals("captions", true) || it.kind.equals("subtitles", true) }
            .sortedByDescending { it.default }
            .mapNotNull { track ->
                track.file?.toHttpUrlOrNull()?.let { Track(it.toString(), track.name ?: track.code ?: "Unknown") }
            }
            .distinctBy { it.url }
            .take(subLimit.coerceAtLeast(0))

        return result.sources.orEmpty()
            .distinctBy { it.url }
            .filterNot { it.type.equals("webpage", true) }
            .parallelCatchingFlatMap { source -> sourceVideos(source, subtitles) }
    }

    private suspend fun findEpisode(
        title: String,
        year: String,
        season: Int,
        number: Int,
        isMovie: Boolean,
    ): OneTouchEpisodeDto? {
        val wanted = normalise(title)
        val wantedYear = year.take(4).toIntOrNull()
        for (page in 1..MAX_SEARCH_PAGES) {
            val search = API.toHttpUrl().newBuilder()
                .addPathSegments("vod/search")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("keyword", title)
                .build()
            val hits = api<List<OneTouchTitleDto>>(search).orEmpty()
            val candidates = hits.filter { item ->
                val names = listOfNotNull(item.title) + item.otherTitles.orEmpty()
                val candidateYear = item.year?.contentOrNull?.toIntOrNull()
                    ?: item.title?.let { YEAR.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                item.id?.isNotBlank() == true &&
                    item.type?.lowercase(Locale.ROOT) in (if (isMovie) MOVIE_TYPES else SERIES_TYPES) &&
                    names.any { normalise(it) == wanted } &&
                    (isMovie || seasonOf(item.title.orEmpty()) == season) &&
                    (wantedYear == null || candidateYear == null || (!isMovie && season > 1) || abs(candidateYear - wantedYear) <= 1)
            }.sortedByDescending { it.year?.contentOrNull?.toIntOrNull() == wantedYear }

            for (candidate in candidates) {
                val detail = api<OneTouchTitleDto>(
                    API.toHttpUrl().newBuilder()
                        .addPathSegments("web/vod")
                        .addPathSegment(candidate.id!!)
                        .addPathSegment("detail")
                        .build(),
                ) ?: continue
                val episodes = detail.episodes.orEmpty()
                // Match numbers, never array positions: the site lists the latest episode first.
                val selected = if (isMovie) {
                    episodes.singleOrNull()
                } else {
                    episodes.firstOrNull { it.episode?.contentOrNull?.toDoubleOrNull() == number.toDouble() }
                } ?: continue
                return selected.copy(identifier = selected.identifier?.takeIf { it.isNotBlank() } ?: detail.id ?: candidate.id)
            }
            if (hits.size < SEARCH_PAGE_SIZE) break
        }
        return null
    }

    private suspend inline fun <reified T> api(url: HttpUrl): T? {
        val body = client.newCall(GET(url, apiHeaders)).await().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }
        val envelope = json.decodeFromString<OneTouchResponseDto<T>>(OneTouchCipher.decrypt(body))
        return envelope.result.takeIf { envelope.success }
    }

    private suspend fun sourceVideos(source: OneTouchSourceDto, subtitles: List<Track>): List<Video> {
        val url = source.url?.toHttpUrlOrNull() ?: return emptyList()
        val body = client.newCall(GET(url, mediaHeaders)).awaitSuccess().use { response ->
            val head = response.peekBody(MANIFEST_HEAD_BYTES).string().trimStart('\uFEFF', ' ', '\r', '\n', '\t')
            if (!head.startsWith("#EXTM3U") && !head.startsWith("<")) return emptyList()
            response.body.string().trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        }
        return when {
            body.startsWith("#EXTM3U") -> playlists.extractFromHls(
                playlistUrl = url.toString(),
                masterHeaders = mediaHeaders,
                videoHeaders = mediaHeaders,
                subtitleList = subtitles,
                videoNameGen = { quality ->
                    label(source, if (quality == "Video") "Auto" else quality, "HLS", subtitles.size)
                },
            )
            else -> dashVideos(url, body, source, subtitles)
        }
    }

    /**
     * Keep the entire MPD, including audio and SegmentTemplates. Treating Representation.text()
     * as a file URL loses segmented DASH. A local `.mpd` also lets extension-based players choose
     * DASH instead of HLS when the upstream URL misleadingly ends in `.m3u8`.
     */
    private fun dashVideos(url: HttpUrl, body: String, source: OneTouchSourceDto, subtitles: List<Track>): List<Video> {
        val document = Jsoup.parse(body, url.toString(), Parser.xmlParser())
        val mpd = document.getElementsByTag("MPD").firstOrNull() ?: return emptyList()
        if (mpd.getElementsByTag("ContentProtection").isNotEmpty()) return emptyList()
        val representations = mpd.getElementsByTag("Representation")
        val height = representations.mapNotNull { it.attr("height").toIntOrNull() }.maxOrNull()
        val hevc = representations.any { it.attr("codecs").startsWith("hev1") || it.attr("codecs").startsWith("hvc1") }

        // The cached document has a file: base. Preserve the original HTTP base for every
        // relative template, BaseURL and subtitle, without rewriting absolute CDN URLs.
        val bases = mpd.children().filter { it.tagName() == "BaseURL" }
        if (bases.isEmpty()) {
            mpd.prependElement("BaseURL").text(url.toString())
        } else {
            bases.forEach { base -> base.text(url.resolve(base.text())?.toString() ?: return emptyList()) }
        }
        cacheDir.mkdirs()
        val cutoff = System.currentTimeMillis() - CACHE_TTL_MS
        cacheDir.listFiles { file -> file.name.startsWith(CACHE_PREFIX) && file.lastModified() < cutoff }
            ?.forEach { it.delete() }
        val manifest = File.createTempFile(CACHE_PREFIX, ".mpd", cacheDir).apply { writeText(document.outerHtml()) }
        val playable = manifest.toURI().toString()
        return listOf(
            Video(
                videoUrl = playable,
                videoTitle = label(source, height?.let { "${it}p" } ?: "Auto", "DASH", subtitles.size, hevc),
                headers = mediaHeaders,
                subtitleTracks = subtitles,
            ),
        )
    }

    private fun label(source: OneTouchSourceDto, quality: String, format: String, subCount: Int, hevc: Boolean = false): String = listOfNotNull(
        source.name?.takeIf { it.isNotBlank() }?.let { "$NAME/$it" } ?: NAME,
        quality,
        format,
        "HEVC".takeIf { hevc },
        "$subCount subs".takeIf { subCount > 0 },
    ).joinToString(" · ")

    private fun seasonOf(title: String): Int = SEASON.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun normalise(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFD)
        .replace(MARKS, "")
        .replace(YEAR, " ")
        .replace(SEASON, " ")
        .lowercase(Locale.ROOT)
        .replace("&", " and ")
        .filter { it.isLetterOrDigit() || it.isWhitespace() }
        .replace(WHITESPACE, " ")
        .trim()

    companion object {
        private const val NAME = "Sora"
        private const val ORIGIN = "https://onetouchtv.xyz"
        private const val PLAYER_ORIGIN = "https://s1.devcorp.me"
        private const val API = "https://api3.devcorp.me"
        private const val MAX_SEARCH_PAGES = 3
        private const val SEARCH_PAGE_SIZE = 20
        private const val MANIFEST_HEAD_BYTES = 512L
        private const val CACHE_PREFIX = "rentaro-onetouch-"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
        private val MOVIE_TYPES = setOf("movie")
        private val SERIES_TYPES = setOf("drama", "tv show", "tv series", "series", "anime")
        private val YEAR = Regex("""\(((?:19|20)\d{2})\)""")
        private val SEASON = Regex("""(?i)\b(?:season\s*|s)(\d+)\b""")
        private val MARKS = Regex("""\p{M}+""")
        private val WHITESPACE = Regex("""\s+""")
    }
}
