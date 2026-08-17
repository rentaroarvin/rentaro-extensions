package eu.kanade.tachiyomi.animeextension.en.rentaro

import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.utils.bodyString
import keiyoushi.utils.parallelCatchingFlatMap
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves Videasy embeds for a Rentaro episode/movie: double-encoded
 * /sources-with-title request → enc-dec.app decrypt → HLS expansion +
 * subtitle/quality formatting.
 */
class RentaroExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private val resultCache = LruCache<CacheKey, CachedResult>(CACHE_SIZE)
    private val resultCacheLock = Any()

    private val serverFailureState = ConcurrentHashMap<String, FailureState>()

    private data class CacheKey(
        val server: VideasyServer,
        val path: String,
        val title: String,
        val year: String,
        val imdbId: String,
    )

    private data class CachedResult(
        val result: VideasyDecryptedResult,
        val expiresAtMillis: Long,
    )

    private data class FailureState(
        val count: Int,
        val lastFailureAtMillis: Long,
    )

    @RequiresApi(Build.VERSION_CODES.N)
    suspend fun videosFromUrl(
        path: String,
        title: String,
        year: String,
        imdbId: String,
        enabledServers: Set<String>,
        subLimit: Int,
        qualityPref: String,
    ): List<Video> {
        val pathParts = path.split("/")
        val isMovie = pathParts.first() == "movie"
        val tmdbId = pathParts[1]
        val seasonId = if (isMovie) "1" else pathParts[2]
        val episodeId = if (isMovie) "1" else pathParts[3]

        val eligibleServers = VIDEASY_SERVERS.filter { server ->
            (!server.movieOnly || isMovie) && server.displayName in enabledServers
        }
        val vidLinkEnabled = VIDLINK_NAME in enabledServers

        if (eligibleServers.isEmpty() && !vidLinkEnabled) {
            return emptyList()
        }

        // The Videasy backends ignore Referer/Origin entirely, but the stream
        // CDNs allowlist the player origin, so send it on every request.
        val backendHeaders = headers.newBuilder()
            .set("Referer", "$PLAYER_ORIGIN/")
            .set("Origin", PLAYER_ORIGIN)
            .build()

        val videasyVideos = if (eligibleServers.isEmpty()) {
            emptyList()
        } else {
            videasyVideos(
                eligibleServers,
                path,
                title,
                year,
                imdbId,
                tmdbId,
                seasonId,
                episodeId,
                isMovie,
                backendHeaders,
                subLimit,
            )
        }

        // VidLink is an independent backend, so a Videasy-wide failure (a bad
        // seed, enc-dec.app being down) must not take it with it.
        //
        // Only IOException is absorbed here. A blanket catch previously hid a
        // NoSuchFieldError thrown during token class-init on older devices, so
        // the server silently vanished instead of surfacing the fault.
        val vidLinkVideos = if (!vidLinkEnabled) {
            emptyList()
        } else {
            try {
                vidLinkVideos(tmdbId, seasonId, episodeId, isMovie, subLimit)
            } catch (e: IOException) {
                emptyList()
            }
        }

        return (videasyVideos + vidLinkVideos).sortedWith(
            compareByDescending<Video> {
                it.quality.contains(qualityPref, ignoreCase = true) ||
                    (qualityPref == "2160" && it.quality.contains("4k", ignoreCase = true))
            }.thenByDescending {
                extractQualityValue(it.quality)
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private suspend fun videasyVideos(
        eligibleServers: List<VideasyServer>,
        path: String,
        title: String,
        year: String,
        imdbId: String,
        tmdbId: String,
        seasonId: String,
        episodeId: String,
        isMovie: Boolean,
        backendHeaders: Headers,
        subLimit: Int,
    ): List<Video> {
        val seed = client.newCall(
            GET("$VIDEASY_API_BASE/seed?mediaId=$tmdbId", backendHeaders),
        ).awaitSuccess().parseAs<SeedDto>().seed

        return eligibleServers.parallelCatchingFlatMap { server ->
            val now = System.currentTimeMillis()

            val stateKey = "${server.displayName}:$path"
            val state = serverFailureState[stateKey]
            val circuitOpen = state != null &&
                state.count >= MAX_SERVER_FAILURES &&
                now - state.lastFailureAtMillis < CIRCUIT_COOLDOWN_MS
            if (circuitOpen) {
                return@parallelCatchingFlatMap emptyList()
            }

            val cacheKey = cacheKey(server, path, title, year, imdbId)

            val cached = synchronized(resultCacheLock) {
                resultCache.get(cacheKey)?.takeIf { it.expiresAtMillis > now }
            }
            if (cached != null) {
                return@parallelCatchingFlatMap buildVideos(server, cached.result, subLimit)
            }

            try {
                val serverUrl = server.apiBase.toHttpUrl().newBuilder().apply {
                    addPathSegments(server.path)
                    addPathSegment("sources-with-title")
                    addEncodedQueryParameter("title", doubleEncode(title))
                    addQueryParameter("mediaType", if (isMovie) "movie" else "tv")
                    addQueryParameter("year", year)
                    addQueryParameter("episodeId", episodeId)
                    addQueryParameter("seasonId", seasonId)
                    addQueryParameter("tmdbId", tmdbId)
                    if (imdbId.isNotBlank()) addQueryParameter("imdbId", imdbId)
                    if (server.language != null) {
                        addQueryParameter("language", server.language)
                    }
                    addQueryParameter("enc", "2")
                    addQueryParameter("seed", seed)
                }.build()

                val encryptedText = client.newCall(
                    GET(serverUrl.toString(), backendHeaders),
                ).awaitSuccess().bodyString()

                val requestBody = mapOf("text" to encryptedText, "id" to tmdbId, "seed" to seed)
                    .toJsonRequestBody()
                val decrypted = client.newCall(POST(DECRYPTION_API_URL, body = requestBody))
                    .awaitSuccess()
                    .parseAs<VideasyDecryptionDto>()
                    .result

                synchronized(resultCacheLock) {
                    resultCache.put(cacheKey, CachedResult(decrypted, now + CACHE_TTL_MS))
                }
                serverFailureState.remove(stateKey)
                buildVideos(server, decrypted, subLimit)
            } catch (e: Throwable) {
                serverFailureState.merge(
                    stateKey,
                    FailureState(1, now),
                ) { old, _ -> FailureState(old.count + 1, now) }
                throw e
            }
        }
    }

    /**
     * VidLink resolves in one signed request: no seed, no external decryption.
     * Responses are either a per-quality map of progressive MP4s or a single
     * adaptive HLS playlist, and `null` means the title simply isn't carried.
     */
    private suspend fun vidLinkVideos(
        tmdbId: String,
        seasonId: String,
        episodeId: String,
        isMovie: Boolean,
        subLimit: Int,
    ): List<Video> {
        val expiry = System.currentTimeMillis() / 1000 + VIDLINK_TOKEN_TTL_SECONDS
        val token = VidLinkToken.create(tmdbId, expiry)

        val url = VIDLINK_API_BASE.toHttpUrl().newBuilder().apply {
            addPathSegments("api/b")
            addPathSegment(if (isMovie) "movie" else "tv")
            addPathSegment(token)
            if (!isMovie) {
                addPathSegment(seasonId)
                addPathSegment(episodeId)
            }
            addQueryParameter("multiLang", "0")
        }.build()

        val vidLinkHeaders = headers.newBuilder()
            .set("Referer", "$VIDLINK_ORIGIN/")
            .set("Origin", VIDLINK_ORIGIN)
            .build()

        val body = client.newCall(GET(url.toString(), vidLinkHeaders))
            .awaitSuccess()
            .bodyString()
            .trim()

        // The API answers a literal `null` for titles it has no source for.
        if (body.isEmpty() || body == "null") return emptyList()

        val stream = body.parseAs<VidLinkResponseDto>().stream ?: return emptyList()

        val subtitles = stream.captions
            .mapNotNull { caption ->
                val subUrl = caption.url ?: return@mapNotNull null
                Track(subUrl, caption.language ?: "Unknown")
            }
            .take(subLimit.coerceAtLeast(0))

        stream.playlist?.takeIf { it.isNotBlank() }?.let { playlist ->
            val expanded = runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = playlist,
                    videoNameGen = { quality ->
                        vidLinkLabel(quality, playlist, subtitles.size)
                    },
                    subtitleList = subtitles,
                    masterHeaders = vidLinkHeaders,
                    videoHeaders = vidLinkHeaders,
                )
            }.getOrDefault(emptyList())

            return expanded.ifEmpty {
                listOf(
                    Video(
                        url = playlist,
                        quality = vidLinkLabel("Auto", playlist, subtitles.size),
                        videoUrl = playlist,
                        headers = vidLinkHeaders,
                        subtitleTracks = subtitles,
                    ),
                )
            }
        }

        // Progressive files: each quality is directly playable, so there is no
        // playlist to expand.
        return stream.qualities.orEmpty()
            .mapNotNull { (label, entry) ->
                val videoUrl = entry.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val quality = if (label.all(Char::isDigit)) "${label}p" else label
                Video(
                    url = videoUrl,
                    quality = vidLinkLabel(quality, videoUrl, subtitles.size),
                    videoUrl = videoUrl,
                    headers = vidLinkHeaders,
                    subtitleTracks = subtitles,
                )
            }
            .sortedByDescending { extractQualityValue(it.quality) }
    }

    private fun vidLinkLabel(quality: String, url: String, subCount: Int): String {
        val parts = mutableListOf(VIDLINK_NAME, quality)
        val lower = url.lowercase()
        when {
            ".m3u8" in lower -> parts += "HLS"
            ".mp4" in lower -> parts += "MP4"
            ".mkv" in lower -> parts += "MKV"
        }
        if (subCount > 0) parts += "$subCount subs"
        return parts.joinToString(" · ")
    }

    private fun pctEncode(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 3)
        for (raw in bytes) {
            val c = raw.toInt() and 0xFF
            val unreserved =
                (c in 0x30..0x39) || // 0-9
                    (c in 0x41..0x5A) || // A-Z
                    (c in 0x61..0x7A) || // a-z
                    c == 0x2D || c == 0x2E || c == 0x5F || c == 0x7E // - . _ ~
            if (unreserved) {
                out.append(c.toChar())
            } else {
                out.append('%')
                out.append(HEX[(c ushr 4) and 0x0F])
                out.append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private fun doubleEncode(s: String): String = pctEncode(pctEncode(s))

    /**
     * Returns true if the given quality string is a language name masquerading
     * as a resolution label. Checks both audioLabel (e.g. "German" from meine)
     * and qualityFilter (e.g. "English"/"Hindi" from hdmovie).
     */
    private fun isLanguageAsQuality(server: VideasyServer, quality: String): Boolean {
        if (qualityRegex.containsMatchIn(quality) || quality.contains("4k", ignoreCase = true)) {
            return false
        }

        if (isGenericQuality(quality)) {
            return true
        }

        return quality.equals(server.audioLabel, ignoreCase = true) ||
            (server.qualityFilter != null && quality.equals(server.qualityFilter, ignoreCase = true))
    }

    private fun isGenericQuality(quality: String): Boolean {
        val normalized = quality.trim().lowercase()
        return normalized in GENERIC_QUALITY_PLACEHOLDERS ||
            normalized.isBlank() ||
            GENERIC_QUALITY_REGEX.matches(normalized)
    }

    /**
     * Returns true if the quality string already represents a real resolution
     * (e.g. "1080p", "720p", "480p", "4K", "2160p", or bare digits like "1080").
     * These don't need HLS expansion — the server already provided the correct label.
     */
    private fun isRealResolution(quality: String): Boolean = quality.isNotBlank() && (
        qualityRegex.containsMatchIn(quality) ||
            quality.contains("4k", ignoreCase = true) ||
            quality.all { it.isDigit() }
        )

    /**
     * Extracts a numeric quality value for sorting. Maps "4K" to 2160
     * so it sorts above 1080p instead of being treated as 0.
     */
    private fun extractQualityValue(quality: String): Int {
        val match = qualityRegex.find(quality)
        if (match != null) {
            return match.groupValues[1].toIntOrNull() ?: 0
        }
        if (quality.contains("4k", ignoreCase = true)) return 2160
        return 0
    }

    private fun buildVideos(
        server: VideasyServer,
        decrypted: VideasyDecryptedResult,
        subLimit: Int,
    ): List<Video> {
        val subtitles = decrypted.subtitles
            .mapNotNull { sub ->
                val u = sub.url ?: return@mapNotNull null
                val l = sub.language ?: return@mapNotNull null
                Track(u, l)
            }
            .take(subLimit.coerceAtLeast(0))

        // Stream CDNs allowlist the player origin: some reject a missing
        // Referer with 403, and all reject an unrecognised one.
        val videoHeaders = headers.newBuilder()
            .set("Referer", "$PLAYER_ORIGIN/")
            .set("Origin", PLAYER_ORIGIN)
            .build()

        val filteredSources = decrypted.sources?.let { sources ->
            server.qualityFilter?.let { filter ->
                sources.filter { it.quality.equals(filter, ignoreCase = true) }
            } ?: sources
        }

        val videos = when {
            !filteredSources.isNullOrEmpty() -> {
                filteredSources.distinctBy { it.url }.flatMap { source ->
                    val rawQuality = source.quality?.takeIf { it.isNotBlank() } ?: "Auto"
                    val isHls = source.url.lowercase().contains(".m3u8")
                    val isDash = source.url.lowercase().contains(".mpd")
                    val isLang = isLanguageAsQuality(server, rawQuality)

                    // Expand when quality is NOT a real resolution AND either:
                    // - URL is .m3u8/.mpd (standard HLS/DASH), or
                    // - Quality is a language name (these are almost always HLS
                    //   even if the URL doesn't contain .m3u8 explicitly)
                    // - Quality is a generic placeholder (e.g. "Auto", "video")
                    //   to catch playlists that lack a file extension.
                    //
                    // FORCE EXPANSION FOR BREACH: m4uhd often returns a master playlist
                    // without a .m3u8 extension. We force it here to extract the variants.
                    val isGeneric = isGenericQuality(rawQuality)
                    val needsExpansion = (!isRealResolution(rawQuality) && (isHls || isDash || isLang || isGeneric)) ||
                        (server.displayName == "Breach")

                    if (needsExpansion) {
                        val expanded = runCatching {
                            playlistUtils.extractFromHls(
                                playlistUrl = source.url,
                                videoNameGen = { quality ->
                                    buildVideoLabel(server, quality, source.url, subtitles.size)
                                },
                                subtitleList = subtitles,
                                masterHeaders = videoHeaders,
                                videoHeaders = videoHeaders,
                            )
                        }.getOrDefault(emptyList())

                        expanded.ifEmpty {
                            listOf(
                                Video(
                                    url = source.url,
                                    quality = buildVideoLabel(server, rawQuality, source.url, subtitles.size),
                                    videoUrl = source.url,
                                    headers = videoHeaders,
                                    subtitleTracks = subtitles,
                                ),
                            )
                        }
                    } else {
                        listOf(
                            Video(
                                url = source.url,
                                quality = buildVideoLabel(server, rawQuality, source.url, subtitles.size),
                                videoUrl = source.url,
                                headers = videoHeaders,
                                subtitleTracks = subtitles,
                            ),
                        )
                    }
                }
            }
            decrypted.streams != null -> {
                decrypted.streams.map { (quality, url) ->
                    Video(
                        url = url,
                        quality = buildVideoLabel(server, quality, url, subtitles.size),
                        videoUrl = url,
                        headers = videoHeaders,
                        subtitleTracks = subtitles,
                    )
                }
            }
            decrypted.url != null -> {
                playlistUtils.extractFromHls(
                    playlistUrl = decrypted.url,
                    videoNameGen = { quality ->
                        buildVideoLabel(server, quality, decrypted.url, subtitles.size)
                    },
                    subtitleList = subtitles,
                    masterHeaders = videoHeaders,
                    videoHeaders = videoHeaders,
                )
            }
            else -> emptyList()
        }

        // Return directly without post-processing to preserve original labels
        return videos.distinctBy { it.videoUrl }
    }

    private fun cacheKey(
        server: VideasyServer,
        path: String,
        title: String,
        year: String,
        imdbId: String,
    ): CacheKey = CacheKey(server, path, title, year, imdbId)

    private fun buildVideoLabel(
        server: VideasyServer,
        quality: String,
        url: String,
        subCount: Int,
    ): String {
        val parts = mutableListOf(server.displayName)

        if (!isLanguageAsQuality(server, quality)) {
            parts += quality
        }

        val isUhd = quality.contains("2160") || quality.contains("4k", ignoreCase = true)
        if (isUhd && !quality.contains("4k", ignoreCase = true)) {
            parts += "4K"
        }

        val lower = url.lowercase()
        val container = when {
            ".m3u8" in lower -> "HLS"
            ".mpd" in lower -> "DASH"
            ".mkv" in lower -> "MKV"
            ".mp4" in lower -> "MP4"
            ".webm" in lower -> "WebM"
            else -> null
        }
        if (container != null) {
            parts += container
        }

        server.audioLabel?.let { parts += "$it audio" }
        if (subCount > 0) {
            parts += "$subCount subs"
        }
        return parts.joinToString(" · ")
    }

    companion object {
        /**
         * Origin the stream CDNs allowlist. Verified: the strict CDNs return
         * 403 with no Referer or an unknown one, and 206 with this value.
         * Not user-configurable — an unrecognised origin breaks playback.
         */
        private const val PLAYER_ORIGIN = "https://player.videasy.to"

        private const val VIDEASY_API_BASE = "https://api.speedracelight.com"
        private const val DECRYPTION_API_URL = "https://enc-dec.app/api/dec-videasy"

        // VidLink: a second, independent backend. It signs its own requests and
        // needs no external decryption service, so it keeps working even if the
        // Videasy chain (seed -> enc=2 -> enc-dec.app) breaks.
        private const val VIDLINK_NAME = "Orion"
        private const val VIDLINK_API_BASE = "https://vidlink.pro"
        private const val VIDLINK_ORIGIN = "https://vidlink.pro"

        // Their token embeds an expiry; the site itself signs ~2 minutes ahead.
        private const val VIDLINK_TOKEN_TTL_SECONDS = 120L
        private const val HEX = "0123456789ABCDEF"

        private const val CACHE_SIZE = 64
        private const val CACHE_TTL_MS = 60_000L

        private const val MAX_SERVER_FAILURES = 2
        private const val CIRCUIT_COOLDOWN_MS = 180_000L

        private val qualityRegex = Regex("""(\d{3,4})[pP]?""")

        private val GENERIC_QUALITY_PLACEHOLDERS = setOf(
            "original",
            "auto",
            "video",
            "full video",
            "watch video",
            "play video",
            "hls",
            "dash",
        )

        private val GENERIC_QUALITY_REGEX = Regex("""^(video|stream|hls|dash)(\s+.*)?$""")

        //   Official servers (verified against website JS + reference table)
        //   Yoru    = cdn                      [MAY HAVE 4K] (api.speedracelight.com)
        //   Cypher  = downloader2                            (api.speedracelight.com)
        //   Breach  = m4uhd                                  (api.speedracelight.com)
        //   Vyse    = hdmovie      [FILTERS quality=English] (api.speedracelight.com)
        //   Killjoy = meine ?lang=german  - German           (api.speedracelight.com)
        //   Fade    = hdmovie      [FILTERS quality=Hindi]   (api.speedracelight.com)
        //   Omen    = lamovie             - Spanish          (api.speedracelight.com)
        //   Raze    = superflix           - Portuguese       (api.speedracelight.com)
        val VIDEASY_SERVERS = listOf(
            VideasyServer(
                "Yoru",
                VIDEASY_API_BASE,
                "cdn",
                mayHave4K = true,
                audioLabel = "Original",
            ),
            VideasyServer(
                "Cypher",
                VIDEASY_API_BASE,
                "downloader2",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Breach",
                VIDEASY_API_BASE,
                "m4uhd",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Vyse",
                VIDEASY_API_BASE,
                "hdmovie",
                qualityFilter = "English",
                audioLabel = "Original",
            ),
            VideasyServer(
                "Killjoy",
                VIDEASY_API_BASE,
                "meine",
                language = "german",
                audioLabel = "German",
            ),
            VideasyServer(
                "Fade",
                VIDEASY_API_BASE,
                "hdmovie",
                qualityFilter = "Hindi",
                audioLabel = "Hindi",
            ),
            VideasyServer(
                "Omen",
                VIDEASY_API_BASE,
                "lamovie",
                audioLabel = "Spanish",
            ),
            VideasyServer(
                "Raze",
                VIDEASY_API_BASE,
                "superflix",
                audioLabel = "Portuguese",
            ),
        )

        /**
         * Servers offered in settings. VidLink is not a Videasy backend, so it
         * is appended rather than derived from [VIDEASY_SERVERS].
         */
        val SERVER_DISPLAY_NAMES: List<String> =
            VIDEASY_SERVERS.map { it.displayName } + VIDLINK_NAME

        /** Audio-language hint shown per server in the preference list. */
        fun audioLabelFor(displayName: String): String = when (displayName) {
            VIDLINK_NAME -> "Original"
            else -> VIDEASY_SERVERS.firstOrNull { it.displayName == displayName }
                ?.audioLabel ?: "Unknown"
        }
    }
}
