package eu.kanade.tachiyomi.animeextension.en.rentaro

import android.os.Build
import android.util.Base64
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
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

    /**
     * One list per backend, destructured at the call site.
     *
     * A data class rather than a Triple because there are four backends now, and
     * a positional Quadruple would make the awaits easy to mismatch.
     */
    private data class BackendResults(
        val videasy: List<Video>,
        val vidLink: List<Video>,
        val nexus: List<Video>,
        val cineJoy: List<Video>,
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
        enabledNexusProviders: Set<String> = NEXUS_PROVIDER_DEFAULT,
        enabledCineJoyServers: Set<String> = CINEJOY_SERVER_DEFAULT,
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
        val nexusEnabled = NEXUS_NAME in enabledServers
        val cineJoyEnabled = CINEJOY_NAME in enabledServers

        if (eligibleServers.isEmpty() && !vidLinkEnabled && !nexusEnabled && !cineJoyEnabled) {
            return emptyList()
        }

        // The Videasy backends ignore Referer/Origin entirely, but the stream
        // CDNs allowlist the player origin, so send it on every request.
        val backendHeaders = headers.newBuilder()
            .set("Referer", "$PLAYER_ORIGIN/")
            .set("Origin", PLAYER_ORIGIN)
            .build()

        // The four backends are unrelated services, so they are resolved
        // concurrently: the wait becomes the slowest of them rather than their
        // sum. Each keeps its own failure handling, since they differ.
        //
        // Servers *within* a backend were already parallel; it was only these
        // that ran one after another.
        val (videasyVideos, vidLinkVideos, nexusVideos, cineJoyVideos) = coroutineScope {
            val videasyTask = async {
                if (eligibleServers.isEmpty()) {
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
            }

            // VidLink is an independent backend, so a Videasy-wide failure (a
            // bad seed, enc-dec.app being down) must not take it with it.
            //
            // Only IOException is absorbed here. A blanket catch previously hid
            // a NoSuchFieldError thrown during token class-init on older
            // devices, so the server silently vanished instead of surfacing the
            // fault.
            val vidLinkTask = async {
                if (!vidLinkEnabled) {
                    emptyList()
                } else {
                    try {
                        vidLinkVideos(tmdbId, seasonId, episodeId, isMovie, subLimit)
                    } catch (e: IOException) {
                        emptyList()
                    }
                }
            }

            // Nexus is a third independent backend with its own encrypted API.
            val nexusTask = async {
                if (!nexusEnabled) {
                    emptyList()
                } else {
                    try {
                        nexusVideos(tmdbId, imdbId, seasonId, episodeId, isMovie, enabledNexusProviders)
                    } catch (_: IOException) {
                        emptyList()
                    }
                }
            }

            // CineJoy is a fourth independent backend, reached through its own
            // encrypt/decrypt chain.
            val cineJoyTask = async {
                if (!cineJoyEnabled) {
                    emptyList()
                } else {
                    try {
                        cineJoyVideos(
                            title,
                            year,
                            imdbId,
                            tmdbId,
                            seasonId,
                            episodeId,
                            isMovie,
                            enabledCineJoyServers,
                            subLimit,
                        )
                    } catch (_: IOException) {
                        emptyList()
                    }
                }
            }

            BackendResults(
                videasyTask.await(),
                vidLinkTask.await(),
                nexusTask.await(),
                cineJoyTask.await(),
            )
        }

        // Grouped by server, best quality first inside each group.
        //
        // Every label is built as "<server> · <detail>…", so the leading segment
        // identifies the group. Server order follows the catalogue rather than
        // the alphabet, which keeps a preferred server near the top instead of
        // scattering one server's entries through the list. Preferred Quality
        // then orders entries within a group, not across the whole list.
        val serverRank = SERVER_ORDER_HINT.withIndex().associate { (index, name) -> name to index }

        return (videasyVideos + vidLinkVideos + nexusVideos + cineJoyVideos)
            .groupBy { videoServerName(it.quality) }
            .toList()
            .sortedBy { (server, _) ->
                // Art and Jay fan out to "Art/<provider>" and "Jay/<server>", so
                // rank on the base name and keep their entries adjacent. Unknown
                // names sort last, stably.
                serverRank[server.substringBefore('/')] ?: serverRank.size
            }
            .flatMap { (_, group) ->
                group.sortedWith(
                    compareByDescending<Video> {
                        it.quality.contains(qualityPref, ignoreCase = true) ||
                            (qualityPref == "2160" && it.quality.contains("4k", ignoreCase = true))
                    }.thenByDescending {
                        extractQualityValue(it.quality)
                    },
                )
            }
    }

    /**
     * The leading segment of a video label, which every builder sets to the
     * server that produced the entry. Art keeps its "Art/<provider>" form so
     * each provider groups separately while staying next to its siblings.
     */
    private fun videoServerName(label: String): String = label.substringBefore(" · ").trim()

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

        val apiHeaders = headers.newBuilder()
            .set("Referer", "$VIDLINK_ORIGIN/")
            .set("Origin", VIDLINK_ORIGIN)
            // Without this the API answers with progressive HEVC MP4s whose CDN
            // rejects direct requests. With it, the same call returns a DASH
            // manifest plus the signed cookie needed to fetch it.
            .set("X-Playback-Environment", VIDLINK_PLAYBACK_ENV)
            .build()

        val body = client.newCall(GET(url.toString(), apiHeaders))
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

        // The CDN authorises by signed CloudFront cookie, not by Referer: the
        // manifest and every segment 403 without it. Header names are copied
        // verbatim from the response so a future addition is picked up too.
        val streamHeaders = headers.newBuilder().apply {
            stream.playlistHeaders.orEmpty().forEach { (name, value) ->
                set(name, value)
            }
        }.build()

        stream.playlist?.takeIf { it.isNotBlank() }?.let { playlist ->
            val meta = stream.playbackMetadata
            val isDash = stream.type.equals("dash", ignoreCase = true) ||
                meta?.format.equals("DASH", ignoreCase = true) ||
                playlist.endsWith(".mpd", ignoreCase = true)

            // PlaylistUtils parses HLS only; a DASH manifest is handed to the
            // player whole, which resolves its own representations.
            if (isDash) {
                val label = meta?.resolutions
                    ?.mapNotNull(String::toIntOrNull)
                    ?.maxOrNull()
                    ?.let { "${it}p" }
                    ?: "Auto"
                return listOf(
                    Video(
                        url = playlist,
                        quality = vidLinkLabel(label, playlist, subtitles.size, meta?.codecName),
                        videoUrl = playlist,
                        headers = streamHeaders,
                        subtitleTracks = subtitles,
                    ),
                )
            }

            val expanded = runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = playlist,
                    videoNameGen = { quality ->
                        vidLinkLabel(quality, playlist, subtitles.size)
                    },
                    subtitleList = subtitles,
                    masterHeaders = streamHeaders,
                    videoHeaders = streamHeaders,
                )
            }.getOrDefault(emptyList())

            return expanded.ifEmpty {
                listOf(
                    Video(
                        url = playlist,
                        quality = vidLinkLabel("Auto", playlist, subtitles.size),
                        videoUrl = playlist,
                        headers = streamHeaders,
                        subtitleTracks = subtitles,
                    ),
                )
            }
        }

        // Progressive files: each quality is directly playable, so there is no
        // playlist to expand. These carry no signed cookie, so the CDN needs the
        // player origin as Referer instead.
        val progressiveHeaders = headers.newBuilder()
            .set("Referer", "$VIDLINK_CDN_ORIGIN/")
            .set("Origin", VIDLINK_CDN_ORIGIN)
            .build()

        return stream.qualities.orEmpty()
            .mapNotNull { (label, entry) ->
                val videoUrl = entry.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val quality = if (label.all(Char::isDigit)) "${label}p" else label
                Video(
                    url = videoUrl,
                    quality = vidLinkLabel(quality, videoUrl, subtitles.size, entry.codecName),
                    videoUrl = videoUrl,
                    headers = progressiveHeaders,
                    subtitleTracks = subtitles,
                )
            }
            .sortedByDescending { extractQualityValue(it.quality) }
    }

    private fun vidLinkLabel(
        quality: String,
        url: String,
        subCount: Int,
        codec: String? = null,
    ): String {
        val parts = mutableListOf(VIDLINK_NAME, quality)
        val lower = url.lowercase()
        when {
            ".m3u8" in lower -> parts += "HLS"
            ".mp4" in lower -> parts += "MP4"
            ".mkv" in lower -> parts += "MKV"
        }
        // HEVC needs hardware support; label it so a failure to play is
        // attributable rather than mysterious.
        if (codec != null && HEVC_NAMES.any { codec.equals(it, ignoreCase = true) }) {
            parts += "HEVC"
        }
        if (subCount > 0) parts += "$subCount subs"
        return parts.joinToString(" · ")
    }

    // ======================= CineJoy (Jay) backend =======================

    /**
     * CineJoy is a fourth independent backend, and the only one whose request
     * body has to be built remotely. Three calls per server:
     *
     *  1. `enc-cinejoy?url=…`  the query is handed to enc-dec.app, which
     *     returns the opaque POST body plus the `state` needed to read the
     *     reply.
     *  2. `POST api.shegu.st/g`  the body from step 1, sent as raw bytes;
     *     answers ciphertext.
     *  3. `POST dec-cinejoy`  ciphertext plus `state` back to enc-dec.app,
     *     which returns the stream list as plain JSON.
     *
     * Each upstream server is a separate chain, so they are resolved
     * concurrently and one failing cannot lose the others.
     */
    private suspend fun cineJoyVideos(
        title: String,
        year: String,
        imdbId: String,
        tmdbId: String,
        seasonId: String,
        episodeId: String,
        isMovie: Boolean,
        enabledServers: Set<String>,
        subLimit: Int,
    ): List<Video> {
        if (enabledServers.isEmpty()) return emptyList()

        return CINEJOY_SERVERS.filter { it in enabledServers }.parallelCatchingFlatMap { server ->
            cineJoyVideosForServer(
                server,
                title,
                year,
                imdbId,
                tmdbId,
                seasonId,
                episodeId,
                isMovie,
                subLimit,
            )
        }
    }

    private suspend fun cineJoyVideosForServer(
        server: String,
        title: String,
        year: String,
        imdbId: String,
        tmdbId: String,
        seasonId: String,
        episodeId: String,
        isMovie: Boolean,
        subLimit: Int,
    ): List<Video> {
        // The upstream query the backend expects, before encryption. Built with
        // HttpUrl so a title needing escaping cannot break the URL, then handed
        // to enc-dec.app as a single encoded `url` parameter.
        val upstream = CINEJOY_API_BASE.toHttpUrl().newBuilder().apply {
            addQueryParameter("title", title)
            addQueryParameter("type", if (isMovie) "movie" else "series")
            addQueryParameter("year", year)
            addQueryParameter("imdb", imdbId)
            addQueryParameter("tmdb", tmdbId)
            addQueryParameter("server", server)
            if (!isMovie) {
                addQueryParameter("season", seasonId)
                addQueryParameter("episode", episodeId)
            }
        }.build().toString()

        // enc-dec.app sits behind Cloudflare and answers 403 (error 1010) to a
        // request without a browser User-Agent; the shared headers carry one.
        val encUrl = "$CINEJOY_ENC_URL?url=${URLEncoder.encode(upstream, "UTF-8")}"
        val enc = client.newCall(GET(encUrl, headers))
            .awaitSuccess()
            .parseAs<CineJoyEncDto>()
        if (enc.status != HTTP_OK) return emptyList()
        val encResult = enc.result ?: return emptyList()
        val payload = encResult.data?.takeIf { it.isNotBlank() } ?: return emptyList()
        val state = encResult.state ?: return emptyList()

        // The site posts these bytes verbatim; it is ciphertext, not JSON, so it
        // is sent as an octet-stream rather than through a JSON body helper.
        val siteHeaders = headers.newBuilder()
            .set("Referer", "$CINEJOY_ORIGIN/")
            .set("Origin", CINEJOY_ORIGIN)
            .build()
        val encryptedBody = base64UrlDecode(payload)
            .toRequestBody(OCTET_STREAM)
        val encrypted = client.newCall(POST(CINEJOY_UPSTREAM_URL, siteHeaders, encryptedBody))
            .awaitSuccess()
            .body
            .bytes()
        if (encrypted.isEmpty()) return emptyList()

        val decBody = buildJsonObject {
            put("text", base64UrlEncode(encrypted))
            put("state", state)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val dec = client.newCall(POST(CINEJOY_DEC_URL, headers, decBody))
            .awaitSuccess()
            .parseAs<CineJoyDecDto>()
        if (dec.status != HTTP_OK) return emptyList()

        // A server with no match for the title reports it by omitting `stream`
        // rather than by status code.
        val streams = dec.result?.data?.stream.orEmpty()
        if (streams.isEmpty()) return emptyList()

        return streams.flatMap { stream -> cineJoyVideosForStream(server, stream, subLimit) }
    }

    private fun cineJoyVideosForStream(
        server: String,
        stream: CineJoyStreamDto,
        subLimit: Int,
    ): List<Video> {
        val subtitles = stream.captions
            .mapNotNull { caption ->
                val url = caption.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Track(url, caption.language ?: "Unknown")
            }
            .take(subLimit.coerceAtLeast(0))

        // Verified against the live API: the playlist and its segments serve
        // without a Referer, but the site sends one and the CDN accepts it, so
        // it is kept for consistency with the call that produced the URL.
        val streamHeaders = headers.newBuilder()
            .set("Referer", "$CINEJOY_ORIGIN/")
            .build()

        // Only an absolute URL is usable. Sakura answers `"playlist": "sub"` and
        // `"dub"` — a SUB/DUB pair it advertises as `hls` but never resolves to
        // an address — and Canaias does the same in `qualities` with a slug like
        // "redeflix-720p". Both would reach the player as an unresolvable host.
        stream.playlist?.takeIf { it.startsWith("http") }?.let { playlist ->
            // Master playlists here carry up to 2160p plus several audio
            // renditions, so they are expanded: handed over whole the player
            // would offer one unselectable rendition.
            val expanded = runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = playlist,
                    videoNameGen = { variant ->
                        cineJoyLabel(server, stream.id, variant, playlist, subtitles.size)
                    },
                    subtitleList = subtitles,
                    masterHeaders = streamHeaders,
                    videoHeaders = streamHeaders,
                )
            }.getOrDefault(emptyList())

            return expanded.ifEmpty {
                listOf(
                    Video(
                        url = playlist,
                        quality = cineJoyLabel(server, stream.id, "Auto", playlist, subtitles.size),
                        videoUrl = playlist,
                        headers = streamHeaders,
                        subtitleTracks = subtitles,
                    ),
                )
            }
        }

        // Progressive entries advertise a quality map, but the live API returns
        // a slug there ("redeflix-720p") rather than a URL. Anything that is not
        // an absolute URL would fail in the player, so it is dropped here.
        return stream.qualities.orEmpty().mapNotNull { (label, entry) ->
            val videoUrl = entry.url?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val quality = if (label.all(Char::isDigit)) "${label}p" else label
            Video(
                url = videoUrl,
                quality = cineJoyLabel(server, stream.id, quality, videoUrl, subtitles.size),
                videoUrl = videoUrl,
                headers = streamHeaders,
                subtitleTracks = subtitles,
            )
        }
    }

    /**
     * Builds a picker label, e.g. `Jay/Lisbon · 1080p · HLS · 2 subs`.
     *
     * The upstream stream id is only included when it adds something: every
     * adaptive entry calls itself "primary", which would repeat in every row.
     */
    private fun cineJoyLabel(
        server: String,
        streamId: String?,
        quality: String,
        url: String,
        subCount: Int,
    ): String {
        val parts = mutableListOf("$CINEJOY_NAME/$server")
        streamId
            ?.takeIf { it.isNotBlank() && !it.equals("primary", ignoreCase = true) }
            ?.let { parts += it }
        parts += quality
        // The container comes from the URL; `quality` is a resolution such as
        // "1080p" and never carries an extension.
        val lower = url.lowercase()
        when {
            ".m3u8" in lower -> parts += "HLS"
            ".mp4" in lower -> parts += "MP4"
            ".mkv" in lower -> parts += "MKV"
        }
        if (subCount > 0) parts += "$subCount subs"
        return parts.joinToString(" · ")
    }

    private fun base64UrlDecode(value: String): ByteArray {
        val padded = value.padEnd(value.length + (4 - value.length % 4) % 4, '=')
        return Base64.decode(padded, Base64.URL_SAFE)
    }

    private fun base64UrlEncode(value: ByteArray): String = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    // ======================== Nexus (Art) backend ========================

    private val nexusJson = Json { ignoreUnknownKeys = true }

    /**
     * Builds the plaintext the encrypted `?q=` parameter carries.
     *
     * `_req_ts` and `_req_salt` mirror what the site's own client appends; the
     * backend tolerates them and echoes them back. Assembled through
     * JsonObject rather than string concatenation so a value needing escaping
     * cannot produce malformed JSON.
     *
     * Deliberately omits `method`. Sending `method=dl` restricts /api/servers
     * to the four providers that expose downloadable files; without it the same
     * call advertises all 27, including the DASH-only ones. The site's own
     * player sends no `method` here.
     */
    private fun nexusPayload(
        tmdbId: Int,
        imdbId: String,
        type: String,
        seasonId: String,
        episodeId: String,
        provider: String? = null,
    ): String = buildJsonObject {
        put("tmdbId", tmdbId)
        put("imdb_id", imdbId)
        put("type", type)
        put("season", seasonId)
        put("episode", episodeId)
        if (provider != null) put("provider", provider)
        put("_req_ts", System.currentTimeMillis())
        put("_req_salt", randomSalt())
    }.toString()

    /**
     * Nexus is a third independent backend, encrypted symmetrically in both
     * directions. /api/servers lists the scrapers carrying the title and
     * /api/sources resolves each one to direct files.
     *
     * Only the scrapers named in [enabledProviders] are resolved. The backend
     * advertises 27 and each is a separate upstream request, so resolving all
     * of them costs a burst of traffic for providers that mostly answer 404.
     */
    private suspend fun nexusVideos(
        tmdbId: String,
        imdbId: String,
        seasonId: String,
        episodeId: String,
        isMovie: Boolean,
        enabledProviders: Set<String>,
    ): List<Video> {
        if (enabledProviders.isEmpty()) return emptyList()

        val tmdbInt = tmdbId.toIntOrNull() ?: return emptyList()
        val type = if (isMovie) "movie" else "tv"

        val serversQuery = NexusCrypto.encode(
            nexusPayload(tmdbInt, imdbId, type, seasonId, episodeId),
        )

        val nexusHeaders = headers.newBuilder()
            .set("Referer", "$NEXUS_ORIGIN/")
            .set("Accept", "application/json")
            .build()

        val serversUrl = "$NEXUS_API_BASE/api/servers?q=${URLEncoder.encode(serversQuery, "UTF-8")}"
        val serversBody = client.newCall(GET(serversUrl, nexusHeaders))
            .awaitSuccess()
            .bodyString()

        val envelope = nexusJson.decodeFromString<NexusEnvelopeDto>(serversBody)
        val serversJson = NexusCrypto.decode(envelope.hash ?: return emptyList())
            ?: return emptyList()
        val servers = nexusJson.decodeFromString<NexusServersDto>(serversJson).servers
            .filter { it.scraper != null && it.scraper in enabledProviders }
        if (servers.isEmpty()) return emptyList()

        // Every scraper proxies a different upstream site, so one being down or
        // slow must not lose the rest.
        return servers.parallelCatchingFlatMap { server ->
            nexusSourcesForServer(
                server,
                tmdbInt,
                imdbId,
                type,
                seasonId,
                episodeId,
                nexusHeaders,
            )
        }
    }

    private suspend fun nexusSourcesForServer(
        server: NexusServerDto,
        tmdbId: Int,
        imdbId: String,
        type: String,
        seasonId: String,
        episodeId: String,
        nexusHeaders: Headers,
    ): List<Video> {
        val provider = server.scraper ?: return emptyList()
        val serverName = server.name ?: provider

        val sourcesQuery = NexusCrypto.encode(
            nexusPayload(tmdbId, imdbId, type, seasonId, episodeId, provider),
        )
        val sourcesUrl = "$NEXUS_API_BASE/api/sources?q=${URLEncoder.encode(sourcesQuery, "UTF-8")}"

        val sourcesBody = client.newCall(GET(sourcesUrl, nexusHeaders))
            .awaitSuccess()
            .bodyString()

        val srcEnvelope = nexusJson.decodeFromString<NexusEnvelopeDto>(sourcesBody)
        val srcJson = NexusCrypto.decode(srcEnvelope.hash ?: return emptyList())
            ?: return emptyList()
        val sourcesDto = nexusJson.decodeFromString<NexusSourcesDto>(srcJson)

        // A provider with no match reports it here rather than by status code.
        if (!sourcesDto.error.isNullOrBlank()) return emptyList()

        val playable = sourcesDto.sources.mapNotNull { source ->
            val rawUrl = source.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Embeds are player pages, not streams.
            if (source.isEmbed == true) return@mapNotNull null
            // Verified against the live API: these answer text/html landing
            // pages rather than media, so they would only fail in the player.
            if (isNexusLandingPage(rawUrl)) return@mapNotNull null

            val url = sanitiseNexusUrl(rawUrl)

            val quality = source.quality?.takeIf { it.isNotBlank() }
                ?: source.label?.takeIf { it.isNotBlank() }
                ?: "Auto"

            // These CDNs disagree about the Referer, so it is applied per host
            // rather than globally.
            //
            // Verified against the live API: the Cloudflare Worker hosts
            // (StremFx, Lolly, Stvvid) answer 403 without it and 206 with it, a
            // wrong value is rejected too, and Origin alone does not help.
            // VidPi is the opposite — its segment host serves media bare and
            // 403s once any Referer is present — so sending it unconditionally
            // trades three broken providers for a different broken one.
            //
            // The worker subdomain rotates on every request
            // (mp4.shafer15c51d, mp4.gyimah15c2da, ...), so the registrable
            // suffix is the only stable thing to key on. A header map supplied
            // by the backend still overrides this.
            val videoHeaders = headers.newBuilder()
                .apply {
                    if (needsNexusReferer(url)) set("Referer", "$NEXUS_ORIGIN/")
                    source.headers?.forEach { (name, value) -> set(name, value) }
                }
                .build()

            NexusCandidate(
                url = url,
                label = nexusLabel(serverName, quality, url, source.type),
                videoHeaders = videoHeaders,
                type = source.type,
            )
        }

        // Distinct releases can still share a label once the same file is
        // offered on several hosts. Those mirrors are worth keeping as
        // fallbacks — these hosts die often — but need numbering so they read
        // as alternates rather than as a glitch.
        val labelCounts = playable.groupingBy { it.label }.eachCount()
        val seen = mutableMapOf<String, Int>()

        return playable.flatMap { candidate ->
            val nth = seen.merge(candidate.label, 1, Int::plus)!!
            val label = if (labelCounts[candidate.label]!! > 1) {
                "${candidate.label} · $nth"
            } else {
                candidate.label
            }

            val asSingleFile = listOf(
                Video(
                    url = candidate.url,
                    quality = label,
                    videoUrl = candidate.url,
                    headers = candidate.videoHeaders,
                ),
            )

            // Dropping `method=dl` widened the catalogue from four
            // download-only providers to all 27, which brought adaptive
            // playlists with it. A master playlist handed to the player as
            // though it were a file offers a single unselectable rendition, so
            // HLS is expanded into its variants here. DASH has no equivalent
            // parser and is passed through whole for the player to resolve.
            val isHls = candidate.type.equals("hls", ignoreCase = true) ||
                candidate.type.equals("m3u8", ignoreCase = true) ||
                ".m3u8" in candidate.url.lowercase()
            if (!isHls) return@flatMap asSingleFile

            runCatching {
                playlistUtils.extractFromHls(
                    playlistUrl = candidate.url,
                    videoNameGen = { variant -> "$label · $variant" },
                    masterHeaders = candidate.videoHeaders,
                    videoHeaders = candidate.videoHeaders,
                )
            }.getOrDefault(emptyList()).ifEmpty { asSingleFile }
        }
    }

    /**
     * Whether a Nexus stream host requires the site Referer.
     *
     * The Cloudflare Worker hosts allowlist the site origin and answer 403
     * without it. Other hosts are either indifferent or actively reject it, so
     * this is deliberately a narrow allowlist rather than a default.
     */
    private fun needsNexusReferer(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        return NEXUS_REFERER_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Whether a Nexus source URL is a hubcloud landing page rather than a file.
     *
     * hubcloud rotates its registrable domain (`hubcloud.cx`, `.ist`, `.one`
     * and `.fans` have all been seen; the site links between them and Nexus
     * currently normalises to `.cx`), so the TLD cannot be part of the test.
     * What is stable is the host label plus the first path segment:
     *
     *     drive  file listing, needs a further hop to reach media
     *     tg     hands off to telegram.me/<bot>
     *     none   a bare host, e.g. the `pixel.` and `gpdl2.` subdomains whose
     *            `?id=` redirects to a worker that answers 500
     *
     * None of these carry media, yet the backend advertises them as `mp4`, so
     * they would only fail once the player had already committed to them.
     *
     * A file is never served from the host root, so a URL with no path segment
     * is a landing page whatever its query carries. Segments outside the
     * blocklist are kept: the `re` redirect does reach a file.
     */
    private fun isNexusLandingPage(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.host.split('.').none { it == "hubcloud" }) return false

        val firstSegment = parsed.pathSegments.firstOrNull { it.isNotEmpty() }
            ?: return true
        return firstSegment in NEXUS_LANDING_PATH_SEGMENTS
    }

    /** A Nexus source that passed filtering, before HLS expansion. */
    private data class NexusCandidate(
        val url: String,
        val label: String,
        val videoHeaders: Headers,
        val type: String?,
    )

    /**
     * Percent-encodes whitespace in a Nexus source URL.
     *
     * k4khdhub embeds the release filename in the path unencoded, so the URL
     * arrives with literal spaces ("…/1397996373/Fight Club (1999) REPACK…").
     * OkHttp rejects that outright, which made a working provider look dead;
     * encoding the spaces returns the file. Only whitespace is touched, so an
     * already-encoded URL is left byte-identical rather than double-encoded.
     */
    private fun sanitiseNexusUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.none(Char::isWhitespace)) return trimmed
        return buildString(trimmed.length) {
            trimmed.forEach { c ->
                when (c) {
                    ' ' -> append("%20")
                    '\t' -> append("%09")
                    '\n', '\r' -> Unit
                    else -> append(c)
                }
            }
        }
    }

    /**
     * Builds a picker label from a Nexus quality string.
     *
     * The two backends word these very differently and both carry the detail
     * that separates otherwise identical entries, so it has to survive:
     *
     *     "Hindi dub : 1080"                              -> 1080p · Hindi dub
     *     "20.26 GB | 1080p | Hindi | English | BluRay"    -> 1080p · BluRay · 20.26 GB
     *
     * Reducing these to the resolution alone made seven different language
     * tracks display as seven identical rows.
     */
    private fun nexusLabel(serverName: String, quality: String, url: String, type: String? = null): String {
        val parts = mutableListOf("$NEXUS_NAME/${shortenNexusServer(serverName)}")

        val resolution = qualityRegex.find(quality)?.groupValues?.get(1)
        when {
            resolution != null -> parts += "${resolution}p"
            quality.contains("4k", ignoreCase = true) -> parts += "4K"
            else -> parts += quality.take(NEXUS_LABEL_LIMIT).trim()
        }

        // "Hindi dub : 1080" style: the audio descriptor precedes the colon and
        // is the only thing distinguishing these entries from one another.
        quality.substringBefore(':', "")
            .takeIf { it.isNotBlank() && it.length <= NEXUS_AUDIO_LIMIT }
            ?.trim()
            ?.let { parts += it }

        // "… | 1080p | Hindi | BluRay | x265 …" style: keep the source and codec
        // tags, which separate a BluRay rip from a WEB-DL of the same height.
        val tags = quality.split('|').map { it.trim() }
        NEXUS_RELEASE_TAGS.firstOrNull { tag -> tags.any { it.equals(tag, ignoreCase = true) } }
            ?.let { parts += it }
        if (tags.any { it.equals("HEVC", ignoreCase = true) || it.equals("x265", ignoreCase = true) }) {
            parts += "HEVC"
        }
        // Size is what separates the 66 GB, 41 GB and 20 GB releases.
        tags.firstOrNull { NEXUS_SIZE_REGEX.matches(it) }?.let { parts += it }

        // The backend's own `type` is authoritative and covers the adaptive
        // sources whose URL carries no usable extension; the URL is only a
        // fallback for entries that omit it.
        val lower = url.lowercase()
        val container = when {
            type.equals("mpd", ignoreCase = true) || ".mpd" in lower -> "DASH"
            type.equals("hls", ignoreCase = true) ||
                type.equals("m3u8", ignoreCase = true) || ".m3u8" in lower -> "HLS"
            ".mkv" in lower -> "MKV"
            ".mp4" in lower || type.equals("mp4", ignoreCase = true) -> "MP4"
            else -> null
        }
        if (container != null) parts += container
        return parts.joinToString(" · ")
    }

    /**
     * Trims the trailing tag from a Nexus server name, e.g.
     *
     *     "MbPly-[Multi-Lang]"      -> "MbPly"
     *     "Nitro - [Multi-Lang]"    -> "Nitro"
     *     "FlyVid (FHD)"            -> "FlyVid"
     *
     * The tag is redundant next to the per-entry language and resolution the
     * label already carries. The separator varies between names, so it is
     * consumed along with the bracket rather than left dangling, and names
     * without a tag ("4k-Hub") keep their own hyphens intact.
     */
    private fun shortenNexusServer(name: String): String = NEXUS_SERVER_TAG_REGEX.replace(name, "")
        .trim()
        .trimEnd('-')
        .trim()
        .ifBlank { name }

    private fun randomSalt(): String = (1..NEXUS_SALT_LENGTH).map { NEXUS_SALT_ALPHABET.random() }.joinToString("")

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

        /**
         * Sent as `X-Playback-Environment` on the API call. The site's own
         * player sends this, and it changes the response substantially:
         * without it the API returns progressive HEVC MP4s on a CDN that
         * rejects direct requests, with it a DASH manifest plus the signed
         * CloudFront cookie needed to fetch it. Captured from a working
         * browser session and confirmed against the live API.
         */
        private const val VIDLINK_PLAYBACK_ENV = "dash-hevc"

        // CineJoy: a fourth independent backend. Unlike the others its request
        // body is built remotely, so a call needs enc-dec.app on the way out as
        // well as on the way back.
        private const val CINEJOY_NAME = "Jay"
        private const val CINEJOY_API_BASE = "https://api.shegu.st/"
        private const val CINEJOY_UPSTREAM_URL = "https://api.shegu.st/g"
        private const val CINEJOY_SERVERS_URL = "https://api.shegu.st/servers"
        private const val CINEJOY_ORIGIN = "https://cinejoy.to"
        private const val CINEJOY_ENC_URL = "https://enc-dec.app/api/enc-cinejoy"
        private const val CINEJOY_DEC_URL = "https://enc-dec.app/api/dec-cinejoy"

        private const val HTTP_OK = 200

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        /**
         * The CineJoy upstream servers offered in settings, with the region the
         * backend's own `/servers` flag reports.
         *
         * Hard-coded rather than read from `/servers` so the preference list can
         * be built without a network call, matching how the Art providers are
         * handled. Verified against the live endpoint, which returned exactly
         * these eight: six US, one JP, one BR.
         */
        val CINEJOY_SERVERS: List<String> = listOf(
            "Lisbon",
            "Nebula",
            "Solara",
            "Castle",
            "Athens",
            "Joy",
            "Sakura",
            "Canaias",
        )

        /** Region each server reports, for the picker note. */
        private val CINEJOY_SERVER_REGION = mapOf(
            "Lisbon" to "US",
            "Nebula" to "US",
            "Solara" to "US",
            "Castle" to "US",
            "Athens" to "US",
            "Joy" to "US",
            "Sakura" to "JP",
            "Canaias" to "BR",
        )

        /**
         * Servers enabled out of the box: the ones that answered with a playlist
         * that actually serves.
         *
         * These are the four US servers that work, which makes the default
         * effectively US-only. That is the backend's catalogue, not a choice:
         * of the eight servers six are US, and the only two non-US options both
         * answer with placeholders rather than addresses (see below). There is
         * no non-US server available to enable.
         *
         * The defaults do carry anime despite the US flag - Lisbon and Solara
         * returned a working ladder for every anime title tried, Castle for
         * about half with subtitle tracks, Nebula for a third.
         */
        val CINEJOY_SERVER_DEFAULT: Set<String> = setOf(
            "Lisbon",
            "Nebula",
            "Solara",
            "Castle",
        )

        /**
         * Servers that never resolved to a playable address, with the reason the
         * backend itself gives. Each was retested against content matching its
         * own region flag, so none of these is an artefact of the probe set:
         *
         *  - Athens (US): answers `"Athens: empty mirror list for /e/movie/550"`
         *    for all 20 titles tried. It has catalogue entries but no mirrors
         *    behind them.
         *  - Joy (US): answers `"Joy: no entry for movie 550"` for all 20.
         *    Nothing catalogued.
         *  - Canaias (BR): does carry Brazilian titles and dubbed Hollywood, but
         *    every `qualities.url` is a slug ("redeflix-720p", "digitalplus").
         *  - Sakura (JP): anime-only, and responds for most anime series, but
         *    only ever as `"playlist": "sub"` / `"dub"`.
         *
         * The slugs are terminal: no resolver endpoint exists for them, and
         * re-requesting with `&source=<slug>` returns the same slug list.
         * [cineJoyVideosForStream] therefore drops anything that is not an
         * absolute URL. If the backend starts returning real addresses, these
         * become usable by enabling them here - no other change needed.
         */
        private val CINEJOY_KNOWN_DEAD = setOf("Athens", "Joy")

        /** Servers whose response is a placeholder slug rather than a URL. */
        private val CINEJOY_PLACEHOLDER_ONLY = setOf("Sakura", "Canaias")

        /** Entry labels for the CineJoy server preference, ordered as the list is. */
        fun cineJoyServerEntries(): List<String> = CINEJOY_SERVERS.map { server ->
            val region = CINEJOY_SERVER_REGION[server]?.let { " ($it)" } ?: ""
            val note = when (server) {
                "Sakura" -> " - anime only, no playable URL yet"
                in CINEJOY_PLACEHOLDER_ONLY -> " - no playable URL yet"
                in CINEJOY_KNOWN_DEAD -> " - no video when tested"
                else -> ""
            }
            "$server$region$note"
        }

        /**
         * Origin the progressive-file CDN allowlists. Only used for the
         * `qualities` fallback, since the DASH path authorises by cookie.
         */
        private const val VIDLINK_CDN_ORIGIN = "https://filmboom.top"

        // Nexus: third independent backend (web.nxsha.app). Encrypted API, no
        // external decryption service needed.
        private const val NEXUS_NAME = "Art"
        private const val NEXUS_API_BASE = "https://web.nxsha.app"
        private const val NEXUS_ORIGIN = "https://web.nxsha.app"

        /**
         * Hosts that answer an HTML landing page instead of media. Confirmed
         * against the live API, where these returned `text/html` for a source
         * the backend still advertised as a playable file.
         *
         * First path segments on a hubcloud host that mean "landing page", not
         * "file". Matched against the host label rather than a full domain
         * because hubcloud rotates its TLD - see [isNexusLandingPage].
         *
         * `drive` is a file listing needing another hop; `tg` is a Telegram
         * hand-off that redirects out to telegram.me.
         */
        private val NEXUS_LANDING_PATH_SEGMENTS = setOf("drive", "tg")

        /** Keeps a verbose Nexus quality string from overflowing the picker. */
        private const val NEXUS_LABEL_LIMIT = 40

        /**
         * Caps the "Hindi dub"-style audio descriptor. Anything longer is not a
         * language tag but the whole quality string lacking a resolution, which
         * the fallback branch already handles.
         */
        private const val NEXUS_AUDIO_LIMIT = 24

        /** Release-source tags worth surfacing, best-quality first. */
        private val NEXUS_RELEASE_TAGS = listOf(
            "BluRay",
            "WEB-DL",
            "WEBRip",
            "HDTS",
            "HDTV",
            "CAM",
        )

        /** Matches a size tag such as "20.26 GB" or "643.3 MB". */
        private val NEXUS_SIZE_REGEX = Regex("""\d+(\.\d+)?\s*[MG]B""", RegexOption.IGNORE_CASE)

        /**
         * Stream hosts that require the site Referer, matched on the
         * registrable suffix because the subdomain rotates per request.
         *
         * Kept as an allowlist: VidPi's segment host serves media bare and
         * rejects the header outright, so this cannot be a global default.
         */
        private val NEXUS_REFERER_HOST_SUFFIXES = listOf("workers.dev")

        /**
         * The scrapers the Nexus backend advertises, each a separate upstream
         * site reached by its own request.
         *
         * `scraper` is the wire value /api/sources expects; `label` is the
         * backend's own display name with its redundant tag trimmed.
         *
         * `hitRate` is how many of four probe titles (two films, two episodes)
         * the scraper returned a playable source for. It measures catalogue
         * coverage, not reliability: a scraper answering for two of four simply
         * carries fewer titles, and works normally for those it has.
         */
        val NEXUS_PROVIDERS = listOf(
            NexusProvider("holly", "Lolly", 4),
            NexusProvider("castle", "CastVid", 4),
            NexusProvider("ophim", "Ophm", 4),
            NexusProvider("yomovies", "StreamX", 4),
            NexusProvider("vidapi", "VidPi", 4),
            NexusProvider("streamflix", "StremFx", 4),
            NexusProvider("nitro", "Nitro", 3),
            NexusProvider("bkl-blast", "MbBlast", 3),
            NexusProvider("rive-citadel", "Citadel", 3),
            NexusProvider("watchout", "Multi-bill", 3),
            NexusProvider("rive-primevids", "Prvibd", 3),
            NexusProvider("imovr", "Topflix", 3),
            NexusProvider("awsind", "AwsPly", 3),
            NexusProvider("k4khdhub", "4k-Hub", 3),
            // The only DASH provider, and the one the site's own player uses.
            NexusProvider("mhbox", "MhPly", 2),
            NexusProvider("mbox", "MbPly", 2),
            NexusProvider("stvv", "Stvvid", 2),
            NexusProvider("hdhub4u", "4k-bk", 2),
            NexusProvider("rive-flowcast", "River", 1),
            NexusProvider("rive-hindicast", "HindiSk", 1),
            NexusProvider("rive-asiacloud", "AsiaLug", 0),
            NexusProvider("levi", "Hevily", 0),
            NexusProvider("toonstream", "TunWatch", 0),
            NexusProvider("tamilblasters", "TamBlast", 0),
            NexusProvider("filmyfly", "FlyVid", 0),
            NexusProvider("rive-guru", "Gbru", 0),
            NexusProvider("em-8", "VidHindi", 0),
        )

        /**
         * Scrapers enabled out of the box, chosen by hand rather than derived
         * from [NexusProvider.hitRate].
         *
         * The hit rate only counts how many probe titles a scraper answered
         * for, which says nothing about whether the answer plays. Every entry
         * below was followed through to real media bytes across two titles;
         * the counts are how many of its sources returned video. Providers
         * offer several mirrors, so a single dead one does not condemn the
         * scraper — checking only the first source is what made some of these
         * look broken earlier.
         */
        val NEXUS_PROVIDER_DEFAULT: Set<String> = setOf(
            "castle", // CastVid - HLS, 9/9 sources
            "streamflix", // StremFx - MKV, 2/2
            "bkl-blast", // MbBlast - MKV, 3/3
            "mhbox", // MhPly   - DASH, 3/3, what the site's own player uses
            "k4khdhub", // 4k-Hub  - MKV, 5/14, the only 2160p source
            "vidapi", // VidPi   - HLS, 4/6, rejects a Referer
            "stvv", // Stvvid  - MP4, 4/6
            "hdhub4u", // 4k-bk   - MKV, 2/3
            "holly", // Lolly   - MP4, 1/5
            "ophim", // Ophm    - kept by request; 0/3 when tested
        )

        /**
         * Scrapers that returned no playable source for any probe title, so a
         * note can distinguish "off by choice" from "known not to work".
         */
        private val NEXUS_KNOWN_DEAD = setOf(
            "nitro", // valid playlist, every segment an ad CDN
            "watchout",
            "imovr",
            "awsind",
            "rive-citadel",
            "yomovies",
            "rive-primevids",
            "mbox",
            "rive-flowcast",
            "rive-hindicast",
            "rive-asiacloud",
            "levi",
            "toonstream",
            "tamilblasters",
            "filmyfly",
            "rive-guru",
            "em-8",
        )

        /** Entry labels for the provider preference, ordered as the list is. */
        fun nexusProviderEntries(): List<String> = NEXUS_PROVIDERS.map { provider ->
            val note = when {
                provider.scraper in NEXUS_PROVIDER_DEFAULT -> ""
                provider.scraper in NEXUS_KNOWN_DEAD -> " - no video when tested"
                else -> ""
            }
            "${provider.label}$note"
        }

        fun nexusProviderValues(): List<String> = NEXUS_PROVIDERS.map { it.scraper }

        /**
         * Matches a trailing "[Multi-Lang]" or "(FHD)" tag on a Nexus server
         * name, together with whatever separator precedes it. Verified against
         * all 27 names the backend has been seen to return.
         */
        private val NEXUS_SERVER_TAG_REGEX = Regex("""\s*[-–]?\s*[\[(][^\])]*[\])]\s*$""")

        private const val NEXUS_SALT_LENGTH = 10
        private const val NEXUS_SALT_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        private val HEVC_NAMES = setOf("hevc", "h265", "h.265")

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
         * Servers offered in settings. VidLink, Nexus and CineJoy are not
         * Videasy backends, so they are appended rather than derived from
         * [VIDEASY_SERVERS].
         */
        val SERVER_DISPLAY_NAMES: List<String> =
            VIDEASY_SERVERS.map { it.displayName } + VIDLINK_NAME + NEXUS_NAME + CINEJOY_NAME

        /**
         * Order the video list groups servers in. Mirrors [SERVER_DISPLAY_NAMES]
         * so the picker follows the same order as the settings list rather than
         * the alphabet; a name missing from it sorts last instead of throwing.
         */
        private val SERVER_ORDER_HINT: List<String> = SERVER_DISPLAY_NAMES

        /**
         * Servers kept in the catalogue but not enabled by default: they resolve
         * sources yet the returned streams do not play. Flagged in the picker so
         * enabling one is a deliberate choice.
         */
        val EXPERIMENTAL_SERVERS: Set<String> = emptySet()

        /** Audio-language hint shown per server in the preference list. */
        fun audioLabelFor(displayName: String): String = when (displayName) {
            VIDLINK_NAME -> "Original"
            NEXUS_NAME -> "Multi-Lang"
            CINEJOY_NAME -> "Multi-Lang"
            else -> VIDEASY_SERVERS.firstOrNull { it.displayName == displayName }
                ?.audioLabel ?: "Unknown"
        }
    }
}
