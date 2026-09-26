package eu.kanade.tachiyomi.animeextension.en.rentaro

import android.media.MediaCodecList
import android.os.Build
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Resolves Rentaro playback through the independent VidLink, Nexus, CineJoy,
 * CineFlix, VidFast and VidLove backend families.
 */
class RentaroExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    /** Mirrors VidLove's own HEVC capability gate before asking for HEVC-only fallbacks. */
    private val supportsHevc by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { type ->
                    type.equals("video/hevc", ignoreCase = true)
                }
            }
        }.getOrDefault(false)
    }

    /**
     * Resolves every enabled backend and returns the finished list.
     *
     * Kept as the terminal value of [videosFlowFromUrl] so the ordering rules
     * live in one place: a host that cannot collect a flow gets exactly what a
     * host that can would see last.
     */
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
        enabledVidLoveProviders: Set<String> = VIDLOVE_PROVIDER_DEFAULT,
    ): List<Video> = videosFlowFromUrl(
        path,
        title,
        year,
        imdbId,
        enabledServers,
        subLimit,
        qualityPref,
        enabledNexusProviders,
        enabledCineJoyServers,
        enabledVidLoveProviders,
    ).last()

    /**
     * Resolves every enabled backend, emitting the list again as family results
     * are collected.
     *
     * Six backend families are started concurrently and differ enormously in
     * cost. Some make one request while others fan out across selectable upstream
     * servers or encrypted request chains. Waiting for every family before
     * returning anything would withhold usable streams until the slowest enabled
     * path completes.
     *
     * Emissions are cumulative and fully ordered, per the [ProgressiveVideoSource]
     * contract: each one carries every stream found so far, so a collector can
     * treat the latest as the whole list. Ordering is recomputed each time rather
     * than appended, because a backend that lands late still belongs in its
     * catalogue position - but nothing already emitted is ever removed.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun videosFlowFromUrl(
        path: String,
        title: String,
        year: String,
        imdbId: String,
        enabledServers: Set<String>,
        subLimit: Int,
        qualityPref: String,
        enabledNexusProviders: Set<String> = NEXUS_PROVIDER_DEFAULT,
        enabledCineJoyServers: Set<String> = CINEJOY_SERVER_DEFAULT,
        enabledVidLoveProviders: Set<String> = VIDLOVE_PROVIDER_DEFAULT,
    ): Flow<List<Video>> = channelFlow {
        val pathParts = path.split("/")
        val isMovie = pathParts.first() == "movie"
        val tmdbId = pathParts[1]
        val seasonId = if (isMovie) "1" else pathParts[2]
        val episodeId = if (isMovie) "1" else pathParts[3]

        val vidLinkEnabled = VIDLINK_NAME in enabledServers
        val nexusEnabled = NEXUS_NAME in enabledServers
        val cineJoyEnabled = CINEJOY_NAME in enabledServers
        val cineFlixEnabled = CINEFLIX_NAME in enabledServers
        val vidFastEnabled = VIDFAST_NAME in enabledServers
        val vidLoveEnabled = VIDLOVE_NAME in enabledServers

        // Emitted even when nothing is enabled: the contract asks for at least
        // one emission so the host can tell "none found" from "still working".
        if (!vidLinkEnabled && !nexusEnabled && !cineJoyEnabled &&
            !cineFlixEnabled && !vidFastEnabled && !vidLoveEnabled
        ) {
            send(emptyList())
            return@channelFlow
        }

        val found = mutableListOf<Video>()
        val lock = Mutex()
        var published = false

        suspend fun publish(batch: List<Video>) {
            lock.withLock {
                // An empty batch from the last outstanding backend still has to
                // produce an emission when no other backend found anything.
                if (batch.isEmpty() && published) return
                found += batch
                published = true
                send(orderVideos(found, qualityPref))
            }
        }

        // Servers *within* a backend are already resolved in parallel; these six
        // family tasks run concurrently and report independently.
        val resolvers: List<suspend () -> List<Video>> = listOf(
            // VidLink is independent from the other backend families.
            //
            // Only IOException is absorbed here. A blanket catch previously hid a
            // NoSuchFieldError thrown during token class-init on older devices,
            // so the server silently vanished instead of surfacing the fault.
            {
                if (!vidLinkEnabled) {
                    emptyList()
                } else {
                    try {
                        vidLinkVideos(tmdbId, seasonId, episodeId, isMovie, subLimit)
                    } catch (_: IOException) {
                        emptyList()
                    }
                }
            },
            // Nexus is an independent backend with its own encrypted API.
            {
                if (!nexusEnabled) {
                    emptyList()
                } else {
                    try {
                        nexusVideos(tmdbId, imdbId, seasonId, episodeId, isMovie, enabledNexusProviders)
                    } catch (_: IOException) {
                        emptyList()
                    }
                }
            },
            // CineJoy is an independent backend, reached through its own
            // encrypt/decrypt chain. Usually the slowest, which is the whole
            // reason the others are not made to wait for it.
            {
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
            },
            // CineFlix is an independent backend, and the cheapest: plain
            // JSON both ways, with a proof of work solved in-process.
            {
                if (!cineFlixEnabled) {
                    emptyList()
                } else {
                    try {
                        cineFlixVideos(title, year, seasonId, episodeId, isMovie, subLimit)
                    } catch (_: IOException) {
                        emptyList()
                    }
                }
            },
            // VidFast is the only backend still reached through
            // enc-dec.app, so it is also the one most likely to fail outright.
            // Isolating it here keeps that from costing the other families.
            {
                if (!vidFastEnabled) {
                    emptyList()
                } else {
                    try {
                        vidFastVideos(
                            tmdbId,
                            seasonId,
                            episodeId,
                            isMovie,
                            subLimit,
                        )
                    } catch (_: IOException) {
                        emptyList()
                    }
                }
            },
            // VidLove exposes its source catalogue as plain JSON. Each upstream is queried
            // independently, so one unavailable provider cannot hide the rest.
            {
                if (!vidLoveEnabled) {
                    emptyList()
                } else {
                    try {
                        vidLoveVideos(
                            tmdbId,
                            seasonId,
                            episodeId,
                            isMovie,
                            enabledVidLoveProviders,
                            subLimit,
                        )
                    } catch (_: IOException) {
                        emptyList()
                    }
                }
            },
        )

        // Publish inside each child rather than awaiting the resolver list in catalogue order.
        // Awaiting Orion first created head-of-line blocking: a finished Dave or Jay result sat
        // hidden until every earlier entry had been collected, after which several batches were
        // emitted back-to-back and appeared to arrive together.
        resolvers
            .map { resolver -> launch { publish(resolver()) } }
            .joinAll()
    }

    /**
     * Groups by server, best quality first inside each group.
     *
     * Every label is built as "<server> · <detail>…", so the leading segment
     * identifies the group. Server order follows the catalogue rather than the
     * alphabet, which keeps a preferred server near the top instead of scattering
     * one server's entries through the list. Preferred Quality then orders
     * entries within a group, not across the whole list.
     */
    private fun orderVideos(videos: List<Video>, qualityPref: String): List<Video> {
        val serverRank = SERVER_ORDER_HINT.withIndex().associate { (index, name) -> name to index }

        return videos
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
     * CineJoy is an independent backend. One call per server:
     * `POST api.wing.st/g` carrying a sealed body, answering ciphertext.
     *
     * The site assembles that body in a WASM module, which is why this used to
     * go through enc-dec.app. The construction underneath is standard, though —
     * P-256 ECDH, HKDF-SHA256, AES-256-GCM — so [CineJoyCipher] seals and opens
     * in-process and no external service is involved.
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

        // The current site fetches subtitles from a separate plain-JSON service rather than
        // returning them inside every encrypted provider response. Read it once per episode,
        // not once per selected provider.
        val subtitles = cineJoySubtitles(
            tmdbId = tmdbId,
            seasonId = seasonId,
            episodeId = episodeId,
            isMovie = isMovie,
            subLimit = subLimit,
        )

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
                subtitles,
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
        externalSubtitles: List<Track>,
        subLimit: Int,
    ): List<Video> {
        // The query the backend expects, as the site's own player sends it. The
        // path segment is `series` rather than `tv`; every other spelling 404s.
        val query = buildJsonObject {
            put("path", "/$server/${if (isMovie) "movie" else "series"}")
            putJsonObject("payload") {
                put("tmdb", tmdbId)
                put("imdb", imdbId)
                put("year", year)
                put("title", title)
                if (!isMovie) {
                    put("season", seasonId)
                    put("episode", episodeId)
                }
            }
        }.toString()

        // Null only if the platform lacks P-256 or AES-GCM, which no supported
        // Android release does.
        val sealed = CineJoyCipher.seal(query) ?: return emptyList()

        // The captured browser request sends the binary body as text/plain and carries no
        // Origin or Referer. The API rejects the retired shegu.st key/domain with 404.
        val siteHeaders = headers
        val encrypted = client.newCall(
            POST(
                CINEJOY_UPSTREAM_URL,
                siteHeaders,
                sealed.body.toRequestBody(CINEJOY_REQUEST_MEDIA_TYPE),
            ),
        )
            .awaitSuccess()
            .body
            .bytes()
        if (encrypted.isEmpty()) return emptyList()

        // Null means the reply failed authentication, which a truncated body or
        // a key mismatch would both cause.
        val plaintext = sealed.open(encrypted) ?: return emptyList()
        val dec = plaintext.parseAs<CineJoyDecResultDto>()

        // A server with no match for the title reports it by omitting `stream`
        // rather than by status code.
        val streams = dec.data?.stream.orEmpty()
        if (streams.isEmpty()) return emptyList()

        return streams.flatMap { stream ->
            cineJoyVideosForStream(server, stream, externalSubtitles, subLimit)
        }
    }

    private fun cineJoyVideosForStream(
        server: String,
        stream: CineJoyStreamDto,
        externalSubtitles: List<Track>,
        subLimit: Int,
    ): List<Video> {
        val subtitles = (
            stream.captions
                .mapNotNull { caption ->
                    val url = caption.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Track(
                        subtitleHintedUrl(url, caption.type, caption.display),
                        caption.display ?: caption.language ?: "Unknown",
                    )
                } +
                externalSubtitles
            )
            .distinctBy { it.url }
            .take(subLimit.coerceAtLeast(0))

        // The browser fetches every stream cross-site from cinejoy.pk, and two CDNs now enforce
        // it: Solara (cheaptruckrepairs.cc) 403s the master, variants and segments without the
        // site Origin/Referer, and Nebula (bright67.online) 404s the init/segments of some titles
        // without the Origin. Lisbon accepts the same headers, so they apply to every stream.
        val streamHeaders = headers.newBuilder()
            .set("Referer", "$CINEJOY_ORIGIN/")
            .set("Origin", CINEJOY_ORIGIN)
            .build()

        // Only an absolute URL is usable. A placeholder label from an upstream provider would
        // otherwise reach the player as an unresolvable host.
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

            // Players that pick a parser from the URL rather than the response
            // get it wrong on two of these servers, because neither variant URL
            // looks like HLS: Nebula's end ".jpg" and are served as image/jpeg,
            // and Solara's are a bare "/m3u8?=<token>" with no extension. Both
            // are genuine HLS - Nebula's segments are fMP4 behind .jpg/.png
            // names, Solara's are MPEG-TS served as text/css - so the media is
            // fine and only the detection is wrong.
            //
            // A "#.m3u8" fragment makes the URL self-describing without altering
            // the request: a fragment is never sent to the server, and relative
            // segment URIs still resolve against the same base. Verified against
            // both CDNs, which answer identically with and without it.
            return expanded.map { video ->
                val hinted = hlsHintedUrl(video.videoUrl ?: video.url)
                if (hinted == null) {
                    video
                } else {
                    Video(
                        url = hinted,
                        quality = video.quality,
                        videoUrl = hinted,
                        headers = video.headers,
                        subtitleTracks = video.subtitleTracks,
                        audioTracks = video.audioTracks,
                    )
                }
            }.ifEmpty {
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
     * Subtitle tracks from the current CineJoy subtitle service.
     *
     * The supplied capture showed the movie shape as
     * `?type=movie&tmdb=<id>`. Television adds season and episode, matching the site's current
     * route model. Failure is non-fatal: video playback is still useful without an external
     * subtitle catalogue.
     */
    private suspend fun cineJoySubtitles(
        tmdbId: String,
        seasonId: String,
        episodeId: String,
        isMovie: Boolean,
        subLimit: Int,
    ): List<Track> {
        if (subLimit <= 0) return emptyList()

        val url = CINEJOY_SUBTITLES_URL.toHttpUrl().newBuilder().apply {
            addQueryParameter("type", if (isMovie) "movie" else "tv")
            addQueryParameter("tmdb", tmdbId)
            if (!isMovie) {
                addQueryParameter("season", seasonId)
                addQueryParameter("episode", episodeId)
            }
        }.build()

        return runCatching {
            client.newCall(GET(url, headers))
                .awaitSuccess()
                .parseAs<CineJoySubtitleResponseDto>()
                .subtitles
                .mapNotNull { subtitle ->
                    val file = subtitle.url?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    Track(
                        subtitleHintedUrl(file, subtitle.type, subtitle.display),
                        subtitle.display ?: subtitle.language ?: "Unknown",
                    )
                }
                .sortedBy { track ->
                    if (track.lang.startsWith("English", ignoreCase = true) ||
                        track.lang.equals("en", ignoreCase = true)
                    ) {
                        0
                    } else {
                        1
                    }
                }
                .take(subLimit)
        }.getOrDefault(emptyList())
    }

    /**
     * Makes an extensionless subtitle URL self-describing without changing its request.
     *
     * Wing returns SubRip from bare `/sub/<token>` URLs. Players choose WebVTT for those URLs,
     * leaving the track selectable but unable to parse any cue. A URL fragment is local to the
     * player and is never sent to the server, so `#.srt` safely selects the correct parser.
     */
    private fun subtitleHintedUrl(url: String, type: String?, display: String?): String {
        if (url.isBlank() || "#" in url) return url

        val path = url.substringBefore("?")
        if (CINEJOY_SUBTITLE_FORMATS.any { path.endsWith(".$it", ignoreCase = true) }) {
            return url
        }

        val declared = type?.trim()?.removePrefix(".")?.lowercase()
        val displayed = display?.substringAfterLast(".", "")?.lowercase()
        val format = when {
            declared in CINEJOY_SUBTITLE_FORMATS -> declared
            displayed in CINEJOY_SUBTITLE_FORMATS -> displayed
            else -> null
        }

        return format?.let { "$url#.$it" } ?: url
    }

    /**
     * Appends a `#.m3u8` fragment to a media-playlist URL that does not already
     * look like HLS, or returns null when the URL needs no help.
     *
     * The fragment is a client-side hint only - it is never transmitted - so it
     * cannot change what the CDN returns, and relative segment URIs continue to
     * resolve against the same base.
     */
    private fun hlsHintedUrl(url: String): String? {
        if (url.isBlank() || '#' in url) return null
        val path = url.substringBefore('?').substringBefore('#')
        if (path.endsWith(".m3u8", ignoreCase = true)) return null
        if (path.endsWith(".mpd", ignoreCase = true)) return null
        return "$url#.m3u8"
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

    /**
     * Resolves a title through CineFlix.
     *
     * Three plain-JSON hops, with no external decryption service anywhere:
     *
     *  1. `/api/search/suggestions?q=` maps the title to the slug its playback
     *     API needs. That slug ends in a ten-character id which is the site's
     *     own and cannot be derived from TMDB data, so the lookup is required.
     *  2. `/api/playback/challenge` issues a challenge and a difficulty.
     *  3. `/api/playback/stream` releases the stream for a counter that clears
     *     it, solved in-process by [CineFlixProof].
     *
     * The challenge endpoint accepts any slug, so a wrong one is not caught
     * until step three answers `P003`; the match is therefore verified before
     * the proof is attempted rather than after.
     */
    private suspend fun cineFlixVideos(
        title: String,
        year: String,
        seasonId: String,
        episodeId: String,
        isMovie: Boolean,
        subLimit: Int,
    ): List<Video> {
        val slug = cineFlixSlug(title, year, isMovie) ?: return emptyList()

        // Matches the supplied browser capture: the challenge and stream requests carry JSON,
        // but no Origin, Referer or authentication cookie of their own.
        val apiHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .build()

        val challengeBody = buildJsonObject {
            put("slug", slug)
            // Both are null for a film; the API distinguishes on their presence.
            if (isMovie) {
                put("season", JsonNull)
                put("episode", JsonNull)
            } else {
                put("season", seasonId.toIntOrNull() ?: 1)
                put("episode", episodeId.toIntOrNull() ?: 1)
            }
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val challenge = client.newCall(
            POST("$CINEFLIX_API_BASE/api/playback/challenge", apiHeaders, challengeBody),
        ).awaitSuccess().parseAs<CineFlixChallengeDto>()

        val challengeId = challenge.challengeId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val puzzle = challenge.challenge?.takeIf { it.isNotBlank() } ?: return emptyList()
        val counter = CineFlixProof.solve(puzzle, challenge.difficulty ?: 0) ?: return emptyList()

        val streamBody = buildJsonObject {
            put("challengeId", challengeId)
            put("counter", counter)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val response = client.newCall(
            POST("$CINEFLIX_API_BASE/api/playback/stream", apiHeaders, streamBody),
        ).awaitSuccess().parseAs<CineFlixStreamResponseDto>()

        val playlist = response.stream?.url?.takeIf { it.startsWith("http") } ?: return emptyList()

        val subtitles = response.tracks
            .filter { it.kind.equals("captions", ignoreCase = true) }
            .mapNotNull { track ->
                val file = track.file?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Track(file, track.label ?: track.language ?: "Unknown")
            }
            .take(subLimit.coerceAtLeast(0))

        // The captured nebula.bright67.online requests carry no CineFlix Referer.
        val streamHeaders = headers

        val expanded = runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = playlist,
                videoNameGen = { variant -> cineFlixLabel(variant, playlist, subtitles.size) },
                subtitleList = subtitles,
                masterHeaders = streamHeaders,
                videoHeaders = streamHeaders,
            )
        }.getOrDefault(emptyList())

        // This CDN disguises HLS as images: the variant playlists are named
        // "playlist.jpg", their segments "playlist_NNN.jpg", and every one is
        // served as image/jpeg. The media is genuine fMP4, so only a player that
        // picks its parser from the URL is misled.
        //
        // A "#.m3u8" fragment makes the URL self-describing without altering the
        // request, since a fragment is never sent to the server and relative
        // segment URIs still resolve against the same base.
        return expanded.map { video ->
            val hinted = hlsHintedUrl(video.videoUrl ?: video.url)
            if (hinted == null) {
                video
            } else {
                Video(
                    url = hinted,
                    quality = video.quality,
                    videoUrl = hinted,
                    headers = video.headers,
                    subtitleTracks = video.subtitleTracks,
                    audioTracks = video.audioTracks,
                )
            }
        }.ifEmpty {
            listOf(
                Video(
                    url = playlist,
                    quality = cineFlixLabel("Auto", playlist, subtitles.size),
                    videoUrl = playlist,
                    headers = streamHeaders,
                    subtitleTracks = subtitles,
                ),
            )
        }
    }

    /**
     * Finds the CineFlix slug for a title.
     *
     * Suggestions are a fuzzy match that readily returns both a film and a
     * series for one query, so the result is filtered by kind and then scored:
     * an exact title with the right year beats a looser match, and a candidate
     * is only accepted if something corroborates it. Ordering alone is not
     * trusted, because a wrong slug still yields a challenge and only fails two
     * requests later.
     */
    private suspend fun cineFlixSlug(title: String, year: String, isMovie: Boolean): String? {
        val url = "$CINEFLIX_API_BASE/api/search/suggestions".toHttpUrl().newBuilder()
            .addQueryParameter("q", title)
            .build()

        val items = client.newCall(GET(url.toString(), headers))
            .awaitSuccess()
            .parseAs<CineFlixSuggestionsDto>()
            .items

        val wanted = if (isMovie) "movie" else "series"
        val candidates = items.filter { it.type.equals(wanted, ignoreCase = true) }
        if (candidates.isEmpty()) return null

        val normalisedTitle = normaliseTitle(title)
        val wantedYear = year.take(4).toIntOrNull()

        return candidates
            .maxByOrNull { candidate ->
                var score = 0
                if (normaliseTitle(candidate.title ?: "") == normalisedTitle) score += 2
                if (wantedYear != null && candidate.year == wantedYear) score += 1
                score
            }
            ?.takeIf { candidate ->
                // Require corroboration: an exact title, or the right year.
                normaliseTitle(candidate.title ?: "") == normalisedTitle ||
                    (wantedYear != null && candidate.year == wantedYear)
            }
            ?.slug
    }

    /** Lowercases and drops punctuation so titles compare on words alone. */
    private fun normaliseTitle(value: String): String = value.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()

    private fun cineFlixLabel(quality: String, url: String, subCount: Int): String {
        val parts = mutableListOf(CINEFLIX_NAME, quality)
        if (".m3u8" in url.lowercase()) parts += "HLS"
        if (subCount > 0) parts += "$subCount subs"
        return parts.joinToString(" · ")
    }

    // ======================== VidLove (Yoru) backend ========================

    /** One upstream exposed by VidLove under its own source selector. */
    private data class VidLoveProvider(
        val key: String,
        val label: String,
        /** These two sources only return some titles when the official HEVC flag is present. */
        val hevcFallback: Boolean = false,
    )

    private data class VidLoveResolvedSource(
        val provider: VidLoveProvider,
        val source: VidLoveSourceDto,
    )

    /**
     * Resolves VidLove's public JSON API.
     *
     * The official player gives every upstream its own `sources=` request and races them. Rentaro
     * keeps all successful answers instead of stopping at the first, so the viewer has mirrors
     * when one CDN fails. Mega Knight and MovieBox are retried with `hevc=1` only when their
     * normal request has no source, matching the site's capability-gated fallback without
     * forcing HEVC for titles that already have an ordinary stream.
     */
    private suspend fun vidLoveVideos(
        tmdbId: String,
        seasonId: String,
        episodeId: String,
        isMovie: Boolean,
        enabledProviders: Set<String>,
        subLimit: Int,
    ): List<Video> = coroutineScope {
        if (tmdbId.isBlank() || enabledProviders.isEmpty()) return@coroutineScope emptyList()

        // The source replies sometimes omit subtitles even when the dedicated endpoint has a
        // full catalogue. Fetch that endpoint alongside the source fan-out so one sparse source
        // cannot remove tracks from every Yoru stream.
        val subtitleTask = async {
            vidLoveSubtitles(tmdbId, seasonId, episodeId, isMovie, subLimit)
        }

        val resolved = VIDLOVE_SOURCES
            .filter { provider -> provider.key in enabledProviders }
            .parallelCatchingFlatMap { provider ->
                val normal = vidLoveResponse(
                    provider = provider,
                    tmdbId = tmdbId,
                    seasonId = seasonId,
                    episodeId = episodeId,
                    isMovie = isMovie,
                    hevc = false,
                )
                val response = if (normal.source != null || !provider.hevcFallback || !supportsHevc) {
                    normal
                } else {
                    vidLoveResponse(
                        provider = provider,
                        tmdbId = tmdbId,
                        seasonId = seasonId,
                        episodeId = episodeId,
                        isMovie = isMovie,
                        hevc = true,
                    )
                }
                val source = response.source ?: return@parallelCatchingFlatMap emptyList()
                listOf(VidLoveResolvedSource(provider, source))
            }
        val subtitles = subtitleTask.await()
        if (resolved.isEmpty()) return@coroutineScope emptyList()

        resolved.flatMap { item ->
            vidLoveVideosForSource(item.provider, item.source, subtitles)
        }
    }

    private suspend fun vidLoveSubtitles(
        tmdbId: String,
        seasonId: String,
        episodeId: String,
        isMovie: Boolean,
        subLimit: Int,
    ): List<Track> {
        if (subLimit <= 0) return emptyList()

        val url = VIDLOVE_API_BASE.toHttpUrl().newBuilder().apply {
            addPathSegment("subtitles")
            addPathSegment(if (isMovie) "movie" else "tv")
            addPathSegment(tmdbId)
            if (!isMovie) {
                addPathSegment(seasonId)
                addPathSegment(episodeId)
            }
        }.build()

        val apiHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .build()

        return runCatching {
            client.newCall(GET(url, apiHeaders))
                .awaitSuccess()
                .parseAs<List<VidLoveSubtitleDto>>()
                .asSequence()
                .mapNotNull { subtitle ->
                    val file = subtitle.file?.toHttpUrlOrNull()?.toString()
                        ?: return@mapNotNull null
                    val label = subtitle.label?.takeIf { it.isNotBlank() } ?: "Unknown"
                    Track(file, label)
                }
                .distinctBy { it.url }
                .sortedBy { track -> if (track.lang.startsWith("English", true)) 0 else 1 }
                .take(subLimit)
                .toList()
        }.getOrDefault(emptyList())
    }

    private suspend fun vidLoveResponse(
        provider: VidLoveProvider,
        tmdbId: String,
        seasonId: String,
        episodeId: String,
        isMovie: Boolean,
        hevc: Boolean,
    ): VidLoveResponseDto {
        val url = VIDLOVE_API_BASE.toHttpUrl().newBuilder().apply {
            addPathSegment(if (isMovie) "movie" else "tv")
            addQueryParameter("id", tmdbId)
            if (!isMovie) {
                addQueryParameter("season", seasonId)
                addQueryParameter("episode", episodeId)
            }
            addQueryParameter("mode", "json")
            addQueryParameter("sources", provider.key)
            if (hevc) addQueryParameter("hevc", "1")
        }.build()

        val apiHeaders = headers.newBuilder()
            .set("Accept", "application/json")
            .build()

        return client.newCall(GET(url, apiHeaders))
            .awaitSuccess()
            .parseAs()
    }

    private fun vidLoveVideosForSource(
        provider: VidLoveProvider,
        source: VidLoveSourceDto,
        subtitles: List<Track>,
    ): List<Video> {
        val streamUrl = source.url?.takeIf { it.startsWith("http") } ?: return emptyList()
        val sourceLabel = source.label?.takeIf { it.isNotBlank() } ?: provider.label
        // The signed media proxy changes an HLS-playlist request into MP4 init bytes when the
        // player origin is absent. These are therefore playback headers, not cosmetic CORS data.
        val streamHeaders = headers.newBuilder()
            .set("Referer", "$VIDLOVE_ORIGIN/")
            .set("Origin", VIDLOVE_ORIGIN)
            .build()

        // VidLove returns the HLS master inline. Its `url` is not that master: Mega Knight's
        // URL, for example, answers an MP4 init fragment while the signed variant playlists live
        // in `manifest`. Parse the inline master rather than asking PlaylistUtils to fetch the
        // wrong resource.
        val adaptive = source.manifest
            ?.takeIf { it.trimStart().startsWith("#EXTM3U") }
            ?.let { manifest ->
                vidLoveManifestVideos(
                    manifest = manifest,
                    baseUrl = streamUrl,
                    sourceLabel = sourceLabel,
                    subtitles = subtitles,
                    streamHeaders = streamHeaders,
                )
            }
            .orEmpty()

        val direct = if (adaptive.isEmpty()) {
            // The official response uses `url` as the direct fallback when it supplies no
            // master variants. These endpoints are extensionless, so the label declares MP4.
            listOf(
                Video(
                    url = streamUrl,
                    quality = vidLoveLabel(
                        sourceLabel = sourceLabel,
                        quality = "Auto",
                        url = streamUrl,
                        subCount = subtitles.size,
                        isHls = false,
                        isMp4 = true,
                    ),
                    videoUrl = streamUrl,
                    headers = streamHeaders,
                    subtitleTracks = subtitles,
                ),
            )
        } else {
            adaptive
        }

        val qualities = source.qualities.mapNotNull { quality ->
            val url = quality.url?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val qualityIsHls = ".m3u8" in url.lowercase()
            val hinted = if (qualityIsHls) hlsHintedUrl(url) ?: url else url
            Video(
                url = hinted,
                quality = vidLoveLabel(
                    sourceLabel = sourceLabel,
                    quality = quality.quality ?: "Auto",
                    url = url,
                    subCount = subtitles.size,
                    isHls = qualityIsHls,
                    isMp4 = !qualityIsHls,
                    codec = quality.codec,
                ),
                videoUrl = hinted,
                headers = streamHeaders,
                subtitleTracks = subtitles,
            )
        }

        return (direct + qualities).distinctBy { video -> video.videoUrl ?: video.url }
    }

    /** Turns VidLove's inline HLS master into normal extension Video entries. */
    private fun vidLoveManifestVideos(
        manifest: String,
        baseUrl: String,
        sourceLabel: String,
        subtitles: List<Track>,
        streamHeaders: Headers,
    ): List<Video> {
        val lines = manifest.lineSequence().map(String::trim).toList()
        val audioTracks = lines
            .asSequence()
            .filter { line -> line.startsWith("#EXT-X-MEDIA:") }
            .mapNotNull { line ->
                val attrs = vidLoveHlsAttributes(line)
                if (!attrs["TYPE"].equals("AUDIO", ignoreCase = true)) return@mapNotNull null
                val rawUrl = attrs["URI"] ?: return@mapNotNull null
                val url = resolveVidLoveUrl(baseUrl, rawUrl) ?: return@mapNotNull null
                val hinted = hlsHintedUrl(url) ?: url
                Track(
                    hinted,
                    attrs["NAME"] ?: attrs["LANGUAGE"] ?: "Audio",
                )
            }
            .distinctBy { it.url }
            .toList()

        return buildList {
            lines.forEachIndexed { index, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF:")) return@forEachIndexed

                val attrs = vidLoveHlsAttributes(line)
                val rawUrl = lines.drop(index + 1)
                    .firstOrNull { candidate -> candidate.isNotBlank() && !candidate.startsWith("#") }
                    ?: return@forEachIndexed
                val url = resolveVidLoveUrl(baseUrl, rawUrl) ?: return@forEachIndexed
                val hinted = hlsHintedUrl(url) ?: url
                val resolution = attrs["RESOLUTION"]?.split('x')
                val width = resolution?.getOrNull(0)?.toIntOrNull() ?: 0
                val height = resolution?.getOrNull(1)?.toIntOrNull() ?: 0
                val quality = when {
                    width >= 3_000 || height >= 1_800 -> "2160p"
                    height > 0 -> "${height}p"
                    else -> "Auto"
                }

                add(
                    Video(
                        url = hinted,
                        quality = vidLoveLabel(
                            sourceLabel = sourceLabel,
                            quality = quality,
                            url = url,
                            subCount = subtitles.size,
                            isHls = true,
                            codec = attrs["CODECS"],
                        ),
                        videoUrl = hinted,
                        headers = streamHeaders,
                        subtitleTracks = subtitles,
                        audioTracks = audioTracks,
                    ),
                )
            }
        }.distinctBy { video -> video.videoUrl ?: video.url }
    }

    private fun vidLoveHlsAttributes(line: String): Map<String, String> = VIDLOVE_HLS_ATTRIBUTE_REGEX.findAll(line.substringAfter(':'))
        .associate { match ->
            match.groupValues[1] to match.groupValues[2].trim().removeSurrounding("\"")
        }

    private fun resolveVidLoveUrl(baseUrl: String, value: String): String? = value.takeIf { it.startsWith("http") }
        ?: baseUrl.toHttpUrlOrNull()?.resolve(value)?.toString()

    private fun vidLoveLabel(
        sourceLabel: String,
        quality: String,
        url: String,
        subCount: Int,
        isHls: Boolean,
        isMp4: Boolean = false,
        codec: String? = null,
    ): String {
        val parts = mutableListOf("$VIDLOVE_NAME/$sourceLabel", quality)
        when {
            isHls || ".m3u8" in url.lowercase() -> parts += "HLS"
            isMp4 || ".mp4" in url.lowercase() -> parts += "MP4"
        }
        if (codec?.contains("hevc", ignoreCase = true) == true ||
            codec?.contains("h265", ignoreCase = true) == true ||
            codec?.contains("h.265", ignoreCase = true) == true ||
            codec?.contains("hvc1", ignoreCase = true) == true ||
            codec?.contains("hev1", ignoreCase = true) == true
        ) {
            parts += "HEVC"
        }
        if (subCount > 0) parts += "$subCount subs"
        return parts.joinToString(" · ")
    }

    // ======================== VidFast (Wave) backend ========================

    /**
     * VidFast is the only backend that still needs
     * enc-dec.app. Four calls per resolve:
     *
     *  1. `GET /movie/{tmdb}` (or `/tv/{tmdb}/{s}/{e}`)  the embed page, whose
     *     HTML carries a short-lived token.
     *  2. `enc-vidfast?text=…`  that token is handed to enc-dec.app, which
     *     returns the two request URLs plus the CSRF token.
     *  3. `POST {servers}`  answers the encrypted server list.
     *  4. `POST {stream}/{data}`  answers one server's encrypted stream.
     *
     * Steps 3 and 4 are decrypted by `dec-vidfast`. That is unavoidable for now:
     * the payloads are encrypted by a bytecode VM embedded in the player bundle,
     * which is handed a virtualised global environment, so there is no cipher to
     * lift out. The site also stalls when devtools are open, which rules out
     * recovering the algorithm from a running page.
     *
     * Subtitles are the exception — `/wyzie` is plain JSON, so they are fetched
     * directly and work regardless of enc-dec.app.
     */
    private suspend fun vidFastVideos(
        tmdbId: String,
        seasonId: String,
        episodeId: String,
        isMovie: Boolean,
        subLimit: Int,
    ): List<Video> {
        if (tmdbId.isBlank()) return emptyList()

        val path = if (isMovie) {
            "movie/$tmdbId"
        } else {
            "tv/$tmdbId/$seasonId/$episodeId"
        }

        // The token lives in the embed page's inlined RSC payload, where it is
        // JSON-escaped, hence the doubled quotes in the pattern.
        val page = client.newCall(GET("$VIDFAST_ORIGIN/$path/", vidFastHeaders()))
            .awaitSuccess()
            .bodyString()
        val token = VIDFAST_TOKEN_REGEX.find(page)?.groupValues?.get(1)
            ?: return emptyList()

        val encUrl = "$VIDFAST_ENC_URL?text=${URLEncoder.encode(token, "UTF-8")}"
        val enc = client.newCall(GET(encUrl, headers))
            .awaitSuccess()
            .parseAs<VidFastEncDto>()
        val parts = enc.result?.takeIf { enc.status == HTTP_OK } ?: return emptyList()
        val serversUrl = parts.servers?.takeIf { it.isNotBlank() } ?: return emptyList()
        val streamBase = parts.stream?.takeIf { it.isNotBlank() } ?: return emptyList()
        val csrf = parts.token?.takeIf { it.isNotBlank() } ?: return emptyList()

        // Both POSTs are unauthenticated apart from these headers, and both
        // answer ciphertext as plain text rather than JSON.
        val postHeaders = vidFastApiHeaders(csrf)

        val serverList = vidFastDecrypt(serversUrl, postHeaders)
            ?.let { runCatching { it.parseAs<List<VidFastServerDto>>() }.getOrNull() }
            .orEmpty()
        if (serverList.isEmpty()) return emptyList()

        val subtitles = vidFastSubtitles(tmdbId, seasonId, episodeId, isMovie, subLimit)

        // Servers are independent, so one failing must not lose the others.
        // Filtered against the user's selection before any request is made, so a
        // disabled server costs nothing.
        return serverList
            .filter { server -> server.name == VIDFAST_SERVER }
            .parallelCatchingFlatMap { server ->
                val data = server.data?.takeIf { it.isNotBlank() }
                    ?: return@parallelCatchingFlatMap emptyList()
                val stream = vidFastDecrypt("$streamBase/$data", postHeaders)
                    ?.let { runCatching { it.parseAs<VidFastStreamDto>() }.getOrNull() }
                    ?: return@parallelCatchingFlatMap emptyList()
                vidFastVideosForStream(server, stream, subtitles)
            }
    }

    /**
     * POSTs to [url] and decrypts the reply through enc-dec.app.
     *
     * Returns the plaintext JSON, or null if either leg fails. The `result` of
     * `dec-vidfast` is already-parsed JSON, so it is re-serialised for the
     * caller to deserialise into a concrete shape.
     */
    private suspend fun vidFastDecrypt(url: String, postHeaders: Headers): String? {
        val ciphertext = client.newCall(POST(url, postHeaders, VIDFAST_EMPTY_BODY))
            .awaitSuccess()
            .bodyString()
            .trim()
        if (ciphertext.isEmpty()) return null

        val body = buildJsonObject { put("text", ciphertext) }
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val decrypted = client.newCall(POST(VIDFAST_DEC_URL, headers, body))
            .awaitSuccess()
            .parseAs<JsonObject>()
        if (decrypted["status"]?.jsonPrimitive?.intOrNull != HTTP_OK) return null
        return decrypted["result"]?.toString()
    }

    private fun vidFastVideosForStream(
        server: VidFastServerDto,
        stream: VidFastStreamDto,
        subtitles: List<Track>,
    ): List<Video> {
        val url = stream.url?.takeIf { it.isNotBlank() } ?: return emptyList()

        // Some CDNs reject a Referer outright, which the payload flags.
        val playbackHeaders = if (stream.noReferrer) {
            headers.newBuilder().removeAll("Referer").removeAll("Origin").build()
        } else {
            vidFastHeaders()
        }

        val name = server.name?.takeIf { it.isNotBlank() } ?: VIDFAST_NAME
        // `4kAvailable` is authoritative; `description` only hints, and hedges
        // with a question mark on some servers.
        val quality = if (stream.is4k) "4K" else "Auto"
        val label = vidFastLabel(name, quality, url, stream.mp4, subtitles.size)

        // A progressive MP4 has no variants to expand, so it is offered as-is.
        if (stream.mp4) {
            return listOf(
                Video(
                    url = url,
                    quality = label,
                    videoUrl = url,
                    headers = playbackHeaders,
                    subtitleTracks = subtitles,
                ),
            )
        }

        return runCatching {
            playlistUtils.extractFromHls(
                playlistUrl = url,
                referer = if (stream.noReferrer) "" else "$VIDFAST_ORIGIN/",
                videoNameGen = { res -> vidFastLabel(name, res, url, false, subtitles.size) },
                subtitleList = subtitles,
            )
        }.getOrNull().orEmpty().ifEmpty {
            listOf(
                Video(
                    url = url,
                    quality = label,
                    videoUrl = url,
                    headers = playbackHeaders,
                    subtitleTracks = subtitles,
                ),
            )
        }
    }

    /**
     * Fetches subtitles from `/wyzie`.
     *
     * Plain JSON, so this leg needs no decryption. English is preferred and the
     * rest follow, matching how the other backends order their tracks.
     */
    private suspend fun vidFastSubtitles(
        tmdbId: String,
        seasonId: String,
        episodeId: String,
        isMovie: Boolean,
        subLimit: Int,
    ): List<Track> {
        if (subLimit <= 0) return emptyList()

        val url = "$VIDFAST_ORIGIN/wyzie".toHttpUrl().newBuilder().apply {
            addQueryParameter("id", tmdbId)
            if (!isMovie) {
                addQueryParameter("season", seasonId)
                addQueryParameter("episode", episodeId)
            }
        }.build().toString()

        val tracks = runCatching {
            client.newCall(GET(url, vidFastHeaders()))
                .awaitSuccess()
                .parseAs<List<VidFastSubtitleDto>>()
        }.getOrNull().orEmpty()

        return tracks
            .mapNotNull { track ->
                val file = track.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = track.display?.takeIf { it.isNotBlank() }
                    ?: track.language?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                Track(file, name)
            }
            .sortedBy { track -> if (track.lang.startsWith("English", true)) 0 else 1 }
            .take(subLimit)
    }

    /**
     * Headers for the embed page and the subtitle endpoint.
     *
     * `X-Requested-With` is deliberately absent: the embed page answers 403 to a
     * request carrying it, even though the two ciphertext POSTs require it.
     */
    private fun vidFastHeaders(): Headers = headers.newBuilder()
        .set("Referer", "$VIDFAST_ORIGIN/")
        .set("Origin", VIDFAST_ORIGIN)
        .build()

    /** Headers for the two ciphertext POSTs, which do want `X-Requested-With`. */
    private fun vidFastApiHeaders(csrf: String): Headers = vidFastHeaders().newBuilder()
        .set("X-Requested-With", "XMLHttpRequest")
        .set("X-CSRF-Token", csrf)
        .build()

    private fun vidFastLabel(
        server: String,
        quality: String,
        url: String,
        isMp4: Boolean,
        subCount: Int,
    ): String {
        val parts = mutableListOf("$VIDFAST_NAME/$server", quality)
        if (isMp4) {
            parts += "MP4"
        } else if (".m3u8" in url.lowercase()) {
            parts += "HLS"
        }
        if (subCount > 0) parts += "$subCount subs"
        return parts.joinToString(" · ")
    }

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
     * Nexus is an independent backend, encrypted symmetrically in both
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

        // 4k-bk (hdhub4u) advertises VidHide player pages as `mp4`. They are HTML, so each is
        // resolved to the HLS stream its player loads; one that cannot be resolved is dropped.
        val vidHideStreams = coroutineScope {
            sourcesDto.sources
                .mapNotNull { it.url?.trim()?.takeIf(::isVidHidePage) }
                .distinct()
                .map { page -> async { page to runCatching { resolveVidHide(page) }.getOrNull() } }
                .map { it.await() }
                .toMap()
        }

        val playable = sourcesDto.sources.mapNotNull { source ->
            val rawUrl = source.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // Embeds are player pages, not streams.
            if (source.isEmbed == true) return@mapNotNull null
            // Verified against the live API: these answer text/html landing
            // pages rather than media, so they would only fail in the player.
            if (isNexusLandingPage(rawUrl)) return@mapNotNull null

            val quality = source.quality?.takeIf { it.isNotBlank() }
                ?: source.label?.takeIf { it.isNotBlank() }
                ?: "Auto"

            if (isVidHidePage(rawUrl.trim())) {
                val stream = vidHideStreams[rawUrl.trim()] ?: return@mapNotNull null
                return@mapNotNull NexusCandidate(
                    url = stream.url,
                    label = nexusLabel(serverName, quality, stream.url, "hls"),
                    videoHeaders = stream.headers,
                    type = "hls",
                    subtitles = stream.subtitles,
                )
            }

            val url = sanitiseNexusUrl(rawUrl)

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
                    subtitleTracks = candidate.subtitles,
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
                    // PlaylistUtils names a playlist with no variants "Video",
                    // which adds nothing to a label that already carries the
                    // height. Citadel and CastVid return media playlists rather
                    // than masters, so that was every one of their rows.
                    videoNameGen = { variant ->
                        if (variant.equals("Video", ignoreCase = true)) label else "$label · $variant"
                    },
                    subtitleList = candidate.subtitles,
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
        val subtitles: List<Track> = emptyList(),
    )

    /** The HLS stream a VidHide player page loads, with the headers its CDN needs. */
    private class VidHideStream(val url: String, val headers: Headers, val subtitles: List<Track>)

    /** Whether a Nexus source URL is a VidHide player page (hdstream4u) rather than a file. */
    private fun isVidHidePage(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        return VIDHIDE_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Resolves a VidHide `/file/<code>` page to the HLS stream its player loads.
     *
     * The player setup is P.A.C.K.E.R.-packed and declares
     * `var links={"hls2":…,"hls3":…,"hls4":…}`; not every page carries all three. Checked
     * against three titles and the site's own player on 26 September 2026:
     *
     *     hls3  CDN master; 404s without the page Referer. What the player actually plays.
     *     hls2  CDN master on a second host; the fallback when hls3 is absent.
     *     hls4  same-origin proxy that pads the variant with ad images and often hangs. Unused.
     *
     * The browser fetches both CDNs cross-site with `Origin` and `Referer` set to the page
     * origin, so those go on the master, variants and segments alike. Some titles still 404
     * on odd segments for the site's own player too, which is a CDN gap, not a header issue.
     */
    private suspend fun resolveVidHide(pageUrl: String): VidHideStream? {
        val page = pageUrl.toHttpUrlOrNull() ?: return null
        val pageHeaders = headers.newBuilder().removeAll("Referer").removeAll("Origin").build()
        val html = client.newCall(GET(pageUrl, pageHeaders)).awaitSuccess().bodyString()

        val script = VIDHIDE_PACKED_REGEX.findAll(html)
            .mapNotNull { unpackPacker(it.value) }
            .firstOrNull { "links" in it && "jwplayer" in it }
            ?: return null

        val links = VIDHIDE_LINKS_REGEX.find(script)?.groupValues?.get(1)
            ?.let { runCatching { nexusJson.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            ?: return null

        val origin = "${page.scheme}://${page.host}"
        val streamHeaders = pageHeaders.newBuilder()
            .set("Referer", "$origin/")
            .set("Origin", origin)
            .build()

        // The page is regenerated per load with a different link set, and either CDN may
        // answer 404 or stall on a given load, so the first master that actually answers wins.
        val streamUrl = listOfNotNull(links["hls3"], links["hls2"])
            .mapNotNull { page.resolve(it)?.toString() }
            .firstOrNull { isLiveHlsMaster(it, streamHeaders) }
            ?: return null

        // Captions sit in the player's `tracks` alongside a thumbnail sprite, which is skipped.
        val subtitles = VIDHIDE_TRACK_REGEX.findAll(script)
            .filter { it.groupValues[3] == "captions" || it.groupValues[3] == "subtitles" }
            .mapNotNull { match ->
                val url = page.resolve(match.groupValues[1])?.toString() ?: return@mapNotNull null
                Track(url, match.groupValues[2])
            }
            .toList()

        return VidHideStream(streamUrl, streamHeaders, subtitles)
    }

    /** Short-timeout client for probing VidHide masters, whose CDNs can stall indefinitely. */
    private val vidHideProbeClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(VIDHIDE_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    }

    /** Whether `url` answers with an HLS playlist right now. */
    private suspend fun isLiveHlsMaster(url: String, headers: Headers): Boolean = runCatching {
        vidHideProbeClient.newCall(GET(url, headers)).awaitSuccess().use { response ->
            response.peekBody(HLS_PROBE_BYTES).string().trimStart().startsWith("#EXTM3U")
        }
    }.getOrDefault(false)

    /**
     * Unpacks Dean Edwards' P.A.C.K.E.R. (`eval(function(p,a,c,k,e,d){…}('…',a,c,'…'.split('|')))`).
     *
     * Every word token in the payload is a base-`a` index into the keyword list and is swapped
     * for that keyword, unless the entry is empty, in which case the token stands for itself.
     */
    private fun unpackPacker(packed: String): String? {
        val match = PACKER_ARGS_REGEX.find(packed) ?: return null
        val payload = match.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        val radix = match.groupValues[2].toIntOrNull() ?: return null
        val keywords = match.groupValues[4].split('|')
        if (radix !in 2..62) return null

        return PACKER_WORD_REGEX.replace(payload) { word ->
            val index = decodeBase(word.value, radix)
            keywords.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: word.value
        }
    }

    /** Parses a token written in P.A.C.K.E.R.'s base-62 digits: 0-9, a-z, then A-Z. */
    private fun decodeBase(token: String, radix: Int): Int {
        var value = 0L
        for (c in token) {
            val digit = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'z' -> c - 'a' + 10
                in 'A'..'Z' -> c - 'A' + 36
                else -> return -1
            }
            if (digit >= radix) return -1
            value = value * radix + digit
            // Past this it cannot index the keyword list, and would overflow.
            if (value > Int.MAX_VALUE) return -1
        }
        return value.toInt()
    }

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
     * The backends word these very differently and the audio language is often
     * the only thing separating otherwise identical entries, so it has to
     * survive. All four shapes seen on the live API:
     *
     *     "Hindi dub : 1080"                            -> 1080p · Hindi dub
     *     "720p | Hindi"                                -> 720p · Hindi audio
     *     "[Hindi, English] - 720P"                     -> 720p · Hindi, English audio
     *     "480P (Telugu)"                               -> 480p · Telugu audio
     *     "20.26 GB | 1080p | Hindi | English | BluRay" -> 1080p · Hindi, English audio · BluRay · 20.26 GB
     *
     * Reducing these to the resolution alone made Citadel's sixteen language
     * variants display as two identical rows, and the same for CastVid and
     * MbBlast. The word "audio" is appended because players group these entries
     * by looking for it - a bare language reads as an unclassified detail.
     */
    private fun nexusLabel(serverName: String, quality: String, url: String, type: String? = null): String {
        val parts = mutableListOf("$NEXUS_NAME/${shortenNexusServer(serverName)}")

        // Codec tags carry a bare number ("x264", "H.265") that reads as a
        // resolution, so they are taken out before the height is looked for.
        // Without this "1.4 GB | … | x264" was labelled 264p.
        val heightSource = NEXUS_CODEC_NOISE_REGEX.replace(quality, " ")
        val resolution = qualityRegex.find(heightSource)?.groupValues?.get(1)
        when {
            resolution != null -> parts += "${resolution}p"
            quality.contains("4k", ignoreCase = true) -> parts += "4K"
            // No height anywhere. Falling back to the raw string produced a row
            // titled with the whole tag list, so only the part before the first
            // separator is used and the rest is left to the tags below.
            else -> quality.substringBefore('|').trim()
                .takeIf { it.isNotBlank() }
                ?.let { parts += it.take(NEXUS_LABEL_LIMIT) }
        }

        nexusAudioDescriptor(quality)?.let { parts += it }

        // "… | 1080p | Hindi | BluRay | x265 …" style: keep the source and codec
        // tags, which separate a BluRay rip from a WEB-DL of the same height.
        // Split on both separators the backends use, so MbBlast's
        // "1080P - HEVC (Telugu)" is not read as one opaque tag.
        val tags = quality.split('|', '-', '(', ')').map { it.trim() }
        NEXUS_RELEASE_TAGS.firstOrNull { tag -> tags.any { it.equals(tag, ignoreCase = true) } }
            ?.let { parts += it }
        if (tags.any { it.equals("HEVC", ignoreCase = true) || it.equals("x265", ignoreCase = true) }) {
            parts += "HEVC"
        }
        // Size is what separates the 66 GB, 41 GB and 20 GB releases. Skipped
        // when the height was missing and the size already became the title, so
        // "1.4 GB | Hindi | …" is not labelled with its size twice.
        tags.firstOrNull { NEXUS_SIZE_REGEX.matches(it) }
            ?.takeIf { size -> parts.none { it == size } }
            ?.let { parts += it }

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
     * Extracts the audio descriptor from a Nexus quality string.
     *
     * Tried in order of how specific the shape is. Anything already carrying its
     * own descriptor word - MhPly's "Hindi dub", "Arabic sub", "Original Audio"
     * - is passed through untouched; a bare language list gets "audio" appended
     * so it reads as a choice rather than a stray tag.
     */
    private fun nexusAudioDescriptor(quality: String): String? {
        // "Hindi dub : 1080,720" - the descriptor precedes the colon.
        quality.substringBefore(':', "")
            .trim()
            .takeIf { it.isNotBlank() && it.length <= NEXUS_AUDIO_LIMIT }
            ?.let { return it }

        // "[Hindi, English] - 720P" and "480P (Telugu)" - bracketed language
        // list. Rejected when it holds no language at all, so MbBlast's
        // "1080P - HEVC (Telugu)" keeps HEVC as a release tag, and Ophm's
        // "Vietsub (Full)" does not turn "Full" into an audio track.
        NEXUS_BRACKETED_AUDIO_REGEX.find(quality)
            ?.groupValues?.get(1)
            ?.trim()
            ?.takeIf { candidate ->
                candidate.isNotBlank() &&
                    candidate.none(Char::isDigit) &&
                    candidate.split(',').any { part ->
                        part.trim().lowercase() !in NEXUS_NON_AUDIO_TAGS
                    }
            }
            ?.let { return nexusAudioSuffixed(nexusTrimLanguages(it)) }

        // "720p | Hindi" and "… | Hindi | English | BluRay | x265" - the
        // pipe-separated parts that are languages rather than release metadata.
        val languages = quality.split('|')
            .map { it.trim() }
            .filter { part ->
                part.isNotEmpty() &&
                    part.none(Char::isDigit) &&
                    part.lowercase() !in NEXUS_NON_AUDIO_TAGS
            }
        if (languages.isNotEmpty()) {
            return nexusAudioSuffixed(nexusTrimLanguages(languages.joinToString(", ")))
        }
        return null
    }

    /**
     * Shortens a language list that would overflow the picker.
     *
     * A list of five dub languages is longer than the label can carry, but
     * dropping it entirely brought back the collisions this is meant to fix -
     * CastVid lists the same height with five different language sets. Keeping
     * the first few and counting the rest stays inside the limit while remaining
     * distinct.
     */
    private fun nexusTrimLanguages(text: String): String {
        if (text.length <= NEXUS_AUDIO_LIMIT) return text

        val languages = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (languages.size <= 1) return text.take(NEXUS_AUDIO_LIMIT).trim()

        for (keep in languages.size - 1 downTo 1) {
            val candidate = languages.take(keep).joinToString(", ") + " +${languages.size - keep}"
            if (candidate.length <= NEXUS_AUDIO_LIMIT) return candidate
        }
        return "${languages.size} langs"
    }

    /** Appends "audio" unless the text already names what kind of track it is. */
    private fun nexusAudioSuffixed(text: String): String = if (NEXUS_AUDIO_WORDS.any { text.contains(it, ignoreCase = true) }) text else "$text audio"

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

    companion object {
        // VidLink is an independent backend. It signs its own requests and
        // needs no external decryption service.
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

        // CineJoy is an independent backend. The site builds its request body
        // in a WASM module, but the construction underneath is standard P-256
        // ECDH plus HKDF and AES-GCM, so [CineJoyCipher] does it in-process.
        private const val CINEJOY_NAME = "Jay"
        private const val CINEJOY_UPSTREAM_URL = "https://api.wing.st/g"
        private const val CINEJOY_SUBTITLES_URL = "https://subs.wing.st/subtitles"

        /** Site origin the Solara and Nebula CDNs allowlist for playlists and segments. */
        private const val CINEJOY_ORIGIN = "https://cinejoy.pk"
        private val CINEJOY_SUBTITLE_FORMATS = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp")

        // CineFlix is an independent backend, and the only one whose whole
        // chain is plain JSON. Its proof of work is solved in-process, so it
        // needs no external decryption service and no browser runtime.
        private const val CINEFLIX_NAME = "Dave"
        private const val CINEFLIX_API_BASE = "https://cineflix.st"

        // VidLove is a plain-JSON backend with one request per selectable upstream.
        private const val VIDLOVE_NAME = "Yoru"
        private const val VIDLOVE_ORIGIN = "https://vidlove.cc"
        private const val VIDLOVE_API_BASE = "https://api.vidlove.cc"
        private val VIDLOVE_SOURCES = listOf(
            VidLoveProvider("megaknight", "Mega Knight", hevcFallback = true),
            VidLoveProvider("warden", "Grand Warden"),
            VidLoveProvider("cinefreak", "P.E.K.K.A"),
            VidLoveProvider("moviebox2", "Barbarian King 2.0", hevcFallback = true),
            VidLoveProvider("ipcloud", "Royal Champion"),
            VidLoveProvider("tcloud", "Ice Wizard"),
            VidLoveProvider("vidapi", "Archer Queen"),
        )

        /**
         * Providers that returned no source for all six regional/type probes on 20 September
         * 2026. Kept selectable for future recovery, but not enabled by default.
         */
        val VIDLOVE_NO_SOURCE_PROVIDERS: Set<String> =
            setOf("warden", "cinefreak", "ipcloud", "tcloud")

        /** Providers that returned at least one playable movie or episode in that matrix. */
        val VIDLOVE_PROVIDER_DEFAULT: Set<String> =
            VIDLOVE_SOURCES.map { it.key }.toSet() - VIDLOVE_NO_SOURCE_PROVIDERS

        fun vidLoveProviderEntries(): List<String> = VIDLOVE_SOURCES.map { it.label }

        fun vidLoveProviderValues(): List<String> = VIDLOVE_SOURCES.map { it.key }

        // VidFast is the only backend that is not fully independent.
        //
        // Its two payloads are encrypted by a bytecode VM embedded in the player
        // bundle: a 15 KB high-entropy blob is executed by an interpreter that
        // receives a virtualised global environment, so there is no cipher
        // routine to lift out and reimplement. The site also detects devtools
        // and stalls before the request fires, which rules out recovering the
        // algorithm from a running page.
        //
        // Removing enc-dec.app from this backend would mean reversing that VM's
        // instruction set and disassembling its bytecode. Everything else is
        // already local: the CSRF token and request base path are constants in
        // the bundle, and subtitles come from a plain-JSON endpoint.
        private const val VIDFAST_NAME = "Wave"
        private const val VIDFAST_ORIGIN = "https://vidfast.vc"
        private const val VIDFAST_ENC_URL = "https://enc-dec.app/api/enc-vidfast"
        private const val VIDFAST_DEC_URL = "https://enc-dec.app/api/dec-vidfast"

        /**
         * Pulls the page token out of the embed HTML.
         *
         * It sits in the inlined RSC payload under either `en` or `token`, where
         * the quotes are JSON-escaped, hence the backslashes.
         */
        private val VIDFAST_TOKEN_REGEX = """\\"(?:en|token)\\":\\"(.*?)\\"""".toRegex()

        /**
         * The only VidFast server still offered, so no nested setting is needed.
         *
         * A live eight-title check on 20 September 2026 found Bravo playable for all four TV
         * episodes tested. Every other configured VidFast server was either consistently 403 or
         * returned no stream, so keeping them in the picker only offered known failures.
         */
        private const val VIDFAST_SERVER = "Bravo"

        /** Status field both enc-dec.app endpoints report success with. */
        private const val HTTP_OK = 200

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val CINEJOY_REQUEST_MEDIA_TYPE = "text/plain; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        /**
         * Both VidFast POSTs carry no body; only the CSRF header matters.
         *
         * Declared after [OCTET_STREAM] on purpose: companion properties
         * initialise in source order, so referencing it earlier would read null.
         */
        private val VIDFAST_EMPTY_BODY = ByteArray(0).toRequestBody(OCTET_STREAM)

        /**
         * Current CineJoy providers, from `https://api.wing.st/servers`.
         *
         * The supplied capture returned exactly these four and marked each `ok`.
         * Hard-coded so opening the settings screen never needs a network call.
         */
        val CINEJOY_SERVERS: List<String> = listOf(
            "Lisbon",
            "Nebula",
            "Solara",
            "Athens",
        )

        val CINEJOY_SERVER_DEFAULT: Set<String> = CINEJOY_SERVERS.toSet()

        /** Entry labels for the CineJoy server preference, ordered as the list is. */
        fun cineJoyServerEntries(): List<String> = CINEJOY_SERVERS.map { server ->
            if (server == "Lisbon") "$server (US, 4K)" else "$server (US)"
        }

        /**
         * Origin the progressive-file CDN allowlists. Only used for the
         * `qualities` fallback, since the DASH path authorises by cookie.
         */
        private const val VIDLINK_CDN_ORIGIN = "https://filmboom.top"

        // Nexus is an independent backend (web.nxsha.app). Encrypted API, no
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

        /** VidHide mirrors 4k-bk links as player pages rather than files. */
        private val VIDHIDE_HOSTS = listOf("hdstream4u.com")

        /** A whole P.A.C.K.E.R. block, from `eval(function(p,a,c,k,e,d)` to its `.split('|')))`. */
        private val VIDHIDE_PACKED_REGEX =
            Regex("""eval\(function\(p,a,c,k,e,d\).*?\.split\('\|'\)\)\)""", RegexOption.DOT_MATCHES_ALL)

        /** The packer's arguments: payload, radix, count and the `|`-joined keyword list. */
        private val PACKER_ARGS_REGEX =
            Regex("""\}\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL)

        private val PACKER_WORD_REGEX = Regex("""\b\w+\b""")

        private val VIDHIDE_LINKS_REGEX = Regex("""var\s+links\s*=\s*(\{[^}]*\})""")

        private val VIDHIDE_TRACK_REGEX = Regex("""\{file:"([^"]+)",label:"([^"]*)",kind:"(\w+)"""")

        /** A live master answers in 1-3 s; a stalled CDN never does. */
        private const val VIDHIDE_PROBE_TIMEOUT_SECONDS = 12L

        private const val HLS_PROBE_BYTES = 64L

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
         * Codec tags whose digits would otherwise be read as a resolution.
         *
         * "1.4 GB | Hindi | English | BluRay | x264" has no height at all, and
         * the x264 was picked up as 264p until these were stripped first.
         */
        private val NEXUS_CODEC_NOISE_REGEX =
            Regex("""\b(?:[xh]\.?26[45]|hevc|avc|av1|vp9|aac|ac3|dd5|dts|atmos)\b""", RegexOption.IGNORE_CASE)

        /**
         * A bracketed language list: `[Hindi, English] - 720P` or `480P (Telugu)`.
         * Only one group matches per string, so both bracket styles share it.
         */
        private val NEXUS_BRACKETED_AUDIO_REGEX = Regex("""[\[(]([^\])]+)[\])]""")

        /**
         * Pipe-separated tags that describe the release rather than its audio.
         *
         * Everything else between pipes is treated as a language, so this has to
         * cover the codec, container and source words the backends emit.
         */
        private val NEXUS_NON_AUDIO_TAGS = setOf(
            "bluray", "web-dl", "webrip", "webdl", "hdrip", "hdts", "hdtv", "cam", "dvdrip",
            "hevc", "avc", "x264", "x265", "h264", "h265", "h.264", "h.265", "av1", "vp9",
            "mkv", "mp4", "ts", "dts", "atmos", "aac", "ac3", "dd", "ddp", "truehd", "org",
            "10bit", "8bit", "hdr", "sdr", "dv", "remux", "esub", "msub",
        )

        /** Words that already say what kind of track a descriptor names. */
        private val NEXUS_AUDIO_WORDS = listOf("dub", "sub", "audio")

        /**
         * Stream hosts that require the site Referer, matched on the
         * registrable suffix because the subdomain rotates per request.
         *
         * Kept as an allowlist: VidPi's segment host serves media bare and
         * rejects the header outright, so this cannot be a global default.
         *
         * `itsnitrox.tech` fronts Multi-blue's (and Nitro's) masters: 403 bare, 200 with the
         * Referer. Their segments sit on `workers.dev`, which already has it.
         */
        private val NEXUS_REFERER_HOST_SUFFIXES = listOf("workers.dev", "itsnitrox.tech")

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
            NexusProvider("bdxs", "Multi-blue", 3),
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
            // Added to the backend by 26 September 2026. Off by default, see the sets below.
            NexusProvider("vidking", "Vip-4K", 1),
            NexusProvider("hdhub4u-direct", "4k-bkl", 3),
            NexusProvider("k4khdhub-direct", "4k-Hublink", 4),
            NexusProvider("rive-quasar", "Kutti", 0),
            NexusProvider("filmyfly-direct", "FlyVid Direct", 0),
        )

        /**
         * Art providers that returned no source for all six anime/Western/Korean movie and TV
         * probes on 20 September 2026. They remain available to select manually.
         */
        val NEXUS_NO_SOURCE_PROVIDERS: Set<String> = setOf(
            "holly",
            "imovr",
            "rive-hindicast",
            "rive-asiacloud",
            "levi",
            "toonstream",
            "tamilblasters",
            "filmyfly",
            "rive-guru",
            "em-8",
            "rive-quasar",
            "filmyfly-direct",
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
            // Multi-blue - HLS, 4/6 titles on 26 September 2026, multi-audio (Hindi, English,
            // Tamil, Telugu, ...). Its master host needs the site Referer, see
            // [NEXUS_REFERER_HOST_SUFFIXES].
            "bdxs",
            "mhbox", // MhPly   - DASH, 3/3, what the site's own player uses
            "k4khdhub", // 4k-Hub  - MKV, 5/14, the only 2160p source
            "vidapi", // VidPi   - HLS, 4/6, rejects a Referer
            "stvv", // Stvvid  - MP4, 4/6
            "hdhub4u", // 4k-bk   - MKV, 2/3
            "ophim", // Ophm    - kept by request; 0/3 when tested
            // Citadel - HLS, 10/11 titles. Was written off against Western
            // probes; it is an Indian-catalogue provider and answers for
            // Bollywood and South Indian cinema with up to 12 per-language
            // sources. Its segments are MPEG-TS behind image/jpeg, like Jay's
            // Nebula.
            "rive-citadel",
        )

        /**
         * Scrapers that returned no playable source, so a note can distinguish
         * "off by choice" from "known not to work".
         *
         * Retested against region-matched content - Bollywood, South Indian,
         * Korean, Chinese, Spanish and cartoons - rather than the Western titles
         * the first pass used, because most of these names are region-specific.
         * That reclassified two of them:
         *
         *  - rive-citadel is now enabled: it is an Indian-catalogue provider,
         *    playable for 10 of 11 titles once probed with the right content.
         *  - mbox, nitro and rive-flowcast do carry non-Western titles and
         *    return many sources, but every host answers 403 on three
         *    consecutive attempts, so they stay off as [NEXUS_UPSTREAM_BLOCKED].
         *
         * The rest answer nothing whatever the region.
         */
        private val NEXUS_KNOWN_DEAD = setOf(
            "watchout",
            "imovr",
            "awsind",
            "yomovies",
            "rive-primevids",
            "rive-hindicast",
            "rive-asiacloud",
            "levi",
            "toonstream",
            "tamilblasters",
            "filmyfly",
            "rive-guru",
            "em-8",
            "rive-quasar",
            "filmyfly-direct",
            // Both answer with hubcloud/hubdrive/hubcdn landing pages for every source (plus
            // hdstream4u, which 4k-bk already resolves), never a media file.
            "hdhub4u-direct",
            "k4khdhub-direct",
        )

        /**
         * Scrapers that resolve real sources but whose CDN refuses the request.
         *
         * Distinguished from [NEXUS_KNOWN_DEAD] because the catalogue is there -
         * nitro answers for 10 of 15 region-matched titles, mbox for 12 with up
         * to 31 sources - and only the fetch fails. nitro additionally serves ad
         * CDN segments when it does answer. Worth revisiting if the block lifts.
         */
        //
        // vidking (Vip-4K) masters answer "Access Denied: Blocked by upstream provider nitrox"
        // with or without the site Referer, the only one on itsnitrox.tech still refused.
        private val NEXUS_UPSTREAM_BLOCKED = setOf("nitro", "mbox", "rive-flowcast", "vidking")

        /** Entry labels for the provider preference, ordered as the list is. */
        fun nexusProviderEntries(): List<String> = NEXUS_PROVIDERS.map { provider ->
            val note = when {
                provider.scraper in NEXUS_PROVIDER_DEFAULT -> ""
                provider.scraper in NEXUS_UPSTREAM_BLOCKED -> " - host refuses playback"
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
        private val qualityRegex = Regex("""(\d{3,4})[pP]?""")
        private val VIDLOVE_HLS_ATTRIBUTE_REGEX =
            Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

        /** The six independent backend families offered in settings. */
        val SERVER_DISPLAY_NAMES: List<String> = listOf(
            VIDLINK_NAME,
            NEXUS_NAME,
            CINEJOY_NAME,
            CINEFLIX_NAME,
            VIDFAST_NAME,
            VIDLOVE_NAME,
        )

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
            CINEFLIX_NAME -> "Original"
            VIDFAST_NAME -> "Multi-Lang"
            VIDLOVE_NAME -> "Original"
            else -> "Unknown"
        }
    }
}
